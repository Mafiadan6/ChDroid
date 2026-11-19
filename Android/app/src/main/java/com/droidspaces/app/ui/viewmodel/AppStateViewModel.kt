package com.droidspaces.app.ui.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.droidspaces.app.R
import com.droidspaces.app.util.AppUpdateChecker
import com.droidspaces.app.util.AppUpdateInfo
import com.droidspaces.app.util.BinaryInstaller
import com.droidspaces.app.util.Constants
import com.droidspaces.app.util.DroidspacesBackendStatus
import com.droidspaces.app.util.DroidspacesChecker
import com.droidspaces.app.util.PreferencesManager
import com.droidspaces.app.util.RootChecker
import com.droidspaces.app.util.RootStatus
import com.droidspaces.app.util.SymlinkInstaller
import com.droidspaces.app.util.SystemInfoManager
import com.droidspaces.app.util.RootSessionManager
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.droidspaces.app.util.HostCapabilities

/**
 * Application-wide state ViewModel that persists across navigation.
 *
 * This ViewModel solves the "settings back button glitch" by:
 * 1. Persisting backend status across navigation (no re-check on back press)
 * 2. Using proper Compose state management (mutableStateOf inside ViewModel)
 * 3. Single source of truth for app-wide state
 *
 * Performance optimizations:
 * - Single check on app cold start
 * - No redundant checks on navigation
 * - Efficient coroutine usage with cancellation support
 * - Cache status in preferences for instant display on restart
 */
class AppStateViewModel(application: Application) : AndroidViewModel(application) {
    private val prefsManager = PreferencesManager.getInstance(application)

    // Backend status - persists across navigation
    // Explicit type annotation required for sealed class
    // Initialize with cached status to prevent UI glitches on boot
    var backendStatus: DroidspacesBackendStatus by mutableStateOf(
        prefsManager.cachedBackendStatus?.let { cached ->
            // Older builds cached Available as ""
            if (cached == "") DroidspacesBackendStatus.Available
            else DroidspacesBackendStatus.entries.firstOrNull { it.name == cached }
        } ?: DroidspacesBackendStatus.Checking
    )
        private set

    // Track if initial check has been completed (persists across navigation)
    var hasCompletedInitialCheck by mutableStateOf(prefsManager.cachedBackendStatus != null)
        private set

    // Track if a refresh is in progress
    var isRefreshing by mutableStateOf(false)
        private set

    // Root access status - initialize with cached status
    var rootStatus: RootStatus by mutableStateOf(
        if (prefsManager.rootAvailable) RootStatus.Granted else RootStatus.Denied
    )
        private set

    // Current refresh job for cancellation
    private var refreshJob: Job? = null

    // ── Installation flow state (drives InstallationScreen) ─────────────────────
    var isInstalling by mutableStateOf(false)
        private set
    var isInstallSuccess by mutableStateOf(false)
        private set
    var installErrorMessage by mutableStateOf<String?>(null)
        private set
    var isStartingBackend by mutableStateOf(false)
        private set

    fun checkBackendStatus(force: Boolean = false) {
        if (hasCompletedInitialCheck && !force) return

        refreshJob?.cancel()

        refreshJob = viewModelScope.launch {
            val previousStatus = backendStatus
            isRefreshing = true

            if (previousStatus == DroidspacesBackendStatus.Checking) {
                backendStatus = DroidspacesBackendStatus.Checking
            }

            try {
                val status = withContext(Dispatchers.IO) {
                    DroidspacesChecker.checkBackendStatus(getApplication())
                }

                val finalStatus = if (status == DroidspacesBackendStatus.Available) {
                    val updateAvailable = withContext(Dispatchers.IO) {
                        DroidspacesChecker.checkUpdateAvailable(getApplication())
                    }
                    if (updateAvailable) DroidspacesBackendStatus.UpdateAvailable else DroidspacesBackendStatus.Available
                } else {
                    status
                }

                if (finalStatus != previousStatus) {
                    backendStatus = finalStatus
                }

                hasCompletedInitialCheck = true

                // Only cache non-Checking statuses to prevent UI glitches
                if (finalStatus != DroidspacesBackendStatus.Checking)
                    prefsManager.cachedBackendStatus = finalStatus.name
            } catch (e: Exception) {
                if (previousStatus != DroidspacesBackendStatus.NotInstalled) {
                    backendStatus = DroidspacesBackendStatus.NotInstalled
                }
                hasCompletedInitialCheck = true
            } finally {
                isRefreshing = false
            }
        }
    }

    /**
     * Reset state for post-installation refresh.
     * This forces a new check on next composition.
     */
    fun resetForPostInstallation() {
        hasCompletedInitialCheck = false
        backendStatus = DroidspacesBackendStatus.Checking
        // Clear version cache to force refresh after backend installation/update
        SystemInfoManager.resetCache()
    }

    /**
     * Run the backend install/update orchestration (moved out of InstallationScreen's
     * LaunchedEffect). Drives the install* state above.
     *
     * Guards against a concurrent run only, not a repeat one. This ViewModel is shared
     * across the nav graph, so its state outlives the installation screen; short-circuiting
     * on a previous success made "Reinstall Backend" a no-op every time after the first,
     * showing the earlier run's completed screen without copying anything. The screen is
     * popped when it is left, so arriving here always means someone asked for an install.
     *
     * Every field is cleared up front rather than left to be overwritten, so a retry after
     * a failure does not render the previous error alongside the new result.
     */
    suspend fun performInstallation() {
        if (isInstalling) return
        isInstalling = true

        isStartingBackend = false
        isInstallSuccess = false
        installErrorMessage = null

        val context = getApplication<Application>()

        val whichBackendMode = withContext(Dispatchers.IO) {
            SystemInfoManager.getBackendMode(context)
        }
        val wasDaemon = whichBackendMode == "DAEMON"

        // Preserve the user's symlink choice across a binary update. No boot
        // module is ever installed or removed on any device, so nothing here
        // depends on module state, sepolicy or a reboot.
        val wasSymlinkEnabled = withContext(Dispatchers.IO) {
            SymlinkInstaller.isSymlinkEnabled()
        }

        // Step 2: Install binaries (atomic mv to canonical path)
        val binaryResult = BinaryInstaller.install(context)
        binaryResult.fold(
            onSuccess = {
                if (wasDaemon) {
                    BinaryInstaller.signalDaemon()
                }

                // No boot module, sepolicy.rule or service.sh: the session
                // bootstrap is the entire install on every rooted phone.
                isStartingBackend = true
                val sessionResult = withContext(Dispatchers.IO) {
                    RootSessionManager.writeMarker().let { marked ->
                        if (!marked) Result.failure(Exception("Could not write session marker"))
                        else RootSessionManager.ensureDaemon()
                    }
                }
                sessionResult.fold(
                    onSuccess = {
                        if (wasSymlinkEnabled) {
                            withContext(Dispatchers.IO) { SymlinkInstaller.enable() }
                        }
                        val daemonFileExists = withContext(Dispatchers.IO) {
                            Shell.cmd("test -f '${Constants.DAEMON_MODE_FILE}'").exec().isSuccess
                        }
                        if (!daemonFileExists) {
                            PreferencesManager.getInstance(context).isDaemonModeEnabled = true
                        }
                        // Ask the fresh backend what this kernel can do, so the
                        // success screen can say so before the user moves on.
                        HostCapabilities.refresh(context)
                        isInstallSuccess = true
                                        isInstalling = false
                        isStartingBackend = false
                        resetForPostInstallation()
                        checkBackendStatus(force = true)
                    },
                    onFailure = { error ->
                        installErrorMessage = error.message
                            ?: context.getString(R.string.session_setup_failed)
                        isInstalling = false
                        isStartingBackend = false
                        resetForPostInstallation()
                        checkBackendStatus(force = true)
                    }
                )
            },
            onFailure = { error ->
                installErrorMessage = error.message ?: context.getString(R.string.binary_installation_failed)
                isInstalling = false
                resetForPostInstallation()
                checkBackendStatus(force = true)
            }
        )
    }

    /**
     * Check if backend is available (working or update available).
     */
    val isBackendAvailable: Boolean
        get() = backendStatus == DroidspacesBackendStatus.Available ||
                backendStatus == DroidspacesBackendStatus.UpdateAvailable

    /**
     * Check root access status.
     */
    fun checkRootStatus() {
        viewModelScope.launch {
            rootStatus = withContext(Dispatchers.IO) {
                RootChecker.checkRootAccess()
            }
            prefsManager.rootAvailable = rootStatus == RootStatus.Granted
        }
    }

    /**
     * Check if root is available.
     */
    val isRootAvailable: Boolean
        get() = rootStatus == RootStatus.Granted

    /** Exposed so Settings can start the backend in the current root session. */
    fun ensureBackendDaemon(onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { RootSessionManager.ensureDaemon() }
            onResult(result)
        }
    }

    // Latest GitHub release, or null when the check is off, offline, or failed.
    var appUpdate: AppUpdateInfo? by mutableStateOf(null)
        private set

    fun checkAppUpdate() {
        if (!prefsManager.checkAppUpdates) {
            appUpdate = null
            return
        }
        viewModelScope.launch {
            appUpdate = withContext(Dispatchers.IO) {
                AppUpdateChecker.fetchLatest(getApplication())
            }
        }
    }

    init {
        // Check root status on initialization
        checkRootStatus()
        checkAppUpdate()
    }
}

