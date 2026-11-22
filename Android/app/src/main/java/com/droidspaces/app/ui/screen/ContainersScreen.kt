package com.droidspaces.app.ui.screen

import com.droidspaces.app.ui.component.DsDialog
import com.droidspaces.app.ui.component.ConfirmPhraseField
import com.droidspaces.app.ui.component.DsTextFieldDefaults

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.traversalIndex
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.droidspaces.app.ui.component.DsSnackbarHost
import com.droidspaces.app.ui.util.ProgressDialog
import com.droidspaces.app.ui.util.FullScreenLoading
import com.droidspaces.app.ui.util.ErrorLogsDialog
import com.droidspaces.app.ui.util.AnimatedListEntry
import com.droidspaces.app.ui.util.rememberAnimatedEntries
import com.droidspaces.app.ui.util.showError
import com.droidspaces.app.ui.util.showSuccess
import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.droidspaces.app.ui.viewmodel.SystemStatsViewModel
import com.droidspaces.app.util.ContainerInfo
import com.droidspaces.app.util.PreferencesManager
import com.droidspaces.app.util.FilePickerUtils
import com.droidspaces.app.ui.component.ContainerCard
import com.droidspaces.app.ui.component.ContainerCardActions
import com.droidspaces.app.ui.component.DialogFooterRow
import com.droidspaces.app.ui.component.TerminalDialog
import com.droidspaces.app.ui.component.ExportContainerDialog
import com.droidspaces.app.util.ValidationUtils
import com.droidspaces.app.ui.component.EmptyState
import com.droidspaces.app.ui.component.ErrorState
import com.droidspaces.app.ui.component.KernelUnsupportedState
import com.droidspaces.app.ui.component.RootUnavailableState
import com.droidspaces.app.ui.component.ChDroidRootfsSheet
import com.droidspaces.app.ui.component.ChDroidRootfsCard
import com.droidspaces.app.util.ChDroidRootfsManager
import com.droidspaces.app.util.ViewModelLogger
import com.droidspaces.app.ui.viewmodel.ContainerViewModel
import com.droidspaces.app.ui.viewmodel.ContainerOperationsViewModel
import com.droidspaces.app.ui.viewmodel.UninstallState
import com.droidspaces.app.ui.viewmodel.SparseOp
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import com.droidspaces.app.R
import com.droidspaces.app.ui.theme.DsIcons
import androidx.compose.ui.window.Dialog

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ContainersScreen(
    isBackendAvailable: Boolean,
    isRootAvailable: Boolean = true,
    isKernelSupported: Boolean = true,
    onNavigateToInstallation: (Uri) -> Unit = {},
    onNavigateToEditContainer: (String) -> Unit = {},
    onNavigateToContainerDetails: (String) -> Unit = {},
    containerViewModel: ContainerViewModel,
    expandedContainerName: String?,
    onExpandedContainerNameChange: (String?) -> Unit,
    bottomBarInset: Dp = 0.dp
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val systemStatsViewModel: SystemStatsViewModel = viewModel()
    val prefsManager = PreferencesManager.getInstance(context)

    val snackbarHostState = remember { SnackbarHostState() }

    // Container lifecycle/maintenance operations + their state live in the ViewModel.
    val opsViewModel: ContainerOperationsViewModel = viewModel()

    // UI-only state (dialog triggers / pending pickers).
    var showUninstallConfirmation by remember { mutableStateOf<ContainerInfo?>(null) }
    var pendingSparseOperation by remember { mutableStateOf<Pair<ContainerInfo, SparseOp>?>(null) }
    var pendingExportContainer by remember { mutableStateOf<ContainerInfo?>(null) }
    var pendingExportEnv by remember { mutableStateOf(false) }
    var askExportEnv by remember { mutableStateOf<ContainerInfo?>(null) }
    var showRootfsSheet by remember { mutableStateOf(false) }
    var showRootfsLogs by remember { mutableStateOf(false) }
    var fabMenuExpanded by rememberSaveable { mutableStateOf(false) }

    // ChDroid rootfs edit/uninstall, mirroring the upstream container actions.
    var chdroidName by remember { mutableStateOf("") }
    var chdroidHostname by remember { mutableStateOf("") }
    var showChdroidEdit by remember { mutableStateOf(false) }
    var chdroidEditName by remember { mutableStateOf("") }
    var chdroidEditHostname by remember { mutableStateOf("") }
    var showChdroidUninstall by remember { mutableStateOf(false) }
    var chdroidConfirmName by remember { mutableStateOf("") }
    var chdroidActionBusy by remember { mutableStateOf(false) }

    // The CLI rootfs is a Containers-tab card with start/stop, mirroring upstream
    // ContainerCard. Terminal is deliberately absent here - it lives on the Panel.
    var chdroidInstalled by remember {
        mutableStateOf(com.droidspaces.app.util.ChDroidPaths.installedLocally())
    }
    var chdroidRunning by remember { mutableStateOf(false) }
    var chdroidDistro by remember { mutableStateOf("") }
    var chdroidBusy by remember { mutableStateOf(false) }

    suspend fun loadChdroidRootfs() {
        val s = ChDroidRootfsManager.snapshot()
        chdroidInstalled = s.installed
        chdroidName = s.name
        chdroidHostname = s.hostname
        chdroidDistro = s.name.ifBlank { s.distro }
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        chdroidRunning = s.running
    }

    // Same live poll MainTabScreen uses for the Home counts. The terminal can
    // come and go without this screen recomposing, so a load-on-mount-only card
    // showed "not running" after exit until a manual refresh kicked in. Poll
    // while the tab is STARTED and the status follows the holder PID in ~2s.
    val chdroidLifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(chdroidLifecycleOwner) {
        chdroidLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                loadChdroidRootfs()
                delay(2000)
            }
        }
    }
    LaunchedEffect(containerViewModel.isRefreshing) {
        if (!containerViewModel.isRefreshing) loadChdroidRootfs()
    }

    fun chdroidAction(start: Boolean) = scope.launch {
        // Droidspaces opens the console on container start/stop and streams the
        // command into it. Do the same here: chroot.sh output belongs in the
        // console, not the Panel terminal.
        showRootfsLogs = true
        chdroidBusy = true
        val result = if (start) ChDroidRootfsManager.start() else ChDroidRootfsManager.stop()
        chdroidBusy = false
        result.fold(
            onSuccess = {
                scope.showSuccess(
                    snackbarHostState,
                    context.getString(if (start) R.string.uc_started else R.string.uc_stopped)
                )
            },
            onFailure = { scope.showError(snackbarHostState, it.message ?: context.getString(R.string.uc_failed)) }
        )
        loadChdroidRootfs()
    }

    fun chdroidSaveIdentity() = scope.launch {
        chdroidActionBusy = true
        val logger = ViewModelLogger { _, _ -> }
        val result = ChDroidRootfsManager.applyIdentity(
            context = context,
            hostname = chdroidEditHostname.trim(),
            containerName = chdroidEditName.trim(),
            logger = logger
        )
        chdroidActionBusy = false
        result.fold(
            onSuccess = {
                showChdroidEdit = false
                scope.showSuccess(snackbarHostState, context.getString(R.string.chdroid_identity_saved))
                loadChdroidRootfs()
            },
            onFailure = {
                scope.showError(snackbarHostState, it.message ?: context.getString(R.string.uc_failed))
            }
        )
    }

    fun chdroidUninstall() = scope.launch {
        chdroidActionBusy = true
        val logger = ViewModelLogger { _, _ -> }
        val result = ChDroidRootfsManager.uninstall(context, logger)
        chdroidActionBusy = false
        result.fold(
            onSuccess = {
                showChdroidUninstall = false
                showRootfsLogs = false
                scope.showSuccess(snackbarHostState, context.getString(R.string.chdroid_uninstalled))
                loadChdroidRootfs()
            },
            onFailure = {
                scope.showError(snackbarHostState, it.message ?: context.getString(R.string.uc_failed))
            }
        )
    }

    // File picker launcher - CreateDocument for saving the export archive
    val exportFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/gzip")
    ) { uri: Uri? ->
        val container = pendingExportContainer
        val includeEnv = pendingExportEnv
        pendingExportContainer = null
        if (uri != null && container != null) {
            scope.launch {
                opsViewModel.executeExport(container, uri, includeEnv, onError = { msg -> scope.showError(snackbarHostState, msg) })
            }
        }
    }

    // File picker launcher - accept all files, validate internally
    // We don't filter in the picker (MIME types are unreliable for tar.xz/tar.gz)
    // FilePickerUtils handles proper filename extraction from any URI type (including recent files)
    // and validates that the file is a .tar.xz or .tar.gz file
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val fileName = FilePickerUtils.getFileName(context, uri)
                // ChDroid installs the Ubuntu-Chroot CLI ZIP directly, no container wizard.
                val isCliZip = fileName?.endsWith(".zip", ignoreCase = true) == true
                val (isValid, checkedName) = FilePickerUtils.isValidTarball(context, uri)
                if (isCliZip || (isValid && checkedName != null)) {
                    onNavigateToInstallation(uri)
                } else {
                    val errorMessage = if (fileName != null) {
                        context.getString(R.string.file_picker_error, fileName)
                    } else {
                        context.getString(R.string.file_picker_error_unknown)
                    }
                    scope.showError(snackbarHostState, errorMessage)
                }
            }
        }
    }

    // Get containers from ViewModel - single source of truth (KernelSU pattern)
    val containers = containerViewModel.containerList
    // Removed cards stay here until they finish leaving, so the empty state waits for them.
    val entries = rememberAnimatedEntries(containers) { it.name }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // Show root unavailable state first, then backend unavailable, then content
        when {
            !isRootAvailable -> {
                RootUnavailableState(modifier = Modifier.padding(bottom = bottomBarInset))
            }
            !isBackendAvailable -> {
                ErrorState(modifier = Modifier.padding(bottom = bottomBarInset))
            }
            !isKernelSupported -> {
                KernelUnsupportedState(modifier = Modifier.padding(bottom = bottomBarInset))
            }
            entries.isEmpty() && !chdroidInstalled -> {
                if (containerViewModel.isRefreshing) {
                    FullScreenLoading(modifier = Modifier.padding(bottom = bottomBarInset))
                } else {
                    EmptyState(
                        icon = DsIcons.Logo,
                        title = context.getString(R.string.no_containers_installed),
                        description = context.getString(R.string.install_container_description),
                        // Reserve the floating tab bar's space so the centered
                        // content sits in the visible region, not behind the bar.
                        modifier = Modifier.padding(bottom = bottomBarInset)
                    )
                }
            }
            else -> {
                // Show container cards
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .combinedClickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                onExpandedContainerNameChange(null)
                                fabMenuExpanded = false
                            }
                        )
                        .padding(horizontal = 16.dp),
                    // Each card carries its own 16dp gap (AnimatedListEntry) on top of this
                    contentPadding = PaddingValues(top = 8.dp, bottom = bottomBarInset + 16.dp),
                ) {
                    if (chdroidInstalled) {
                        item(key = "chdroid-rootfs-card") {
                            ChDroidRootfsCard(
                                distro = chdroidDistro,
                                path = com.droidspaces.app.util.ChDroidPaths.base,
                                isRunning = chdroidRunning,
                                isBusy = chdroidBusy,
                                onStart = { chdroidAction(start = true) },
                                onStop = { chdroidAction(start = false) },
                                onShowLogs = { showRootfsLogs = true },
                                onEdit = {
                                    chdroidEditName = chdroidName
                                    chdroidEditHostname = chdroidHostname
                                    showChdroidEdit = true
                                },
                                onUninstall = {
                                    chdroidConfirmName = ""
                                    showChdroidUninstall = true
                                }
                            )
                        }
                    }

                    items(entries, key = { it.key }) { entry ->
                        val container = entry.item
                        // Console button is always visible - logs persist for each container
                        val isRunning = opsViewModel.runningOperationContainer == container.name
                        fun run(op: String) = scope.launch {
                            opsViewModel.executeOperation(
                                container, op,
                                onRefresh = { containerViewModel.refresh() },
                                onClearUsage = { systemStatsViewModel.clearContainerUsage(it) },
                                onFailureSnackbar = { scope.showError(snackbarHostState, it) }
                            )
                        }

                        AnimatedListEntry(entry) {
                            ContainerCard(
                                container = container,
                                isOperationRunning = isRunning,
                                isExpanded = expandedContainerName == container.name,
                                actions = ContainerCardActions(
                                onToggleExpand = {
                                    onExpandedContainerNameChange(if (expandedContainerName == container.name) null else container.name)
                                },
                                 onShowLogs = {
                                    opsViewModel.showLogViewerFor = container.name
                                },
                                onStart = { run("start") },
                                onStop = { run("stop") },
                                onRestart = { run("restart") },
                                onEdit = {
                                    onExpandedContainerNameChange(null)
                                    onNavigateToEditContainer(container.name)
                                },
                                onEnter = {
                                    onNavigateToContainerDetails(container.name)
                                },
                                onUninstall = {
                                    onExpandedContainerNameChange(null)
                                    showUninstallConfirmation = container
                                },
                                onMigrate = {
                                    onExpandedContainerNameChange(null)
                                    pendingSparseOperation = container to SparseOp.MIGRATE
                                },
                                onResize = {
                                    onExpandedContainerNameChange(null)
                                    pendingSparseOperation = container to SparseOp.RESIZE
                                },
                                onExport = {
                                    onExpandedContainerNameChange(null)
                                    // Only a container with env vars has something to decide.
                                    if (ValidationUtils.countEnvVars(container.envFileContent) > 0) {
                                        askExportEnv = container
                                    } else {
                                        pendingExportEnv = false
                                        pendingExportContainer = container
                                        exportFileLauncher.launch(exportFileName(container))
                                    }
                                }
                                )
                            )
                        }
                    }
                }
            }
        }

        // One FAB, a Material 3 Expressive menu: the plus morphs into a cross and the two
        // install routes rise above it. Replaces the small FAB stacked on an extended one.
        if (isBackendAvailable && isRootAvailable && isKernelSupported) {
            BackHandler(fabMenuExpanded) { fabMenuExpanded = false }

            FloatingActionButtonMenu(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 24.dp, bottom = bottomBarInset + 16.dp),
                expanded = fabMenuExpanded,
                button = {
                    ToggleFloatingActionButton(
                        modifier = Modifier.semantics {
                            traversalIndex = -1f
                            contentDescription = context.getString(R.string.add_container)
                            stateDescription = context.getString(
                                if (fabMenuExpanded) R.string.fab_menu_expanded else R.string.fab_menu_collapsed
                            )
                        },
                        checked = fabMenuExpanded,
                        onCheckedChange = { fabMenuExpanded = !fabMenuExpanded }
                    ) {
                        val imageVector by remember {
                            derivedStateOf { if (checkedProgress > 0.5f) Icons.Default.Close else Icons.Default.Add }
                        }
                        Icon(
                            painter = rememberVectorPainter(imageVector),
                            contentDescription = null,
                            modifier = Modifier.animateIcon({ checkedProgress })
                        )
                    }
                }
            ) {
                FloatingActionButtonMenuItem(
                    onClick = {
                        fabMenuExpanded = false
                        showRootfsSheet = true
                    },
                    icon = { Icon(Icons.Default.CloudDownload, contentDescription = null) },
                    text = { Text(context.getString(R.string.repo_fab_label)) }
                )
                // Last item, nearest the thumb. TalkBack reaches the close button before the
                // first item, so the last one offers a close action of its own.
                FloatingActionButtonMenuItem(
                    modifier = Modifier.semantics {
                        isTraversalGroup = true
                        customActions = listOf(
                            CustomAccessibilityAction(context.getString(R.string.close_menu)) {
                                fabMenuExpanded = false
                                true
                            }
                        )
                    },
                    onClick = {
                        fabMenuExpanded = false
                        filePickerLauncher.launch("*/*")
                    },
                    icon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                    text = { Text(context.getString(R.string.install_from_file)) }
                )
            }
        }

        // ChDroid rootfs sources: built-in link, custom link, file import
        if (showRootfsSheet) {
            val orientation = LocalConfiguration.current.orientation
            key(orientation) {
                ChDroidRootfsSheet(
                    onDismiss = { showRootfsSheet = false },
                    onInstall = { uri -> onNavigateToInstallation(uri) },
                    onImport = {
                        showRootfsSheet = false
                        filePickerLauncher.launch("*/*")
                    }
                )
            }
        }

        // SNACKBAR LAYER (Highest Z-index in the root Box)
        DsSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomBarInset + 16.dp)
        )

        // Log viewer dialog - console stays open, user must close manually
        opsViewModel.showLogViewerFor?.let { containerName ->
            // Load logs from memory first, fallback to cache if empty
            val memoryLogs = opsViewModel.containerLogs[containerName]?.toList() ?: emptyList()
            val cachedLogs = if (memoryLogs.isEmpty()) {
                prefsManager.loadContainerLogs(containerName)
            } else {
                emptyList()
            }
            val logs = memoryLogs.ifEmpty { cachedLogs }
            val isBlocking = opsViewModel.runningOperationContainer == containerName // Blocking when operation is running
            TerminalDialog(
                title = context.getString(R.string.logs_title, containerName),
                logs = logs,
                onDismiss = {
                    opsViewModel.showLogViewerFor = null
                    // Refresh container status when console is closed (KernelSU pattern)
                    containerViewModel.refresh()
                },
                onClear = {
                    opsViewModel.clearLogsBuffer(containerName)
                },
                isBlocking = isBlocking // Block dismissal when operation is running
            )
        }

        // ChDroid rootfs start/stop console - same viewer, backed by chroot.sh output.
        if (showRootfsLogs) {
            TerminalDialog(
                title = context.getString(R.string.chdroid_rootfs_console_title, chdroidDistro.ifBlank { "ChDroid" }),
                logs = ChDroidRootfsManager.logs(),
                onDismiss = { showRootfsLogs = false },
                onClear = { ChDroidRootfsManager.clearLogs() },
                isBlocking = chdroidBusy,
                clipboardLabel = context.getString(R.string.chdroid_rootfs_console_clipboard)
            )
        }

        // ChDroid rootfs edit - same identity script the installer uses, so the
        // card name, /etc/hostname and chroot.sh's C_HOSTNAME all move together.
        if (showChdroidEdit) {
            DsDialog(
                onDismiss = { showChdroidEdit = false },
                footer = {
                    DialogFooterRow(
                        dismissLabel = context.getString(R.string.cancel),
                        confirmLabel = context.getString(R.string.save_changes),
                        onDismiss = { showChdroidEdit = false },
                        onConfirm = { chdroidSaveIdentity() },
                        confirmEnabled = chdroidEditName.isNotBlank() && chdroidEditHostname.isNotBlank() && !chdroidActionBusy
                    )
                }
            ) {
                Text(
                    context.getString(R.string.chdroid_edit_title),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    context.getString(R.string.chdroid_edit_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = chdroidEditName,
                    onValueChange = { chdroidEditName = it },
                    label = { Text(context.getString(R.string.chdroid_edit_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = DsTextFieldDefaults.colors()
                )
                OutlinedTextField(
                    value = chdroidEditHostname,
                    onValueChange = { chdroidEditHostname = it },
                    label = { Text(context.getString(R.string.hostname)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = DsTextFieldDefaults.colors()
                )
            }
        }

        // ChDroid rootfs uninstall - upstream's typed-name confirmation.
        if (showChdroidUninstall) {
            DsDialog(
                onDismiss = { showChdroidUninstall = false },
                borderColor = MaterialTheme.colorScheme.error,
                footer = {
                    DialogFooterRow(
                        dismissLabel = context.getString(R.string.cancel),
                        confirmLabel = context.getString(R.string.uninstall),
                        onDismiss = { showChdroidUninstall = false },
                        onConfirm = { chdroidUninstall() },
                        confirmEnabled = chdroidConfirmName == chdroidName && chdroidName.isNotBlank() && !chdroidActionBusy,
                        destructive = true
                    )
                }
            ) {
                Text(
                    context.getString(R.string.chdroid_uninstall_title),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge
                )
                ConfirmPhraseField(
                    value = chdroidConfirmName,
                    onValueChange = { chdroidConfirmName = it },
                    isError = chdroidConfirmName.isNotEmpty() && chdroidConfirmName != chdroidName,
                    instruction = context.getString(R.string.type_container_name_to_confirm),
                    phrase = chdroidName,
                    capitalization = KeyboardCapitalization.None
                )
            }
        }

        askExportEnv?.let { container ->
            ExportContainerDialog(
                containerName = container.name,
                envCount = ValidationUtils.countEnvVars(container.envFileContent),
                onConfirm = { includeEnv ->
                    askExportEnv = null
                    pendingExportEnv = includeEnv
                    pendingExportContainer = container
                    exportFileLauncher.launch(exportFileName(container))
                },
                onDismiss = { askExportEnv = null }
            )
        }

        // Uninstall confirmation dialog
        showUninstallConfirmation?.let { container ->
            UninstallConfirmationDialog(
                containerName = container.name,
                onConfirm = {
                    showUninstallConfirmation = null
                    scope.launch {
                        opsViewModel.executeUninstall(
                            container,
                            onError = { msg -> scope.showError(snackbarHostState, msg) },
                            onSuccess = { msg -> scope.showSuccess(snackbarHostState, msg) },
                            onRefresh = { containerViewModel.refresh() }
                        )
                    }
                },
                onDismiss = {
                    showUninstallConfirmation = null
                }
            )
        }

        // Uninstall progress dialog
        (opsViewModel.uninstallState as? UninstallState.InProgress)?.let { state ->
            ProgressDialog(
                message = state.message
            )
        }

        // Uninstall logs dialog (only on failure)
        opsViewModel.uninstallLogsDialog?.let { logs ->
            ErrorLogsDialog(
                logs = logs,
                onDismiss = { opsViewModel.dismissUninstallLogs() }
            )
        }

        // Sparse operation size dialog
        pendingSparseOperation?.let { (container, op) ->
            SparseSizeDialog(
                title = context.getString(
                    if (op == SparseOp.MIGRATE) R.string.migrate_dialog_title
                    else R.string.resize_dialog_title
                ),
                message = context.getString(
                    if (op == SparseOp.MIGRATE) R.string.migrate_dialog_message
                    else R.string.resize_dialog_message,
                    container.name
                ),
                initialSize = container.sparseImageSizeGB ?: 8,
                // Only a resize can be a no-op: a migrate has no image yet, so
                // picking the default size there is a legitimate choice.
                currentSize = if (op == SparseOp.RESIZE) container.sparseImageSizeGB else null,
                onConfirm = { size ->
                    pendingSparseOperation = null
                    scope.launch {
                        opsViewModel.executeSparseOperation(container, op, size, onRefresh = { containerViewModel.refresh() })
                    }
                },
                onDismiss = { pendingSparseOperation = null }
            )
        }
    }
}

/** <name>_yyyyMMdd_HHmmss.tar.gz, the name the save dialog suggests. */
private fun exportFileName(container: ContainerInfo): String {
    val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
    return "${container.name}_${timestamp}.tar.gz"
}

@Composable
private fun SparseSizeDialog(
    title: String,
    message: String,
    initialSize: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
    currentSize: Int? = null
) {
    val context = LocalContext.current
    var sizeText by remember { mutableStateOf(initialSize.toString()) }
    val size = sizeText.toIntOrNull()
    val isOutOfRange = size == null || size !in 4..512
    // The field opens pre-filled with the image's existing size, so confirming
    // straight away would run a resize that resizes nothing.
    val isSameSize = currentSize != null && size == currentSize
    val isValid = !isOutOfRange && !isSameSize

    DsDialog(
        onDismiss = onDismiss,
        footer = {
            DialogFooterRow(
                dismissLabel = context.getString(R.string.cancel),
                confirmLabel = context.getString(R.string.continue_button),
                onDismiss = onDismiss,
                onConfirm = { size?.let { onConfirm(it) } },
                confirmEnabled = isValid
            )
        }
    ) {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            OutlinedTextField(
                value = sizeText,
                onValueChange = { if (it.isEmpty() || it.all { c -> c.isDigit() }) sizeText = it },
                label = { Text(context.getString(R.string.size_gb)) },
                placeholder = { Text(context.getString(R.string.size_range_4_512_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = DsTextFieldDefaults.colors(),
                isError = !isValid && sizeText.isNotEmpty(),
                supportingText = {
                    if (sizeText.isNotEmpty()) {
                        if (isOutOfRange) {
                            Text(context.getString(R.string.enter_size_between_4_512_gb))
                        } else if (isSameSize) {
                            Text(context.getString(R.string.resize_same_as_current, currentSize))
                        }
                    }
                },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                )
            )
    }
    }


@Composable
private fun UninstallConfirmationDialog(
    containerName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var confirmText by remember { mutableStateOf("") }
    val isConfirmed = confirmText == containerName

    DsDialog(
        onDismiss = onDismiss,
        borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.3f),
        footer = {
            DialogFooterRow(
                dismissLabel = context.getString(R.string.cancel),
                confirmLabel = context.getString(R.string.uninstall),
                onDismiss = onDismiss,
                onConfirm = onConfirm,
                confirmEnabled = isConfirmed,
                destructive = true
            )
        }
    ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Default.Warning, contentDescription = null,
                    tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(24.dp)
                )
                Text(
                    text = context.getString(R.string.uninstall_container_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = buildAnnotatedString {
                    val template = context.getString(R.string.uninstall_container_message)
                    val parts = template.split("%1\$s")
                    append(parts.getOrElse(0) { "" })
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(containerName) }
                    append(parts.getOrElse(1) { "" })
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ConfirmPhraseField(
                value = confirmText,
                onValueChange = { confirmText = it },
                isError = confirmText.isNotEmpty() && !isConfirmed,
                instruction = context.getString(R.string.type_container_name_to_confirm),
                phrase = containerName,
                // A container name is case-sensitive; the keyboard must not upper-case it.
                capitalization = KeyboardCapitalization.None
            )
    }
    }

