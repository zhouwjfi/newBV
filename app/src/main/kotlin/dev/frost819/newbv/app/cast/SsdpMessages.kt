package dev.frost819.newbv.app.cast

/**
 * SSDP 报文构造与解析工具。
 *
 * 纯函数实现，便于 JVM 单元测试。遵循 UPnP Device Architecture 1.0。
 */
object SsdpMessages {
    /** SSDP 组播地址。 */
    const val GROUP = "239.255.255.250"

    /** 私有发现端口，避开电视系统 DLNA 占用的 1900。 */
    const val PORT = 47823

    /** 通配搜索目标。 */
    const val SSDP_ALL = "ssdp:all"

    /** `CACHE-CONTROL max-age` 秒数。 */
    const val MAX_AGE_SECONDS = 1800

    private val SUPPORTED_TARGETS =
        listOf(
            CastSoap.DEVICE_TYPE,
            CastSoap.AV_TRANSPORT,
            CastSoap.RENDERING_CONTROL,
        )

    /**
     * 解析 M-SEARCH 请求的搜索目标（`ST` 头）。
     *
     * @param message 完整的 SSDP 报文。
     * @return `ST` 值；非 M-SEARCH 报文或无 `ST` 头时返回 null。
     */
    fun parseSearchTarget(message: String): String? {
        if (!message.startsWith("M-SEARCH", ignoreCase = true)) return null
        return headerValue(message, "ST")
    }

    /**
     * 判断搜索目标是否由本机设备可响应。
     *
     * @param st 搜索目标。
     */
    fun isSupportedTarget(st: String?): Boolean {
        if (st.isNullOrBlank()) return false
        if (st.equals(SSDP_ALL, ignoreCase = true)) return true
        return SUPPORTED_TARGETS.any { it.equals(st, ignoreCase = true) }
    }

    /**
     * 将搜索目标映射为响应中使用的 `ST`。
     *
     * `ssdp:all` 统一按设备类型应答，其余按请求值应答。
     *
     * @param st 请求的搜索目标。
     */
    fun responseTarget(st: String): String = if (st.equals(SSDP_ALL, ignoreCase = true)) CastSoap.DEVICE_TYPE else st

    /**
     * 构造 M-SEARCH 的 200 OK 应答。
     *
     * @param location 设备描述地址。
     * @param udn 设备唯一标识（`uuid:xxxx`）。
     * @param st 应答的搜索目标。
     * @param server SERVER 头内容。
     * @param date 日期头内容。
     */
    fun buildSearchResponse(
        location: String,
        udn: String,
        st: String,
        server: String,
        date: String,
    ): String =
        buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("CACHE-CONTROL: max-age=$MAX_AGE_SECONDS\r\n")
            append("DATE: $date\r\n")
            append("EXT:\r\n")
            append("LOCATION: $location\r\n")
            append("SERVER: $server\r\n")
            append("ST: $st\r\n")
            append("USN: $udn::$st\r\n")
            append("\r\n")
        }

    /**
     * 构造 NOTIFY 通告报文。
     *
     * @param location 设备描述地址。
     * @param udn 设备唯一标识。
     * @param nt 通知类型（设备类型或服务类型）。
     * @param alive true 表示 `ssdp:alive`，false 表示 `ssdp:byebye`。
     * @param server SERVER 头内容。
     */
    fun buildNotify(
        location: String,
        udn: String,
        nt: String,
        alive: Boolean,
        server: String,
    ): String =
        buildString {
            append("NOTIFY * HTTP/1.1\r\n")
            append("HOST: $GROUP:$PORT\r\n")
            append("CACHE-CONTROL: max-age=$MAX_AGE_SECONDS\r\n")
            append("LOCATION: $location\r\n")
            append("NT: $nt\r\n")
            append("NTS: ${if (alive) "ssdp:alive" else "ssdp:byebye"}\r\n")
            append("SERVER: $server\r\n")
            append("USN: $udn::$nt\r\n")
            append("\r\n")
        }

    /** 待通告的类型列表（设备类型 + 两个服务类型）。 */
    fun notifyTargets(): List<String> = SUPPORTED_TARGETS

    /** 提取报文的指定头部（大小写不敏感）。 */
    private fun headerValue(
        message: String,
        name: String,
    ): String? =
        message
            .lineSequence()
            .drop(1)
            .mapNotNull { line ->
                val index = line.indexOf(':')
                if (index <= 0) return@mapNotNull null
                val key = line.substring(0, index).trim()
                if (!key.equals(name, ignoreCase = true)) return@mapNotNull null
                line.substring(index + 1).trim()
            }.firstOrNull()
}
