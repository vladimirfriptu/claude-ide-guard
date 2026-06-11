package dev.friptu.ideguard

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.concurrency.AppExecutorUtil
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Application-level service owning the single loopback HTTP server.
 *
 * Bound to 127.0.0.1 only. Started lazily on the first project open (see
 * [GuardStartup]) and idempotent. Handlers run on a small pool off the EDT;
 * a periodic TTL sweep clears stale in-flight entries. A bind failure (e.g.
 * port in use) is logged, never thrown to the IDE.
 */
@Service(Service.Level.APP)
class GuardServer : Disposable {

    private val log = logger<GuardServer>()
    private var server: HttpServer? = null
    private var executor: ExecutorService? = null
    private var sweepFuture: ScheduledFuture<*>? = null
    private var initialized = false

    private val state: GuardState
        get() = ApplicationManager.getApplication().getService(GuardState::class.java)

    private val router: GuardRouter by lazy {
        GuardRouter(state, PlatformDirtyChecker(), System::currentTimeMillis)
    }

    /** Starts the server (idempotent) plus the one-time sweep + UI wiring. */
    @Synchronized
    fun ensureStarted() {
        if (!initialized) {
            GuardUiRefresher.install(state)
            GuardEditorLocker.install(state)
            sweepFuture = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
                { runCatching { state.sweep(System.currentTimeMillis(), TTL_MILLIS) } },
                SWEEP_INTERVAL_SEC, SWEEP_INTERVAL_SEC, TimeUnit.SECONDS,
            )
            initialized = true
        }
        startHttp(GuardSettings.getInstance().port)
    }

    /** Rebinds the HTTP server to the currently configured port. */
    @Synchronized
    fun restart() {
        stopHttp()
        startHttp(GuardSettings.getInstance().port)
    }

    private fun startHttp(port: Int) {
        if (server != null) return
        try {
            val loopback = InetAddress.getByName("127.0.0.1")
            val httpServer = HttpServer.create(InetSocketAddress(loopback, port), 0)
            httpServer.createContext("/health") { ex -> dispatch(ex) { router.health() } }
            httpServer.createContext("/editing") { ex -> dispatch(ex) { router.editing(readBody(ex)) } }
            httpServer.createContext("/check") { ex -> dispatch(ex) { router.check(queryParam(ex, "path")) } }

            val pool = Executors.newFixedThreadPool(4) { r ->
                Thread(r, "claude-ide-guard-http").apply { isDaemon = true }
            }
            httpServer.executor = pool
            httpServer.start()

            server = httpServer
            executor = pool
            log.info("claude-ide-guard HTTP server listening on 127.0.0.1:$port")
        } catch (e: IOException) {
            log.warn("claude-ide-guard could not bind 127.0.0.1:$port (is it in use?). Indicators/guard disabled.", e)
        }
    }

    private fun stopHttp() {
        server?.stop(0)
        server = null
        executor?.shutdownNow()
        executor = null
    }

    private fun dispatch(exchange: HttpExchange, handler: () -> GuardRouter.Result) {
        try {
            val result = handler()
            respondJson(exchange, result.status, result.body)
        } catch (t: Throwable) {
            log.warn("claude-ide-guard handler error", t)
            runCatching { respondJson(exchange, 500, """{"ok":false}""") }
        } finally {
            exchange.close()
        }
    }

    private fun readBody(exchange: HttpExchange): String =
        exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)

    private fun queryParam(exchange: HttpExchange, name: String): String? {
        val query = exchange.requestURI.rawQuery ?: return null
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            if (eq < 0) continue
            if (pair.substring(0, eq) == name) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8)
            }
        }
        return null
    }

    override fun dispose() {
        sweepFuture?.cancel(true)
        sweepFuture = null
        stopHttp()
    }

    companion object {
        const val DEFAULT_PORT = 7337
        const val TTL_MILLIS = 60_000L
        const val SWEEP_INTERVAL_SEC = 15L

        fun respondJson(exchange: HttpExchange, status: Int, body: String) {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }
}
