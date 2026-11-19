package com.droidspaces.app

import android.content.Intent
import android.widget.Toast
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.droidspaces.app.service.RootfsDownloadService
import com.droidspaces.app.ui.component.rememberPermissionRequest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import com.droidspaces.app.ui.util.rememberClearFocus
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import com.droidspaces.app.ui.navigation.DroidspacesNavigation
import com.droidspaces.app.ui.theme.DroidspacesTheme
import com.droidspaces.app.ui.theme.rememberThemeState
import com.droidspaces.app.util.CrashLogger

class MainActivity : AppCompatActivity() {

    companion object {
        const val ACTION_SHORTCUT_CONTAINERS = "com.droidspaces.app.action.SHORTCUT_CONTAINERS"
        const val ACTION_SHORTCUT_PANEL = "com.droidspaces.app.action.SHORTCUT_PANEL"
        const val ACTION_SHORTCUT_SETTINGS = "com.droidspaces.app.action.SHORTCUT_SETTINGS"
        const val ACTION_INSTALL_ROOTFS = "com.droidspaces.app.action.INSTALL_ROOTFS"
        const val EXTRA_ROOTFS_FILE = "rootfs_file"
    }

    private var isLoading by mutableStateOf(false)

    // Deep-link target from a launcher long-press shortcut ("containers" / "panel"
    // / "settings"), consumed once by DroidspacesNavigation then reset to null.
    private var pendingShortcut by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Install splash screen before super.onCreate for faster display
        val splashScreen = installSplashScreen()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)
        CrashLogger.install(this)

        // Set condition immediately - UI will hide splash when ready
        // Start with false to show UI immediately (content is ready)
        splashScreen.setKeepOnScreenCondition { isLoading }

        // Skip shortcut handling when the activity is relaunched from Recents,
        // otherwise the stale shortcut intent would re-fire the deep link.
        if ((intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) {
            handleIntent(intent)
        }

        // Render UI immediately - no blocking operations
        setContent {
            ThemeWrapper {
                // Samsung's SecFgsManagerController suppresses FGS notifications from apps without
                // POST_NOTIFICATIONS, strips ONGOING, and then TerminalSessionService gets killed and
                // its binder cycles null/non-null into a crash loop. Asking up front breaks that cycle.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val requestNotifications = rememberPermissionRequest(
                        permission = android.Manifest.permission.POST_NOTIFICATIONS,
                        title = getString(R.string.notification_permission_title),
                        rationale = getString(R.string.notification_permission_rationale),
                        settingsIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    )
                    LaunchedEffect(Unit) { requestNotifications {} }
                }
                // A tap that no screen content consumes drops the focused text field
                // and the keyboard with it, on every screen, so no screen has to wrap
                // itself. Dialogs and sheets are their own windows and do the same
                // themselves. A gesture detector rather than clickable keeps TalkBack
                // from announcing the whole app as a button.
                val clearFocus = rememberClearFocus()
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) { detectTapGestures { clearFocus() } },
                    color = MaterialTheme.colorScheme.background
                ) {
                    DroidspacesNavigation(
                        onContentReady = { isLoading = false },
                        pendingShortcut = pendingShortcut,
                        onShortcutHandled = { pendingShortcut = null }
                    )
                }
                CrashReportDialog()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        pendingShortcut = when (intent.action) {
            ACTION_SHORTCUT_CONTAINERS -> "containers"
            ACTION_SHORTCUT_PANEL -> "panel"
            ACTION_SHORTCUT_SETTINGS -> "settings"
            // The activity is exported, so take a file name, never a URI: the worst a stranger can
            // do is open the installer on a tarball we downloaded ourselves.
            ACTION_INSTALL_ROOTFS -> intent.getStringExtra(EXTRA_ROOTFS_FILE)
                ?.takeIf { '/' !in it }
                ?.let { RootfsDownloadService.findDownloaded(this, it) }
                ?.let { "install:$it" }
                ?: "containers"
            else -> pendingShortcut
        }
    }
}

/** Surfaces the last on-device crash log so it can be read without adb. */
@Composable
private fun CrashReportDialog() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var report by remember { mutableStateOf(CrashLogger.read(context)) }
    var visible by remember { mutableStateOf(report != null) }
    if (!visible || report == null) return
    AlertDialog(
        onDismissRequest = { visible = false },
        title = { Text("ChDroid crash report") },
        text = {
            Text(
                text = report!!,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.verticalScroll(rememberScrollState())
            )
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(report!!))
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
            }) { Text("Copy") }
        },
        dismissButton = {
            TextButton(onClick = {
                CrashLogger.clear(context)
                report = null
                visible = false
            }) { Text("Clear") }
        }
    )
}

@Composable
private fun ThemeWrapper(content: @Composable () -> Unit) {
    // Use reactive theme state that updates instantly when preferences change
    val themeState = rememberThemeState()

    DroidspacesTheme(
        darkTheme = themeState.darkTheme,
        dynamicColor = themeState.useDynamicColor,
        amoledMode = themeState.amoledMode,
        themePalette = themeState.themePalette
    ) {
        content()
    }
}
