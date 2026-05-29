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

    private var hostToIpBytes: Map<String, ByteArray> = emptyMap()
    private var activeGroupCount = 0
    private var activeDomainCount = 0
    private var firstMappedDomain = ""
    private var firstMappedIp = ""

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpn()
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

    private fun startVpn() {
        if (!reloadMappings()) {
            Prefs.setEnabled(this, false)
            stopSelf()
            return
        }

        if (running.get()) {
            updateRunningNotification()
            isRunning = true
            Prefs.setEnabled(this, true)
            return
        }

        val builder = Builder()
            .setSession("SIFMapper")
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
                .setContentText(buildNotificationText())
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

    private fun reloadMappings(): Boolean {
        val groups = Prefs.mappingGroups(this)
        val map = linkedMapOf<String, ByteArray>()
        activeGroupCount = 0
        activeDomainCount = 0
        firstMappedDomain = ""
        firstMappedIp = ""

        groups.forEach { group ->
            val ip = parseIpv4(group.targetIp) ?: return@forEach
            val normalizedDomains = group.domains
                .map(::normalizeDomain)
                .filter { it.isNotEmpty() }
            if (normalizedDomains.isEmpty()) return@forEach

            activeGroupCount += 1
            normalizedDomains.forEach { domain ->
                if (!map.containsKey(domain)) {
                    map[domain] = ip
                }
                activeDomainCount += 1
                if (firstMappedDomain.isEmpty()) {
                    firstMappedDomain = domain
                    firstMappedIp = group.targetIp.trim()
                }
            }
        }

        hostToIpBytes = map
        return hostToIpBytes.isNotEmpty()
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
        val mappedIp = hostToIpBytes[parsed.name.lowercase()]

        if (mappedIp != null) {
            if (parsed.qType == 1) {
                return DnsPacketUtils.buildMappedAResponse(query, mappedIp)
            }
            return DnsPacketUtils.buildNoErrorEmptyResponse(query)
        }

        return forwardToUpstreamDns(query)
    }

    private fun buildNotificationText(): String {
        if (activeDomainCount <= 1 && firstMappedDomain.isNotEmpty()) {
            return getString(
                R.string.notification_running_text_format,
                firstMappedDomain,
                firstMappedIp
            )
        }
        return getString(
            R.string.notification_running_summary_format,
            activeDomainCount,
            activeGroupCount
        )
    }

    private fun parseIpv4(ip: String): ByteArray? {
        return try {
            val addr = InetAddress.getByName(ip.trim())
            if (addr.hostAddress == ip.trim() && addr.address.size == 4) addr.address else null
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeDomain(raw: String): String {
        return raw.trim().lowercase().trimEnd('.')
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

    private fun updateRunningNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(getString(R.string.notification_running_title))
                .setContentText(buildNotificationText())
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

        private const val CHANNEL_ID = "host_vpn_channel"
        private const val NOTIFICATION_ID = 1011

        private const val VPN_LOCAL_IP = "10.23.0.2"
        private const val VPN_DNS_IP = "10.23.0.1"
        private const val UPSTREAM_DNS = "1.1.1.1"
    }
}
