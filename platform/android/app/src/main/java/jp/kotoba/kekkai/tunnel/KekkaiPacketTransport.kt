package jp.kotoba.kekkai.tunnel

import android.net.VpnService

interface KekkaiPacketTransport {
    val ready: Boolean
    fun start(service: VpnService, receive: (ByteArray) -> Unit)
    fun send(packet: ByteArray)
    fun stop()
}

object KekkaiTransportFactory {
    @Volatile private var builder: (() -> KekkaiPacketTransport)? = null

    fun install(value: () -> KekkaiPacketTransport) {
        synchronized(this) {
            check(builder == null) { "Kekkai transport factory already installed" }
            builder = value
        }
    }

    fun make(): KekkaiPacketTransport? = builder?.invoke()
}
