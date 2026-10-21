package ru.medeng.dicomtool.mwl.model

/** UI conversion only. Storage and network always retain the original PN string. */
object MwlPatientNameInput {
    data class Components(val family: String, val given: String, val middle: String)

    fun simpleComponents(raw: String): Components? {
        if (raw.any { it == '=' || it == '\\' || it.isISOControl() }) return null
        val parts = raw.split('^')
        if (parts.size > 3) return null
        // A free-form name with spaces has no unambiguous family/given-name boundary.
        if (parts.size == 1 && raw.any(Char::isWhitespace)) return null
        return Components(parts[0], parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" })
    }

    fun compose(family: String, given: String, middle: String): String {
        require(listOf(family, given, middle).all(::validComponent))
        return listOf(family, given, middle).dropLastWhile { it.isEmpty() }.joinToString("^")
    }

    fun validComponent(value: String): Boolean = value.none { it in "^=\\" || it.isISOControl() }
}
