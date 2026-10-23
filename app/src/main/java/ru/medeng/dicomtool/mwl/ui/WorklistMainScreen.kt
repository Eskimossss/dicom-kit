package ru.medeng.dicomtool.mwl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.medeng.dicomtool.ui.theme.AppBackgroundGray
import ru.medeng.dicomtool.ui.theme.CardBorderGray
import ru.medeng.dicomtool.ui.theme.CardSurfaceWhite
import ru.medeng.dicomtool.ui.theme.PrimaryBlue
import ru.medeng.dicomtool.ui.theme.TextPrimaryDark
import ru.medeng.dicomtool.ui.theme.TextSecondarySlate
import ru.medeng.dicomtool.ui.theme.ErrorRed

@Composable
fun WorklistMainScreen(
    isExpandedWidth: Boolean,
    viewModel: MwlViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val readError by viewModel.repositoryReadError.collectAsStateWithLifecycle()
    val tabs = MwlSubTab.entries

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppBackgroundGray)
    ) {
        // Верхний ряд подвкладок Worklist
        if (isExpandedWidth) {
            TabRow(
                selectedTabIndex = selectedTab.ordinal,
                containerColor = CardSurfaceWhite,
                contentColor = PrimaryBlue,
                divider = { HorizontalDivider(color = CardBorderGray) },
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTab.ordinal]),
                        color = PrimaryBlue
                    )
                }
            ) {
                tabs.forEach { tab ->
                    val isSelected = selectedTab == tab
                    Tab(
                        selected = isSelected,
                        onClick = { viewModel.selectTab(tab) },
                        text = {
                            Text(
                                text = tab.titleRu,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) PrimaryBlue else TextSecondarySlate
                            )
                        }
                    )
                }
            }
        } else {
            ScrollableTabRow(
                selectedTabIndex = selectedTab.ordinal,
                containerColor = CardSurfaceWhite,
                contentColor = PrimaryBlue,
                edgePadding = 8.dp,
                divider = { HorizontalDivider(color = CardBorderGray) },
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTab.ordinal]),
                        color = PrimaryBlue
                    )
                }
            ) {
                tabs.forEach { tab ->
                    val isSelected = selectedTab == tab
                    Tab(
                        selected = isSelected,
                        onClick = { viewModel.selectTab(tab) },
                        text = {
                            Text(
                                text = tab.titleRu,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) PrimaryBlue else TextSecondarySlate
                            )
                        }
                    )
                }
            }
        }

        readError?.let { Text(it, color = ErrorRed, modifier = Modifier.fillMaxWidth().padding(12.dp)) }
        // Содержимое выбранной подвкладки
        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                MwlSubTab.SCP_SERVER -> MwlScpTab(viewModel = viewModel, isExpandedWidth = isExpandedWidth)
                MwlSubTab.APPOINTMENTS -> if (readError == null) MwlAppointmentsTab(viewModel = viewModel, isExpandedWidth = isExpandedWidth)
                MwlSubTab.PATIENTS -> if (readError == null) MwlPatientsTab(viewModel = viewModel, isExpandedWidth = isExpandedWidth)
                MwlSubTab.SCU_CLIENT -> MwlScuTab(viewModel = viewModel, isExpandedWidth = isExpandedWidth)
                MwlSubTab.JOURNAL -> MwlJournalTab(viewModel = viewModel, isExpandedWidth = isExpandedWidth)
            }
        }
    }
}
