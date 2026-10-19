package ru.medeng.dicomtool.ui.navigation

import androidx.compose.foundation.layout.Box
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
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
    val receptionOptions by viewModel.receptionStorageOptions.collectAsStateWithLifecycle()
    val archiveIndex by viewModel.archiveIndexState.collectAsStateWithLifecycle()
    val archiveBusy by viewModel.isMigrating.collectAsStateWithLifecycle()
    val scpState by viewModel.scpState.collectAsStateWithLifecycle()
    val aeTitleInput by viewModel.aeTitleInput.collectAsStateWithLifecycle()
    val portInput by viewModel.portInput.collectAsStateWithLifecycle()
    val dcm4cheReport by viewModel.dcm4cheReport.collectAsStateWithLifecycle()
    val loggerHealth by viewModel.loggerHealth.collectAsStateWithLifecycle()
    val studies by viewModel.studies.collectAsStateWithLifecycle()
    val selectedModality by viewModel.selectedModalityFilter.collectAsStateWithLifecycle()
    val selectedStudyUid by viewModel.selectedStudyUid.collectAsStateWithLifecycle()
    val selectedSeriesUid by viewModel.selectedSeriesUid.collectAsStateWithLifecycle()
    val selectedInstanceIndex by viewModel.selectedInstanceIndex.collectAsStateWithLifecycle()
    val selectedTagForDetail by viewModel.selectedTagForDetail.collectAsStateWithLifecycle()
    val tagSearchQuery by viewModel.tagSearchQuery.collectAsStateWithLifecycle()
    val viewerZoom by viewModel.viewerZoom.collectAsStateWithLifecycle()
    val viewerPan by viewModel.viewerPan.collectAsStateWithLifecycle()
    val viewerInverted by viewModel.viewerInverted.collectAsStateWithLifecycle()
    val viewerWW by viewModel.viewerWindowWidth.collectAsStateWithLifecycle()
    val viewerWL by viewModel.viewerWindowCenter.collectAsStateWithLifecycle()
    val viewerFrame by viewModel.viewerCurrentFrame.collectAsStateWithLifecycle()
    val viewerRenderState by viewModel.viewerRenderState.collectAsStateWithLifecycle()
    val receivedTags by viewModel.receivedTags.collectAsStateWithLifecycle()
    val isViewerFullScreen by viewModel.isViewerFullScreen.collectAsStateWithLifecycle()
    val scuConfig by viewModel.scuConfig.collectAsStateWithLifecycle()
    val preparedScuObject by viewModel.preparedScuObject.collectAsStateWithLifecycle()
    val freehandStrokes by viewModel.freehandStrokes.collectAsStateWithLifecycle()
    val candidateFiles by viewModel.candidateFiles.collectAsStateWithLifecycle()
    val batchProgress by viewModel.batchProgress.collectAsStateWithLifecycle()
    val logDisplayMode by viewModel.logDisplayMode.collectAsStateWithLifecycle()
    val logTrafficFilter by viewModel.logTrafficFilter.collectAsStateWithLifecycle()
    val logOutcomeFilter by viewModel.logOutcomeFilter.collectAsStateWithLifecycle()
    val logSearchQuery by viewModel.logSearchQuery.collectAsStateWithLifecycle()
    val filteredSessionSummaries by viewModel.filteredSessionSummaries.collectAsStateWithLifecycle()
    val filteredLogs by viewModel.filteredLogs.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val expandedSessionKeys by viewModel.expandedSessionKeys.collectAsStateWithLifecycle()
    val expandedLogIds by viewModel.expandedLogIds.collectAsStateWithLifecycle()
    val autoScrollLogs by viewModel.autoScrollLogs.collectAsStateWithLifecycle()
    val storageLocationInfo by viewModel.storageLocationInfo.collectAsStateWithLifecycle()
    val isMigrating by viewModel.isMigrating.collectAsStateWithLifecycle()
    val migrationStatusMessage by viewModel.migrationStatusMessage.collectAsStateWithLifecycle()
    val externalSummary by viewModel.externalFileSummary.collectAsStateWithLifecycle()
    val externalTags by viewModel.externalFileTags.collectAsStateWithLifecycle()
    val isExternalLoading by viewModel.isExternalFileLoading.collectAsStateWithLifecycle()
    val externalError by viewModel.externalFileError.collectAsStateWithLifecycle()
    val externalSearchQuery by viewModel.externalTagSearchQuery.collectAsStateWithLifecycle()
    val externalEncodingOverride by viewModel.externalTagEncodingOverride.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val activity = context as? Activity


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

    // Keep navigation across Activity recreation, but do not restore a Viewer/queue screen
    // into a new process whose transient source, bitmap and network operation no longer exist.
    val processId = ru.medeng.dicomtool.data.logging.DicomSessionManager.appRunId
    var currentTab by rememberSaveable(key = "main-tab-$processId") { mutableStateOf(MainBottomTab.RECEIVER) }
    var activeSubScreen by rememberSaveable(key = "sub-screen-$processId") { mutableStateOf(ActiveSubScreen.NONE) }
    var lastExitBack by remember { mutableStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(currentTab, activeSubScreen, isViewerFullScreen) { lastExitBack = 0L }
    BackHandler {
        when {
            isViewerFullScreen -> viewModel.setViewerFullScreen(false)
            activeSubScreen != ActiveSubScreen.NONE -> {
                viewModel.selectTagForDetail(null)
                activeSubScreen = ActiveSubScreen.NONE
                lastExitBack = 0L
            }
            lastExitBack != 0L && android.os.SystemClock.elapsedRealtime() - lastExitBack in 1L..2000L -> activity?.finish()
            else -> {
                lastExitBack = android.os.SystemClock.elapsedRealtime()
                android.widget.Toast.makeText(context, "Нажмите «Назад» ещё раз для выхода", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isExpandedWidth = maxWidth >= 700.dp

        Scaffold(
            containerColor = AppBackgroundGray,
            contentWindowInsets = if (isViewerFullScreen) WindowInsets(0, 0, 0, 0) else WindowInsets.safeDrawing,
            topBar = {
                Column {
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
                                        if (tab == MainBottomTab.TAGS) viewModel.ensureExternalViewer()
                                        if (tab == MainBottomTab.RECEIVED) viewModel.ensureReceivedViewer()
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(if (isViewerFullScreen) androidx.compose.foundation.layout.PaddingValues(0.dp) else innerPadding)
            ) {
                when (activeSubScreen) {
                    ActiveSubScreen.SETTINGS_AND_CLEANUP -> {
                        SettingsAndCleanupScreen(
                            storageInfo = storageLocationInfo,
                            receptionOptions = receptionOptions,
                            onReceptionOptionsChange = viewModel::updateReceptionStorageOptions,
                            onRecoverIncoming = viewModel::recoverRetainedIncoming,
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
                                tags = receivedTags,
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
                                onStepFrame = viewModel::stepViewerFrame,
                                onStepInstance = viewModel::stepSeriesInstance,
                                onEnsureDecoded = viewModel::ensureReceivedViewer,
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
                                    indexState = archiveIndex,
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
                                        onToggleInvert = viewModel::toggleViewerInvert,
                                    onAdjustWindow = viewModel::adjustWindowWidthLevel,
                                    onSetWindowWidthLevel = viewModel::setWindowWidthLevel,
                                    onStepFrame = viewModel::stepViewerFrame,
                                    onToggleFullScreen = viewModel::toggleViewerFullScreen,
                                    onEnsureDecoded = viewModel::ensureExternalViewer
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
