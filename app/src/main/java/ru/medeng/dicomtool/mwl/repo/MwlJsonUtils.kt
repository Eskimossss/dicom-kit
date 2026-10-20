package ru.medeng.dicomtool.mwl.repo

object MwlJsonUtils {

    fun escape(s: String?): String {
        if (s == null) return ""
        val sb = StringBuilder()
        for (ch in s) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\b' -> sb.append("\\b")
                '\u000c' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        sb.append(String.format("\\u%04x", ch.code))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        return sb.toString()
    }

    fun unescape(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                val next = s[i + 1]
                when (next) {
                    '\\' -> { sb.append('\\'); i += 2 }
                    '"' -> { sb.append('"'); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }
                    'f' -> { sb.append('\u000c'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'u' -> {
                        if (i + 6 <= s.length) {
                            val hex = s.substring(i + 2, i + 6)
                            val code = hex.toIntOrNull(16) ?: 0
                            sb.append(code.toChar())
                            i += 6
                        } else {
                            sb.append(c)
                            i++
                        }
                    }
                    else -> {
                        sb.append(next)
                        i += 2
                    }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }


    // Keep nested values as validated JSON so callers can decode arrays without truncation.
    fun parseObject(json: String): Map<String, String> = Reader(json).let { r ->
        val result = r.obj(); r.end(); result
    }
    fun parseArrayOfObjects(json: String): List<Map<String, String>> = Reader(json).let { r ->
        val result = r.array().map { parseObject(it) }; r.end(); result
    }
    private class Reader(private val text: String) {
        private var i = 0
        private var depth = 0
        private fun ws() { while (i < text.length && text[i] in " \t\r\n") i++ }
        private fun take(c: Char) { ws(); require(i < text.length && text[i++] == c) { "Invalid MWL JSON" } }
        private fun at(c: Char): Boolean { ws(); return i < text.length && text[i] == c }
        fun end() { ws(); require(i == text.length) { "Trailing MWL JSON data" } }
        private fun string(): String {
            take('"'); val start = i
            while (i < text.length) {
                val c = text[i++]
                if (c == '"') return unescape(text.substring(start, i - 1))
                require(c.code >= 0x20) { "Unescaped JSON control character" }
                if (c == '\\') {
                    require(i < text.length)
                    val escaped = text[i++]
                    require(escaped in "\"\\/bfnrtu")
                    if (escaped == 'u') { require(i + 4 <= text.length && text.substring(i, i + 4).all { it.digitToIntOrNull(16) != null }); i += 4 }
                }
            }
            error("Unterminated MWL JSON string")
        }
        private fun value(): String {
            ws(); require(i < text.length); val start = i
            when (text[i]) {
                '"' -> string()
                '{' -> obj()
                '[' -> array()
                else -> {
                    while (i < text.length && text[i] !in ",]} \t\r\n") i++
                    val token = text.substring(start, i)
                    require(token in listOf("true", "false", "null") || Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?").matches(token)) { "Invalid MWL JSON value" }
                }
            }
            return text.substring(start, i)
        }
        fun obj(): Map<String, String> {
            require(++depth <= 64); take('{'); val result = linkedMapOf<String, String>()
            if (!at('}')) do {
                val key = string(); take(':'); val raw = value()
                require(!result.containsKey(key)) { "Duplicate JSON key" }
                result[key] = if (raw.startsWith('"')) unescape(raw.substring(1, raw.length - 1)) else raw
                if (!at(',')) break
                take(',')
            } while (true)
            take('}'); depth--; return result
        }
        fun array(): List<String> {
            require(++depth <= 64); take('['); val result = mutableListOf<String>()
            if (!at(']')) do {
                result.add(value())
                if (!at(',')) break
                take(',')
            } while (true)
            take(']'); depth--; return result
        }
    }
}
