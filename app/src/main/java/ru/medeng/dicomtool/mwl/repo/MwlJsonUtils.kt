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

    /**
     * Преобразует JSON-массив объектов в список Map<String, String>.
     */
    fun parseArrayOfObjects(json: String): List<Map<String, String>> {
        val list = mutableListOf<Map<String, String>>()
        val trimmed = json.trim()
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return list

        var inString = false
        var escape = false
        var braceLevel = 0
        var objectStartIndex = -1

        for (i in trimmed.indices) {
            val c = trimmed[i]
            if (escape) {
                escape = false
                continue
            }
            if (c == '\\' && inString) {
                escape = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (c == '{') {
                    if (braceLevel == 0) objectStartIndex = i
                    braceLevel++
                } else if (c == '}') {
                    braceLevel--
                    if (braceLevel == 0 && objectStartIndex != -1) {
                        val objStr = trimmed.substring(objectStartIndex, i + 1)
                        list.add(parseObject(objStr))
                        objectStartIndex = -1
                    }
                }
            }
        }
        return list
    }

    /**
     * Разбирает один плоский JSON-объект в Map<String, String>.
     */
    fun parseObject(json: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val trimmed = json.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return map
        val content = trimmed.substring(1, trimmed.length - 1).trim()
        if (content.isEmpty()) return map

        var inString = false
        var escape = false
        var keyStartIndex = -1
        var currentKey: String? = null
        var valueStartIndex = -1

        var i = 0
        while (i < content.length) {
            val c = content[i]
            if (escape) {
                escape = false
                i++
                continue
            }
            if (c == '\\' && inString) {
                escape = true
                i++
                continue
            }
            if (c == '"') {
                if (!inString) {
                    if (currentKey == null) {
                        keyStartIndex = i + 1
                    } else if (valueStartIndex == -1) {
                        valueStartIndex = i + 1
                    }
                } else {
                    if (currentKey == null) {
                        currentKey = unescape(content.substring(keyStartIndex, i))
                    } else if (valueStartIndex != -1) {
                        val value = unescape(content.substring(valueStartIndex, i))
                        map[currentKey] = value
                        currentKey = null
                        valueStartIndex = -1
                    }
                }
                inString = !inString
                i++
                continue
            }
            if (!inString) {
                if (c == ':') {
                    // Переход к значению (если значение не строка, например true/false/number)
                    if (currentKey != null && valueStartIndex == -1) {
                        var k = i + 1
                        while (k < content.length && content[k].isWhitespace()) k++
                        if (k < content.length && content[k] != '"') {
                            val vStart = k
                            while (k < content.length && content[k] != ',' && content[k] != '}') k++
                            val rawVal = content.substring(vStart, k).trim()
                            map[currentKey] = rawVal
                            currentKey = null
                            i = k
                            continue
                        }
                    }
                } else if (c == ',') {
                    currentKey = null
                    valueStartIndex = -1
                }
            }
            i++
        }
        return map
    }
}
