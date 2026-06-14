package com.example.qrsc

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

private data class NavItem(val label: String, val icon: ImageVector)

private val navItems = listOf(
    NavItem("采集", Icons.Default.Search),
    NavItem("文件", Icons.AutoMirrored.Filled.List),
    NavItem("设置", Icons.Default.Settings),
)

@Composable
fun QRFileMainScreen(viewModel: MainViewModel) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val isBlackScreen by ScannerState.isBlackScreen.collectAsState()

    Scaffold(
        bottomBar = {
            if (!isBlackScreen) {
                NavigationBar(
                    tonalElevation = 0.dp,
                    containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
                ) {
                    navItems.forEachIndexed { index, item ->
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label, style = androidx.compose.material3.MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        when (selectedTab) {
            0 -> ScannerTab(viewModel, Modifier.padding(paddingValues))
            1 -> FilesTab(viewModel, Modifier.padding(paddingValues))
            2 -> SettingsTab(viewModel, Modifier.padding(paddingValues))
        }
    }
}
