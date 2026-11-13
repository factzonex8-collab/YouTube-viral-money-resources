package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoMode
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.ClipForgeViewModel
import com.example.ui.screens.AgentScreen
import com.example.ui.screens.ClipsHistoryScreen
import com.example.ui.screens.ServerSetupScreen
import com.example.ui.screens.StudioScreen
import com.example.ui.theme.ClipForgeTheme

const val ROUTE_STUDIO = "studio"
const val ROUTE_AGENT = "agent"
const val ROUTE_CLIPS = "clips"
const val ROUTE_SERVER = "server"

private data class NavDestination(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ClipForgeTheme(darkTheme = true) {
                ClipForgeApp()
            }
        }
    }
}

@Composable
fun ClipForgeApp(
    viewModel: ClipForgeViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: ROUTE_STUDIO
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.statusBannerMessage) {
        val msg = uiState.statusBannerMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissBanner()
        }
    }

    val destinations = remember {
        listOf(
            NavDestination(
                route = ROUTE_STUDIO,
                labelRes = R.string.nav_studio,
                selectedIcon = Icons.Rounded.AutoAwesome,
                unselectedIcon = Icons.Outlined.AutoAwesome,
                testTag = "nav_studio"
            ),
            NavDestination(
                route = ROUTE_AGENT,
                labelRes = R.string.nav_agent,
                selectedIcon = Icons.Rounded.AutoMode,
                unselectedIcon = Icons.Outlined.AutoMode,
                testTag = "nav_agent"
            ),
            NavDestination(
                route = ROUTE_CLIPS,
                labelRes = R.string.nav_clips,
                selectedIcon = Icons.Rounded.VideoLibrary,
                unselectedIcon = Icons.Outlined.VideoLibrary,
                testTag = "nav_clips"
            ),
            NavDestination(
                route = ROUTE_SERVER,
                labelRes = R.string.nav_server,
                selectedIcon = Icons.Rounded.Dns,
                unselectedIcon = Icons.Outlined.Dns,
                testTag = "nav_server"
            )
        )
    }

    fun navigateTo(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            bottomBar = {
                if (!useRail) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp
                    ) {
                        destinations.forEach { dest ->
                            val selected = currentRoute == dest.route
                            val label = stringResource(dest.labelRes)
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateTo(dest.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = label
                                    )
                                },
                                label = { Text(label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                modifier = Modifier.testTag(dest.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        destinations.forEach { dest ->
                            val selected = currentRoute == dest.route
                            val label = stringResource(dest.labelRes)
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTo(dest.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = label
                                    )
                                },
                                label = { Text(label) },
                                modifier = Modifier.testTag(dest.testTag)
                            )
                        }
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = ROUTE_STUDIO,
                    modifier = Modifier.weight(1f)
                ) {
                    composable(ROUTE_STUDIO) {
                        StudioScreen(
                            uiState = uiState,
                            onUrlChange = viewModel::updateYoutubeUrl,
                            onCaptionStyleSelect = viewModel::selectCaptionStyle,
                            onWhisperModelSelect = viewModel::selectWhisperModel,
                            onDurationChange = viewModel::updateTargetDuration,
                            onGenerateClick = viewModel::generateShort,
                            onDownloadClick = viewModel::downloadActiveClip,
                            onOpenBackendSettings = { navigateTo(ROUTE_SERVER) },
                            buildStreamUrl = viewModel::buildStreamUrl
                        )
                    }
                    composable(ROUTE_AGENT) {
                        AgentScreen(
                            uiState = uiState,
                            onNicheChange = viewModel::updateNicheDraft,
                            onIntervalSelect = viewModel::updateAgentInterval,
                            onPeakHourSelect = viewModel::updateAgentPeakHour,
                            onAutoUploadToggle = viewModel::updateAgentAutoUpload,
                            onSaveSettings = { viewModel.saveAgentConfig(null) },
                            onToggleAutonomy = { enabled -> viewModel.saveAgentConfig(enabled) },
                            onRunNow = viewModel::runAutonomousCycleNow,
                            onRefresh = { viewModel.refreshAgentState(false) },
                            onUploadRun = viewModel::uploadExistingAgentRun
                        )
                    }
                    composable(ROUTE_CLIPS) {
                        ClipsHistoryScreen(
                            uiState = uiState,
                            onRefresh = viewModel::refreshJobs,
                            onSelectClip = { job ->
                                viewModel.selectJobForPreview(job)
                                navigateTo(ROUTE_STUDIO)
                            },
                            onDownloadClip = viewModel::downloadActiveClip
                        )
                    }
                    composable(ROUTE_SERVER) {
                        ServerSetupScreen(
                            uiState = uiState,
                            onServerUrlDraftChange = viewModel::updateServerUrlDraft,
                            onSaveServerUrl = viewModel::saveAndTestServerUrl,
                            onTestConnection = {
                                viewModel.checkBackendHealth(uiState.serverUrlDraft)
                            }
                        )
                    }
                }
            }
        }
    }
}
