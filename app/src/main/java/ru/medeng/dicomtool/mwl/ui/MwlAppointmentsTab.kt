package ru.medeng.dicomtool.mwl.ui

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import ru.medeng.dicomtool.mwl.model.MwlStationProfile

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.medeng.dicomtool.mwl.model.MwlModalityTemplate
import ru.medeng.dicomtool.mwl.model.MwlPatient
import ru.medeng.dicomtool.mwl.model.MwlScheduledStudy
import ru.medeng.dicomtool.mwl.model.MwlValidation
import ru.medeng.dicomtool.ui.components.WhiteDiagnosticCard
import ru.medeng.dicomtool.ui.theme.CardBorderGray
import ru.medeng.dicomtool.ui.theme.CardSurfaceWhite
import ru.medeng.dicomtool.ui.theme.ErrorRed
import ru.medeng.dicomtool.ui.theme.InputBackgroundGray
import ru.medeng.dicomtool.ui.theme.PrimaryBlue
import ru.medeng.dicomtool.ui.theme.PrimaryBlueLight
import ru.medeng.dicomtool.ui.theme.StopButtonRed
import ru.medeng.dicomtool.ui.theme.SuccessGreen
import ru.medeng.dicomtool.ui.theme.SuccessGreenBackground
import ru.medeng.dicomtool.ui.theme.TextPrimaryDark
import ru.medeng.dicomtool.ui.theme.TextSecondarySlate
import ru.medeng.dicomtool.ui.theme.WarningAmber
import ru.medeng.dicomtool.ui.theme.WarningAmberLight

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MwlAppointmentsTab(
    viewModel: MwlViewModel,
    isExpandedWidth: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val patients by viewModel.patients.collectAsStateWithLifecycle()
    val studies by viewModel.studies.collectAsStateWithLifecycle()
    val stationProfiles by viewModel.stationProfiles.collectAsStateWithLifecycle()
    var showPatients by rememberSaveable { mutableStateOf(false) }
    val searchQuery by viewModel.appointmentSearchQuery.collectAsStateWithLifecycle()
    val modalityFilter by viewModel.appointmentModalityFilter.collectAsStateWithLifecycle()

    val showPatientDialog by viewModel.showPatientDialog.collectAsStateWithLifecycle()
    val editingPatient by viewModel.editingPatient.collectAsStateWithLifecycle()

    val showStudyDialog by viewModel.showStudyDialog.collectAsStateWithLifecycle()
    val editingStudy by viewModel.editingStudy.collectAsStateWithLifecycle()

    val showSyntheticDialog by viewModel.showSyntheticDialog.collectAsStateWithLifecycle()
    val studyToDelete by viewModel.studyToDelete.collectAsStateWithLifecycle()
    val patientToDelete by viewModel.patientToDelete.collectAsStateWithLifecycle()

    val showExportDialog by viewModel.showDataExportDialog.collectAsStateWithLifecycle()
    val showImportDialog by viewModel.showDataImportDialog.collectAsStateWithLifecycle()

    // Фильтрация списка назначений
    val filteredStudies = remember(studies, searchQuery, modalityFilter) {
        val q = searchQuery.trim().lowercase()
        studies.filter { s ->
            val matchesMod = modalityFilter == "ALL" || s.modality.equals(modalityFilter, ignoreCase = true)
            val matchesQuery = q.isEmpty() ||
                    s.patientName.lowercase().contains(q) ||
                    s.patientId.lowercase().contains(q) ||
                    s.accessionNumber.lowercase().contains(q) ||
                    s.spsId.lowercase().contains(q) ||
                    s.requestedProcedureDescription.lowercase().contains(q)
            matchesMod && matchesQuery
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
        // Панель управления и поиска
        WhiteDiagnosticCard {
            viewModel.stationProfileReadError?.let { Text(it, color = ErrorRed) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Локальная база назначений (MWL)",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimaryDark
                    )
                    Text(
                        text = "Пациентов: ${patients.size} · Исследований: ${studies.size}",
                        fontSize = 12.sp,
                        color = TextSecondarySlate
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = { viewModel.showDataExportDialog.value = true }) {
                        Icon(Icons.Default.FileDownload, contentDescription = "Экспорт JSON", tint = PrimaryBlue)
                    }
                    IconButton(onClick = { viewModel.showDataImportDialog.value = true }) {
                        Icon(Icons.Default.FileUpload, contentDescription = "Импорт JSON", tint = PrimaryBlue)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Поисковая строка
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.appointmentSearchQuery.value = it },
                placeholder = { Text("Поиск по ФИО, ID, Accession, SPS…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Фильтр по модальностям
            val modalities = listOf("ALL", "CR", "DX", "MG", "CT", "US", "MR")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                modalities.forEach { mod ->
                    FilterChip(
                        selected = modalityFilter == mod,
                        onClick = { viewModel.appointmentModalityFilter.value = mod },
                        label = { Text(if (mod == "ALL") "Все модальности" else mod) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Кнопки добавления
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { viewModel.openNewStudyDialog() },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Назначение", fontSize = 13.sp)
                }

                OutlinedButton(
                    onClick = { viewModel.openNewPatientDialog() },
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Пациент", fontSize = 13.sp)
                }

                Button(
                    onClick = { viewModel.showSyntheticDialog.value = true },
                    colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                ) {
                    Icon(Icons.Default.AutoMode, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Тестовые", fontSize = 13.sp)
                }
            }
            FilterChip(selected = showPatients, onClick = { showPatients = !showPatients },
                label = { Text(if (showPatients) "Показать назначения" else "Пациенты (${patients.size})") })
        }
        }

        // Список найденных исследований
        if (showPatients) {
            items(patients.filter { searchQuery.isBlank() || it.patientName.contains(searchQuery.trim(), true) || it.patientId.contains(searchQuery.trim(), true) }, key = { "patient:${it.id}" }) { patient ->
                WhiteDiagnosticCard {
                    Text(patient.patientName, fontWeight = FontWeight.Bold)
                    Text("Patient ID: ${patient.patientId} · Назначений: ${studies.count { it.patientInternalId == patient.id }}")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { viewModel.openEditPatientDialog(patient) }) { Text("Редактировать пациента") }
                        TextButton(onClick = { viewModel.openNewStudyDialog(patient) }) { Text("Назначить исследование") }
                    }
                }
            }
        } else if (filteredStudies.isEmpty()) {
            item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = CardSurfaceWhite,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, CardBorderGray)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Назначения не найдены",
                        fontWeight = FontWeight.Bold,
                        color = TextPrimaryDark
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Нажмите «Тестовые», чтобы создать типовые назначения (Рентген, Маммография, КТ, УЗИ)",
                        fontSize = 12.sp,
                        color = TextSecondarySlate
                    )
                }
            }
            }
        } else {
                items(filteredStudies, key = { it.id }) { study ->
                    StudyCardItem(
                        study = study,
                        onEdit = { viewModel.openEditStudyDialog(study) },
                        onDuplicate = { viewModel.duplicateStudy(study) },
                        onDelete = { viewModel.confirmDeleteStudy(study) }
                    )
                }
        }
    }

    // --- Диалоги ---

    // 1. Диалог создания / редактирования пациента
    if (showPatientDialog && editingPatient != null) {
        PatientEditorDialog(
            patient = editingPatient!!,
            onDismiss = { viewModel.showPatientDialog.value = false },
            onSave = { p ->
                val result = viewModel.savePatient(p)
                if (result is MwlValidation.Result.Invalid) {
                    Toast.makeText(context, result.messageRu, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // 2. Диалог создания / редактирования исследования
    if (showStudyDialog && editingStudy != null) {
        StudyEditorDialog(
            study = editingStudy!!,
            patients = patients,
            stationProfiles = stationProfiles,
            onNewPatient = viewModel::openNewPatientDialog,
            onSaveStation = viewModel::saveStationProfile,
            onDismiss = { viewModel.showStudyDialog.value = false },
            onSave = { s ->
                val result = viewModel.saveStudy(s)
                if (result is MwlValidation.Result.Invalid) {
                    Toast.makeText(context, result.messageRu, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // 3. Диалог генерации синтетических данных
    if (showSyntheticDialog) {
        SyntheticGeneratorDialog(
            onDismiss = { viewModel.showSyntheticDialog.value = false },
            onGenerate = { template, mode, count ->
                viewModel.generateSynthetic(template, mode, count)
                Toast.makeText(context, "Синтетические данные созданы", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // 4. Диалог подтверждения удаления исследования
    if (studyToDelete != null) {
        AlertDialog(
            onDismissRequest = { viewModel.studyToDelete.value = null },
            title = { Text("Удалить назначение?") },
            text = { Text("Назначение ${studyToDelete?.accessionNumber} (${studyToDelete?.modality} · ${studyToDelete?.patientName}) будет безвозвратно удалено.") },
            confirmButton = {
                Button(
                    onClick = { viewModel.executeDeleteStudy() },
                    colors = ButtonDefaults.buttonColors(containerColor = StopButtonRed)
                ) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.studyToDelete.value = null }) {
                    Text("Отмена")
                }
            }
        )
    }

    // 5. Диалог экспорта JSON
    if (showExportDialog) {
        val exportedJson = remember { viewModel.exportSyntheticData() }
        AlertDialog(
            onDismissRequest = { viewModel.showDataExportDialog.value = false },
            title = { Text("Экспорт синтетических назначений (JSON)") },
            text = {
                Column {
                    Text("Экспортируются ТОЛЬКО синтетические шаблоны. Реальные данные пациентов защищены и не выгружаются.", fontSize = 12.sp, color = TextSecondarySlate)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = exportedJson,
                        onValueChange = {},
                        readOnly = true,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    clipboardManager.setText(AnnotatedString(exportedJson))
                    Toast.makeText(context, "JSON скопирован в буфер обмена", Toast.LENGTH_SHORT).show()
                    viewModel.showDataExportDialog.value = false
                }) {
                    Text("Скопировать в буфер")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.showDataExportDialog.value = false }) {
                    Text("Закрыть")
                }
            }
        )
    }

    // 6. Диалог импорта JSON
    if (showImportDialog) {
        var importInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { viewModel.showDataImportDialog.value = false },
            title = { Text("Импорт тестовых назначений") },
            text = {
                Column {
                    Text("Вставьте JSON-структуру синтетических пациентов и исследований:", fontSize = 12.sp, color = TextSecondarySlate)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = importInput,
                        onValueChange = { importInput = it },
                        placeholder = { Text("{\n  \"version\": 1,\n  \"patients\": [...]\n}") },
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (importInput.isBlank()) {
                        Toast.makeText(context, "Вставьте JSON для импорта", Toast.LENGTH_SHORT).show()
                    } else {
                        try {
                            val count = viewModel.importSyntheticData(importInput)
                            Toast.makeText(context, "Импортировано исследований: $count", Toast.LENGTH_SHORT).show()
                            viewModel.showDataImportDialog.value = false
                        } catch (_: Exception) {
                            Toast.makeText(context, "Импорт не выполнен: проверьте формат, обязательные поля и доступное место. Сохранённые данные не изменены.", Toast.LENGTH_LONG).show()
                        }
                    }
                }) {
                    Text("Импортировать")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.showDataImportDialog.value = false }) {
                    Text("Отмена")
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StudyCardItem(study: MwlScheduledStudy, onEdit: () -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit) {
    WhiteDiagnosticCard {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(study.modality, fontWeight = FontWeight.Bold, color = PrimaryBlue)
            if (study.isSynthetic) Text("ТЕСТ", color = SuccessGreen)
            Text(study.patientName, fontWeight = FontWeight.Bold)
        }
        Text(study.requestedProcedureDescription.ifEmpty { study.spsDescription }, color = TextSecondarySlate)
        Text("ID: ${study.patientId} · ACC: ${study.accessionNumber}", fontSize = 12.sp)
        Text("${study.spsStartDate} ${study.spsStartTime} · ${study.scheduledStationAeTitle}", fontSize = 12.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = onDuplicate) { Icon(Icons.Default.ContentCopy, "Дублировать", tint = TextSecondarySlate) }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Редактировать", tint = PrimaryBlue) }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Удалить", tint = ErrorRed) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PatientEditorDialog(patient: MwlPatient, onDismiss: () -> Unit, onSave: (MwlPatient) -> Unit) {
    var name by rememberSaveable(patient.id) { mutableStateOf(patient.patientName) }
    var id by rememberSaveable(patient.id) { mutableStateOf(patient.patientId) }
    var birth by rememberSaveable(patient.id) { mutableStateOf(patient.patientBirthDate) }
    var sex by rememberSaveable(patient.id) { mutableStateOf(patient.patientSex) }
    MwlFormDialog(if (patient.patientName.isEmpty()) "Новый пациент" else "Редактирование пациента", onDismiss,
        // Manual edits must never retain a synthetic provenance flag and enter the synthetic-only export.
        onSave = { onSave(patient.copy(patientName = name.trim(), patientId = id.trim(), patientBirthDate = birth.trim(), patientSex = sex, isSynthetic = false)) }) {
        OutlinedTextField(name, { name = it }, label = { Text("ФИО · PN: Фамилия^Имя^Отчество") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(id, { id = it }, label = { Text("Patient ID · LO, до 64 символов") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(birth, { birth = it.filter(Char::isDigit) }, label = { Text("Дата рождения YYYYMMDD (необязательно)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("M" to "Муж (M)", "F" to "Жен (F)", "O" to "Другой (O)").forEach { (code, label) ->
                FilterChip(selected = sex == code, onClick = { sex = code }, label = { Text(label) })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StudyEditorDialog(
    study: MwlScheduledStudy, patients: List<MwlPatient>, stationProfiles: List<MwlStationProfile>,
    onNewPatient: () -> Unit, onSaveStation: (MwlStationProfile) -> MwlValidation.Result,
    onDismiss: () -> Unit, onSave: (MwlScheduledStudy) -> Unit
) {
    var selectedId by rememberSaveable(study.id) { mutableStateOf(study.patientInternalId) }
    LaunchedEffect(study.patientInternalId) { selectedId = study.patientInternalId }
    val patient = patients.firstOrNull { it.id == selectedId }
    var patientPicker by rememberSaveable(study.id) { mutableStateOf(false) }
    var patientQuery by rememberSaveable { mutableStateOf("") }
    val draftSaver = remember(study.id) { listSaver<MwlScheduledStudy, String>(save = {
        listOf(it.modality, it.scheduledStationAeTitle, it.scheduledStationName, it.spsLocation,
            it.requestedProcedureDescription, it.spsDescription, it.requestedProcedureId, it.spsId,
            it.accessionNumber, it.spsStartDate, it.spsStartTime, it.studyInstanceUid)
    }, restore = { study.copy(modality = it[0], scheduledStationAeTitle = it[1], scheduledStationName = it[2],
        spsLocation = it[3], requestedProcedureDescription = it[4], spsDescription = it[5], requestedProcedureId = it[6],
        spsId = it[7], accessionNumber = it[8], spsStartDate = it[9], spsStartTime = it[10], studyInstanceUid = it[11]) }) }
    var draft by rememberSaveable(study.id, stateSaver = draftSaver) { mutableStateOf(study) }
    var editingStation by remember { mutableStateOf<MwlStationProfile?>(null) }
    var selectedStationId by rememberSaveable { mutableStateOf<String?>(null) }
    MwlFormDialog(if (study.patientInternalId.isBlank()) "Новое назначение" else "Назначение", onDismiss,
        saveEnabled = patient != null,
        onSave = {
            patient?.let { p -> onSave(draft.copy(patientInternalId = p.id, patientName = p.patientName,
                patientId = p.patientId, isSynthetic = p.isSynthetic && study.isSynthetic,
                modality = draft.modality.trim(), scheduledStationAeTitle = draft.scheduledStationAeTitle.trim(),
                scheduledStationName = draft.scheduledStationName.trim(), spsLocation = draft.spsLocation.trim(),
                requestedProcedureDescription = draft.requestedProcedureDescription.trim(), spsDescription = draft.spsDescription.trim(),
                requestedProcedureId = draft.requestedProcedureId.trim(), spsId = draft.spsId.trim(),
                accessionNumber = draft.accessionNumber.trim(), spsStartDate = draft.spsStartDate.trim(),
                spsStartTime = draft.spsStartTime.trim(), studyInstanceUid = draft.studyInstanceUid.trim())) }
        }) {
        Text("Пациент (обязательно)", fontWeight = FontWeight.Bold)
        Text(patient?.let { "${it.patientName} · ${it.patientId}" } ?: "Пациент не выбран")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { patientPicker = true }) { Text("Выбрать пациента") }
            TextButton(onClick = onNewPatient) { Text("Создать пациента") }
        }
        Text("Изменение пациента применяется только кнопкой «Сохранить».", fontSize = 12.sp)
        Text("Профиль аппарата (поля можно изменить вручную)", fontWeight = FontWeight.Bold)
        stationProfiles.forEach { profile ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { draft = profile.applyTo(draft); selectedStationId = profile.id }, modifier = Modifier.weight(1f)) {
                    Text("${if (selectedStationId == profile.id) "✓ " else ""}${profile.name} · ${profile.modality} · ${profile.scheduledStationAeTitle}")
                }
                IconButton(onClick = { editingStation = profile }) { Icon(Icons.Default.Edit, "Редактировать профиль ${profile.name}") }
            }
        }
        TextButton(onClick = { editingStation = MwlStationProfile(name = "", modality = draft.modality,
            scheduledStationAeTitle = draft.scheduledStationAeTitle, scheduledStationName = draft.scheduledStationName, location = draft.spsLocation) }) { Text("Новый профиль аппарата") }
        OutlinedTextField(draft.modality, { draft = draft.copy(modality = it.uppercase(java.util.Locale.US)) }, label = { Text("Modality · CS, до 16 символов") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(draft.scheduledStationAeTitle, { draft = draft.copy(scheduledStationAeTitle = it) }, label = { Text("AE Title аппарата · AE, до 16 байт ASCII") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(draft.scheduledStationName, { draft = draft.copy(scheduledStationName = it) }, label = { Text("Название кабинета / станции · SH, до 16 символов") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(draft.spsLocation, { draft = draft.copy(spsLocation = it) }, label = { Text("Место проведения исследования · SH, до 16 символов") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(draft.requestedProcedureDescription, { draft = draft.copy(requestedProcedureDescription = it, spsDescription = it) }, label = { Text("Описание исследования · LO, до 64 символов") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(draft.requestedProcedureId, { draft = draft.copy(requestedProcedureId = it) }, label = { Text("Requested Procedure ID · SH, до 16 символов") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(draft.spsId, { draft = draft.copy(spsId = it) }, label = { Text("SPS ID · SH, до 16 символов") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(draft.accessionNumber, { draft = draft.copy(accessionNumber = it) }, label = { Text("Accession Number · SH, до 16 символов") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        MwlCalendarField("Дата назначения", draft.spsStartDate, { draft = draft.copy(spsStartDate = it) })
        OutlinedTextField(draft.spsStartTime, { draft = draft.copy(spsStartTime = it) }, label = { Text("Время SPS · TM: HH[MM[SS[.FFFFFF]]]") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(draft.studyInstanceUid, { draft = draft.copy(studyInstanceUid = it.trim()) }, label = { Text("Study Instance UID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { draft = draft.copy(studyInstanceUid = MwlValidation.generateStudyInstanceUid()) }) { Text("Сгенерировать UID") }
    }
    if (patientPicker) AlertDialog(onDismissRequest = { patientPicker = false }, title = { Text("Выбор пациента") }, text = {
        Column {
            OutlinedTextField(patientQuery, { patientQuery = it }, label = { Text("Поиск: Patient Name / Patient ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                items(patients.filter { patientQuery.isBlank() || it.patientName.contains(patientQuery.trim(), true) || it.patientId.contains(patientQuery.trim(), true) }, key = { it.id }) { p ->
                    TextButton(onClick = { selectedId = p.id; patientPicker = false }, modifier = Modifier.fillMaxWidth()) { Text("${p.patientName} · ${p.patientId}") }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { patientPicker = false }) { Text("Закрыть") } })
    editingStation?.let { profile ->
        MwlStationEditor(profile, onDismiss = { editingStation = null }, onSave = { edited ->
            val result = onSaveStation(edited)
            if (result is MwlValidation.Result.Valid) { draft = edited.applyTo(draft); selectedStationId = edited.id }
            result
        })
    }
}
@Composable
private fun SyntheticGeneratorDialog(
    onDismiss: () -> Unit,
    onGenerate: (template: MwlModalityTemplate, mode: Int, count: Int) -> Unit
) {
    var selectedTemplate by remember { mutableStateOf(MwlModalityTemplate.CHEST_XRAY) }
    var selectedMode by remember { mutableIntStateOf(0) } // 0: single, 1: multiple patients, 2: multiple studies
    var count by remember { mutableIntStateOf(5) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Генератор синтетических назначений") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Шаблон модальности и исследования:", fontSize = 12.sp, color = TextSecondarySlate)
                MwlModalityTemplate.entries.forEach { template ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedTemplate = template },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selectedTemplate == template, onClick = { selectedTemplate = template })
                        Column(modifier = Modifier.padding(start = 6.dp)) {
                            Text(template.titleRu, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(template.spsDescription, fontSize = 11.sp, color = TextSecondarySlate)
                        }
                    }
                }

                HorizontalDivider(color = CardBorderGray)

                Text("Режим генерации:", fontSize = 12.sp, color = TextSecondarySlate)
                val modes = listOf(
                    0 to "Один пациент (1 исследование)",
                    1 to "Пакет из 5 пациентов (разные аппараты)",
                    2 to "Один пациент с несколькими исследованиями"
                )
                modes.forEach { (mCode, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedMode = mCode },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selectedMode == mCode, onClick = { selectedMode = mCode })
                        Text(label, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onGenerate(selectedTemplate, selectedMode, count) },
                colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen)
            ) {
                Text("Сгенерировать")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}
