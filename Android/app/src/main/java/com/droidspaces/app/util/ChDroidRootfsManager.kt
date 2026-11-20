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
    private val logBuffer = mutableStateListOf<Pair<Int, String>>()
    private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
        val cached = PreferencesManager.getInstance(appContext!!).loadContainerLogs(LOG_KEY)
        if (logBuffer.isEmpty()) logBuffer.addAll(cached)

        // Rootfs ZIPs published before the prompt/fast-stop fixes still carry the
        // old chroot.sh and 256-colour PS1. Re-apply the identity patch on every
        // app start so an APK update repairs an existing install in place - no
        // repack, no re-download, no reboot. Cheap: a few shell builtins, and it
        // bails immediately when no rootfs is installed.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                if (!isInstalled()) return@runCatching
                CrashLogger.log("chroot startup patch: begin")
                val host = hostname()
                val name = containerName()
                val q = ContainerCommandBuilder::quote
                val identity = ContainerInstaller.deployAsset(appContext!!, "chdroid_identity.sh")
                try {
                    val result = Shell.cmd(
                        "${q(identity.absolutePath)} " +
                            "${q(Constants.CHDROID_ROOTFS_BASE)} " +
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

    /** True when the CLI manager and a usable rootfs are on the device. */
    suspend fun isInstalled(): Boolean = withContext(Dispatchers.IO) {
        Shell.cmd(
            "test -x '${Constants.CHDROID_ROOTFS_SCRIPT}' && " +
                "test -x '${Constants.CHDROID_ROOTFS_DIR}/bin/sh'"
        ).exec().isSuccess
    }

    suspend fun status(): String = withContext(Dispatchers.IO) {
        if (!isInstalled()) return@withContext "not installed"
        Shell.cmd("sh '${Constants.CHDROID_ROOTFS_SCRIPT}' status 2>&1").exec()
            .out.joinToString(" ").trim().ifEmpty { "unknown" }
    }

    /** Distro id written by the installer into the rootfs base. */
    suspend fun distro(): String = withContext(Dispatchers.IO) {
        Shell.cmd("cat '${Constants.CHDROID_ROOTFS_BASE}/distro-id' 2>/dev/null").exec()
            .out.firstOrNull()?.trim().orEmpty()
    }

    /** Container name chosen in the setup screen and written at install time. */
    suspend fun containerName(): String = withContext(Dispatchers.IO) {
        Shell.cmd("cat '${Constants.CHDROID_ROOTFS_BASE}/container-name' 2>/dev/null").exec()
            .out.firstOrNull()?.trim().orEmpty()
    }

    /**
     * Hostname baked into the installed rootfs, not a hardcoded app value.
     * Ubuntu ships /etc/hostname; Arch does not, so fall back to the distro id
     * recorded at install time, which is still a property of the installed tarball.
     */
    suspend fun hostname(): String = withContext(Dispatchers.IO) {
        val baked = Shell.cmd("head -n 1 '${Constants.CHDROID_ROOTFS_DIR}/etc/hostname' 2>/dev/null").exec()
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

    /**
     * chroot.sh flips the WHOLE DEVICE to SELinux permissive on start and never
     * restores it. Capture the original mode once before the first start, then
     * put it back when the rootfs stops. State file survives terminal restarts so
     * a chroot started from a terminal is still restored when stopped from a card.
     */
    private val selinuxStateFile: String
        get() = "${Constants.CHDROID_ROOTFS_BASE}/selinux-original"

    private fun saveSelinuxCmd(): String {
        val state = selinuxStateFile
        return "[ ! -f $state ] && { read -r m < /sys/fs/selinux/enforce 2>/dev/null && " +
            "echo \"\$m\" > $state; }; true"
    }

    private fun restoreSelinuxCmd(): String {
        val state = selinuxStateFile
        return "if [ -f $state ]; then read -r m < $state 2>/dev/null; " +
            "[ \"\$m\" = 1 ] && setenforce 1 2>/dev/null; rm -f $state; fi; true"
    }

    /** Start the namespace and mounts without consuming a terminal. */
    suspend fun start(): Result<Unit> = withContext(Dispatchers.IO) {
        val logger = operationLogger()
        withContext(Dispatchers.Main.immediate) { clearLogs() }
        runCatching {
            CrashLogger.log("chroot start: begin")
            if (!isInstalled()) {
                logger.e("")
                logger.e("rootfs is not installed")
                throw Exception("rootfs is not installed")
            }
            val command =
                "${saveSelinuxCmd()}; sh '${Constants.CHDROID_ROOTFS_SCRIPT}' start --no-shell; " +
                    androidRootMountCmd()
            val success = ContainerOperationExecutor.executeCommand(
                command = command,
                operation = "start",
                logger = logger,
                operationCompletedMessage = operationDoneMessage()
            )
            if (!success) {
                logger.e("")
                logger.e(operationFailedMessage())
                throw Exception("chroot start failed")
            }
        }.also {
            withContext(Dispatchers.Main.immediate) { saveLogs() }
        }
    }

    suspend fun stop(): Result<Unit> = withContext(Dispatchers.IO) {
        val logger = operationLogger()
        withContext(Dispatchers.Main.immediate) { clearLogs() }
        runCatching {
            CrashLogger.log("chroot stop: begin")
            val script = Constants.CHDROID_ROOTFS_SCRIPT
            val base = Constants.CHDROID_ROOTFS_BASE
            // timeout keeps a hung in-chroot `systemctl stop` from freezing the
            // button forever. If stop leaves the holder alive, force it down and
            // clear the state files so status() reports STOPPED immediately.
            val command =
                "out=\$(timeout 15 sh '$script' stop 2>&1); echo \"\$out\"; " +
                    "if sh '$script' status 2>&1 | grep -q RUNNING; then " +
                    "echo '[chdroid] stop left chroot RUNNING - forcing holder down'; " +
                    "p=\$(cat '$base/holder.pid' 2>/dev/null); " +
                    "[ -n \"\$p\" ] && kill -9 \"\$p\" 2>/dev/null; " +
                    "rm -f '$base/holder.pid' '$base/holder.pid.flags'; " +
                    "fi; " +
                    restoreSelinuxCmd() +
                    "if sh '$script' status 2>&1 | grep -q RUNNING; then exit 1; fi"
            val success = ContainerOperationExecutor.executeCommand(
                command = command,
                operation = "stop",
                logger = logger,
                operationCompletedMessage = operationDoneMessage()
            )
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
                        "${ContainerCommandBuilder.quote(cacheDir.absolutePath)} 2>&1"
                ).exec()
                result.out.map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
                    if (line.startsWith("[UC-INSTALL]")) logger.i(line) else logger.d(line)
                }
                if (!result.isSuccess) {
                    throw Exception(result.err.joinToString("\n").ifEmpty { "CLI install failed" })
                }
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
                            "${q(Constants.CHDROID_ROOTFS_BASE)} " +
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
                    "mkdir -p ${q(Constants.CHDROID_ROOTFS_BASE)} && " +
                        "printf '1\\n' > ${q(Constants.CHDROID_MOUNT_FLAG)}"
                } else {
                    "rm -f ${q(Constants.CHDROID_MOUNT_FLAG)}"
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

                logger.i("rootfs ready at ${Constants.CHDROID_ROOTFS_SCRIPT}")
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
        val script = Constants.CHDROID_ROOTFS_SCRIPT
        val mount = Constants.CHDROID_MOUNT_SCRIPT
        val body =
            "${saveSelinuxCmd()}; sh $script -s start --no-shell >/dev/null 2>&1 || exec su; " +
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
        val script = Constants.CHDROID_ROOTFS_SCRIPT
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
        val rootfs = Constants.CHDROID_ROOTFS_DIR
        val base = Constants.CHDROID_ROOTFS_BASE
        // The release's bundled busybox is a minimal build with no awk/sed/cut/
        // cat/ip applets, so this whole pass uses POSIX shell builtins (read,
        // parameter expansion, $(( ))) plus readlink/sleep/getprop, which exist
        // on every Android. `~` is a placeholder for `$` to keep Kotlin raw
        // strings readable; it is replaced at the end.
        val script = """
            PID=''; IFS= read -r PID < '$base/holder.pid' 2>/dev/null
            DISTRO=''; IFS= read -r DISTRO < '$base/distro-id' 2>/dev/null
            HOST=''; IFS= read -r HOST < '$rootfs/etc/hostname' 2>/dev/null
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

            # A process belongs to the chroot if its root symlink is the rootfs.
            # Include the holder itself as a baseline so CPU/RAM are never blank.
            ticks() {
              t=0
              for d in /proc/[0-9]*; do
                r=~(readlink "~d/root" 2>/dev/null)
                if [ "~r" = '$rootfs' ] || [ "~d" = "/proc/~PID" ]; then
                  IFS= read -r line < "~d/stat" 2>/dev/null || continue
                  rest=~{line##*) }
                  set -- ~rest
                  t=~((t + ~{12} + ~{13}))
                fi
              done
              echo "~t"
            }
            host_ticks() {
              read -r _ u n s i w q r _ < /proc/stat
              echo ~((u+n+s+w+q+r))
            }
            CPU=''; RSS_KB=0
            for d in /proc/[0-9]*; do
              r=~(readlink "~d/root" 2>/dev/null)
              if [ "~r" = '$rootfs' ] || [ "~d" = "/proc/~PID" ]; then
                read -r _ rss _ < "~d/statm" 2>/dev/null || continue
                RSS_KB=~((RSS_KB + rss * 4))
              fi
            done
            T1=~(ticks); H1=~(host_ticks)
            sleep 0.25
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
