package com.droidspaces.app.util

import android.content.Context
import android.net.Uri
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
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

    /** Start the namespace and mounts without consuming a terminal. */
    suspend fun start(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            check(isInstalled()) { "rootfs is not installed" }
            val result = Shell.cmd(
                "sh '${Constants.CHDROID_ROOTFS_SCRIPT}' start --no-shell 2>&1; " +
                    androidRootMountCmd()
            ).exec()
            val output = result.out.joinToString("\n").trim()
            if (!result.isSuccess) throw Exception(output.ifEmpty { "chroot start failed" })
        }
    }

    suspend fun stop(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val result = Shell.cmd("sh '${Constants.CHDROID_ROOTFS_SCRIPT}' stop 2>&1").exec()
            if (!result.isSuccess) {
                throw Exception(result.out.joinToString("\n").ifEmpty { "chroot stop failed" })
            }
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

                val q = ContainerCommandBuilder::quote

                // Apply the setup screen's choices to the installed rootfs so the
                // terminal hostname and the Containers card reflect what He picked.
                if (hostname.isNotBlank()) {
                    val apply = Shell.cmd(
                        "printf '%s\\n' ${q(hostname)} > ${q("${Constants.CHDROID_ROOTFS_DIR}/etc/hostname")} " +
                            "&& printf '%s\\n' ${q(hostname)} >> ${q("${Constants.CHDROID_ROOTFS_DIR}/etc/hosts")} " +
                            "&& printf '%s\\n' ${q(containerName.ifBlank { hostname })} > " +
                            "${q("${Constants.CHDROID_ROOTFS_BASE}/container-name")} 2>&1"
                    ).exec()
                    if (!apply.isSuccess) logger.e("could not apply hostname: ${apply.errorText()}")
                    else logger.i("hostname set to $hostname")
                } else if (containerName.isNotBlank()) {
                    Shell.cmd(
                        "printf '%s\\n' ${q(containerName)} > " +
                            "${q("${Constants.CHDROID_ROOTFS_BASE}/container-name")} 2>&1"
                    ).exec()
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
    fun terminalCommand(): String =
        "sh '${Constants.CHDROID_ROOTFS_SCRIPT}' start --no-shell 2>/dev/null; " +
            "${androidRootMountCmd()}; " +
            "sh '${Constants.CHDROID_ROOTFS_SCRIPT}' start root; " +
            // On a successful enter, chroot.sh exec's over this shell and this is
            // never reached. If the chroot cannot be entered for any reason, keep
            // the terminal alive with a host root shell instead of closing the tab.
            "exec su"

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
        val script = """
            BB='${Constants.INSTALL_PATH}/busybox-release'
            [ -x "~BB" ] || BB='${Constants.INSTALL_PATH}/busybox'
            PID=~(cat '$base/holder.pid' 2>/dev/null)
            DISTRO=~(head -n1 '$base/distro-id' 2>/dev/null)
            PRETTY=~(~BB sed -n 's/^PRETTY_NAME="\?\([^"]*\)"\?/\1/p' '$rootfs/etc/os-release' 2>/dev/null | head -1)
            HOST=~(head -n1 '$rootfs/etc/hostname' 2>/dev/null)
            IP=~(~BB ip -4 -o addr show scope global 2>/dev/null | ~BB awk '{print $4}' | ~BB cut -d/ -f1 | head -1)
            [ -n "~IP" ] || IP=~(getprop dhcp.wlan0.ipaddress 2>/dev/null | ~BB cut -d' ' -f1)
            [ -n "~PRETTY" ] || PRETTY=~DISTRO
            [ -n "~HOST" ] || HOST=~DISTRO
            UPTIME_S=''
            CPU=''
            RSS_KB=0
            MEMTOTAL=~(~BB awk '/^MemTotal:/{print ~2}' /proc/meminfo)
            NS=''
            HOSTNS=~(readlink /proc/1/ns/pid 2>/dev/null)
            [ -n "~PID" ] && [ -r /proc/~PID/ns/pid ] && NS=~(readlink /proc/~PID/ns/pid 2>/dev/null)
            if [ -n "~PID" ] && [ -r /proc/~PID/stat ]; then
              START=~(~BB cut -d' ' -f22 /proc/~PID/stat)
              NOW=~(~BB cut -d' ' -f1 /proc/uptime)
              UPTIME_S=~(~BB awk "BEGIN{printf \"%d\", ~NOW - ~START/100}")
            fi
            # The holder is a sleeping namespace anchor, not the container. Walk
            # every process in its PID namespace the way the backend's collect.c
            # does. Without a private PID namespace, match by chroot root instead.
            match() {
              if [ -z "~NS" ] || [ "~NS" = "~HOSTNS" ]; then
                [ ~(readlink /proc/~1/root 2>/dev/null) = '$rootfs' ]
              else
                [ ~(readlink /proc/~1/ns/pid 2>/dev/null) = "~NS" ]
              fi
            }
            ticks() {
              t=0
              for d in /proc/[0-9]*; do
                p=~{d#/proc/}
                match "~p" || continue
                line=~(cat "~d/stat" 2>/dev/null) || continue
                rest=~{line##*) }
                set -- ~rest
                t=$((t + ~{12} + ~{13}))
              done
              echo "~t"
            }
            host_ticks() {
              read -r _ u n s i w q r _ < /proc/stat
              echo $((u+n+s+i+w+q+r))
            }
            if [ -n "~PID" ]; then
              for d in /proc/[0-9]*; do
                p=~{d#/proc/}
                match "~p" || continue
                set -- ~(cat "~d/statm" 2>/dev/null)
                RSS_KB=$((RSS_KB + ~{2} * 4))
              done
              T1=~(ticks)
              H1=~(host_ticks)
              ~BB sleep 0.25
              T2=~(ticks)
              H2=~(host_ticks)
              DT=$((T2 - T1)); [ ~DT -lt 0 ] && DT=0
              DH=$((H2 - H1))
              [ ~DH -gt 0 ] && CPU=~(~BB awk "BEGIN{printf \"%.1f\", ~DT*100.0/~DH}")
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
            cpuUsage = m["CPU"]?.toDoubleOrNull(),
            ramUsedKb = ramUsedKb,
            ramPercent = if (memTotalKb > 0 && ramUsedKb != null)
                (ramUsedKb.toDouble() * 100.0 / memTotalKb).coerceIn(0.0, 100.0) else null,
            ramLimitKb = null
        )
    }
}
