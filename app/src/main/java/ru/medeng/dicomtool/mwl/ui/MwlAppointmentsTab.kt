package ru.medeng.dicomtool.mwl.ui

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

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Панель управления и поиска
        WhiteDiagnosticCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
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
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(modalities) { mod ->
                    FilterChip(
                        selected = modalityFilter == mod,
                        onClick = { viewModel.appointmentModalityFilter.value = mod },
                        label = { Text(if (mod == "ALL") "Все модальности" else mod) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Кнопки добавления
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { viewModel.openNewStudyDialog() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Назначение", fontSize = 13.sp)
                }

                OutlinedButton(
                    onClick = { viewModel.openNewPatientDialog() },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Пациент", fontSize = 13.sp)
                }

                Button(
                    onClick = { viewModel.showSyntheticDialog.value = true },
                    colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.AutoMode, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Тестовые", fontSize = 13.sp)
                }
            }
        }

        // Список найденных исследований
        if (filteredStudies.isEmpty()) {
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
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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

@Composable
private fun StudyCardItem(
    study: MwlScheduledStudy,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = CardSurfaceWhite,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, CardBorderGray)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Бейдж модальности
                    Surface(
                        color = PrimaryBlueLight,
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(1.dp, PrimaryBlue.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = study.modality,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryBlue,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    if (study.isSynthetic) {
                        Surface(
                            color = SuccessGreenBackground,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "ТЕСТ",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = SuccessGreen,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Text(
                        text = study.patientName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimaryDark
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onDuplicate, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Дублировать", tint = TextSecondarySlate, modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = "Редактировать", tint = PrimaryBlue, modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Удалить", tint = ErrorRed, modifier = Modifier.size(16.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = study.requestedProcedureDescription.ifEmpty { study.spsDescription },
                fontSize = 13.sp,
                color = TextSecondarySlate
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "ID: ${study.patientId} · ACC: ${study.accessionNumber}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextSecondarySlate
                )
                Text(
                    text = "${study.spsStartDate} ${study.spsStartTime} · ${study.scheduledStationAeTitle}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextSecondarySlate
                )
            }
        }
    }
}

@Composable
private fun PatientEditorDialog(
    patient: MwlPatient,
    onDismiss: () -> Unit,
    onSave: (MwlPatient) -> Unit
) {
    var name by remember { mutableStateOf(patient.patientName) }
    var id by remember { mutableStateOf(patient.patientId) }
    var birth by remember { mutableStateOf(patient.patientBirthDate) }
    var sex by remember { mutableStateOf(patient.patientSex) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (patient.patientName.isEmpty()) "Новый пациент" else "Редактирование пациента") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ФИО (формат PN: Фамилия^Имя^Отчество)", fontSize = 11.sp, color = TextSecondarySlate)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("ИВАНОВ^ИВАН^ИВАНОВИЧ") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Patient ID (VR: LO)", fontSize = 11.sp, color = TextSecondarySlate)
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Дата рождения (YYYYMMDD)", fontSize = 11.sp, color = TextSecondarySlate)
                OutlinedTextField(
                    value = birth,
                    onValueChange = { birth = it.filter { c -> c.isDigit() } },
                    placeholder = { Text("19850615") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Пол (M / F / O)", fontSize = 11.sp, color = TextSecondarySlate)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("M" to "Муж (M)", "F" to "Жен (F)", "O" to "Другой (O)").forEach { (code, label) ->
                        FilterChip(
                            selected = sex == code,
                            onClick = { sex = code },
                            label = { Text(label, fontSize = 12.sp) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    patient.copy(
                        patientName = name.trim(),
                        patientId = id.trim(),
                        patientBirthDate = birth.trim(),
                        patientSex = sex
                    )
                )
            }) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

@Composable
private fun StudyEditorDialog(
    study: MwlScheduledStudy,
    patients: List<MwlPatient>,
    onDismiss: () -> Unit,
    onSave: (MwlScheduledStudy) -> Unit
) {
    var selectedPatientInternalId by remember { mutableStateOf(study.patientInternalId) }
    val currentSelectedPatient = patients.firstOrNull { it.id == selectedPatientInternalId }

    var accessionNumber by remember { mutableStateOf(study.accessionNumber) }
    var studyInstanceUid by remember { mutableStateOf(study.studyInstanceUid) }
    var rpDescription by remember { mutableStateOf(study.requestedProcedureDescription) }
    var modality by remember { mutableStateOf(study.modality) }
    var spsStartDate by remember { mutableStateOf(study.spsStartDate) }
    var spsStartTime by remember { mutableStateOf(study.spsStartTime) }
    var stationAe by remember { mutableStateOf(study.scheduledStationAeTitle) }
    var stationName by remember { mutableStateOf(study.scheduledStationName) }
    var location by remember { mutableStateOf(study.spsLocation) }
    var procedureId by remember { mutableStateOf(study.requestedProcedureId) }
    var stepId by remember { mutableStateOf(study.spsId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (study.accessionNumber.isEmpty()) "Новое назначение" else "Редактирование назначения") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Пациент: ${currentSelectedPatient?.patientName ?: study.patientName} (${currentSelectedPatient?.patientId ?: study.patientId})", fontWeight = FontWeight.Bold, fontSize = 13.sp)

                Text("Модальность (CR, DX, MG, CT, US, MR)", fontSize = 11.sp, color = TextSecondarySlate)
                OutlinedTextField(
                    value = modality,
                    onValueChange = { modality = it.uppercase() },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(value = procedureId, onValueChange = { procedureId = it }, label = { Text("Requested Procedure ID (до 16 символов)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = stepId, onValueChange = { stepId = it }, label = { Text("SPS ID (до 16 символов)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = location, onValueChange = { location = it }, label = { Text("Место выполнения SPS (до 16 символов)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = stationName, onValueChange = { stationName = it }, label = { Text("Имя станции (до 16 символов)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Описание исследования (VR: LO)", fontSize = 11.sp, color = TextSecondarySlate)
                OutlinedTextField(
                    value = rpDescription,
                    onValueChange = { rpDescription = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Accession No", fontSize = 11.sp, color = TextSecondarySlate)
                        OutlinedTextField(
                            value = accessionNumber,
                            onValueChange = { accessionNumber = it },
                            singleLine = true
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Station AE", fontSize = 11.sp, color = TextSecondarySlate)
                        OutlinedTextField(
                            value = stationAe,
                            onValueChange = { stationAe = it.uppercase() },
                            singleLine = true
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Дата SPS (YYYYMMDD)", fontSize = 11.sp, color = TextSecondarySlate)
                        OutlinedTextField(
                            value = spsStartDate,
                            onValueChange = { spsStartDate = it.filter { c -> c.isDigit() } },
                            singleLine = true
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Время SPS (HHMMSS)", fontSize = 11.sp, color = TextSecondarySlate)
                        OutlinedTextField(
                            value = spsStartTime,
                            onValueChange = { spsStartTime = it.filter { c -> c.isDigit() } },
                            singleLine = true
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Study Instance UID", fontSize = 11.sp, color = TextSecondarySlate)
                    TextButton(onClick = { studyInstanceUid = MwlValidation.generateStudyInstanceUid() }) {
                        Text("Сгенерировать UID", fontSize = 11.sp)
                    }
                }
                OutlinedTextField(
                    value = studyInstanceUid,
                    onValueChange = { studyInstanceUid = it.trim() },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val p = currentSelectedPatient
                onSave(
                    study.copy(
                        patientInternalId = p?.id ?: study.patientInternalId,
                        patientName = p?.patientName ?: study.patientName,
                        patientId = p?.patientId ?: study.patientId,
                        accessionNumber = accessionNumber.trim(),
                        studyInstanceUid = studyInstanceUid.trim(),
                        requestedProcedureDescription = rpDescription.trim(),
                        spsDescription = rpDescription.trim(),
                        modality = modality.trim(),
                        spsStartDate = spsStartDate.trim(),
                        spsStartTime = spsStartTime.trim(),
                        scheduledStationAeTitle = stationAe.trim(),
                        scheduledStationName = stationName.trim(),
                        spsLocation = location.trim(),
                        requestedProcedureId = procedureId.trim(),
                        spsId = stepId.trim()
                    )
                )
            }) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
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
