package dev.friptu.ideguard

import java.nio.file.Paths

/**
 * Heuristic extractor of file accesses from a Bash command string. Best-effort:
 * recognizes a fixed set of common commands and redirections, resolves obvious
 * relative / `~` paths against cwd / home, and SKIPS anything ambiguous (globs,
 * variables, flags, `/dev`, unknown commands). Never throws.
 */
object BashCommandParser {

    private val READ_CMDS = setOf("cat", "head", "tail", "less", "more", "wc", "nl", "od", "xxd")
    private val VALUE_FLAGS = setOf("-n", "-c", "-A", "-B", "-C", "-m", "-e", "-s")
    private val REDIR = Regex("""^(?:\d|&)?(>>?)(.*)$""")
    private val READ_REDIR = Regex("""^<(.*)$""")

    fun parse(command: String, cwd: String?, userHome: String): List<FileAccess> {
        val out = LinkedHashMap<String, LockMode>() // path -> mode; WRITE wins
        for (segment in splitSegments(command)) {
            runCatching { parseSegment(segment, cwd, userHome, out) }
        }
        return out.map { (p, m) -> FileAccess(p, m) }
    }

    /** WRITE dominates READ for the same path; first writer/reader wins ordering. */
    private fun put(out: MutableMap<String, LockMode>, path: String?, mode: LockMode) {
        if (path == null) return
        val existing = out[path]
        if (existing == LockMode.WRITE) return
        if (mode == LockMode.WRITE || existing == null) out[path] = mode
    }

    /** Resolves a raw token to an absolute normalized path, or null if ambiguous. */
    private fun resolve(tokenIn: String?, cwd: String?, userHome: String): String? {
        var t = tokenIn ?: return null
        if (t.isEmpty() || t.startsWith("-")) return null
        if (t.contains('$') || t.contains('`')) return null
        if (t.contains('*') || t.contains('?')) return null
        if (t.startsWith("/dev/") || t.startsWith("/proc/")) return null
        if (t == "~") t = userHome
        else if (t.startsWith("~/")) t = userHome + t.substring(1)
        return runCatching {
            val p = if (t.startsWith("/")) Paths.get(t)
            else {
                if (cwd.isNullOrBlank()) return null
                Paths.get(cwd, t)
            }
            p.normalize().toString()
        }.getOrNull()
    }

    private fun basename(s: String): String = s.substringAfterLast('/')

    /** Splits on shell control operators (| ; & newline), respecting quotes. */
    private fun splitSegments(command: String): List<String> {
        val segs = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        var quote = ' '
        while (i < command.length) {
            val c = command[i]
            if (quote != ' ') {
                sb.append(c)
                if (c == quote) quote = ' '
                i++; continue
            }
            when (c) {
                '\'', '"' -> { quote = c; sb.append(c); i++ }
                '|', ';', '\n', '&' -> {
                    segs.add(sb.toString()); sb.setLength(0); i++
                    if (i < command.length && (command[i] == '|' || command[i] == '&')) i++
                }
                else -> { sb.append(c); i++ }
            }
        }
        if (sb.isNotEmpty()) segs.add(sb.toString())
        return segs
    }

    /** Whitespace tokenizer that drops surrounding quotes. */
    private fun tokenize(segment: String): List<String> {
        val tokens = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        var quote = ' '
        fun flush() { if (sb.isNotEmpty()) { tokens.add(sb.toString()); sb.setLength(0) } }
        while (i < segment.length) {
            val c = segment[i]
            if (quote != ' ') {
                if (c == quote) quote = ' ' else sb.append(c)
                i++; continue
            }
            when (c) {
                '\'', '"' -> { quote = c; i++ }
                ' ', '\t' -> { flush(); i++ }
                else -> { sb.append(c); i++ }
            }
        }
        flush()
        return tokens
    }

    /** Positional args with flags (and their values) removed. */
    private fun nonFlagArgs(args: List<String>): List<String> {
        val res = ArrayList<String>()
        var skipNext = false
        for (a in args) {
            if (skipNext) { skipNext = false; continue }
            if (a == "--") continue
            if (a.startsWith("-")) {
                if (a in VALUE_FLAGS) skipNext = true
                continue
            }
            res.add(a)
        }
        return res
    }

    private fun parseSegment(
        segment: String,
        cwd: String?,
        userHome: String,
        out: MutableMap<String, LockMode>,
    ) {
        val raw = tokenize(segment)
        if (raw.isEmpty()) return
        val positional = ArrayList<String>()
        var i = 0
        while (i < raw.size) {
            val t = raw[i]
            val wr = REDIR.matchEntire(t)
            if (wr != null) {
                val glued = wr.groupValues[2]
                val target = if (glued.isNotEmpty()) glued else raw.getOrNull(++i)
                put(out, resolve(target, cwd, userHome), LockMode.WRITE)
                i++; continue
            }
            val rr = READ_REDIR.matchEntire(t)
            if (rr != null) {
                val glued = rr.groupValues[1]
                val target = if (glued.isNotEmpty()) glued else raw.getOrNull(++i)
                put(out, resolve(target, cwd, userHome), LockMode.READ)
                i++; continue
            }
            positional.add(t)
            i++
        }
        if (positional.isNotEmpty()) classify(positional, cwd, userHome, out)
    }

    private fun classify(
        positional: List<String>,
        cwd: String?,
        userHome: String,
        out: MutableMap<String, LockMode>,
    ) {
        val cmd = basename(positional[0])
        val args = positional.drop(1)
        when (cmd) {
            in READ_CMDS ->
                nonFlagArgs(args).forEach { put(out, resolve(it, cwd, userHome), LockMode.READ) }
            "grep" -> {
                val recursive = args.any { it == "-r" || it == "-R" || it == "--recursive" || it == "-d" }
                if (!recursive) {
                    nonFlagArgs(args).drop(1) // first non-flag arg is the PATTERN
                        .forEach { put(out, resolve(it, cwd, userHome), LockMode.READ) }
                }
            }
            "tee", "touch", "truncate" ->
                nonFlagArgs(args).forEach { put(out, resolve(it, cwd, userHome), LockMode.WRITE) }
            "sed", "perl" -> {
                if (args.any { it == "-i" || it.startsWith("-i.") }) {
                    nonFlagArgs(args).drop(1)
                        .forEach { put(out, resolve(it, cwd, userHome), LockMode.WRITE) }
                }
            }
            "mv", "cp" -> {
                val files = nonFlagArgs(args)
                if (files.size >= 2) {
                    put(out, resolve(files.last(), cwd, userHome), LockMode.WRITE)
                    files.dropLast(1)
                        .forEach { put(out, resolve(it, cwd, userHome), LockMode.READ) }
                }
            }
            "dd" -> args.forEach { a ->
                when {
                    a.startsWith("of=") -> put(out, resolve(a.substring(3), cwd, userHome), LockMode.WRITE)
                    a.startsWith("if=") -> put(out, resolve(a.substring(3), cwd, userHome), LockMode.READ)
                }
            }
            else -> { /* unknown command: do nothing */ }
        }
    }
}
