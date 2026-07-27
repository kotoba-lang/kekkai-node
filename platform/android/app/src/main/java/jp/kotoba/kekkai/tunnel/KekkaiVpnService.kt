package jp.kotoba.kekkai.tunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class KekkaiVpnService : VpnService() {
    private val running = AtomicBoolean(false)
    private var descriptor: ParcelFileDescriptor? = null
    private var transport: KekkaiPacketTransport? = null
    private var reader: Thread? = null
    private var writer: FileOutputStream? = null
    private val mtu = 1280

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running.compareAndSet(false, true)) startTunnel()
        return START_STICKY
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    private fun startTunnel() {
        val nextTransport = KekkaiTransportFactory.make()
        if (nextTransport == null) {
            running.set(false)
            stopSelf()
            return
        }
        createForegroundNotification()
        val nextDescriptor = Builder()
            .setSession("Kekkai managed tunnel")
            .setMtu(mtu)
            .addAddress("100.96.0.2", 32)
            .addAddress("fd7a:6b65::2", 128)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer("100.96.0.1")
            .addDnsServer("fd7a:6b65::1")
            .setBlocking(true)
            .establish()
        if (nextDescriptor == null) {
            running.set(false)
            stopSelf()
            return
        }
        descriptor = nextDescriptor
        writer = FileOutputStream(nextDescriptor.fileDescriptor)
        transport = nextTransport
        nextTransport.start(this) { packet ->
            val parsed = PacketParser.parse(packet, packet.size) ?: return@start
            if (KotobaPacketPlane.decide(
                    parsed.family, parsed.bytes.size, mtu, 1,
                    if (nextTransport.ready) 1 else 0
                ) == KotobaPacketPlane.OVERLAY
            ) {
                synchronized(this) { writer?.write(parsed.bytes) }
            }
        }
        reader = Thread({
            val input = FileInputStream(nextDescriptor.fileDescriptor)
            val buffer = ByteArray(65_575)
            while (running.get()) {
                val count = input.read(buffer)
                if (count <= 0) break
                val parsed = PacketParser.parse(buffer, count) ?: continue
                if (KotobaPacketPlane.decide(
                        parsed.family, parsed.bytes.size, mtu, 1,
                        if (nextTransport.ready) 1 else 0
                    ) == KotobaPacketPlane.OVERLAY
                ) {
                    nextTransport.send(parsed.bytes)
                }
            }
        }, "kekkai-tun-reader").apply { start() }
    }

    private fun stopTunnel() {
        if (!running.getAndSet(false)) return
        transport?.stop()
        transport = null
        descriptor?.close()
        descriptor = null
        writer = null
        reader?.interrupt()
        reader = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun createForegroundNotification() {
        val channelId = "kekkai-tunnel"
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    channelId, "Kekkai Tunnel",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        startForeground(
            41042,
            Notification.Builder(this, channelId)
                .setContentTitle("Kekkai managed tunnel")
                .setContentText("Zero-trust packet protection is active")
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setOngoing(true)
                .build()
        )
    }
}
