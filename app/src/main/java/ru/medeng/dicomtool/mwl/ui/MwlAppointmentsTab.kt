package ru.medeng.dicomtool.mwl.ui

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import ru.medeng.dicomtool.mwl.model.MwlStationProfile
import ru.medeng.dicomtool.dicom.charset.DicomPersonName
import ru.medeng.dicomtool.ui.components.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.style.TextOverflow
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
    val searchQuery by viewModel.appointmentSearchQuery.collectAsStateWithLifecycle()
    val modalityFilter by viewModel.appointmentModalityFilter.collectAsStateWithLifecycle()
    var searchOpen by rememberSaveable { mutableStateOf(searchQuery.isNotEmpty()) }

    val showPatientDialog by viewModel.showPatientDialog.collectAsStateWithLifecycle()
    val editingPatient by viewModel.editingPatient.collectAsStateWithLifecycle()

    val showStudyDialog by viewModel.showStudyDialog.collectAsStateWithLifecycle()
    val editingStudy by viewModel.editingStudy.collectAsStateWithLifecycle()

    val showSyntheticDialog by viewModel.showSyntheticDialog.collectAsStateWithLifecycle()
    val studyToDelete by viewModel.studyToDelete.collectAsStateWithLifecycle()

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
        // Панель управления и поиска (компактный адаптивный UI)
        WhiteDiagnosticCard {
            viewModel.stationProfileReadError?.let { Text(it, color = ErrorRed) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Назначения MWL (${filteredStudies.size}/${studies.size})",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimaryDark,
                        maxLines = 1
                    )
                    Text(
                        text = "Пациентов: ${patients.size} · Назначений: ${studies.size}",
                        fontSize = 11.sp,
                        color = TextSecondarySlate,
                        maxLines = 1
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        modifier = Modifier.size(44.dp),
                        onClick = {
                            if (searchOpen && searchQuery.isNotEmpty()) {
                                viewModel.appointmentSearchQuery.value = ""
                            }
                            searchOpen = !searchOpen
                        }
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = if (searchOpen) "Скрыть поиск" else "Поиск",
                            tint = if (searchOpen || searchQuery.isNotEmpty()) PrimaryBlue else TextSecondarySlate
                        )
                    }
                    IconButton(
                        modifier = Modifier.size(44.dp),
                        onClick = { viewModel.showDataExportDialog.value = true }
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = "Экспорт JSON", tint = PrimaryBlue)
                    }
                    IconButton(
                        modifier = Modifier.size(44.dp),
                        onClick = { viewModel.showDataImportDialog.value = true }
                    ) {
                        Icon(Icons.Default.FileUpload, contentDescription = "Импорт JSON", tint = PrimaryBlue)
                    }
                }
            }

            if (searchOpen) {
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.appointmentSearchQuery.value = it },
                    placeholder = { Text("Поиск по пациенту, ID или Accession", fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        IconButton(onClick = {
                            if (searchQuery.isNotEmpty()) {
                                viewModel.appointmentSearchQuery.value = ""
                            } else {
                                searchOpen = false
                            }
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Очистить поиск", modifier = Modifier.size(18.dp))
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))
                val modalities = listOf("ALL", "CR", "DX", "MG", "CT", "US", "MR")
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    modalities.forEach { mod ->
                        FilterChip(
                            selected = modalityFilter == mod,
                            onClick = { viewModel.appointmentModalityFilter.value = mod },
                            label = { Text(if (mod == "ALL") "Все" else mod, fontSize = 11.sp) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { viewModel.openNewStudyDialog() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Назначение", fontSize = 12.sp)
                }

                Button(
                    onClick = { viewModel.showSyntheticDialog.value = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen)
                ) {
                    Icon(Icons.Default.AutoMode, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Тестовые", fontSize = 12.sp)
                }
            }
        }
        }

        // Список найденных исследований
        if (filteredStudies.isEmpty()) {
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
            onDismiss = { viewModel.closeStudyDialog() },
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
            Text(DicomPersonName.display(study.patientName, false).ifEmpty { "ФИО не указано" }, fontWeight = FontWeight.Bold)
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

@Composable
internal fun MwlPatientIdentity(patient: MwlPatient) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = DicomPersonName.display(patient.patientName, false).ifEmpty { "ФИО не указано" },
            fontWeight = FontWeight.Bold,
            color = TextPrimaryDark
        )
        Text(
            text = "ID: ${patient.patientId}" + if (patient.patientBirthDate.isNotBlank()) " · ${patient.patientBirthDate}" else "",
            fontSize = 12.sp,
            color = TextSecondarySlate
        )
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
    var showConfirmOverwriteIds by remember { mutableStateOf(false) }
    var showConfirmOverwriteUid by remember { mutableStateOf(false) }
    val initialProfileId = remember(study.id, stationProfiles) {
        stationProfiles.firstOrNull { profile ->
            profile.scheduledStationAeTitle.isNotBlank() &&
            profile.scheduledStationAeTitle.equals(study.scheduledStationAeTitle, ignoreCase = true) &&
            (profile.modality.isBlank() || profile.modality.equals(study.modality, ignoreCase = true))
        }?.id
    }
    var selectedStationId by rememberSaveable(study.id) { mutableStateOf(initialProfileId) }
    val selectedProfile = stationProfiles.firstOrNull { it.id == selectedStationId }
    MwlFormDialog(if (study.patientInternalId.isBlank()) "Новое назначение" else "Назначение", onDismiss,
        saveEnabled = patient != null,
        onSave = {
            val finalModality = (selectedProfile?.modality ?: draft.modality).trim()
            val finalStudyUid = draft.studyInstanceUid.trim().ifBlank { MwlValidation.generateStudyInstanceUid() }
            val fallbackNum = (100000..999999).random()
            val finalAcc = draft.accessionNumber.trim().ifBlank { "ACC-$fallbackNum" }
            val finalRpId = draft.requestedProcedureId.trim().ifBlank { "RP-$fallbackNum" }
            val finalSpsId = draft.spsId.trim().ifBlank { "SPS-$fallbackNum" }
            val finalDesc = draft.requestedProcedureDescription.trim()
            val finalSpsDesc = draft.spsDescription.trim().ifBlank { finalDesc }
            patient?.let { p -> onSave(draft.copy(patientInternalId = p.id, patientName = p.patientName,
                patientId = p.patientId, isSynthetic = p.isSynthetic && study.isSynthetic,
                modality = finalModality, scheduledStationAeTitle = draft.scheduledStationAeTitle.trim(),
                scheduledStationName = draft.scheduledStationName.trim(), spsLocation = draft.spsLocation.trim(),
                requestedProcedureDescription = finalDesc, spsDescription = finalSpsDesc,
                requestedProcedureId = finalRpId, spsId = finalSpsId,
                accessionNumber = finalAcc, spsStartDate = draft.spsStartDate.trim(),
                spsStartTime = draft.spsStartTime.trim(), studyInstanceUid = finalStudyUid)) }
        }) {
        Text("Пациент (обязательно)", fontWeight = FontWeight.Bold)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = InputBackgroundGray,
            border = BorderStroke(1.dp, CardBorderGray)
        ) {
            Box(Modifier.padding(10.dp)) {
                if (patient != null) {
                    MwlPatientIdentity(patient)
                } else {
                    Text("Пациент не выбран", color = ErrorRed, fontSize = 13.sp)
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { patientPicker = true },
                modifier = Modifier.weight(1f)
            ) {
                Text("Выбрать пациента", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(
                onClick = onNewPatient,
                modifier = Modifier.weight(1f)
            ) {
                Text("Создать пациента", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Аппарат", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            OutlinedButton(
                onClick = {
                    editingStation = MwlStationProfile(
                        name = "",
                        modality = draft.modality.ifBlank { "CR" },
                        scheduledStationAeTitle = draft.scheduledStationAeTitle,
                        scheduledStationName = draft.scheduledStationName,
                        location = draft.spsLocation
                    )
                },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.defaultMinSize(minHeight = 36.dp)
            ) {
                Text("+ Новый профиль", fontSize = 12.sp)
            }
        }

        if (selectedProfile != null) {
            var switchStationMenuOpen by remember { mutableStateOf(false) }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = CardSurfaceWhite,
                border = BorderStroke(1.dp, PrimaryBlue.copy(alpha = 0.6f))
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = selectedProfile.name,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = TextPrimaryDark
                            )
                            Text(
                                text = "AE: ${selectedProfile.scheduledStationAeTitle}",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = TextSecondarySlate
                            )
                            if (selectedProfile.scheduledStationName.isNotBlank()) {
                                Text(
                                    text = "Станция: ${selectedProfile.scheduledStationName}",
                                    fontSize = 11.sp,
                                    color = TextSecondarySlate
                                )
                            }
                            Text(
                                text = "Модальность: ${selectedProfile.modality}",
                                fontSize = 11.sp,
                                color = TextSecondarySlate
                            )
                        }
                        IconButton(
                            onClick = { editingStation = selectedProfile },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Редактировать профиль ${selectedProfile.name}",
                                tint = PrimaryBlue
                            )
                        }
                    }

                    HorizontalDivider(
                        color = CardBorderGray.copy(alpha = 0.6f),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.weight(1f)) {
                            OutlinedButton(
                                onClick = {
                                    if (stationProfiles.size > 1) {
                                        switchStationMenuOpen = true
                                    }
                                },
                                enabled = stationProfiles.size > 1,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 36.dp)
                            ) {
                                Text(
                                    text = "Сменить аппарат",
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            DropdownMenu(
                                expanded = switchStationMenuOpen,
                                onDismissRequest = { switchStationMenuOpen = false }
                            ) {
                                stationProfiles.filter { it.id != selectedProfile.id }.forEach { profile ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(profile.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                                Text(
                                                    "${profile.modality} · ${profile.scheduledStationAeTitle}",
                                                    fontSize = 11.sp,
                                                    color = TextSecondarySlate
                                                )
                                            }
                                        },
                                        onClick = {
                                            selectedStationId = profile.id
                                            draft = profile.applyTo(draft)
                                            switchStationMenuOpen = false
                                        }
                                    )
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = { selectedStationId = null },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed),
                            modifier = Modifier.weight(1f).defaultMinSize(minHeight = 36.dp)
                        ) {
                            Text(
                                text = "Снять выбор",
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = InputBackgroundGray.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, CardBorderGray)
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (stationProfiles.isNotEmpty()) {
                        Text("Выберите аппарат или введите вручную:", fontSize = 11.sp, color = TextSecondarySlate)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            stationProfiles.forEach { profile ->
                                OutlinedButton(
                                    onClick = {
                                        selectedStationId = profile.id
                                        draft = profile.applyTo(draft)
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.defaultMinSize(minHeight = 32.dp)
                                ) {
                                    Text("${profile.name} (${profile.modality})", fontSize = 11.sp)
                                }
                            }
                        }
                    } else {
                        Text("Профили аппаратов не настроены (ручной ввод)", fontSize = 11.sp, color = TextSecondarySlate)
                    }
                }
            }
        }

        if (selectedProfile == null) {
            MwlModalityField(draft.modality, { draft = draft.copy(modality = it) })
        }
        DicomHelpField(draft.scheduledStationAeTitle, { draft = draft.copy(scheduledStationAeTitle = it) }, "AE Title аппарата", DicomFieldHelp.StationAe)
        DicomHelpField(draft.scheduledStationName, { draft = draft.copy(scheduledStationName = it) }, "Название станции", DicomFieldHelp.StationName)
        DicomHelpField(draft.spsLocation, { draft = draft.copy(spsLocation = it) }, "Место проведения", DicomFieldHelp.StepLocation)
        val effectiveModality = (selectedProfile?.modality ?: draft.modality).trim()
        MwlStudyDescriptionField(
            value = draft.requestedProcedureDescription,
            modality = effectiveModality,
            onValueChange = { draft = draft.copy(requestedProcedureDescription = it, spsDescription = it) }
        )
        DicomHelpField(draft.requestedProcedureId, { draft = draft.copy(requestedProcedureId = it) }, "Requested Procedure ID", DicomFieldHelp.ProcedureId)
        DicomHelpField(draft.spsId, { draft = draft.copy(spsId = it) }, "Scheduled Procedure Step ID", DicomFieldHelp.StepId)
        DicomHelpField(draft.accessionNumber, { draft = draft.copy(accessionNumber = it) }, "Accession Number", DicomFieldHelp.Accession)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { MwlCalendarField("Дата назначения", draft.spsStartDate, { draft = draft.copy(spsStartDate = it) }) }
            DicomHelpButton(DicomFieldHelp.StepDate)
        }
        DicomHelpField(draft.spsStartTime, { draft = draft.copy(spsStartTime = it) }, "Время начала этапа", DicomFieldHelp.StepTime)
        DicomHelpField(draft.studyInstanceUid, { draft = draft.copy(studyInstanceUid = it) }, "Study Instance UID", DicomFieldHelp.StudyUid)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    val anyFilled = draft.accessionNumber.isNotBlank() ||
                            draft.requestedProcedureId.isNotBlank() ||
                            draft.spsId.isNotBlank()
                    if (anyFilled) {
                        showConfirmOverwriteIds = true
                    } else {
                        draft = draft.copy(
                            accessionNumber = MwlValidation.generateAccessionNumber(),
                            requestedProcedureId = MwlValidation.generateRequestedProcedureId(),
                            spsId = MwlValidation.generateSpsId()
                        )
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Сгенерировать ID", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            OutlinedButton(
                onClick = {
                    if (draft.studyInstanceUid.isNotBlank()) {
                        showConfirmOverwriteUid = true
                    } else {
                        draft = draft.copy(studyInstanceUid = MwlValidation.generateStudyInstanceUid())
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Сгенерировать UID", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        if (showConfirmOverwriteIds) {
            AlertDialog(
                onDismissRequest = { showConfirmOverwriteIds = false },
                title = { Text("Идентификаторы заполнены") },
                text = { Text("Один или несколько идентификаторов (Accession / RP / SPS ID) уже содержат значения. Как поступить?") },
                confirmButton = {
                    TextButton(onClick = {
                        draft = draft.copy(
                            accessionNumber = MwlValidation.generateAccessionNumber(),
                            requestedProcedureId = MwlValidation.generateRequestedProcedureId(),
                            spsId = MwlValidation.generateSpsId()
                        )
                        showConfirmOverwriteIds = false
                    }) {
                        Text("Перезаписать все")
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { showConfirmOverwriteIds = false }) {
                            Text("Отмена")
                        }
                        TextButton(onClick = {
                            draft = draft.copy(
                                accessionNumber = draft.accessionNumber.ifBlank { MwlValidation.generateAccessionNumber() },
                                requestedProcedureId = draft.requestedProcedureId.ifBlank { MwlValidation.generateRequestedProcedureId() },
                                spsId = draft.spsId.ifBlank { MwlValidation.generateSpsId() }
                            )
                            showConfirmOverwriteIds = false
                        }) {
                            Text("Только пустые")
                        }
                    }
                }
            )
        }

        if (showConfirmOverwriteUid) {
            AlertDialog(
                onDismissRequest = { showConfirmOverwriteUid = false },
                title = { Text("Перезаписать Study Instance UID?") },
                text = { Text("Study Instance UID уже заполнен. Сгенерировать новый UID?") },
                confirmButton = {
                    TextButton(onClick = {
                        draft = draft.copy(studyInstanceUid = MwlValidation.generateStudyInstanceUid())
                        showConfirmOverwriteUid = false
                    }) {
                        Text("Перезаписать")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showConfirmOverwriteUid = false }) {
                        Text("Отмена")
                    }
                }
            )
        }
    }
    if (patientPicker) MwlFormDialog("Выбор пациента", onDismiss = { patientPicker = false }, onSave = { patientPicker = false }, saveLabel = "Закрыть") {
            val pickerFocus = LocalFocusManager.current
            val keyboard = LocalSoftwareKeyboardController.current
            OutlinedTextField(patientQuery, { patientQuery = it }, label = { Text("Поиск: Patient Name / Patient ID") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { pickerFocus.clearFocus(); keyboard?.hide() }))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                items(patients.filter { patientQuery.isBlank() || it.patientName.contains(patientQuery.trim(), true) || it.patientId.contains(patientQuery.trim(), true) }, key = { it.id }) { p ->
                    Surface(onClick = { pickerFocus.clearFocus(); keyboard?.hide(); selectedId = p.id; patientPicker = false }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.padding(12.dp)) { MwlPatientIdentity(p) }
                    }
                }
            }
    }
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
