package ru.medeng.dicomtool.mwl.ui

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.imePadding

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.dcm4che3.data.Attributes
import org.dcm4che3.data.Tag
import ru.medeng.dicomtool.mwl.model.MwlDateFilterOption
import ru.medeng.dicomtool.mwl.model.MwlQueryResultItem
import ru.medeng.dicomtool.mwl.model.MwlScuFilter
import ru.medeng.dicomtool.mwl.model.MwlScuProfile
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MwlScuTab(
    viewModel: MwlViewModel,
    isExpandedWidth: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val profiles by viewModel.scuProfiles.collectAsStateWithLifecycle()
    val selectedProfile by viewModel.selectedProfile.collectAsStateWithLifecycle()
    val filter by viewModel.scuFilter.collectAsStateWithLifecycle()
    val results by viewModel.scuResults.collectAsStateWithLifecycle()
    val isRunning by viewModel.isQueryRunning.collectAsStateWithLifecycle()
    val queryCount by viewModel.queryCount.collectAsStateWithLifecycle()
    val statusMsg by viewModel.queryStatusMessage.collectAsStateWithLifecycle()

    val showProfileDialog by viewModel.showProfileDialog.collectAsStateWithLifecycle()
    val editingProfile by viewModel.editingProfile.collectAsStateWithLifecycle()
    val profileToDelete by viewModel.profileToDelete.collectAsStateWithLifecycle()
    val selectedDetail by viewModel.selectedResultDetail.collectAsStateWithLifecycle()

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Карточка 1: Выбор профиля удалённого сервера MWL SCP
        WhiteDiagnosticCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Клиент Modality Worklist (SCU)",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimaryDark
                    )
                    Text(
                        text = "Запрос списка назначений по DICOM DIMSE C-FIND",
                        fontSize = 12.sp,
                        color = TextSecondarySlate
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = { viewModel.openNewProfileDialog() }) {
                        Icon(Icons.Default.Add, contentDescription = "Новый профиль", tint = PrimaryBlue)
                    }
                    if (selectedProfile != null) {
                        IconButton(onClick = { viewModel.openEditProfileDialog(selectedProfile!!) }) {
                            Icon(Icons.Default.Edit, contentDescription = "Редактировать", tint = TextSecondarySlate)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Dropdown выбора профиля
            var profileExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = profileExpanded,
                onExpandedChange = { profileExpanded = it }
            ) {
                OutlinedTextField(
                    value = selectedProfile?.name ?: "Выберите профиль сервера",
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = profileExpanded) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = profileExpanded,
                    onDismissRequest = { profileExpanded = false }
                ) {
                    profiles.forEach { prof ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(prof.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text("${prof.host}:${prof.port} · Called: ${prof.calledAet}", fontSize = 11.sp, color = TextSecondarySlate)
                                }
                            },
                            onClick = {
                                viewModel.selectProfile(prof)
                                profileExpanded = false
                            }
                        )
                    }
                }
            }
            selectedProfile?.let { Text("${it.host}:${it.port} · Called AE: ${it.calledAet}", fontSize = 12.sp) }
        }

        // Filters wrap naturally; no intrinsic-height row of five date choices.
        WhiteDiagnosticCard {
            Text("Критерии поиска Worklist", fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MwlDateFilterOption.entries.forEach { option ->
                    FilterChip(selected = filter.dateOption == option,
                        onClick = { viewModel.scuFilter.value = filter.copy(dateOption = option) },
                        label = { Text(option.labelRu, maxLines = 1) })
                }
            }
            when (filter.dateOption) {
                MwlDateFilterOption.CUSTOM_DATE -> MwlCalendarField("Дата назначения", filter.specificDate,
                    { viewModel.scuFilter.value = filter.copy(specificDate = it) })
                MwlDateFilterOption.DATE_RANGE -> MwlAdaptiveFields(
                    first = { MwlCalendarField("Начало диапазона", filter.startDate, { viewModel.scuFilter.value = filter.copy(startDate = it) }) },
                    second = { MwlCalendarField("Окончание диапазона", filter.endDate, { viewModel.scuFilter.value = filter.copy(endDate = it) }) })
                else -> Unit
            }
            val dateError = filter.validateDateSelection() as? MwlValidation.Result.Invalid
            dateError?.let { Text(it.messageRu, color = ErrorRed) }
            MwlAdaptiveFields(first = {
                OutlinedTextField(filter.modality, { viewModel.scuFilter.value = filter.copy(modality = it.uppercase(java.util.Locale.US)) }, label = { Text("Modality") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }, second = {
                OutlinedTextField(filter.scheduledStationAe, { viewModel.scuFilter.value = filter.copy(scheduledStationAe = it) }, label = { Text("AE Title аппарата") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            })
            MwlAdaptiveFields(first = {
                OutlinedTextField(filter.patientName, { viewModel.scuFilter.value = filter.copy(patientName = it) }, label = { Text("ФИО пациента") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }, second = {
                OutlinedTextField(filter.patientId, { viewModel.scuFilter.value = filter.copy(patientId = it) }, label = { Text("Patient ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            })
            OutlinedTextField(filter.accessionNumber, { viewModel.scuFilter.value = filter.copy(accessionNumber = it) }, label = { Text("Accession Number") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (filter.isFilterEmpty()) Text("Запрос без фильтров может вернуть большой объём данных.", color = WarningAmber)
            Button(onClick = { viewModel.queryWorklist() }, enabled = !isRunning && dateError == null,
                modifier = Modifier.fillMaxWidth()) {
                Text(if (isRunning) "Запрос ($queryCount)…" else "Получить Worklist")
            }
            if (isRunning) OutlinedButton(onClick = { viewModel.cancelWorklistQuery() }, modifier = Modifier.fillMaxWidth()) { Text("Отмена (C-CANCEL)") }
            statusMsg?.let { Text(it, color = if (it.contains("Ошибка", true)) ErrorRed else TextSecondarySlate) }
        }
        // Карточка 3: Результаты запроса
        WhiteDiagnosticCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Результаты Worklist (${results.size})",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimaryDark
                )
                if (results.isNotEmpty()) {
                    Text(
                        text = "Нажмите на запись для просмотра тегов",
                        fontSize = 11.sp,
                        color = TextSecondarySlate
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (results.isEmpty()) {
                Text(
                    text = if (isRunning) "Ожидание ответов C-FIND-RSP…" else "Назначений в текущей сессии нет. Нажмите «Получить Worklist».",
                    fontSize = 13.sp,
                    color = TextSecondarySlate,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            } else {
                results.forEach { item ->
                    MwlResultCardItem(
                        item = item,
                        onClick = { viewModel.openResultDetail(item) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }

    // Диалог создания/редактирования профиля SCU
    if (showProfileDialog && editingProfile != null) {
        ProfileEditorDialog(
            profile = editingProfile!!,
            onDismiss = { viewModel.showProfileDialog.value = false },
            onSave = { p ->
                val res = viewModel.saveProfile(p)
                if (res is MwlValidation.Result.Invalid) {
                    Toast.makeText(context, res.messageRu, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // Диалог подробного просмотра результата (Clinical Card + Technical DICOM Dataset View)
    if (selectedDetail != null) {
        MwlResultDetailDialog(
            item = selectedDetail!!,
            onDismiss = { viewModel.selectedResultDetail.value = null }
        )
    }
}

@Composable
private fun MwlResultCardItem(
    item: MwlQueryResultItem,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        color = InputBackgroundGray,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, CardBorderGray)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        color = PrimaryBlueLight,
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(1.dp, PrimaryBlue.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = item.modality.ifEmpty { "OT" },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryBlue,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    Text(
                        text = item.patientName.ifEmpty { "ФИО не указано" },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimaryDark
                    )
                }

                Text(
                    text = "ACC: ${item.accessionNumber}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextSecondarySlate
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = item.requestedProcedureDescription.ifEmpty { item.spsDescription.ifEmpty { "Без описания" } },
                fontSize = 12.sp,
                color = TextSecondarySlate
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "ID: ${item.patientId} · Пол: ${item.patientSex}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextSecondarySlate
                )
                Text(
                    text = "${item.spsStartDate} ${item.spsStartTime} · ${item.scheduledStationAeTitle}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextSecondarySlate
                )
            }
        }
    }
}

@Composable
private fun ProfileEditorDialog(
    profile: MwlScuProfile,
    onDismiss: () -> Unit,
    onSave: (MwlScuProfile) -> Unit
) {
    var name by remember { mutableStateOf(profile.name) }
    var host by remember { mutableStateOf(profile.host) }
    var port by remember { mutableStateOf(profile.port.toString()) }
    var calledAet by remember { mutableStateOf(profile.calledAet) }
    var callingAet by remember { mutableStateOf(profile.callingAet) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (profile.name.isEmpty()) "Новый профиль MWL SCP" else "Редактирование профиля") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Название профиля", fontSize = 11.sp, color = TextSecondarySlate)
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(modifier = Modifier.weight(1.5f)) {
                        Text("IP / Hostname", fontSize = 11.sp, color = TextSecondarySlate)
                        OutlinedTextField(value = host, onValueChange = { host = it }, singleLine = true)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Порт", fontSize = 11.sp, color = TextSecondarySlate)
                        OutlinedTextField(value = port, onValueChange = { port = it.filter { c -> c.isDigit() } }, singleLine = true)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Called AE Title", fontSize = 11.sp, color = TextSecondarySlate)
                        OutlinedTextField(value = calledAet, onValueChange = { calledAet = it.uppercase() }, singleLine = true)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Calling AE Title", fontSize = 11.sp, color = TextSecondarySlate)
                        OutlinedTextField(value = callingAet, onValueChange = { callingAet = it.uppercase() }, singleLine = true)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    profile.copy(
                        name = name.trim(),
                        host = host.trim(),
                        port = port.toIntOrNull() ?: 11112,
                        calledAet = calledAet.trim(),
                        callingAet = callingAet.trim()
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
private fun MwlResultDetailDialog(
    item: MwlQueryResultItem,
    onDismiss: () -> Unit
) {
    var activeTab by remember { mutableIntStateOf(0) } // 0: Карточка, 1: Технические теги DICOM

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Назначение Worklist", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("${item.modality} · ${item.patientName}", fontSize = 12.sp, color = TextSecondarySlate)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                TabRow(selectedTabIndex = activeTab) {
                    Tab(selected = activeTab == 0, onClick = { activeTab = 0 }, text = { Text("Назначение", fontSize = 12.sp) })
                    Tab(selected = activeTab == 1, onClick = { activeTab = 1 }, text = { Text("DICOM Dataset", fontSize = 12.sp) })
                }
                Spacer(modifier = Modifier.height(10.dp))

                if (activeTab == 0) {
                    // Карточка клинических данных
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        DetailItem("ФИО пациента", item.patientName)
                        DetailItem("Patient ID", item.patientId)
                        DetailItem("Дата рождения", item.patientBirthDate)
                        DetailItem("Пол", item.patientSex)
                        DetailItem("Accession Number", item.accessionNumber)
                        DetailItem("Модальность", item.modality)
                        DetailItem("Study Instance UID", item.studyInstanceUid)
                        DetailItem("Описание процедуры", item.requestedProcedureDescription)
                        DetailItem("SPS ID", item.spsId)
                        DetailItem("Дата и время SPS", "${item.spsStartDate} ${item.spsStartTime}")
                        DetailItem("Station AE Title", item.scheduledStationAeTitle)
                        DetailItem("Станция", item.scheduledStationName)
                        DetailItem("Исполнитель", item.scheduledPerformingPhysician)
                        DetailItem("Кодировка (0008,0005)", item.specificCharacterSet)
                    }
                } else {
                    // Техническое представление DICOM Attributes
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(340.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val attrs = item.rawAttributes
                        if (attrs != null) {
                            for (line in ru.medeng.dicomtool.mwl.engine.MwlDatasetInspector.lines(attrs)) {
                                Text(text = line, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = TextPrimaryDark)
                            }
                        } else {
                            Text("Сырые DICOM-атрибуты недоступны", fontSize = 12.sp, color = TextSecondarySlate)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Закрыть") }
        }
    )
}

@Composable
private fun DetailItem(label: String, value: String) {
    if (value.isBlank()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontSize = 11.sp, color = TextSecondarySlate)
        Text(text = value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimaryDark)
    }
}
