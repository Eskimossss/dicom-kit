package ru.medeng.dicomtool.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

enum class DicomFieldHelp(val title: String, val tag: String, val purpose: String, val example: String, val limits: String) {
    PatientName("Имя пациента", "(0010,0010)", "Имя в формате DICOM PN. Группы после = — альтернативные представления имени.", "ИВАНОВ^ИВАН^ИВАНОВИЧ", "PN: до 3 групп по 64 символа, до 5 компонентов в группе: фамилия^имя^отчество^префикс^суффикс. Пробелы не заменяются на ^ автоматически."),
    PatientId("Patient ID", "(0010,0020)", "Идентификатор пациента в медицинской системе. Не является частью ФИО.", "SYNTH-PAT-0001", "LO: до 64 символов. Существующий ID сохраняется, пока вы явно его не измените."),
    Modality("Модальность", "(0008,0060)", "Тип исследования в Scheduled Procedure Step. Выбор кода не добавляет поддержку новых SOP Class.", "CT", "CS: до 16 символов, A–Z, цифры, пробел и подчёркивание. Можно ввести другой код вручную."),
    Accession("Номер направления (Accession Number)", "(0008,0050)", "Номер запроса на исследование в направляющей системе.", "ACC-0001", "SH: до 16 символов."),
    StudyUid("Study Instance UID", "(0020,000D)", "Глобальный идентификатор исследования. Сгенерируйте новый для нового исследования.", "2.25.123456789", "UI: до 64 символов, цифры и точки; без ведущих нулей в компонентах, кроме 0."),
    ProcedureId("Requested Procedure ID", "(0040,1001)", "Идентификатор запрошенной процедуры.", "RP-0001", "SH: до 16 символов."),
    StepId("Scheduled Procedure Step ID", "(0040,0009)", "Идентификатор запланированного этапа процедуры.", "SPS-0001", "SH: до 16 символов; внутри Sequence (0040,0100)."),
    StationAe("Scheduled Station AE Title", "(0040,0001)", "DICOM AE Title аппарата, которому назначено исследование. Это не IP-адрес сервера MWL.", "CT_ROOM_1", "AE: до 16 байт ASCII, без обратной косой черты и управляющих символов; внутри (0040,0100)."),
    StationName("Scheduled Station Name", "(0040,0010)", "Название станции или кабинета для запланированного этапа.", "CT-ROOM-1", "SH: до 16 символов; внутри (0040,0100)."),
    StepLocation("Scheduled Procedure Step Location", "(0040,0011)", "Место выполнения запланированного этапа.", "FLOOR-2", "SH: до 16 символов; внутри (0040,0100)."),
    StepDate("Дата начала этапа", "(0040,0002)", "Календарная дата начала Scheduled Procedure Step.", "20261021", "DA: YYYYMMDD, 8 цифр, действительная дата; внутри (0040,0100)."),
    StepTime("Время начала этапа", "(0040,0003)", "Локальное время начала Scheduled Procedure Step.", "093000", "TM: HH[MM[SS[.FFFFFF]]], часы 00–23; внутри (0040,0100)."),
    BirthDate("Дата рождения", "(0010,0030)", "Дата рождения пациента; может отсутствовать.", "19800515", "DA: YYYYMMDD. В форме доступны календарь, быстрый переход к году и месяцу и точный ввод. Будущая дата недопустима."),
    Description("Описание исследования", "(0032,1060), (0040,0007)", "Описание запрошенной процедуры и запланированного этапа.", "КТ органов грудной клетки", "LO: до 64 символов.")
}

@Composable
fun DicomHelpButton(help: DicomFieldHelp) {
    var open by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { open = true }) { Icon(Icons.AutoMirrored.Outlined.HelpOutline, "Справка: ${help.title}", Modifier.size(20.dp)) }
    if (open) AppDialog(onDismiss = { open = false }) {
            val compact = maxHeight < 200.dp
            Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight).testTag("dicom-help"), shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = if (compact) 4.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 12.dp)) {
                    if (!compact) Text(help.title, style = MaterialTheme.typography.titleLarge)
                    Column(Modifier.weight(1f, false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (compact) Text(help.title, style = MaterialTheme.typography.titleMedium)
                        Text("DICOM Tag: ${help.tag}", style = MaterialTheme.typography.labelLarge)
                        Text(help.purpose)
                        Text("Пример: ${help.example}")
                        Text(help.limits)
                    }
                    TextButton(onClick = { open = false }, modifier = Modifier.align(Alignment.End)) { Text("Закрыть") }
                }
            }
    }
}

@Composable
fun DicomHelpField(value: String, onValueChange: (String) -> Unit, label: String, help: DicomFieldHelp, modifier: Modifier = Modifier) {
    OutlinedTextField(value, onValueChange, label = { Text(label) }, singleLine = true,
        trailingIcon = { DicomHelpButton(help) }, modifier = modifier.fillMaxWidth())
}
