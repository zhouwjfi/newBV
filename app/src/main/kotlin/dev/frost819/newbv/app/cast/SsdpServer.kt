package dev.frost819.newbv.app.cast

import dev.frost819.newbv.core.log.Loggers
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketException
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * SSDP 监听服务（设备发现）。
 *
 * 绑定 `239.255.255.250:47823` 私有组播端口，响应发送端的 M-SEARCH 请求，
 * 并周期发送 `ssdp:alive` 通告，使已启动的发送端也能发现本机。
 *
 * 不使用标准 1900 端口，避免与电视系统自带 DLNA 服务冲突。
 *
 * @param location 设备描述地址（`http://ip:port/description.xml`）。
 * @param udn 设备唯一标识（`uuid:xxxx`）。
 * @param server SERVER 头内容。
 */
class SsdpServer(
    private val location: String,
    private val udn: String,
    private val server: String,
) {
    private val logger = Loggers.get("CastSsdp")

    @Volatile
    private var running = false

    private var socket: MulticastSocket? = null
    private var workThread: Thread? = null

    /**
     * 启动监听。
     *
     * 立即发送一次 `ssdp:alive`，之后每 [NOTIFY_INTERVAL_MS] 毫秒重新通告。
     */
    fun start() {
        if (running) return
        running = true
        workThread =
            Thread({ receiveLoop() }, "CastSsdp").apply {
                isDaemon = true
                start()
            }
    }

    /**
     * 停止监听。
     *
     * 先发送 `ssdp:byebye` 通告，再关闭套接字退出接收循环。
     */
    fun stop() {
        if (!running) return
        running = false
        socket?.let { sendNotify(it, alive = false) }
        socket?.close()
        socket = null
        workThread = null
    }

    private fun receiveLoop() {
        val multicastSocket =
            try {
                // 必须用 null 构造未绑定套接字：MulticastSocket() 会自动绑定到
                // 随机端口，随后再 bind 会抛 "already bound"
                MulticastSocket(null).apply {
                    reuseAddress = true
                    // 显式绑定 IPv4 任意地址，避免默认落到 IPv6-only 套接字
                    bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), SsdpMessages.PORT))
                    joinAllGroups()
                }
            } catch (e: IOException) {
                logger.warn(e) { "Unable to bind SSDP socket, device discovery disabled" }
                running = false
                return
            }

        socket = multicastSocket
        try {
            multicastSocket.soTimeout = RECEIVE_TIMEOUT_MS
            sendNotify(multicastSocket, alive = true)
        } catch (e: IOException) {
            logger.warn(e) { "Unable to send SSDP alive notify" }
        }

        var lastNotifyAt = System.currentTimeMillis()
        val buffer = ByteArray(BUFFER_SIZE)

        while (running) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                multicastSocket.receive(packet)
                val message = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                val target = SsdpMessages.parseSearchTarget(message)
                if (target != null && SsdpMessages.isSupportedTarget(target)) {
                    val response = buildSearchResponse(SsdpMessages.responseTarget(target))
                    sendTo(multicastSocket, response, packet.address, packet.port)
                }
            } catch (_: SocketTimeoutException) {
                val now = System.currentTimeMillis()
                if (now - lastNotifyAt >= NOTIFY_INTERVAL_MS) {
                    lastNotifyAt = now
                    try {
                        sendNotify(multicastSocket, alive = true)
                    } catch (e: IOException) {
                        if (!running) break
                        logger.warn(e) { "Unable to send SSDP alive notify" }
                    }
                }
            } catch (e: IOException) {
                if (!running) break
                logger.warn(e) { "SSDP receive failed" }
            }
        }

        multicastSocket.close()
        logger.info { "SSDP stopped" }
    }

    private fun buildSearchResponse(target: String): String =
        SsdpMessages.buildSearchResponse(
            location = location,
            udn = udn,
            st = target,
            server = server,
            date = httpDate(),
        )

    private fun sendNotify(
        multicastSocket: MulticastSocket,
        alive: Boolean,
    ) {
        val group = InetAddress.getByName(SsdpMessages.GROUP)
        SsdpMessages
            .notifyTargets()
            .forEach { target ->
                val message = SsdpMessages.buildNotify(location, udn, target, alive, server)
                sendTo(multicastSocket, message, group, SsdpMessages.PORT)
            }
    }

    private fun sendTo(
        multicastSocket: MulticastSocket,
        message: String,
        address: InetAddress,
        port: Int,
    ) {
        val bytes = message.toByteArray(Charsets.UTF_8)
        multicastSocket.send(DatagramPacket(bytes, bytes.size, address, port))
    }

    private fun MulticastSocket.joinAllGroups() {
        val group = InetAddress.getByName(SsdpMessages.GROUP)
        val groupAddress = InetSocketAddress(group, SsdpMessages.PORT)
        NetworkInterface
            .getNetworkInterfaces()
            ?.toList()
            ?.filter { it.isUp && !it.isLoopback && it.supportsMulticast() }
            ?.forEach { networkInterface ->
                try {
                    joinGroup(groupAddress, networkInterface)
                } catch (e: IOException) {
                    logger.warn(e) { "Join multicast group failed on ${networkInterface.name}" }
                }
            }
    }

    private fun httpDate(): String =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }
            .format(Date())

    private companion object {
        const val BUFFER_SIZE = 4096
        const val RECEIVE_TIMEOUT_MS = 2000
        const val NOTIFY_INTERVAL_MS = 30_000L
    }
}
