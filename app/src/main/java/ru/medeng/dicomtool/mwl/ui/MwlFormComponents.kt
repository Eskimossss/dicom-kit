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
    saveEnabled: Boolean = true, saveLabel: String = "Сохранить", content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().imePadding().padding(16.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 640.dp).fillMaxWidth().heightIn(max = maxHeight).testTag("mwl-form:$title"),
                shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).testTag("mwl-form-content:$title"),
                        verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text("Отмена") }
                        Button(onClick = onSave, enabled = saveEnabled) { Text(saveLabel) }
                    }
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
        MwlFormDialog("Выбор даты", onDismiss = { open = false }, saveEnabled = state.selectedDateMillis != null,
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
        OutlinedTextField(modality, { modality = it.uppercase(java.util.Locale.US) }, label = { Text("Modality · CS, до 16 символов") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(ae, { ae = it }, label = { Text("AE Title аппарата · AE, до 16 байт ASCII") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(station, { station = it }, label = { Text("Название кабинета / станции · SH, до 16 символов") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(location, { location = it }, label = { Text("Место проведения исследования · SH, до 16 символов") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
