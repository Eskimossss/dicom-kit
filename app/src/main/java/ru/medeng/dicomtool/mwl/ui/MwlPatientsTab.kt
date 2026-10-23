package ru.medeng.dicomtool.mwl.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.medeng.dicomtool.dicom.charset.DicomPersonName
import ru.medeng.dicomtool.mwl.model.MwlPatient
import ru.medeng.dicomtool.mwl.model.MwlValidation
import ru.medeng.dicomtool.ui.components.WhiteDiagnosticCard
import ru.medeng.dicomtool.ui.theme.CardBorderGray
import ru.medeng.dicomtool.ui.theme.CardSurfaceWhite
import ru.medeng.dicomtool.ui.theme.ErrorRed
import ru.medeng.dicomtool.ui.theme.PrimaryBlue
import ru.medeng.dicomtool.ui.theme.StopButtonRed
import ru.medeng.dicomtool.ui.theme.TextPrimaryDark
import ru.medeng.dicomtool.ui.theme.TextSecondarySlate

/**
 * Вкладка «Пациенты» в Worklist.
 * Предоставляет изолированный список пациентов с быстрым поиском,
 * созданием, редактированием и защищённым удалением.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MwlPatientsTab(
    viewModel: MwlViewModel,
    isExpandedWidth: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val patients by viewModel.patients.collectAsStateWithLifecycle()
    val studies by viewModel.studies.collectAsStateWithLifecycle()
    val searchQuery by viewModel.patientSearchQuery.collectAsStateWithLifecycle()
    val stationProfiles by viewModel.stationProfiles.collectAsStateWithLifecycle()

    var searchOpen by rememberSaveable { mutableStateOf(searchQuery.isNotEmpty()) }
    var confirmDeletePatient by remember { mutableStateOf<MwlPatient?>(null) }

    val showPatientDialog by viewModel.showPatientDialog.collectAsStateWithLifecycle()
    val editingPatient by viewModel.editingPatient.collectAsStateWithLifecycle()

    val showStudyDialog by viewModel.showStudyDialog.collectAsStateWithLifecycle()
    val editingStudy by viewModel.editingStudy.collectAsStateWithLifecycle()

    // Фильтрация пациентов по ФИО и Patient ID
    val filteredPatients = remember(patients, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) {
            patients
        } else {
            patients.filter { p ->
                p.patientName.lowercase().contains(q) ||
                p.patientId.lowercase().contains(q)
            }
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            WhiteDiagnosticCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Пациенты MWL (${filteredPatients.size}/${patients.size})",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimaryDark,
                            maxLines = 1
                        )
                        Text(
                            text = "Всего в базе: ${patients.size} пациентов",
                            fontSize = 11.sp,
                            color = TextSecondarySlate,
                            maxLines = 1
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            modifier = Modifier.size(44.dp),
                            onClick = {
                                if (searchOpen && searchQuery.isNotEmpty()) {
                                    viewModel.patientSearchQuery.value = ""
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

                        Button(
                            onClick = { viewModel.openNewPatientDialog() },
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Пациент", fontSize = 12.sp)
                        }
                    }
                }

                if (searchOpen) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { viewModel.patientSearchQuery.value = it },
                        placeholder = { Text("Поиск по ФИО или Patient ID…", fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        trailingIcon = {
                            IconButton(onClick = {
                                if (searchQuery.isNotEmpty()) {
                                    viewModel.patientSearchQuery.value = ""
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
                }
            }
        }

        if (filteredPatients.isEmpty()) {
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
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            tint = TextSecondarySlate,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (searchQuery.isNotEmpty()) "Пациенты не найдены" else "База пациентов пуста",
                            fontWeight = FontWeight.Bold,
                            color = TextPrimaryDark
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (searchQuery.isNotEmpty()) "Попробуйте изменить запрос поиска" else "Нажмите «+ Пациент», чтобы добавить пациента в базу MWL",
                            fontSize = 12.sp,
                            color = TextSecondarySlate
                        )
                    }
                }
            }
        } else {
            items(filteredPatients, key = { it.id }) { patient ->
                val appointmentCount = studies.count { it.patientInternalId == patient.id }
                WhiteDiagnosticCard {
                    // Строка 1: Информация о пациенте и кнопка удаления в правом углу
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = DicomPersonName.display(patient.patientName, false).ifEmpty { "ФИО не указано" },
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = TextPrimaryDark,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            val details = buildList {
                                add("ID: ${patient.patientId}")
                                if (patient.patientBirthDate.isNotBlank()) {
                                    add("ДР: ${patient.patientBirthDate}")
                                }
                                add("Назначений: $appointmentCount")
                            }.joinToString(" · ")
                            Text(
                                text = details,
                                fontSize = 12.sp,
                                color = TextSecondarySlate,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(
                            modifier = Modifier.size(36.dp),
                            onClick = {
                                confirmDeletePatient = patient
                            }
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Удалить пациента",
                                tint = ErrorRed,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Строка 2: Кнопки «Редактировать» и «+ Назначить» бок о бок
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.openEditPatientDialog(patient) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Редактировать", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }

                        Button(
                            onClick = { viewModel.openNewStudyDialog(patient) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Назначить", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
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

    // 2. Диалог создания исследования для выбранного пациента
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

    // 3. Диалог подтверждения удаления пациента (с каскадным удалением назначений при их наличии)
    confirmDeletePatient?.let { p ->
        val count = studies.count { it.patientInternalId == p.id }
        val displayName = DicomPersonName.display(p.patientName, false).ifEmpty { p.patientId }
        AlertDialog(
            onDismissRequest = { confirmDeletePatient = null },
            title = { Text("Удалить пациента?") },
            text = {
                if (count > 0) {
                    Text(
                        text = "Пациент «$displayName» (ID: ${p.patientId}) имеет связанные назначения ($count шт.).\n\n" +
                                "Пациент и все его назначения будут безвозвратно удалены.",
                        color = TextPrimaryDark,
                        lineHeight = 20.sp
                    )
                } else {
                    Text(
                        text = "Пациент «$displayName» (ID: ${p.patientId}) будет безвозвратно удалён из локальной базы MWL.",
                        color = TextPrimaryDark,
                        lineHeight = 20.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.confirmDeletePatient(p)
                        viewModel.executeDeletePatient(cascade = true)
                        confirmDeletePatient = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StopButtonRed)
                ) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeletePatient = null }) {
                    Text("Отмена")
                }
            }
        )
    }
}
