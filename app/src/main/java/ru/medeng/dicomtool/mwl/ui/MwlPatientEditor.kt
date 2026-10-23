package ru.medeng.dicomtool.mwl.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import ru.medeng.dicomtool.mwl.model.*
import ru.medeng.dicomtool.ui.components.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.Month
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PatientEditorDialog(patient: MwlPatient, onDismiss: () -> Unit, onSave: (MwlPatient) -> Unit) {
    // raw is authoritative even in the simple mode: opening/saving never normalizes an existing PN.
    var raw by rememberSaveable(patient.id) { mutableStateOf(patient.patientName) }
    var advanced by rememberSaveable(patient.id) { mutableStateOf(MwlPatientNameInput.simpleComponents(raw) == null) }
    val parts = MwlPatientNameInput.simpleComponents(raw)
    var family by rememberSaveable(patient.id) { mutableStateOf(parts?.family.orEmpty()) }
    var given by rememberSaveable(patient.id) { mutableStateOf(parts?.given.orEmpty()) }
    var middle by rememberSaveable(patient.id) { mutableStateOf(parts?.middle.orEmpty()) }
    var id by rememberSaveable(patient.id) { mutableStateOf(patient.patientId) }
    var birth by rememberSaveable(patient.id) { mutableStateOf(patient.patientBirthDate) }
    var sex by rememberSaveable(patient.id) { mutableStateOf(patient.patientSex) }
    MwlFormDialog(if (patient.patientName.isEmpty()) "Новый пациент" else "Редактирование пациента", onDismiss,
        // Keep even unusual historical DA unchanged when editing unrelated patient fields.
        saveEnabled = birth == patient.patientBirthDate || MwlBirthDate.valid(birth),
        onSave = { onSave(patient.copy(patientName = raw, patientId = id, patientBirthDate = birth, patientSex = sex, isSynthetic = false)) }) {
        if (advanced) {
            OutlinedTextField(raw, { raw = it }, label = { Text("Исходное имя DICOM PN") }, modifier = Modifier.fillMaxWidth(),
                trailingIcon = { DicomHelpButton(DicomFieldHelp.PatientName) })
            if (parts == null) Text("Это имя сохраняется целиком в расширенном режиме.", style = MaterialTheme.typography.bodySmall)
        } else {
            OutlinedTextField(family, { if (MwlPatientNameInput.validComponent(it)) { family = it; raw = MwlPatientNameInput.compose(it, given, middle) } },
                label = { Text("Фамилия") }, singleLine = true, trailingIcon = { DicomHelpButton(DicomFieldHelp.PatientName) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(given, { if (MwlPatientNameInput.validComponent(it)) { given = it; raw = MwlPatientNameInput.compose(family, it, middle) } },
                label = { Text("Имя") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(middle, { if (MwlPatientNameInput.validComponent(it)) { middle = it; raw = MwlPatientNameInput.compose(family, given, it) } },
                label = { Text("Отчество (необязательно)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        TextButton(onClick = {
            if (advanced && parts != null) { family = parts.family; given = parts.given; middle = parts.middle }
            advanced = !advanced
        }, enabled = !advanced || parts != null) {
            Text(if (advanced) "Ввод по отдельным полям" else "Расширенный редактор PN")
        }
        DicomHelpField(id, { id = it }, "Patient ID", DicomFieldHelp.PatientId)
        MwlBirthDateField(birth, { birth = it })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("M" to "Муж (M)", "F" to "Жен (F)", "O" to "Другой (O)").forEach { (code, label) ->
                FilterChip(
                    selected = sex == code,
                    onClick = { sex = if (sex == code) "" else code },
                    label = { Text(label) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MwlBirthDateField(value: String, onDate: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.weight(1f)) { Text("Дата рождения: ${MwlBirthDate.display(value)}") }
        DicomHelpButton(DicomFieldHelp.BirthDate)
    }
    if (!open) return
    val today = LocalDate.now()
    val initial = MwlBirthDate.parse(value)?.takeUnless { it.isAfter(today) || it.year < 1 }
    val shown = initial ?: today.minusYears(40)
    val selectable = remember(today) { object : SelectableDates {
        override fun isSelectableDate(utcTimeMillis: Long) = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate() <= today
        override fun isSelectableYear(year: Int) = year in 1..today.year
    } }
    val state = rememberDatePickerState(initialSelectedDateMillis = initial?.utcMillis(), initialDisplayedMonthMillis = shown.utcMillis(),
        yearRange = 1..today.year, selectableDates = selectable)
    var year by rememberSaveable { mutableStateOf(shown.year.toString()) }
    var month by rememberSaveable { mutableStateOf(shown.monthValue) }
    var monthsOpen by remember { mutableStateOf(false) }
    var calendarRevision by rememberSaveable { mutableIntStateOf(0) }
    var exact by rememberSaveable { mutableStateOf(false) }
    var raw by rememberSaveable { mutableStateOf(value) }
    val yearNumber = year.toIntOrNull()
    val selected = state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE) }
    MwlFormDialog("Дата рождения", onDismiss = { open = false }, saveLabel = "Выбрать", calendarContent = true,
        saveEnabled = if (exact) MwlBirthDate.valid(raw) else selected != null,
        onSave = { onDate(if (exact) raw else selected!!); open = false }) {
        val dialogFocus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        if (exact) {
            OutlinedTextField(raw, { raw = it }, label = { Text("Дата DICOM DA") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                isError = !MwlBirthDate.valid(raw))
            if (!MwlBirthDate.valid(raw)) Text("Введите действительную дату YYYYMMDD не позже сегодняшней или оставьте поле пустым.", color = MaterialTheme.colorScheme.error)
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(year, { year = it.filter(Char::isDigit).take(4) }, label = { Text("Год") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { dialogFocus.clearFocus(); keyboard?.hide() }),
                    modifier = Modifier.weight(1f), isError = yearNumber == null || yearNumber !in 1..today.year)
                Box(Modifier.weight(1f)) {
                    OutlinedButton(onClick = { dialogFocus.clearFocus(); keyboard?.hide(); monthsOpen = true }, modifier = Modifier.fillMaxWidth()) { Text(Month.of(month).getDisplayName(TextStyle.FULL_STANDALONE, Locale("ru"))) }
                    DropdownMenu(expanded = monthsOpen, onDismissRequest = { monthsOpen = false }) {
                        (1..12).forEach { number -> DropdownMenuItem(text = { Text(Month.of(number).getDisplayName(TextStyle.FULL_STANDALONE, Locale("ru"))) },
                            onClick = { month = number; monthsOpen = false }) }
                    }
                }
            }
            TextButton(onClick = {
                dialogFocus.clearFocus()
                keyboard?.hide()
                state.displayedMonthMillis = LocalDate.of(yearNumber!!, month, 1).utcMillis()
                calendarRevision++
            },
                enabled = yearNumber != null && yearNumber in 1..today.year && (yearNumber < today.year || month <= today.monthValue)) { Text("Перейти к месяцу") }
            // Material's month list remembers its initial scroll position. Recreate that list on an explicit jump.
            key(calendarRevision) { DatePicker(state, title = null, headline = null, showModeToggle = false) }
        }
        TextButton(onClick = {
            if (!exact) raw = selected ?: value
            else MwlBirthDate.parse(raw)?.takeIf { MwlBirthDate.valid(raw) }?.let { state.selectedDateMillis = it.utcMillis(); state.displayedMonthMillis = it.utcMillis() }
            exact = !exact
        }) { Text(if (exact) "Календарь" else "Точный ввод даты") }
        TextButton(onClick = { onDate(""); open = false }) { Text("Дата рождения неизвестна") }
    }
}

private fun LocalDate.utcMillis() = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
