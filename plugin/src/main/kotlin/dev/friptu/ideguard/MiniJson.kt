package dev.friptu.ideguard

/**
 * Tiny dependency-free JSON helper. We only ever exchange small, flat objects
 * with string values, so a full JSON library would be overkill. [parseFlatObject]
 * tolerantly reads a single-level object; [obj] builds one with proper escaping.
 */
object MiniJson {

    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 2)
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }

    /** Builds `{"k":"v",...}` with all keys and values escaped. */
    fun obj(vararg pairs: Pair<String, String>): String =
        pairs.joinToString(separator = ",", prefix = "{", postfix = "}") { (k, v) ->
            "\"${escape(k)}\":\"${escape(v)}\""
        }

    /**
     * Parses a single-level JSON object into a map. String values are unescaped;
     * literals (`true`/`false`/numbers) are stored as their raw text; JSON `null`
     * maps to a Kotlin `null` value. Malformed input yields whatever was parsed
     * so far — callers must validate required keys, never trust completeness.
     */
    fun parseFlatObject(input: String): Map<String, String?> {
        val result = LinkedHashMap<String, String?>()
        var i = 0
        val n = input.length

        fun skipWs() { while (i < n && input[i].isWhitespace()) i++ }

        fun parseString(): String {
            i++ // skip opening quote
            val sb = StringBuilder()
            while (i < n) {
                val c = input[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= n) break
                        when (val e = input[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                val end = minOf(i + 4, n)
                                val hex = input.substring(i, end)
                                i = end
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }

        skipWs()
        if (i >= n || input[i] != '{') return result
        i++ // {
        while (i < n) {
            skipWs()
            if (i >= n || input[i] == '}') break
            if (input[i] != '"') break
            val key = parseString()
            skipWs()
            if (i < n && input[i] == ':') i++ else break
            skipWs()
            if (i >= n) break
            if (input[i] == '"') {
                result[key] = parseString()
            } else {
                val start = i
                while (i < n && input[i] != ',' && input[i] != '}') i++
                val raw = input.substring(start, i).trim()
                result[key] = if (raw == "null") null else raw
            }
            skipWs()
            if (i < n && input[i] == ',') { i++; continue }
            break
        }
        return result
    }
}
