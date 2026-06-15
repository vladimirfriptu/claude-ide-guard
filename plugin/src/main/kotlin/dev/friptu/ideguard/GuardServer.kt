package dev.friptu.ideguard

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.LowMemoryWatcher
import com.intellij.util.concurrency.AppExecutorUtil
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
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
        GuardRouter(
            state,
            PlatformDirtyChecker(),
            { GuardSettings.getInstance().bashDetectionEnabled },
            {
                val bases = ProjectManager.getInstance().openProjects.mapNotNull { it.basePath }
                WorktreeResolver.expandBashRoots(bases, GuardSettings.getInstance().showWorktreeActivity)
            },
            worktreeCacheSize = { WorktreeResolver.cacheSize() },
        ) { System.currentTimeMillis() }
    }

    /** Logged once when the listener table crosses [LISTENER_WARN_THRESHOLD], reset when it falls back. */
    @Volatile
    private var leakWarned = false

    /** Starts the server (idempotent) plus the one-time sweep + UI wiring. */
    @Synchronized
    fun ensureStarted() {
        if (!initialized) {
            GuardUiRefresher.install(state)
            GuardEditorLocker.install(state)
            // Under IDE memory pressure, drop the (rebuildable) worktree cache.
            // Tied to this Disposable service, so it is unregistered on dispose.
            LowMemoryWatcher.register({ WorktreeResolver.clearCache() }, this)
            sweepFuture = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
                {
                    runCatching {
                        val lease = GuardSettings.getInstance().lockLeaseSeconds * 1000L
                        state.sweep(System.currentTimeMillis(), lease, RECENT_TTL_MILLIS, READ_RECENT_TTL_MILLIS)
                        warnIfLeaking()
                    }
                },
                SWEEP_INTERVAL_SEC, SWEEP_INTERVAL_SEC, TimeUnit.SECONDS,
            )
            initialized = true
        }
        startHttp(resolvePort())
    }

    /** Rebinds the HTTP server to the currently configured port. */
    @Synchronized
    fun restart() {
        stopHttp()
        startHttp(resolvePort())
    }

    /**
     * The `claude.ide.guard.port` system property wins over the persisted
     * setting. The sandbox passes it so it never collides with a production
     * instance already holding the default port.
     */
    private fun resolvePort(): Int =
        System.getProperty(PORT_PROPERTY)?.toIntOrNull() ?: GuardSettings.getInstance().port

    private fun startHttp(port: Int) {
        if (server != null) return
        try {
            val loopback = InetAddress.getByName("127.0.0.1")
            val httpServer = HttpServer.create(InetSocketAddress(loopback, port), 0)
            httpServer.createContext("/health") { ex -> dispatch(ex) { router.health() } }
            httpServer.createContext("/acquire") { ex -> dispatch(ex) { router.acquire(readBody(ex)) } }
            httpServer.createContext("/release") { ex -> dispatch(ex) { router.release(readBody(ex)) } }
            httpServer.createContext("/acquire-bash") { ex -> dispatch(ex) { router.acquireBash(readBody(ex)) } }
            httpServer.createContext("/release-bash") { ex -> dispatch(ex) { router.releaseBash(readBody(ex)) } }

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

    /** Warns once if the listener table looks like it is growing without bound. */
    private fun warnIfLeaking() {
        val d = state.diagnostics()
        if (d.listeners > LISTENER_WARN_THRESHOLD) {
            if (!leakWarned) {
                leakWarned = true
                log.warn(
                    "claude-ide-guard: GuardState listener count is high (${d.listeners}) — " +
                        "possible listener leak. locks=${d.locks} recent=${d.recent} " +
                        "worktreeCache=${WorktreeResolver.cacheSize()}",
                )
            }
        } else {
            leakWarned = false
        }
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

    override fun dispose() {
        sweepFuture?.cancel(true)
        sweepFuture = null
        stopHttp()
    }

    companion object {
        const val DEFAULT_PORT = 7337
        const val PORT_PROPERTY = "claude.ide.guard.port"
        const val RECENT_TTL_MILLIS = 15 * 60_000L
        /** Reads linger only briefly after they finish, just long enough not to flash. */
        const val READ_RECENT_TTL_MILLIS = 60_000L
        const val SWEEP_INTERVAL_SEC = 15L

        /** Realistic ceiling is a handful (one per open tool window); above this signals a leak. */
        const val LISTENER_WARN_THRESHOLD = 50

        fun respondJson(exchange: HttpExchange, status: Int, body: String) {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }
}
