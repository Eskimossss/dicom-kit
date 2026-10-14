package ru.medeng.dicomtool.ui.navigation

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.medeng.dicomtool.ui.components.DicomToolMainTopBar
import ru.medeng.dicomtool.ui.screens.log.DicomLogScreen
import ru.medeng.dicomtool.ui.screens.received.DicomViewerAndTagsScreen
import ru.medeng.dicomtool.ui.screens.received.ReceivedStudiesScreen
import ru.medeng.dicomtool.ui.screens.receiver.ReceiverScpScreen
import ru.medeng.dicomtool.ui.screens.send.ScuImageGeneratorSubScreen
import ru.medeng.dicomtool.ui.screens.send.SendScuScreen
import ru.medeng.dicomtool.ui.screens.settings.SettingsAndCleanupScreen
import ru.medeng.dicomtool.ui.screens.tags.DicomTagsScreen
import ru.medeng.dicomtool.ui.theme.AppBackgroundGray
import ru.medeng.dicomtool.ui.theme.CardBorderGray
import ru.medeng.dicomtool.ui.theme.CardSurfaceWhite
import ru.medeng.dicomtool.ui.theme.PrimaryBlue
import ru.medeng.dicomtool.ui.theme.PrimaryBlueLight
import ru.medeng.dicomtool.ui.theme.TextSecondarySlate
import ru.medeng.dicomtool.ui.viewmodel.DicomToolViewModel

/**
 * 5 основных разделов нижней навигации согласно ТЗ:
 * 1. Приёмник
 * 2. Принятые
 * 3. Отправка
 * 4. Теги
 * 5. Журнал
 */
enum class MainBottomTab(
    val titleRu: String,
    val icon: ImageVector
) {
    RECEIVER("Приёмник", Icons.Outlined.Home),
    RECEIVED("Принятые", Icons.Outlined.Inventory2),
    SEND("Отправка", Icons.AutoMirrored.Outlined.Send),
    TAGS("Теги и просмотр", Icons.Outlined.Sell),
    LOG("Журнал", Icons.AutoMirrored.Outlined.ListAlt)
}

/**
 * Дополнительные экраны приложения согласно ТЗ:
 * DICOM Viewer, DICOM Tags, генератор изображения, настройки и очистка данных.
 */
enum class ActiveSubScreen {
    NONE,
    DICOM_VIEWER_INFO,
    DICOM_TAGS,
    SCU_IMAGE_GENERATOR,
    SETTINGS_AND_CLEANUP
}

@Composable
fun DicomToolAppRoot(
    viewModel: DicomToolViewModel = viewModel()
) {
    val scpState by viewModel.scpState.collectAsState()
    val aeTitleInput by viewModel.aeTitleInput.collectAsState()
    val portInput by viewModel.portInput.collectAsState()
    val dcm4cheReport by viewModel.dcm4cheReport.collectAsState()
    val loggerHealth by viewModel.loggerHealth.collectAsState()
    val studies by viewModel.studies.collectAsState()
    val selectedModality by viewModel.selectedModalityFilter.collectAsState()
    val selectedStudyUid by viewModel.selectedStudyUid.collectAsState()
    val selectedSeriesUid by viewModel.selectedSeriesUid.collectAsState()
    val selectedInstanceIndex by viewModel.selectedInstanceIndex.collectAsState()
    val selectedTagForDetail by viewModel.selectedTagForDetail.collectAsState()
    val tagSearchQuery by viewModel.tagSearchQuery.collectAsState()
    val viewerZoom by viewModel.viewerZoom.collectAsState()
    val viewerPan by viewModel.viewerPan.collectAsState()
    val viewerInverted by viewModel.viewerInverted.collectAsState()
    val viewerWW by viewModel.viewerWindowWidth.collectAsState()
    val viewerWL by viewModel.viewerWindowCenter.collectAsState()
    val viewerFrame by viewModel.viewerCurrentFrame.collectAsState()
    val viewerRenderState by viewModel.viewerRenderState.collectAsState()
    val isViewerFullScreen by viewModel.isViewerFullScreen.collectAsState()
    val scuConfig by viewModel.scuConfig.collectAsState()
    val preparedScuObject by viewModel.preparedScuObject.collectAsState()
    val freehandStrokes by viewModel.freehandStrokes.collectAsState()
    val candidateFiles by viewModel.candidateFiles.collectAsState()
    val batchProgress by viewModel.batchProgress.collectAsState()
    val logDisplayMode by viewModel.logDisplayMode.collectAsState()
    val logTrafficFilter by viewModel.logTrafficFilter.collectAsState()
    val logOutcomeFilter by viewModel.logOutcomeFilter.collectAsState()
    val logSearchQuery by viewModel.logSearchQuery.collectAsState()
    val filteredSessionSummaries by viewModel.filteredSessionSummaries.collectAsState()
    val filteredLogs by viewModel.filteredLogs.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val expandedSessionKeys by viewModel.expandedSessionKeys.collectAsState()
    val expandedLogIds by viewModel.expandedLogIds.collectAsState()
    val autoScrollLogs by viewModel.autoScrollLogs.collectAsState()
    val storageLocationInfo by viewModel.storageLocationInfo.collectAsState()
    val isMigrating by viewModel.isMigrating.collectAsState()
    val migrationStatusMessage by viewModel.migrationStatusMessage.collectAsState()
    val externalSummary by viewModel.externalFileSummary.collectAsState()
    val externalTags by viewModel.externalFileTags.collectAsState()
    val isExternalLoading by viewModel.isExternalFileLoading.collectAsState()
    val externalError by viewModel.externalFileError.collectAsState()
    val externalSearchQuery by viewModel.externalTagSearchQuery.collectAsState()
    val externalEncodingOverride by viewModel.externalTagEncodingOverride.collectAsState()

    val context = LocalContext.current
    val activity = context as? Activity

    // Перехват системной кнопки / жеста "Назад" при активном полноэкранном режиме
    BackHandler(enabled = isViewerFullScreen) {
        viewModel.setViewerFullScreen(false)
    }

    // Управление системными панелями Android (status bar и navigation bar)
    DisposableEffect(isViewerFullScreen) {
        val window = activity?.window
        if (window != null) {
            val insetsController = WindowInsetsControllerCompat(window, window.decorView)
            if (isViewerFullScreen) {
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
                insetsController.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            val window = activity?.window
            if (window != null) {
                WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    var currentTab by rememberSaveable { mutableStateOf(MainBottomTab.RECEIVER) }
    var activeSubScreen by rememberSaveable { mutableStateOf(ActiveSubScreen.NONE) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isExpandedWidth = maxWidth >= 700.dp

        Scaffold(
            containerColor = AppBackgroundGray,
            contentWindowInsets = if (isViewerFullScreen) WindowInsets(0, 0, 0, 0) else WindowInsets.safeDrawing,
            topBar = {
                if (!isViewerFullScreen && activeSubScreen == ActiveSubScreen.NONE) {
                    val topBarTitle = when (currentTab) {
                        MainBottomTab.RECEIVER -> "DICOM Приёмник (SCP)"
                        MainBottomTab.RECEIVED -> "Принятые исследования"
                        MainBottomTab.SEND -> "Отправка DICOM (SCU)"
                        MainBottomTab.TAGS -> "Теги и просмотр"
                        MainBottomTab.LOG -> "Журнал DICOM"
                    }
                    DicomToolMainTopBar(
                        title = topBarTitle,
                        onOpenSettings = { activeSubScreen = ActiveSubScreen.SETTINGS_AND_CLEANUP }
                    )
                }
            },
            bottomBar = {
                if (!isViewerFullScreen) {
                    Column {
                        HorizontalDivider(color = CardBorderGray, thickness = 1.dp)
                        NavigationBar(
                            containerColor = CardSurfaceWhite,
                            tonalElevation = 0.dp
                        ) {
                            MainBottomTab.entries.forEach { tab ->
                                val isSelected = currentTab == tab
                                NavigationBarItem(
                                    selected = isSelected,
                                    onClick = {
                                        currentTab = tab
                                        activeSubScreen = ActiveSubScreen.NONE
                                    },
                                    icon = {
                                        Icon(
                                            imageVector = tab.icon,
                                            contentDescription = tab.titleRu
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = tab.titleRu,
                                            fontSize = if (isExpandedWidth) 12.sp else if (tab == MainBottomTab.TAGS) 9.sp else 10.sp,
                                            lineHeight = 11.sp,
                                            textAlign = TextAlign.Center,
                                            maxLines = if (isExpandedWidth) 1 else 2,
                                            softWrap = true,
                                            overflow = TextOverflow.Ellipsis,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                        )
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = PrimaryBlue,
                                        selectedTextColor = PrimaryBlue,
                                        indicatorColor = PrimaryBlueLight,
                                        unselectedIconColor = TextSecondarySlate,
                                        unselectedTextColor = TextSecondarySlate
                                    )
                                )
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(if (isViewerFullScreen) androidx.compose.foundation.layout.PaddingValues(0.dp) else innerPadding)
            ) {
                when (activeSubScreen) {
                    ActiveSubScreen.SETTINGS_AND_CLEANUP -> {
                        SettingsAndCleanupScreen(
                            storageInfo = storageLocationInfo,
                            isMigrating = isMigrating,
                            migrationStatusMessage = migrationStatusMessage,
                            studiesCount = studies.size,
                            filesCount = viewModel.getTotalSavedFilesCount(),
                            totalBytes = viewModel.getTotalStorageBytes(),
                            logsCount = logs.size,
                            onBack = { activeSubScreen = ActiveSubScreen.NONE },
                            onSelectSafStorage = { uri -> viewModel.setCustomSafStorage(uri) },
                            onResetToInternalStorage = { viewModel.resetToInternalStorage() },
                            onMigrateStudies = { toSaf -> viewModel.migrateStudies(toSaf) },
                            onClearAllStudiesConfirmed = { viewModel.clearAllStudies() },
                            onClearAllLogsConfirmed = { viewModel.clearAllLogs() }
                        )
                    }

                    ActiveSubScreen.DICOM_VIEWER_INFO,
                    ActiveSubScreen.DICOM_TAGS -> {
                        val study = viewModel.getSelectedStudyOrDefault()
                        if (study == null) {
                            activeSubScreen = ActiveSubScreen.NONE
                        } else {
                            DicomViewerAndTagsScreen(
                                study = study,
                                selectedSeriesUid = selectedSeriesUid,
                                selectedInstanceIndex = selectedInstanceIndex,
                                selectedTagForDetail = selectedTagForDetail,
                                tags = viewModel.getFilteredTagsForCurrentStudy(),
                                tagSearchQuery = tagSearchQuery,
                                zoom = viewerZoom,
                                pan = viewerPan,
                                inverted = viewerInverted,
                                windowWidth = viewerWW,
                                windowCenter = viewerWL,
                                currentFrame = viewerFrame,
                                renderState = viewerRenderState,
                                isFullScreen = isViewerFullScreen,
                                onToggleFullScreen = viewModel::toggleViewerFullScreen,
                                initialTab = if (activeSubScreen == ActiveSubScreen.DICOM_TAGS) 1 else 0,
                                onBack = {
                                    viewModel.selectTagForDetail(null)
                                    viewModel.setViewerFullScreen(false)
                                    activeSubScreen = ActiveSubScreen.NONE
                                },
                                onSelectSeries = viewModel::selectSeries,
                                onSelectTagForDetail = viewModel::selectTagForDetail,
                                onTagSearchChange = viewModel::updateTagSearchQuery,
                                onZoomFactor = viewModel::adjustViewerZoom,
                                onPanDelta = viewModel::updateViewerPan,
                                onFitToScreen = viewModel::fitViewerToScreen,
                                onResetTransform = viewModel::resetViewerTransform,
                                onResetWindowOnly = viewModel::resetWindowToDicomDefault,
                                onToggleInvert = viewModel::toggleViewerInvert,
                                onAdjustWindow = viewModel::adjustWindowWidthLevel,
                                onSetWindowWidthLevel = viewModel::setWindowWidthLevel,
                                onApplyWindowPreset = viewModel::applyWindowPreset,
                                onStepFrame = viewModel::stepViewerFrame,
                                onStepInstance = viewModel::stepSeriesInstance,
                                onEnsureDecoded = { viewModel.triggerFrameDecodeAndRender(resetWindowToFileDefault = false) },
                                onDeleteCurrentStudy = { viewModel.deleteStudy(study.studyInstanceUid) }
                            )
                        }
                    }

                    ActiveSubScreen.SCU_IMAGE_GENERATOR -> {
                        ScuImageGeneratorSubScreen(
                            config = scuConfig,
                            preparedObject = preparedScuObject,
                            freehandStrokes = freehandStrokes,
                            onUpdateConfig = viewModel::updateScuConfig,
                            onSetImageMode = viewModel::setTestImageMode,
                            onRegenerateSmiley = viewModel::regenerateSmiley,
                            onSetBrushThickness = viewModel::setBrushThickness,
                            onAddStroke = { pts -> viewModel.addFreehandStroke(pts) },
                            onClearCanvas = viewModel::clearFreehandCanvas,
                            onRegenerateUids = { viewModel.syncPreparedScuObject(forceNewObject = true) },
                            onBack = { activeSubScreen = ActiveSubScreen.NONE }
                        )
                    }

                    ActiveSubScreen.NONE -> {
                        when (currentTab) {
                            MainBottomTab.RECEIVER -> {
                                ReceiverScpScreen(
                                    state = scpState,
                                    aeTitleInput = aeTitleInput,
                                    portInput = portInput,
                                    isExpandedWidth = isExpandedWidth,
                                    storageInfo = storageLocationInfo,
                                    onOpenFolder = { viewModel.openStorageFolder(context) },
                                    onAeTitleChange = viewModel::updateScpAeTitleInput,
                                    onPortChange = viewModel::updateScpPortInput,
                                    onIncrementPort = viewModel::incrementScpPort,
                                    onRefreshNetwork = viewModel::refreshNetworkInterface,
                                    onToggleServerState = viewModel::toggleScpServer
                                )
                            }

                            MainBottomTab.RECEIVED -> {
                                ReceivedStudiesScreen(
                                    studies = studies,
                                    selectedModality = selectedModality,
                                    selectedStudyUid = selectedStudyUid,
                                    onSelectModality = viewModel::setModalityFilter,
                                    onOpenStudyViewer = { study ->
                                        viewModel.selectStudy(study.studyInstanceUid)
                                        activeSubScreen = ActiveSubScreen.DICOM_VIEWER_INFO
                                    },
                                    onDeleteStudy = viewModel::deleteStudy,
                                    onRefreshStudies = viewModel::refreshReceivedStudies
                                )
                            }

                            MainBottomTab.SEND -> {
                                SendScuScreen(
                                    config = scuConfig,
                                    preparedObject = preparedScuObject,
                                    freehandStrokes = freehandStrokes,
                                    isExpandedWidth = isExpandedWidth,
                                    candidateFiles = candidateFiles,
                                    batchProgress = batchProgress,
                                    onUpdateConfig = viewModel::updateScuConfig,
                                    onSetSendMode = viewModel::setScuSendMode,
                                    onAddCandidateFiles = viewModel::addCandidateFilesFromUris,
                                    onScanCandidateFolder = viewModel::scanCandidateFolder,
                                    onCancelFolderScan = viewModel::cancelFolderScan,
                                    onRemoveCandidateFile = viewModel::removeCandidateFile,
                                    onClearCandidateFiles = viewModel::clearCandidateFiles,
                                    onTriggerCStoreBatch = viewModel::triggerCStoreFileBatch,
                                    onCancelBatchSend = viewModel::cancelBatchSend,
                                    onUpdateBirthDateDigits = viewModel::updatePatientBirthDateDigits,
                                    onSetImageMode = viewModel::setTestImageMode,
                                    onRegenerateSmiley = viewModel::regenerateSmiley,
                                    onSetBrushThickness = viewModel::setBrushThickness,
                                    onAddStroke = { pts -> viewModel.addFreehandStroke(pts) },
                                    onClearCanvas = viewModel::clearFreehandCanvas,
                                    onRegenerateUids = viewModel::createNewScuObject,
                                    onTriggerCEcho = viewModel::triggerCEchoScu,
                                    onTriggerCStore = viewModel::triggerCStoreScu,
                                    onOpenGeneratorSubScreen = {
                                        activeSubScreen = ActiveSubScreen.SCU_IMAGE_GENERATOR
                                    }
                                )
                            }

                            MainBottomTab.TAGS -> {
                                DicomTagsScreen(
                                    summary = externalSummary,
                                    tags = externalTags,
                                    isLoading = isExternalLoading,
                                    errorMessage = externalError,
                                    searchQuery = externalSearchQuery,
                                    encodingOverride = externalEncodingOverride,
                                    viewerRenderState = viewerRenderState,
                                    viewerZoom = viewerZoom,
                                    viewerPan = viewerPan,
                                    viewerInverted = viewerInverted,
                                    viewerWindowWidth = viewerWW,
                                    viewerWindowCenter = viewerWL,
                                    viewerCurrentFrame = viewerFrame,
                                    isViewerFullScreen = isViewerFullScreen,
                                    onSelectFileUri = viewModel::loadExternalDicomFile,
                                    onClearFile = viewModel::clearExternalFile,
                                    onSearchQueryChange = viewModel::updateExternalTagSearchQuery,
                                    onEncodingOverrideChange = viewModel::setExternalTagEncodingOverride,
                                    onZoomFactor = viewModel::adjustViewerZoom,
                                    onPanDelta = viewModel::updateViewerPan,
                                    onFitToScreen = viewModel::fitViewerToScreen,
                                    onResetTransform = viewModel::resetViewerTransform,
                                    onResetWindowOnly = viewModel::resetWindowToDicomDefault,
                                    onApplyWindowPreset = viewModel::applyWindowPreset,
                                    onToggleInvert = viewModel::toggleViewerInvert,
                                    onAdjustWindow = viewModel::adjustWindowWidthLevel,
                                    onSetWindowWidthLevel = viewModel::setWindowWidthLevel,
                                    onStepFrame = viewModel::stepViewerFrame,
                                    onToggleFullScreen = viewModel::toggleViewerFullScreen,
                                    onEnsureDecoded = { viewModel.triggerFrameDecodeAndRender(resetWindowToFileDefault = false) }
                                )
                            }

                            MainBottomTab.LOG -> {
                                DicomLogScreen(
                                    displayMode = logDisplayMode,
                                    trafficFilter = logTrafficFilter,
                                    outcomeFilter = logOutcomeFilter,
                                    searchQuery = logSearchQuery,
                                    sessionSummaries = filteredSessionSummaries,
                                    logs = filteredLogs,
                                    expandedSessionKeys = expandedSessionKeys,
                                    expandedLogIds = expandedLogIds,
                                    autoScroll = autoScrollLogs,
                                    onSetDisplayMode = viewModel::setLogDisplayMode,
                                    onSetTrafficFilter = viewModel::setLogTrafficFilter,
                                    onSetOutcomeFilter = viewModel::setLogOutcomeFilter,
                                    onSearchQueryChange = viewModel::updateLogSearchQuery,
                                    onToggleAutoScroll = viewModel::toggleAutoScrollLogs,
                                    onToggleSessionExpand = viewModel::toggleSessionExpanded,
                                    onToggleLogExpand = viewModel::toggleLogExpanded,
                                    onClearLogsConfirmed = viewModel::clearAllLogs,
                                    loggerHealth = loggerHealth
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
