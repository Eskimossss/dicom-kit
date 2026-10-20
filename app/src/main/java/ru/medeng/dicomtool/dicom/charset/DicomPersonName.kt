package ru.medeng.dicomtool.dicom.charset

import java.util.Locale

/** Display-only PN parsing: representation groups remain alternatives; components retain their meaning. */
object DicomPersonName {
    fun display(raw: String?, normalizeUpperCase: Boolean = true): String {
        if (raw.isNullOrBlank()) return ""
        val text = raw.take(2048).filterNot { it.isISOControl() }.trim()
        if (text in listOf("-", "N/A") || text.equals("ANONYMOUS", true)) return ""
        val group = text.split('=').take(3).firstOrNull { it.any { c -> c != '^' && !c.isWhitespace() } } ?: return ""
        val components = group.split('^').take(5).map { component ->
            component.trim().split(Regex(" +")).joinToString(" ") { word ->
                if (normalizeUpperCase && word.length > 1 && word.any { it.isLetter() } && word.none { it.isLowerCase() })
                    word.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
                else word
            }
        }
        return listOf(3, 0, 1, 2, 4).mapNotNull { components.getOrNull(it)?.takeIf(String::isNotBlank) }.joinToString(" ")
    }
}
