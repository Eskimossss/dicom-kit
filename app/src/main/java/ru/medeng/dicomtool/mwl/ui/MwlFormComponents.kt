package ru.medeng.dicomtool.mwl.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import ru.medeng.dicomtool.mwl.model.*
import ru.medeng.dicomtool.ui.components.*
import androidx.compose.ui.platform.LocalFocusManager

@Composable
internal fun MwlAdaptiveFields(first: @Composable () -> Unit, second: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (maxWidth >= 480.dp) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) { first() }
            Column(Modifier.weight(1f)) { second() }
        } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { first(); second() }
    }
}

/** Content scrolls above stable actions, including landscape and the software keyboard. */
@Composable
internal fun MwlFormDialog(
    title: String, onDismiss: () -> Unit, onSave: () -> Unit,
    saveEnabled: Boolean = true, saveLabel: String = "Сохранить", calendarContent: Boolean = false, content: @Composable ColumnScope.() -> Unit
) {
    // The seven 48dp day targets and Material's internal padding need the full 360dp on a phone.
    AppDialog(onDismiss, padding = if (calendarContent) PaddingValues(vertical = 16.dp) else PaddingValues(16.dp)) {
            val compact = maxHeight < 200.dp
            Surface(Modifier.widthIn(max = 640.dp).fillMaxWidth().heightIn(max = maxHeight).testTag("mwl-form:$title"),
                shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(horizontal = if (calendarContent) 0.dp else 16.dp, vertical = if (compact) 4.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) {
                    if (!compact) Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = if (calendarContent) 16.dp else 0.dp))
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).testTag("mwl-form-content:$title"),
                        verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
                    Row(Modifier.fillMaxWidth().padding(horizontal = if (calendarContent) 16.dp else 0.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text("Отмена") }
                        Button(onClick = onSave, enabled = saveEnabled) { Text(saveLabel) }
                    }
                }
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MwlCalendarField(label: String, value: String, onDate: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = modifier.fillMaxWidth()) {
        val date = runCatching { LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull()
        Text("$label: ${date?.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) ?: "выбрать дату"}")
    }
    if (open) {
        val initial = runCatching { LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull()
        val state = rememberDatePickerState(initialSelectedDateMillis = initial?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli())
        MwlFormDialog("Выбор даты", onDismiss = { open = false }, saveEnabled = state.selectedDateMillis != null, calendarContent = true,
            saveLabel = "Выбрать", onSave = {
                state.selectedDateMillis?.let {
                    onDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE))
                }
                open = false
            }) {
            DatePicker(state = state, showModeToggle = true, title = null, headline = null)
        }
    }
}

@Composable
internal fun MwlStationEditor(profile: MwlStationProfile, onDismiss: () -> Unit, onSave: (MwlStationProfile) -> MwlValidation.Result) {
    var name by rememberSaveable(profile.id) { mutableStateOf(profile.name) }
    var modality by rememberSaveable(profile.id) { mutableStateOf(profile.modality) }
    var ae by rememberSaveable(profile.id) { mutableStateOf(profile.scheduledStationAeTitle) }
    var station by rememberSaveable(profile.id) { mutableStateOf(profile.scheduledStationName) }
    var location by rememberSaveable(profile.id) { mutableStateOf(profile.location) }
    var error by remember { mutableStateOf<String?>(null) }
    MwlFormDialog("Профиль аппарата", onDismiss, onSave = {
        val result = onSave(profile.copy(name = name.trim(), modality = modality.trim(),
            scheduledStationAeTitle = ae.trim(), scheduledStationName = station.trim(), location = location.trim()))
        if (result is MwlValidation.Result.Invalid) error = result.messageRu else onDismiss()
    }) {
        OutlinedTextField(name, { name = it }, label = { Text("Название профиля") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        MwlModalityField(modality, { modality = it })
        DicomHelpField(ae, { ae = it }, "AE Title аппарата", DicomFieldHelp.StationAe)
        DicomHelpField(station, { station = it }, "Название станции", DicomFieldHelp.StationName)
        DicomHelpField(location, { location = it }, "Место проведения", DicomFieldHelp.StepLocation)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
internal fun MwlModalityField(value: String, onValue: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var manual by rememberSaveable { mutableStateOf(value.isNotEmpty() && MwlModalityChoices.standard.none { it.first == value }) }
    val focus = LocalFocusManager.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            OutlinedButton(onClick = { focus.clearFocus(); open = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Модальность: ${MwlModalityChoices.label(value).ifEmpty { "выбрать" }}")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                MwlModalityChoices.standard.forEach { (code, description) ->
                    DropdownMenuItem(text = { Text("$code — $description") }, onClick = { onValue(code); manual = false; open = false })
                }
                DropdownMenuItem(text = { Text("Другой код — ручной ввод") }, onClick = { manual = true; open = false })
            }
        }
        DicomHelpButton(DicomFieldHelp.Modality)
    }
    if (manual) DicomHelpField(value, { onValue(it.uppercase(java.util.Locale.US)) }, "Код модальности", DicomFieldHelp.Modality)
}
