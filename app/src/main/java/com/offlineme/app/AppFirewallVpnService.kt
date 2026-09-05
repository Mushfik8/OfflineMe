package com.offlineme.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Local, on-device firewall.
 *
 * Only packages in [BlockedAppsStore] are added to the VPN's allowed-app
 * list, so only THEIR traffic ever reaches this service. Every other app —
 * Netflix, YouTube, your browser, anything you haven't blocked — bypasses
 * the VPN completely and keeps its normal, unfiltered connection at full
 * speed.
 *
 * Blocked apps' packets are read just far enough to identify the sender,
 * then dropped. Nothing is forwarded, so a blocked app simply sees no
 * network — which is exactly the behavior we want, with no need to build
 * a userspace NAT/relay.
 *
 * IMPORTANT: rename the package above to match your actual project, and
 * remember that calling BlockedAppsStore.setBlocked(...) by itself does
 * NOT change what's running — call AppFirewallVpnService.start(context)
 * (or .refresh()) afterward so the tunnel gets rebuilt with the new list.
 */
class AppFirewallVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var readThread: Thread? = null
    @Volatile private var running = false

    private lateinit var store: BlockedAppsStore
    private val lastNotified = HashMap<String, Long>()

    override fun onCreate() {
        super.onCreate()
        store = BlockedAppsStore(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTunnel()
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundNotification()
        restartTunnel()
        return START_STICKY
    }

    /** Rebuilds the tunnel from the current blocked list. Call after any change to it. */
    fun restartTunnel() {
        stopTunnel()

        val builder = Builder()
            .setSession("App firewall")
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")

        store.getBlockedPackages().forEach { pkg ->
            try {
                builder.addAllowedApplication(pkg)
            } catch (e: PackageManager.NameNotFoundException) {
                // App was uninstalled since being blocked — just skip it.
            }
        }

        vpnInterface = builder.establish() ?: return // user hasn't granted VPN permission yet
        running = true
        readThread = Thread { readLoop() }.also { it.start() }
    }

    private fun stopTunnel() {
        running = false
        readThread?.interrupt()
        readThread = null
        vpnInterface?.close()
        vpnInterface = null
    }

    private fun readLoop() {
        val fd = vpnInterface ?: return
        val input = FileInputStream(fd.fileDescriptor)
        val buffer = ByteArray(32767)

        while (running) {
            val length = try {
                input.read(buffer)
            } catch (e: Exception) {
                break
            }
            if (length > 0) identifySender(buffer, length)
            // Nothing is ever written back — a blocked app's packets just vanish.
        }
    }

    /** Parses just enough of the IPv4 header to find out which app this packet is from. */
    private fun identifySender(buffer: ByteArray, length: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return // getConnectionOwnerUid needs API 29+
        if (length < 20) return

        val versionAndIhl = buffer[0].toInt() and 0xFF
        if (versionAndIhl shr 4 != 4) return // IPv4 only for now — add IPv6 parsing later if you need it
        val ihl = (versionAndIhl and 0x0F) * 4
        if (length < ihl + 4) return

        val protocol = buffer[9].toInt() and 0xFF
        if (protocol != OsConstants.IPPROTO_TCP && protocol != OsConstants.IPPROTO_UDP) return

        val srcIp = InetAddress.getByAddress(buffer.copyOfRange(12, 16))
        val dstIp = InetAddress.getByAddress(buffer.copyOfRange(16, 20))
        val srcPort = ((buffer[ihl].toInt() and 0xFF) shl 8) or (buffer[ihl + 1].toInt() and 0xFF)
        val dstPort = ((buffer[ihl + 2].toInt() and 0xFF) shl 8) or (buffer[ihl + 3].toInt() and 0xFF)

        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val uid = try {
            cm.getConnectionOwnerUid(
                protocol,
                InetSocketAddress(srcIp, srcPort),
                InetSocketAddress(dstIp, dstPort)
            )
        } catch (e: Exception) {
            return
        }
        if (uid == Process.INVALID_UID) return

        val packageName = packageManager.getPackagesForUid(uid)?.firstOrNull() ?: return
        notifyIfDue(packageName)
    }

    /** Debounced so a chatty app doesn't spam the popup/notification every few milliseconds. */
    private fun notifyIfDue(packageName: String) {
        val now = System.currentTimeMillis()
        val last = lastNotified[packageName] ?: 0L
        if (now - last < NOTIFY_COOLDOWN_MS) return
        lastNotified[packageName] = now
        onBlockedAttempt?.invoke(packageName)
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "App firewall", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("App firewall active")
            .setContentText("Filtering internet access for blocked apps")
            .setSmallIcon(android.R.drawable.ic_lock_lock) // swap for your own icon
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    /** Called if the user revokes VPN permission from system settings. */
    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    companion object {
        const val ACTION_STOP = "com.offlineme.app.action.STOP"
        private const val CHANNEL_ID = "app_firewall_service"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFY_COOLDOWN_MS = 4000L

        /** Set this from your app (Application class is a good spot) to hear about blocked attempts. */
        var onBlockedAttempt: ((packageName: String) -> Unit)? = null

        fun start(context: Context) {
            val intent = Intent(context, AppFirewallVpnService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Same as start() — call this name when the intent is "push my updated block list". */
        fun refresh(context: Context) = start(context)

        fun stop(context: Context) {
            context.startService(Intent(context, AppFirewallVpnService::class.java).apply {
                action = ACTION_STOP
            })
        }
    }
}
