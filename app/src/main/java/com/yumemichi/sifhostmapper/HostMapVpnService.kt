package com.yumemichi.sifhostmapper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

class HostMapVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var workerThread: Thread? = null
    private val running = AtomicBoolean(false)

    private var targetIpBytes: ByteArray? = null
    private var configuredHosts: List<String> = emptyList()
    private var matchHosts: Set<String> = emptySet()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val ip = intent.getStringExtra(EXTRA_TARGET_IP).orEmpty()
                startVpn(ip)
            }
            ACTION_STOP -> {
                stopVpn()
                stopSelf()
            }
        }
        return Service.START_STICKY
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun startVpn(ipText: String) {
        val addr = try {
            InetAddress.getByName(ipText)
        } catch (_: Exception) {
            null
        }
        if (addr == null || addr.address.size != 4) {
            Prefs.setEnabled(this, false)
            stopSelf()
            return
        }
        targetIpBytes = addr.address
        configuredHosts = Prefs.hosts(this).toList()
        if (configuredHosts.isEmpty()) {
            Prefs.setEnabled(this, false)
            stopSelf()
            return
        }
        matchHosts = buildHostMatchSet(configuredHosts)

        if (running.get()) {
            addr.hostAddress?.let { updateRunningNotification(it) }
            isRunning = true
            Prefs.setEnabled(this, true)
            return
        }

        val builder = Builder()
            .setSession("SIFHostMapper")
            .addAddress(VPN_LOCAL_IP, 32)
            .addDnsServer(VPN_DNS_IP)
            .addRoute(VPN_DNS_IP, 32)
            .setBlocking(true)

        val established = builder.establish() ?: run {
            Prefs.setEnabled(this, false)
            isRunning = false
            return
        }
        vpnInterface = established

        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(getString(R.string.notification_running_title))
                .setContentText(addr.hostAddress?.let { buildNotificationText(it) })
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build()
        )

        running.set(true)
        isRunning = true
        Prefs.setEnabled(this, true)
        workerThread = Thread { runPacketLoop(established) }.also { it.start() }
    }

    private fun stopVpn() {
        running.set(false)
        isRunning = false
        Prefs.setEnabled(this, false)
        workerThread?.interrupt()
        workerThread = null
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun runPacketLoop(fd: ParcelFileDescriptor) {
        FileInputStream(fd.fileDescriptor).use { input ->
            FileOutputStream(fd.fileDescriptor).use { output ->
                val buffer = ByteArray(32767)
                while (running.get()) {
                    val length = try {
                        input.read(buffer)
                    } catch (_: Exception) {
                        break
                    }
                    if (length <= 0) continue

                    val queryPacket = IpUdpPacketUtils.parseUdpDnsPacket(buffer, length) ?: continue
                    if (queryPacket.dstPort != 53) continue

                    val dnsQuery = queryPacket.dnsPayload
                    val dnsResponse = resolveDnsQuery(dnsQuery) ?: continue

                    val replyPacket = IpUdpPacketUtils.buildIpv4UdpPacket(
                        srcIp = queryPacket.dstIp,
                        dstIp = queryPacket.srcIp,
                        srcPort = 53,
                        dstPort = queryPacket.srcPort,
                        payload = dnsResponse
                    )

                    try {
                        output.write(replyPacket)
                    } catch (_: Exception) {
                        break
                    }
                }
            }
        }
    }

    private fun resolveDnsQuery(query: ByteArray): ByteArray? {
        val parsed = DnsPacketUtils.parseQuery(query) ?: return null

        if (isTargetDomain(parsed.name)) {
            if (parsed.qType == 1) {
                val targetIp = targetIpBytes ?: return null
                return DnsPacketUtils.buildMappedAResponse(query, targetIp)
            }
            return DnsPacketUtils.buildNoErrorEmptyResponse(query)
        }

        return forwardToUpstreamDns(query)
    }

    private fun isTargetDomain(queryName: String): Boolean {
        return matchHosts.contains(queryName.lowercase())
    }

    private fun buildHostMatchSet(hosts: Collection<String>): Set<String> {
        val matched = linkedSetOf<String>()
        hosts.forEach { host ->
            val normalized = normalizeHost(host)
            if (normalized.isEmpty()) return@forEach
            matched += normalized
            matched += if (normalized.startsWith("www.")) {
                normalized.removePrefix("www.")
            } else {
                "www.$normalized"
            }
        }
        return matched
    }

    private fun normalizeHost(raw: String): String {
        return raw.trim().lowercase().trimEnd('.')
    }

    private fun buildNotificationText(ip: String): String {
        return if (configuredHosts.size <= 1) {
            getString(
                R.string.notification_running_text_format,
                configuredHosts.firstOrNull().orEmpty(),
                ip
            )
        } else {
            getString(
                R.string.notification_running_multi_text_format,
                configuredHosts.size,
                ip
            )
        }
    }

    private fun forwardToUpstreamDns(query: ByteArray): ByteArray? {
        val socket = DatagramSocket()
        protect(socket)
        return try {
            socket.soTimeout = 2000
            val remote = InetAddress.getByName(UPSTREAM_DNS)
            socket.send(DatagramPacket(query, query.size, remote, 53))
            val respBuffer = ByteArray(2048)
            val responsePacket = DatagramPacket(respBuffer, respBuffer.size)
            socket.receive(responsePacket)
            respBuffer.copyOf(responsePacket.length)
        } catch (_: Exception) {
            null
        } finally {
            socket.close()
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun updateRunningNotification(ip: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(getString(R.string.notification_running_title))
                .setContentText(buildNotificationText(ip))
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build()
        )
    }

    companion object {
        @Volatile
        var isRunning: Boolean = false

        const val ACTION_START = "com.yumemichi.sifhostmapper.START"
        const val ACTION_STOP = "com.yumemichi.sifhostmapper.STOP"
        const val EXTRA_TARGET_IP = "target_ip"

        private const val CHANNEL_ID = "host_vpn_channel"
        private const val NOTIFICATION_ID = 1011

        private const val VPN_LOCAL_IP = "10.23.0.2"
        private const val VPN_DNS_IP = "10.23.0.1"
        private const val UPSTREAM_DNS = "1.1.1.1"
    }
}
