package ru.medeng.dicomtool.mwl.model

/** Common standard Defined Terms (PS3.3 C.7.3.1.1.1); this is not a SOP Class support list. */
object MwlModalityChoices {
    val standard = listOf(
        "CR" to "Компьютерная рентгенография", "DX" to "Цифровая рентгенография",
        "MG" to "Маммография", "CT" to "Компьютерная томография",
        "MR" to "Магнитно-резонансная томография", "US" to "Ультразвуковое исследование",
        "RF" to "Рентгеноскопия", "XA" to "Рентгеновская ангиография",
        "NM" to "Ядерная медицина", "PT" to "Позитронная эмиссионная томография",
        "ECG" to "Электрокардиография", "ES" to "Эндоскопия",
        "IO" to "Внутриротовая рентгенография", "PX" to "Панорамная рентгенография",
        "XC" to "Цифровая фотография", "OP" to "Офтальмологическая фотография",
        "OCT" to "Оптическая когерентная томография (кроме офтальмологии)", "OT" to "Другая модальность"
    )
    fun label(code: String): String = standard.firstOrNull { it.first == code }?.let { "${it.first} — ${it.second}" } ?: code
}
