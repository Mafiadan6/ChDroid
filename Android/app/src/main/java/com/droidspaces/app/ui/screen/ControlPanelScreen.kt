package com.droidspaces.app.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.droidspaces.app.ui.component.DsSnackbarHost
import com.droidspaces.app.ui.component.EmptyState
import com.droidspaces.app.ui.component.RootUnavailableState
import com.droidspaces.app.ui.component.PullToRefreshWrapper
import com.droidspaces.app.ui.component.RunningContainerCard
import com.droidspaces.app.ui.util.AnimatedListEntry
import com.droidspaces.app.ui.util.showError
import com.droidspaces.app.ui.util.rememberAnimatedEntries
import com.droidspaces.app.ui.viewmodel.ContainerViewModel
import com.droidspaces.app.ui.viewmodel.SystemStatsViewModel
import com.droidspaces.app.util.AnlandUtils
import com.droidspaces.app.util.ChDroidRootfsManager
import com.droidspaces.app.util.ContainerInfo
import com.droidspaces.app.util.ContainerOSInfoManager
import com.droidspaces.app.util.TermuxX11Utils
import androidx.compose.ui.platform.LocalContext
import com.droidspaces.app.R
import kotlinx.coroutines.delay

/**
 * Control Panel screen - shows system stats and running containers.
 *
 * No PullToRefreshWrapper of its own: MainTabScreen's pager wraps it, and a
 * second wrapper inside the first glitches the gesture.
 */
@Composable
fun ControlPanelScreen(
    isBackendAvailable: Boolean,
    isRootAvailable: Boolean = true,
    isKernelSupported: Boolean = true,
    containerViewModel: ContainerViewModel,
    onNavigateToContainerDetails: (String) -> Unit = {},
    onNavigateToTerminal: (String) -> Unit = {},
    rootfsRunning: Boolean = false,
    rootfsName: String = "",
    onOpenRootfsTerminal: () -> Unit = {},
    onStartRootfs: () -> Unit = {},
    onStopRootfs: () -> Unit = {},
    bottomBarInset: Dp = 0.dp,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val systemStatsViewModel: SystemStatsViewModel = viewModel()

    // Get running containers - derived from ViewModel state
    val runningContainers = containerViewModel.containerList.filter { it.isRunning }
    val entries = rememberAnimatedEntries(runningContainers) { it.name }

    val lifecycleOwner = LocalLifecycleOwner.current

    // Poll only while this screen is composed (i.e. the Panel tab is selected)
    // AND the app is in the foreground (Lifecycle STARTED).  repeatOnLifecycle
    // cancels the loop on background / tab-away and restarts it on return, so
    // re-opening the app on the Panel tab resumes polling without a tab switch.
    // Restarts whenever the running container set changes.
    LaunchedEffect(runningContainers) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // A container that died on its own (poweroff inside it) is only noticed by
            // this poll, so it has to tell the list, or its card stays up with no stats.
            systemStatsViewModel.monitorContainers(runningContainers, onStopped = containerViewModel::refresh)
        }
    }

    val containerUsageMap = systemStatsViewModel.containerUsageMap

    // The CLI rootfs is not a backend container, so SystemStatsViewModel cannot
    // poll it. Probe chroot.sh's namespace directly and feed the same OSInfo shape
    // into the card, so uptime / IP / CPU / RAM stay live while the Panel is open.
    var rootfsStats by remember { mutableStateOf<ContainerOSInfoManager.OSInfo?>(null) }
    LaunchedEffect(rootfsRunning) {
        if (!rootfsRunning) {
            rootfsStats = null
            return@LaunchedEffect
        }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                rootfsStats = ChDroidRootfsManager.stats()
                delay(2000)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Show content based on root and backend availability
        // Using when instead of early return to prevent UI glitches during recomposition
        when {
            !isRootAvailable -> {
                RootUnavailableState(modifier = Modifier.padding(bottom = bottomBarInset))
            }
            // ChDroid drives chroot.sh, not the Droidspaces backend. A missing
            // backend or an unmet container kernel gate must not blank this tab —
            // the rootfs is still usable and must still be listed here.
            else -> {
                if (entries.isEmpty() && !rootfsRunning) {
                    EmptyState(
                        icon = Icons.Default.Dashboard,
                        title = context.getString(R.string.no_containers_running),
                        description = context.getString(R.string.start_container_first),
                        // Reserve the floating tab bar's space so the centered
                        // content sits in the visible region, not behind the bar.
                        modifier = Modifier.padding(bottom = bottomBarInset)
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp)
                            // Each card carries its own 16dp gap (AnimatedListEntry) on top of this
                            .padding(top = 8.dp, bottom = bottomBarInset + 16.dp),
                    ) {
                        if (rootfsRunning) {
                            // Same card Droidspaces uses on the Panel. The CLI rootfs
                            // is not a backend container, so a synthetic ContainerInfo
                            // with its name is enough for RunningContainerCard, which
                            // only reads container.name for its identity and icon.
                            key("chdroid-rootfs") {
                                val displayName = rootfsName.ifBlank { "ChDroid" }
                                RunningContainerCard(
                                    container = ContainerInfo(
                                        name = displayName,
                                        hostname = displayName,
                                        rootfsPath = ""
                                    ),
                                    onEnter = onOpenRootfsTerminal,
                                    onTerminalClick = onOpenRootfsTerminal,
                                    osInfo = rootfsStats,
                                    modifier = Modifier.padding(bottom = 16.dp)
                                )
                            }
                        }
                        entries.forEach { entry ->
                            val container = entry.item
                            // The heartbeat reports the broker's socket or x11 only for a container
                            // booted with that display, so it gates the pill, not the saved config.
                            val anlandSock = containerUsageMap[container.name]?.anlandSocket
                            val (displayLabel, displayIcon) = when {
                                anlandSock != null -> context.getString(R.string.anland_option) to rememberVectorPainter(Icons.Default.DesktopWindows)
                                containerUsageMap[container.name]?.x11 == true -> context.getString(R.string.termux_x11_option) to painterResource(R.drawable.ic_x11)
                                else -> null to null
                            }
                            // Keyed, so a card's state stays with its container when one leaves.
                            key(entry.key) {
                                AnimatedListEntry(entry) {
                                    RunningContainerCard(
                                        container = container,
                                        onEnter = {
                                            onNavigateToContainerDetails(container.name)
                                        },
                                        onTerminalClick = {
                                            onNavigateToTerminal(container.name)
                                        },
                                        displayLabel = displayLabel,
                                        displayIcon = displayIcon,
                                        onLaunchDisplay = {
                                            if (anlandSock != null) {
                                                if (!AnlandUtils.launchWindow(context, container.name, anlandSock))
                                                    scope.showError(snackbarHostState, context.getString(R.string.anland_not_installed))
                                            } else if (!TermuxX11Utils.launch(context))
                                                scope.showError(snackbarHostState, context.getString(R.string.termux_x11_not_installed))
                                        },
                                        osInfo = containerUsageMap[container.name],
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Snackbar host (always present)
        DsSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomBarInset + 16.dp)
        )
    }
}

