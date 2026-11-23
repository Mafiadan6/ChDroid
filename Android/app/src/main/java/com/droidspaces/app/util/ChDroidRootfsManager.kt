package com.droidspaces.app.util

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.droidspaces.app.R
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Drives the Ubuntu-Chroot v4.4 CLI release directly.
 *
 * The release ZIP is a Magisk module, but its useful parts are plain files:
 * chroot.sh, a static busybox, post_exec.sh and rootfs.tar.gz. We install those
 * to /data/local/ubuntu-chroot with no module flash and no boot script. The
 * chroot's namespace, mounts, DNS and login shell all come from chroot.sh -
 * Droidspaces is only the UI and the terminal in this mode.
 */
object ChDroidRootfsManager {

    /**
     * Start/stop console, same shape upstream Droidspaces shows for its containers:
     * a level-tagged line list persisted through PreferencesManager so the last
     * action is still readable after an app restart.
     */
    private const val LOG_KEY = "chdroid-rootfs"
    // Keep in sync with PATCH_VERSION in chdroid_identity.sh. A launch only
    // re-patches the installed script when this revision changes.
    private const val PATCH_VERSION = 7
    private val logBuffer = mutableStateListOf<Pair<Int, String>>()
    private var appContext: Context? = null
    private val startupPatchRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    // The launch patch owns this job. start() joins it before dispatch: if the
    // script is rewritten while `sh chroot.sh start` is already reading the old
    // inode, the running copy still spawns the namespace holder with inherited
    // stdout and libsu waits ~30s for EOF. Joining makes the first start fast.
    @Volatile
    private var startupPatchJob: kotlinx.coroutines.Job? = null
    // Teardown after a terminal tab closes must outlive the screen's coroutine
    // scope, or cancellation lands in the middle of the root command.
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var shellReleaseJob: kotlinx.coroutines.Job? = null

    fun install(context: Context) {
        appContext = context.applicationContext
        val cached = PreferencesManager.getInstance(appContext!!).loadContainerLogs(LOG_KEY)
        if (logBuffer.isEmpty()) logBuffer.addAll(cached)

        // Rootfs ZIPs published before the prompt/fast-stop fixes still carry the
        // old chroot.sh and 256-colour PS1. Re-apply the identity patch on every
        // app start so an APK update repairs an existing install in place - no
        // repack, no re-download, no reboot. Cheap: a few shell builtins, and it
        // bails immediately when no rootfs is installed.
        startupPatchJob = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            if (!startupPatchRunning.compareAndSet(false, true)) {
                CrashLogger.log("chroot startup patch: already running, skipped")
                return@launch
            }
            try {
                runCatching {
                    if (!isInstalled()) return@runCatching
                    val marker = Shell.cmd(
                        "cat '${ChDroidPaths.base}/chdroid-patch-version' 2>/dev/null"
                    ).exec().out.firstOrNull()?.trim()
                    if (marker == PATCH_VERSION.toString()) {
                        CrashLogger.log("chroot startup patch: v$marker current, skipped")
                        return@runCatching
                    }
                    CrashLogger.log("chroot startup patch: begin (was $marker)")
                    val host = hostname()
                    val name = containerName()
                    val q = ContainerCommandBuilder::quote
                    val identity = ContainerInstaller.deployAsset(appContext!!, "chdroid_identity.sh")
                    try {
                        val result = Shell.cmd(
                            "${q(identity.absolutePath)} " +
                                "${q(ChDroidPaths.base)} " +
                                "${q(host)} " +
                                "${q(name)} 2>&1"
                        ).exec()
                        CrashLogger.log(
                            "chroot startup patch: code=${result.code} " +
                                "out=${result.out.joinToString(" | ").take(300)}"
                        )
                    } finally {
                        identity.delete()
                    }
                }.onFailure { CrashLogger.log("chroot startup patch: error=${it.message}") }
            } finally {
                startupPatchRunning.set(false)
                CrashLogger.log("chroot startup patch: finished")
            }
        }
    }

    /** Snapshot-backed so the console streams lines while chroot.sh is still running. */
    fun logs(): SnapshotStateList<Pair<Int, String>> = logBuffer

    fun clearLogs() {
        logBuffer.clear()
        appContext?.let { PreferencesManager.getInstance(it).clearContainerLogs(LOG_KEY) }
    }

    private fun recordLogLine(level: Int, message: String) {
        logBuffer.add(level to message)
        while (logBuffer.size > 200) logBuffer.removeAt(0)
    }

    private fun saveLogs() {
        appContext?.let { PreferencesManager.getInstance(it).saveContainerLogs(LOG_KEY, logBuffer.toList()) }
    }

    private fun operationLogger(): ViewModelLogger = ViewModelLogger { level, message ->
        recordLogLine(level, message)
    }

    private fun operationDoneMessage(): String =
        appContext?.getString(R.string.operation_completed_success)
            ?: "Operation completed successfully!"

    private fun operationFailedMessage(): String =
        appContext?.getString(R.string.operation_failed) ?: "Operation failed!"

    data class RootfsSnapshot(
        val installed: Boolean,
        val base: String,
        val distro: String,
        val name: String,
        val hostname: String,
        val running: Boolean,
    )

    /**
     * One shell round-trip for every value the UI polls. The Home and Containers
     * screens used to fire four separate Shell.cmd calls each on a two-second
     * timer; concurrent execs on the shared root shell returned spurious empty
     * results, which made the installed card vanish until the next refresh.
     */
    suspend fun snapshot(): RootfsSnapshot = withContext(Dispatchers.IO) {
        val pointer = Constants.CHDROID_ROOTFS_POINTER
        val legacy = Constants.CHDROID_ROOTFS_LEGACY_BASE
        val command = listOf(
            "base=\$(cat '$pointer' 2>/dev/null)",
            "case \"\$base\" in /data/local/*-chroot) ;; *) base='$legacy' ;; esac",
            "if [ -x \"\$base/chroot.sh\" ] && [ -x \"\$base/rootfs/bin/sh\" ]; then",
            "  echo 'installed=1'",
            "  echo \"base=\$base\"",
            "  echo \"distro=\$(head -n 1 \"\$base/distro-id\" 2>/dev/null)\"",
            // The wizard's container.config is the source of truth for the name and
            // hostname; the plain files are downstream copies that older installs
            // left at the release's baked values.
            "  n=''; h=''",
            "  while IFS='=' read -r k v; do",
            "    case \"\$k\" in name) n=\"\$v\" ;; hostname) h=\"\$v\" ;; esac;",
            "  done < \"\$base/container.config\" 2>/dev/null",
            "  [ -n \"\$n\" ] || n=\$(head -n 1 \"\$base/container-name\" 2>/dev/null)",
            "  echo \"name=\$n\"",
            "  [ -n \"\$h\" ] || h=\$(head -n 1 \"\$base/hostname\" 2>/dev/null)",
            "  [ -n \"\$h\" ] || h=\$(head -n 1 \"\$base/rootfs/etc/hostname\" 2>/dev/null)",
            "  [ -n \"\$h\" ] || h=\$(head -n 1 \"\$base/distro-id\" 2>/dev/null)",
            "  echo \"hostname=\$h\"",
            "  p=\$(cat \"\$base/holder.pid\" 2>/dev/null)",
            "  if [ -n \"\$p\" ] && kill -0 \"\$p\" 2>/dev/null; then echo 'running=1'; else echo 'running=0'; fi",
            "else",
            "  echo 'installed=0'",
            "  echo \"base=\$base\"",
            "  echo 'distro='",
            "  echo 'name='",
            "  echo 'hostname='",
            "  echo 'running=0'",
            "fi",
        ).joinToString("\n")
        // A command sharing the root shell with a long stop can come back empty.
        // Retry before believing "not installed", or the card blanks out until
        // the next manual refresh.
        var values = emptyMap<String, String>()
        repeat(3) { attempt ->
            val parsed = Shell.cmd(command).exec().out
                .map { it.trim() }
                .filter { it.contains('=') }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
            if (parsed.containsKey("installed") || attempt == 2) values = parsed
        }
        val base = values["base"].orEmpty().ifBlank { legacy }
        ChDroidPaths.use(base)
        RootfsSnapshot(
            installed = values["installed"] == "1",
            base = base,
            distro = values["distro"].orEmpty(),
            name = values["name"].orEmpty(),
            hostname = values["hostname"].orEmpty(),
            running = values["running"] == "1",
        )
    }

    /** True when the CLI manager and a usable rootfs are on the device. */
    suspend fun isInstalled(): Boolean = withContext(Dispatchers.IO) {
        // snapshot() retries an empty answer. A plain test -x here could report
        // "not installed" right after a long stop and send the user to reinstall.
        snapshot().installed
    }

    suspend fun status(): String = withContext(Dispatchers.IO) {
        if (!isInstalled()) return@withContext "not installed"
        Shell.cmd("sh '${ChDroidPaths.script}' status 2>&1").exec()
            .out.joinToString(" ").trim().ifEmpty { "unknown" }
    }

    /** Distro id written by the installer into the rootfs base. */
    suspend fun distro(): String = withContext(Dispatchers.IO) {
        Shell.cmd("cat '${ChDroidPaths.base}/distro-id' 2>/dev/null").exec()
            .out.firstOrNull()?.trim().orEmpty()
    }

    /** Container name chosen in the setup screen and written at install time. */
    suspend fun containerName(): String = withContext(Dispatchers.IO) {
        persistedConfig()["name"]?.takeIf { it.isNotBlank() }
            ?: Shell.cmd("cat '${ChDroidPaths.base}/container-name' 2>/dev/null").exec()
                .out.firstOrNull()?.trim().orEmpty()
    }

    /**
     * Hostname the user typed in the setup wizard. container.config is the source
     * of truth; the plain base/hostname file is the fallback for installs made
     * before that config existed. /etc/hostname inside the rootfs is only a
     * downstream copy, and the release's baked value must never win over either.
     */
    suspend fun hostname(): String = withContext(Dispatchers.IO) {
        persistedConfig()["hostname"]?.takeIf { it.isNotBlank() }?.let { return@withContext it }
        val q = ContainerCommandBuilder::quote
        val saved = Shell.cmd("head -n 1 ${q("${ChDroidPaths.base}/hostname")} 2>/dev/null").exec()
            .out.firstOrNull()?.trim().orEmpty()
        if (saved.isNotBlank()) return@withContext saved
        val baked = Shell.cmd("head -n 1 '${ChDroidPaths.rootfs}/etc/hostname' 2>/dev/null").exec()
            .out.firstOrNull()?.trim().orEmpty()
        baked.ifEmpty { distro().ifEmpty { "localhost" } }
    }

    /**
     * Shell fragment that applies the optional full-Android-root bind, if the
     * setup screen enabled it. Safe on installs that never deployed the script —
     * a missing or disabled script is a no-op and never breaks the start chain.
     */
    private fun androidRootMountCmd(): String {
        val script = ContainerCommandBuilder.quote(Constants.CHDROID_MOUNT_SCRIPT)
        return "[ -f $script ] && sh $script 2>/dev/null || true"
    }

    // SELinux stays enforcing. chdroid_identity.sh strips every setenforce 0 from
    // the installed chroot.sh on app start and forces the device back to enforcing,
    // so no start/stop command here ever has to touch the device's policy mode.

    /** Start the namespace and mounts without consuming a terminal. */
    suspend fun start(): Result<Unit> = withContext(Dispatchers.IO) {
        val logger = operationLogger()
        withContext(Dispatchers.Main.immediate) { clearLogs() }
        runCatching {
            val startedAt = System.currentTimeMillis()
            CrashLogger.log("chroot start: begin")
            // Never start against a script that is being rewritten: the running
            // shell keeps the replaced inode, whose holder still holds stdout.
            startupPatchJob?.join()
            // A Start press wins over a pending post-exit teardown; otherwise the
            // delayed release would kill the holder this start just created.
            shellReleaseJob?.cancel()
            shellReleaseJob = null
            if (!isInstalled()) {
                logger.e("")
                logger.e("rootfs is not installed")
                throw Exception("rootfs is not installed")
            }
            // CLI mode: the namespace and mounts are all chroot.sh needs. The
            // release's post_exec.sh is a guest-side script that can spend many
            // seconds inside nsenter+bash on every start, so skip it here.
            val command =
                "sh '${ChDroidPaths.script}' start --no-shell --skip-post-exec; " +
                    androidRootMountCmd()
            val success = ContainerOperationExecutor.executeCommand(
                command = command,
                operation = "start",
                logger = logger,
                operationCompletedMessage = operationDoneMessage()
            )
            CrashLogger.log("chroot start: command returned success=$success in ${System.currentTimeMillis() - startedAt}ms")
            if (!success) {
                logger.e("")
                logger.e(operationFailedMessage())
                throw Exception("chroot start failed")
            }
        }.also {
            withContext(Dispatchers.Main.immediate) { saveLogs() }
        }
    }

    /**
     * Host-side teardown, shared by the Stop button and the last terminal exit.
     * Everything here touches only /proc and the rootfs base; no guest process is
     * spawned, so libsu's exec() returns as soon as the commands finish.
     */
    private fun teardownCommand(): String {
        val rootfs = ChDroidPaths.rootfs
        val base = ChDroidPaths.base
        val q = ContainerCommandBuilder::quote
        val r = q(rootfs)
        val b = q(base)
        return listOf(
            "T0=\$(date +%s)",
            "echo '[info] stopping rootfs processes'",
            // One ls over all proc roots beats a readlink fork per PID and never
            // needs lsof or a guest shell.
            "ls -l /proc/[0-9]*/root 2>/dev/null | " +
                "awk -v t=$r '\$11==t {split(\$9,a,\"/\"); print a[3]}' | " +
                "while read -r pid; do kill -9 \"\$pid\" 2>/dev/null; done; true",
            "echo \"[info] processes killed +\$(( \$(date +%s) - T0 ))s\"",
            "echo '[info] unmounting rootfs'",
            "while read -r dev mp rest; do " +
                "case \"\$mp\" in $r|$r/*) umount -l \"\$mp\" 2>/dev/null ;; esac; " +
                "done < /proc/mounts; true",
            "echo \"[info] unmounted +\$(( \$(date +%s) - T0 ))s\"",
            "p=\$(cat $b/holder.pid 2>/dev/null); " +
                "[ -n \"\$p\" ] && kill -9 \"\$p\" 2>/dev/null; true",
            "rm -f $b/holder.pid $b/holder.pid.flags 2>/dev/null; true",
            "if [ -x /system/bin/setenforce ]; then /system/bin/setenforce 1 2>/dev/null; " +
                "else setenforce 1 2>/dev/null; fi; true",
            "echo \"[info] rootfs stopped +\$(( \$(date +%s) - T0 ))s\"",
        ).joinToString("\n")
    }

    /**
     * Typing `exit` in the last rootfs shell ends the guest session but leaves
     * chroot.sh's namespace holder alive, which made the card still read
     * "running" and Start answer "already running". Tear the holder down when
     * the last terminal tab closes so the 2s status poll sees the truth.
     */
    suspend fun releaseAfterShellExit() = withContext(Dispatchers.IO) {
        runCatching {
            Shell.cmd(teardownCommand()).exec()
            CrashLogger.log("chroot shell exit: holder released")
        }.onFailure { CrashLogger.log("chroot shell exit: release error=${it.message}") }
    }

    /**
     * Called when the last terminal tab goes away. The release runs on a global
     * scope because the composable that called it is being disposed, and a short
     * delay lets terminalSession's own terminate/EOF cleanup finish first. The
     * guard also absorbs the double-close when X and onSessionFinished both fire.
     */
    fun releaseAfterShellExitAsync() {
        if (shellReleaseJob?.isActive == true) return
        shellReleaseJob = backgroundScope.launch {
            delay(500)
            releaseAfterShellExit()
        }
    }

    /** Path of the rootfs's persisted setup-wizard configuration. */
    private val configPath: String get() = "${ChDroidPaths.base}/container.config"

    /** Key/values the setup wizard wrote at install time; the source of truth for name and hostname. */
    private suspend fun persistedConfig(): Map<String, String> = withContext(Dispatchers.IO) {
        val q = ContainerCommandBuilder::quote
        val raw = Shell.cmd("cat ${q(configPath)} 2>/dev/null").exec().out.joinToString("\n")
        ContainerManager.parseConfigValues(raw)
    }

    /**
     * Rebuild the exact field set the install wizard offered, from the config
     * file written at install time. Installs made before that file existed fall
     * back to defaults plus the live name/hostname, so Edit still opens full.
     */
    suspend fun loadEditableConfig(): ContainerInfo = withContext(Dispatchers.IO) {
        val snap = snapshot()
        val q = ContainerCommandBuilder::quote
        val raw = Shell.cmd("cat ${q(configPath)} 2>/dev/null").exec().out.joinToString("\n")
        val parsed = if (raw.isNotBlank()) ContainerManager.parseConfig(raw, snap.name) else null
        val flagOn = Shell.cmd("[ -f ${q(ChDroidPaths.mountFlag)} ] && echo 1").exec()
            .out.firstOrNull()?.trim() == "1"
        val base = parsed ?: ContainerInfo(
            name = snap.name,
            hostname = snap.hostname,
            rootfsPath = ChDroidPaths.rootfs,
        )
        base.copy(
            name = snap.name.ifBlank { base.name },
            hostname = snap.hostname.ifBlank { base.hostname },
            rootfsPath = ChDroidPaths.rootfs,
            status = ContainerStatus.STOPPED,
            pid = null,
            selinuxPermissive = false,
            mountAndroidRoot = flagOn,
        )
    }

    private fun persistConfig(context: Context, info: ContainerInfo) {
        val tmp = File(context.cacheDir, "chdroid-rootfs.config")
        tmp.writeText(
            info.copy(
                rootfsPath = ChDroidPaths.rootfs,
                selinuxPermissive = false,
            ).toConfigContent()
        )
        val written = ContainerManager.installFileDurably(tmp.absolutePath, configPath, "644")
        tmp.delete()
        if (!written.isSuccess) {
            throw Exception("could not save rootfs config: ${written.err.joinToString(" ").ifBlank { "write failed" }}")
        }
        // Keep plain copies too. The identity script and the stats/snapshot readers
        // use them when a config written by an older build is absent or incomplete.
        val q = ContainerCommandBuilder::quote
        val base = q(ChDroidPaths.base)
        Shell.cmd(
            "mkdir -p $base && " +
                "printf '%s\\n' ${q(info.hostname)} > $base/hostname && " +
                "printf '%s\\n' ${q(info.name)} > $base/container-name"
        ).exec()
    }

    /** Save every wizard field, then re-apply identity and the Android-root flag. */
    suspend fun saveEditableConfig(
        context: Context,
        info: ContainerInfo,
        logger: ViewModelLogger,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            persistConfig(context, info)
            applyIdentity(context, info.hostname, info.name, logger).getOrThrow()
            val q = ContainerCommandBuilder::quote
            val flag = if (info.mountAndroidRoot) {
                "mkdir -p ${q(ChDroidPaths.base)} && printf '1\\n' > ${q(ChDroidPaths.mountFlag)}"
            } else {
                "rm -f ${q(ChDroidPaths.mountFlag)}"
            }
            Shell.cmd(flag).exec()
            CrashLogger.log("chroot config saved: name='${info.name}' hostname='${info.hostname}'")
        }
    }

    suspend fun stop(): Result<Unit> = withContext(Dispatchers.IO) {
        val logger = operationLogger()
        withContext(Dispatchers.Main.immediate) { clearLogs() }
        runCatching {
            val startedAt = System.currentTimeMillis()
            CrashLogger.log("chroot stop: begin")
            // Do not call chroot.sh stop here. Even with its slow steps patched
            // out, that script spawns guest children that can keep libsu's stdout
            // pipe open for tens of seconds after the teardown itself is done.
            // Everything stop actually needs is host-side and instant: one proc
            // scan, one lazy-unmount pass, the holder PID, the state files.
            val command = teardownCommand()
            CrashLogger.log("chroot stop: dispatching command")
            val success = ContainerOperationExecutor.executeCommand(
                command = command,
                operation = "stop",
                logger = logger,
                operationCompletedMessage = operationDoneMessage()
            )
            CrashLogger.log("chroot stop: command returned success=$success in ${System.currentTimeMillis() - startedAt}ms")
            if (!success) {
                logger.e("")
                logger.e(operationFailedMessage())
                throw Exception("chroot still running after stop")
            }
            CrashLogger.log("chroot stop: verified stopped")
        }.also {
            withContext(Dispatchers.Main.immediate) { saveLogs() }
        }
    }

    /**
     * Re-apply the setup identity to an installed rootfs. Used by the Containers
     * edit action: same script the installer and app start use, so container name,
     * hostname, prompt and the installed chroot.sh all stay in sync.
     */
    suspend fun applyIdentity(
        context: Context,
        hostname: String,
        containerName: String,
        logger: ViewModelLogger
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val q = ContainerCommandBuilder::quote
            val identity = ContainerInstaller.deployAsset(context, "chdroid_identity.sh")
            try {
                val result = Shell.cmd(
                    "${q(identity.absolutePath)} " +
                        "${q(ChDroidPaths.base)} " +
                        "${q(hostname)} " +
                        "${q(containerName)} 2>&1"
                ).exec()
                result.out.map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
                    if (line.startsWith("[CHDROID-ID]")) logger.i(line) else logger.d(line)
                }
                if (!result.isSuccess) throw Exception(result.errorText())
            } finally {
                identity.delete()
            }
        }
    }

    /**
     * Remove the CLI rootfs completely. Stop first, then kill anything still
     * rooted in it and drop its mounts, so a half-stopped container cannot keep
     * the active rootfs busy and defeat the delete.
     */
    suspend fun uninstall(context: Context, logger: ViewModelLogger): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val base = ChDroidPaths.base
            val rootfs = ChDroidPaths.rootfs
            val script = ChDroidPaths.script
            logger.i("stopping rootfs before uninstall")
            val command =
                "if [ -f '$script' ]; then " +
                    "if command -v timeout >/dev/null 2>&1; then timeout 15 sh '$script' stop >/dev/null 2>&1; " +
                    "else sh '$script' stop >/dev/null 2>&1; fi; fi; " +
                    "for procdir in /proc/[0-9]*; do " +
                    "r=\$(readlink \"\$procdir/root\" 2>/dev/null) || continue; " +
                    "[ \"\$r\" = '$rootfs' ] && kill -9 \"\${procdir##*/}\" 2>/dev/null; " +
                    "done; " +
                    "p=\$(cat '$base/holder.pid' 2>/dev/null); [ -n \"\$p\" ] && kill -9 \"\$p\" 2>/dev/null; " +
                    "while read -r dev mp rest; do " +
                    "case \"\$mp\" in '$rootfs'|'$rootfs'/*) umount -l \"\$mp\" 2>/dev/null ;; esac; " +
                    "done < /proc/mounts; " +
                    "rm -rf '$base' || exit 1; " +
                    "rm -f '${Constants.CHDROID_ROOTFS_POINTER}' 2>/dev/null"
            val success = ContainerOperationExecutor.executeCommand(
                command = command,
                operation = "uninstall",
                logger = logger,
                operationCompletedMessage = operationDoneMessage()
            )
            if (!success) throw Exception("could not remove rootfs")
            withContext(Dispatchers.Main.immediate) { clearLogs() }
        }
    }

    /**
     * Pull bin/busybox out of the release ZIP before the shell installer runs.
     * The app's own busybox has no `unzip` applet, so it cannot be the bootstrap —
     * the release ships its own unzip-capable busybox and that is what the
     * installer needs for every later extraction. Read through the content
     * resolver so Downloads/SAF URIs work without a world-readable file path.
     */
    private fun bootstrapBusybox(context: Context, zipUri: Uri): String? {
        val input = context.contentResolver.openInputStream(zipUri) ?: return null
        val temp = File(context.cacheDir, "chdroid-release-busybox")
        return try {
            ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == "bin/busybox") {
                        FileOutputStream(temp).use { zis.copyTo(it) }
                        break
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            if (temp.length() == 0L) return null
            // Native binaries cannot be exec'd from an app's cache on modern
            // Android (W^X / SELinux). Install into the root-owned bin directory.
            val target = "${Constants.INSTALL_PATH}/busybox-release"
            val result = ContainerManager.installFileDurably(temp.absolutePath, target, "755")
            temp.delete()
            if (result.isSuccess) target else null
        } catch (e: Exception) {
            null
        }
    }

    /** Install the CLI release ZIP into /data/local/ubuntu-chroot. */
    suspend fun install(
        context: Context,
        zipUri: Uri,
        containerName: String,
        hostname: String,
        mountAndroidRoot: Boolean,
        logger: ViewModelLogger,
        config: ContainerInfo? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val zip = FilePickerUtils.realPath(context, zipUri) ?: run {
                logger.i("Copying rootfs ZIP to temporary location...")
                val copy = File("${context.cacheDir}/chdroid-rootfs.zip")
                context.contentResolver.openInputStream(zipUri)?.use { input ->
                    FileOutputStream(copy).use { input.copyTo(it) }
                } ?: throw Exception("Failed to open rootfs ZIP")
                copy.absolutePath
            }
            logger.i("rootfs ZIP: $zip")

            val bootstrap = bootstrapBusybox(context, zipUri)
                ?: throw Exception("release ZIP is missing bin/busybox")
            logger.i("bootstrap busybox: $bootstrap")

            val installer = ContainerInstaller.deployAsset(context, "install_ubuntu_chroot_cli.sh")
            try {
                val cacheDir = File(context.cacheDir, "ubuntu-chroot-install").also { it.mkdirs() }
                val result = Shell.cmd(
                    "BUSYBOX_PATH=${ContainerCommandBuilder.quote(bootstrap)} " +
                        "${ContainerCommandBuilder.quote(installer.absolutePath)} " +
                        "${ContainerCommandBuilder.quote(zip)} " +
                        "${ContainerCommandBuilder.quote(bootstrap)} " +
                        "${ContainerCommandBuilder.quote(cacheDir.absolutePath)} " +
                        "${ContainerCommandBuilder.quote(containerName)} 2>&1"
                ).exec()
                result.out.map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
                    if (line.startsWith("[UC-INSTALL]")) logger.i(line) else logger.d(line)
                }
                if (!result.isSuccess) {
                    throw Exception(result.err.joinToString("\n").ifEmpty { "CLI install failed" })
                }
                // The installer picks a distro-named base from the ZIP manifest and
                // reports it back so the rest of this session uses the same path.
                result.out.firstOrNull { it.contains("[UC-INSTALL] base=") }
                    ?.substringAfter("base=")?.trim()?.takeIf { it.isNotBlank() }
                    ?.let { ChDroidPaths.use(it) }
                check(isInstalled()) { "installer finished but rootfs is not usable" }

                // Apply the setup screen's identity to the installed rootfs. The
                // release ZIP's chroot.sh hardcodes C_HOSTNAME="ubuntu" and rewrites
                // /proc/sys/kernel/hostname on every start, so the old inline
                // /etc/hostname write was silently overridden. This script patches
                // that constant and derives a clean distro id from /etc/os-release.
                val q = ContainerCommandBuilder::quote
                val identity = ContainerInstaller.deployAsset(context, "chdroid_identity.sh")
                try {
                    val apply = Shell.cmd(
                        "${q(identity.absolutePath)} " +
                            "${q(ChDroidPaths.base)} " +
                            "${q(hostname)} " +
                            "${q(containerName)} 2>&1"
                    ).exec()
                    apply.out.map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
                        if (line.startsWith("[CHDROID-ID]")) logger.i(line) else logger.d(line)
                    }
                    if (!apply.isSuccess) {
                        logger.e("could not apply install identity: ${apply.errorText()}")
                    }
                } finally {
                    identity.delete()
                }

                // Optional full-Android-root bind mount. The flag is what the
                // mount script checks at every start; deploying the script here
                // keeps it alongside the other CLI tools, outside the app APK.
                val flagSetup = if (mountAndroidRoot) {
                    "mkdir -p ${q(ChDroidPaths.base)} && " +
                        "printf '1\\n' > ${q(ChDroidPaths.mountFlag)}"
                } else {
                    "rm -f ${q(ChDroidPaths.mountFlag)}"
                }
                Shell.cmd("$flagSetup 2>&1").exec()

                val mountScript = ContainerInstaller.deployAsset(context, "android_root_mount.sh")
                val scriptSetup = ContainerManager.installFileDurably(
                    mountScript.absolutePath, Constants.CHDROID_MOUNT_SCRIPT, "755"
                )
                mountScript.delete()
                if (!scriptSetup.isSuccess) {
                    logger.e("could not deploy android-root mount script: ${scriptSetup.err.joinToString(" ")}")
                } else {
                    logger.i("android-root bind mount ${if (mountAndroidRoot) "enabled" else "disabled"}")
                }

                // Persist the whole setup-wizard configuration alongside the rootfs
                // so the edit screen can reopen every field the install wizard offered.
                if (config != null) persistConfig(context, config)

                logger.i("rootfs ready at ${ChDroidPaths.script}")
            } finally {
                installer.delete()
            }
        }
    }

    /**
     * The terminal entry command. Bring the namespace up in no-shell mode first
     * so the holder PID exists, apply the Android-root bind inside that namespace,
     * then enter the login shell. Re-running `start root` sees the chroot already
     * running and only enters, so nothing is mounted twice.
     */
    fun terminalCommand(): String {
        // TerminalSession starts /system/bin/sh as the *app* uid. chroot.sh bails
        // out with "This script must be run as root." unless euid is 0, so the
        // whole chain has to run under su - not just the trailing fallback shell.
        // Single-quoting the su payload means the outer sh passes every `$`
        // through untouched to su's shell, so the loop variables survive.
        //
        // -s on the setup pass keeps the "already running" / "no-shell mode"
        // noise off the terminal. chroot.sh execs over this shell when it enters,
        // so typing `exit` ends the session and closes the tab. The root-shell
        // fallback sits on the SETUP pass only: a failed `exit` leaves a non-zero
        // $? that a trailing `|| exec su` would mistake for a failed entry and
        // drop He into /system/bin/sh instead of closing the tab.
        val script = ChDroidPaths.script
        val mount = Constants.CHDROID_MOUNT_SCRIPT
        val body =
            "sh $script -s start --no-shell --skip-post-exec >/dev/null 2>&1 || exec su; " +
                "${refreshDnsCmd()}; " +
                "[ -f $mount ] && sh $mount 2>/dev/null; " +
                "sh $script start root"
        return "su -c '$body'"
    }

    /**
     * Refresh /etc/resolv.conf from the phone's current network before entering.
     * chroot.sh only seeds DNS when the namespace is first created, so switching
     * Wi-Fi/carrier after the first start leaves the chroot with a dead resolver
     * (pacman: "Resolving timed out"). The refresh has to run through
     * `chroot.sh run` — /etc/resolv.conf is a symlink into the namespace's /run
     * tmpfs, so a host-side write hits the underlying directory instead.
     */
    private fun refreshDnsCmd(): String {
        val script = ChDroidPaths.script
        return "dnscmd=; first=1; " +
            "for i in 1 2 3 4; do d=\$(getprop net.dns\$i); " +
            "[ x\$d != x ] && { if [ \$first -eq 1 ]; then " +
            "dnscmd=\"echo nameserver \$d >/etc/resolv.conf;\"; first=0; " +
            "else dnscmd=\"\$dnscmd echo nameserver \$d >>/etc/resolv.conf;\"; fi; }; done; " +
            "[ \$first -eq 1 ] && dnscmd=\"echo nameserver 8.8.8.8 >/etc/resolv.conf;\"; " +
            "dnscmd=\"\$dnscmd echo nameserver 8.8.8.8 >>/etc/resolv.conf; " +
            "echo nameserver 1.1.1.1 >>/etc/resolv.conf;\"; " +
            "sh $script -s run \"\$dnscmd\" >/dev/null 2>&1 || true"
    }

    /**
     * Live stats for the running rootfs, shaped like the backend's OSInfo so the
     * Panel's RunningContainerCard shows the same uptime / IP / CPU / RAM rows a
     * Droidspaces container does. One shell pass reads everything; `~` stands in
     * for `$` so the script reads cleanly without Kotlin escaping noise.
     */
    suspend fun stats(): ContainerOSInfoManager.OSInfo? = withContext(Dispatchers.IO) {
        if (!isInstalled()) return@withContext null
        val rootfs = ChDroidPaths.rootfs
        val base = ChDroidPaths.base
        // The release's bundled busybox is a minimal build with no awk/sed/cut/
        // cat/ip applets, so this whole pass uses POSIX shell builtins (read,
        // parameter expansion, $(( ))) plus readlink/sleep/getprop, which exist
        // on every Android. `~` is a placeholder for `$` to keep Kotlin raw
        // strings readable; it is replaced at the end.
        val script = """
            PID=''; IFS= read -r PID < '$base/holder.pid' 2>/dev/null
            DISTRO=''; IFS= read -r DISTRO < '$base/distro-id' 2>/dev/null
            HOST=''; IFS= read -r HOST < '$base/hostname' 2>/dev/null
            [ -n "~HOST" ] || IFS= read -r HOST < '$rootfs/etc/hostname' 2>/dev/null
            # PRETTY_NAME via the read builtin - no grep/head/sed on this device.
            PRETTY=''
            while IFS= read -r line; do
              case "~line" in PRETTY_NAME=*) PRETTY=~{line#PRETTY_NAME=}; break ;; esac
            done < '$rootfs/etc/os-release' 2>/dev/null
            PRETTY=~{PRETTY#\"}; PRETTY=~{PRETTY%\"}
            [ -n "~PRETTY" ] || PRETTY=~DISTRO
            [ -n "~HOST" ] || HOST=~DISTRO
            IP=~(getprop dhcp.wlan0.ipaddress 2>/dev/null); IP=~{IP%% *}
            [ -n "~IP" ] || { IP=~(getprop dhcp.eth0.ipaddress 2>/dev/null); IP=~{IP%% *}; }
            # Modern Android often leaves the dhcp.* props empty. toybox `ip` reads
            # the live interface address; this chroot shares the host net namespace.
            if [ -z "~IP" ]; then
              IPL=~(/system/bin/ip -4 addr show wlan0 2>/dev/null)
              case "~IPL" in *'inet '*) IP=~{IPL#*inet }; IP=~{IP%%/*}; IP=~{IP%% *} ;; esac
            fi
            if [ -z "~IP" ]; then
              for IF in wlan1 eth0 rmnet_data0 rmnet_data1 rmnet0; do
                [ -n "~IP" ] && break
                IPL=~(/system/bin/ip -4 addr show "~IF" 2>/dev/null)
                case "~IPL" in *'inet '*) IP=~{IPL#*inet }; IP=~{IP%%/*}; IP=~{IP%% *} ;; esac
              done
            fi

            # MemTotal straight from the kernel via the read builtin.
            MEMTOTAL=0
            while read -r k v u; do [ "~k" = 'MemTotal:' ] && { MEMTOTAL=~v; break; }; done < /proc/meminfo

            # Host tick totals cover every core; process ticks cover one. Normalize.
            NCPU=0
            while IFS= read -r line; do
              # ARM /proc/cpuinfo writes "processor : 0" (space before colon).
              case "~line" in processor*) NCPU=~((NCPU + 1)) ;; esac
            done < /proc/cpuinfo
            [ ~NCPU -gt 0 ] || NCPU=1

            # Uptime from the namespace holder's starttime (field 22 of stat).
            UPTIME_S=''
            if [ -n "~PID" ] && [ -r /proc/~PID/stat ]; then
              IFS= read -r line < /proc/~PID/stat
              rest=~{line##*) }
              set -- ~rest
              START=~{20}
              NOW=''; read -r NOW _ < /proc/uptime
              UPTIME_S=~(( ~{NOW%.*} - START / 100 ))
            fi

            # One `ls -l` over the proc roots finds every PID rooted in the rootfs.
            # Match the arrow + target as a suffix, not positional fields: toybox ls
            # prints an ISO date (`2026-11-23 00:10`) while GNU prints month/day/time,
            # so counting fields shift depending on the device's ls. The PID comes
            # from the /proc/<pid>/root path before the arrow, never from the date.
            PIDS=~(ls -l /proc/[0-9]*/root 2>/dev/null | while read -r line; do
              case "~line" in
                *" -> ~rootfs"|*" -> ~rootfs/")
                  p=~{line#*/proc/}
                  p=~{p%%/*}
                  [ -n "~p" ] && printf '%s\n' "~p"
                  ;;
              esac
            done)
            # The namespace holder is not chrooted, so add it explicitly as the
            # baseline - CPU/RAM are never blank while the container is up.
            case " ~PIDS " in *" ~PID "*) ;; *) [ -n "~PID" ] && PIDS="~PIDS ~PID" ;; esac

            # Pages are 4 KB on most devices but 16 KB on newer ARM64 kernels. Derive
            # the real size from this shell's own VmRSS / statm resident instead of
            # hardcoding it, so RAM tracks the guest instead of a fixed multiplier.
            PG=4
            read -r _ rss0 _ < /proc/self/statm 2>/dev/null
            if [ -n "~rss0" ] && [ ~rss0 -gt 0 ]; then
              while read -r k v u; do
                if [ "~k" = 'VmRSS:' ]; then PG=~((v / rss0)); break; fi
              done < /proc/self/status 2>/dev/null
            fi
            [ ~PG -gt 0 ] || PG=4

            RSS_KB=0
            for p in ~PIDS; do
              read -r _ rss _ < /proc/~p/statm 2>/dev/null || continue
              RSS_KB=~((RSS_KB + rss * PG))
            done

            ticks() {
              t=0
              for p in ~PIDS; do
                IFS= read -r line < /proc/~p/stat 2>/dev/null || continue
                rest=~{line##*) }
                set -- ~rest
                t=~((t + ~{12} + ~{13}))
              done
              echo "~t"
            }
            host_ticks() {
              # Total jiffies across every core, INCLUDING idle. Dividing a process
              # delta by non-idle host ticks made idle phones report 0.0% no matter
              # what the guest was doing. With all cores counted, DT * NCPU / DH is
              # the standard one-core-normalised utilisation.
              read -r _ u n s i w q r st _ < /proc/stat
              echo ~((u+n+s+i+w+q+r+st))
            }
            CPU=''
            T1=~(ticks); H1=~(host_ticks)
            sleep 0.15
            T2=~(ticks); H2=~(host_ticks)
            DT=~((T2 - T1)); [ ~DT -lt 0 ] && DT=0
            DH=~((H2 - H1))
            if [ ~DH -gt 0 ]; then
              CPUM=~(( DT * 1000 * NCPU / DH ))
              CPU="~((CPUM / 10)).~((CPUM % 10))"
            fi
            echo "DISTRO=~DISTRO"
            echo "PRETTY=~PRETTY"
            echo "HOST=~HOST"
            echo "IP=~IP"
            echo "UPTIME=~UPTIME_S"
            echo "CPU=~CPU"
            echo "RSS=~RSS_KB"
            echo "MEMTOTAL=~MEMTOTAL"
        """.trimIndent().replace('~', '$')
        val out = Shell.cmd(script).exec().out
        val m = out.associate { line ->
            val i = line.indexOf('=')
            if (i > 0) line.substring(0, i) to line.substring(i + 1) else "" to ""
        }
        val uptimeSec = m["UPTIME"]?.toLongOrNull() ?: 0L
        val uptimeLabel = when {
            uptimeSec >= 3600 -> "${uptimeSec / 3600}h ${(uptimeSec % 3600) / 60}m"
            uptimeSec >= 60 -> "${uptimeSec / 60}m ${uptimeSec % 60}s"
            uptimeSec > 0 -> "${uptimeSec}s"
            else -> ""
        }
        val pretty = m["PRETTY"]?.ifBlank { null }
        val ramUsedKb = m["RSS"]?.toLongOrNull()?.takeIf { it > 0 }
        val memTotalKb = m["MEMTOTAL"]?.toLongOrNull() ?: 0L
        ContainerOSInfoManager.OSInfo(
            prettyName = pretty,
            name = pretty,
            version = null,
            versionId = null,
            id = m["DISTRO"]?.ifBlank { null },
            hostname = m["HOST"]?.ifBlank { null },
            ipAddress = m["IP"]?.ifBlank { null },
            uptime = uptimeLabel.ifBlank { null },
            cpuUsage = m["CPU"]?.toDoubleOrNull()?.coerceIn(0.0, 100.0),
            ramUsedKb = ramUsedKb,
            ramPercent = if (memTotalKb > 0 && ramUsedKb != null)
                (ramUsedKb.toDouble() * 100.0 / memTotalKb).coerceIn(0.0, 100.0) else null,
            ramLimitKb = null
        )
    }
}
