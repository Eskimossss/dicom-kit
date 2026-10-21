package ru.medeng.dicomtool.mwl.model

import java.time.LocalDate
import java.time.format.DateTimeFormatter

object MwlBirthDate {
    fun parse(value: String): LocalDate? = runCatching { LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull()
    fun valid(value: String, today: LocalDate = LocalDate.now()): Boolean =
        value.isEmpty() || (value.length == 8 && value.all { it in '0'..'9' } && parse(value)?.let { it.year >= 1 && !it.isAfter(today) } == true)
    fun display(value: String): String = parse(value)?.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) ?: value.ifEmpty { "не указана" }
}
