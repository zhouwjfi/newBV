package dev.frost819.newbv.app.cast

import java.util.Locale

/**
 * DLNA/UPnP SOAP 报文与设备描述文档的生成/解析工具。
 *
 * 所有函数均为纯函数（不依赖 Android 环境），便于 JVM 单元测试。
 * 报文格式遵循 UPnP Device Architecture 1.0 / AVTransport 1。
 */
object CastSoap {
    /** AVTransport 服务类型。 */
    const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"

    /** RenderingControl 服务类型。 */
    const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"

    /** MediaRenderer 设备类型。 */
    const val DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1"

    /** AVTransport 服务 ID，发送端通过 `serviceId.contains("AVTransport")` 匹配控制地址。 */
    const val AV_TRANSPORT_SERVICE_ID = "urn:upnp-org:serviceId:AVTransport"

    /** RenderingControl 服务 ID。 */
    const val RENDERING_CONTROL_SERVICE_ID = "urn:upnp-org:serviceId:RenderingControl"

    private val ACTION_HEADER_REGEX = Regex("""<([A-Za-z0-9_]+)>([^<]*)</\1>""")
    private val BODY_ACTION_REGEX = Regex("""<u:([A-Za-z0-9_]+)""")
    private val TITLE_REGEX = Regex("""<dc:title>(.*?)</dc:title>""", RegexOption.DOT_MATCHES_ALL)

    /**
     * SOAPAction 头部解析结果。
     *
     * @param serviceType 服务类型，如 [AV_TRANSPORT]。
     * @param action 动作名，如 `SetAVTransportURI`。
     */
    data class SoapAction(
        val serviceType: String,
        val action: String,
    )

    /**
     * 解析 `SOAPAction` 请求头。
     *
     * 头部格式为 `"服务类型#动作名"`（外层引号可有可无）。
     *
     * @param header 请求头原文。
     * @return 解析结果，格式不合法时返回 null。
     */
    fun parseSoapAction(header: String?): SoapAction? {
        val raw = header?.trim()?.trim('"') ?: return null
        val index = raw.indexOf('#')
        if (index <= 0 || index == raw.length - 1) return null
        return SoapAction(
            serviceType = raw.substring(0, index).trim(),
            action = raw.substring(index + 1).trim(),
        )
    }

    /**
     * 从 SOAP 请求体解析动作名。
     *
     * 作为 `SOAPAction` 头缺失时的兜底：提取 `<u:动作名 ...>` 中的动作名。
     *
     * @param body 请求体 XML。
     * @return 动作名，未找到返回 null。
     */
    fun parseActionFromBody(body: String): String? =
        BODY_ACTION_REGEX
            .find(body)
            ?.groupValues
            ?.getOrNull(1)

    /**
     * 解析 SOAP 请求体中的参数。
     *
     * 只提取叶子节点文本，并对 XML 实体做反转义。
     * 发送端的 DIDL-Lite 元数据是按实体转义后传输的，因此不会干扰叶子节点匹配。
     *
     * @param body 请求体 XML。
     * @return 参数名到参数值的映射。
     */
    fun parseArguments(body: String): Map<String, String> =
        ACTION_HEADER_REGEX
            .findAll(body)
            .associate { match ->
                match.groupValues[1] to unescape(match.groupValues[2].trim())
            }

    /**
     * 生成 SOAP 动作响应报文。
     *
     * @param serviceType 服务类型。
     * @param action 动作名。
     * @param arguments 响应参数（名称与值），按顺序输出。
     */
    fun renderResponse(
        serviceType: String,
        action: String,
        arguments: List<Pair<String, String>> = emptyList(),
    ): String {
        val body =
            arguments.joinToString("\n") { (name, value) ->
                "<$name>${escape(value)}</$name>"
            }
        return """
            |<?xml version="1.0" encoding="utf-8"?>
            |<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
            |<s:Body>
            |<u:$action xmlns:u="$serviceType">
            |$body
            |</u:$action>
            |</s:Body>
            |</s:Envelope>
            """.trimMargin()
    }

    /**
     * 生成 UPnP 错误响应报文。
     *
     * @param errorCode UPnP 错误码（如 401 无效动作）。
     * @param errorDescription 错误描述。
     */
    fun renderFault(
        errorCode: Int,
        errorDescription: String,
    ): String =
        """
        |<?xml version="1.0" encoding="utf-8"?>
        |<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
        |<s:Body>
        |<s:Fault>
        |<faultcode>s:Client</faultcode>
        |<faultstring>UPnPError</faultstring>
        |<detail>
        |<UPnPError xmlns="urn:schemas-upnp-org:control-1-0">
        |<errorCode>$errorCode</errorCode>
        |<errorDescription>${escape(errorDescription)}</errorDescription>
        |</UPnPError>
        |</detail>
        |</s:Fault>
        |</s:Body>
        |</s:Envelope>
        """.trimMargin()

    /**
     * 生成设备描述文档（`description.xml`）。
     *
     * @param friendlyName 设备显示名。
     * @param udn 设备唯一标识，如 `uuid:xxxx`。
     * @param urlBase 设备根地址，如 `http://192.168.1.10:8080`。
     */
    fun renderDescription(
        friendlyName: String,
        udn: String,
        urlBase: String,
    ): String =
        """
        |<?xml version="1.0" encoding="utf-8"?>
        |<root xmlns="urn:schemas-upnp-org:device-1-0">
        |<specVersion><major>1</major><minor>0</minor></specVersion>
        |<URLBase>${escape(urlBase)}</URLBase>
        |<device>
        |<deviceType>$DEVICE_TYPE</deviceType>
        |<friendlyName>${escape(friendlyName)}</friendlyName>
        |<manufacturer>newBV</manufacturer>
        |<manufacturerURL>https://github.com/Frost819/newBV</manufacturerURL>
        |<modelName>newBV DLNA Renderer</modelName>
        |<modelNumber>1.0</modelNumber>
        |<UDN>${escape(udn)}</UDN>
        |<serviceList>
        |<service>
        |<serviceType>$AV_TRANSPORT</serviceType>
        |<serviceId>$AV_TRANSPORT_SERVICE_ID</serviceId>
        |<controlURL>/control/avtransport</controlURL>
        |<eventSubURL>/event/avtransport</eventSubURL>
        |<SCPDURL>/scpd/avtransport.xml</SCPDURL>
        |</service>
        |<service>
        |<serviceType>$RENDERING_CONTROL</serviceType>
        |<serviceId>$RENDERING_CONTROL_SERVICE_ID</serviceId>
        |<controlURL>/control/renderingcontrol</controlURL>
        |<eventSubURL>/event/renderingcontrol</eventSubURL>
        |<SCPDURL>/scpd/renderingcontrol.xml</SCPDURL>
        |</service>
        |</serviceList>
        |</device>
        |</root>
        """.trimMargin()

    /**
     * 生成 AVTransport 服务描述（SCPD）。
     *
     * 提供发送端可能查询的动作与状态变量，内容遵循 AVTransport 1 规范的最小子集。
     */
    fun renderAvTransportScpd(): String =
        """
        |<?xml version="1.0" encoding="utf-8"?>
        |<scpd xmlns="urn:schemas-upnp-org:service-1-0">
        |<specVersion><major>1</major><minor>0</minor></specVersion>
        |<actionList>
        |<action><name>SetAVTransportURI</name><argumentList>
        |<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        |<argument><name>CurrentURI</name><direction>in</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>
        |<argument><name>CurrentURIMetaData</name><direction>in</direction><relatedStateVariable>AVTransportURIMetaData</relatedStateVariable></argument>
        |</argumentList></action>
        |<action><name>SetNextAVTransportURI</name><argumentList>
        |<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        |<argument><name>NextURI</name><direction>in</direction><relatedStateVariable>NextAVTransportURI</relatedStateVariable></argument>
        |<argument><name>NextURIMetaData</name><direction>in</direction><relatedStateVariable>NextAVTransportURIMetaData</relatedStateVariable></argument>
        |</argumentList></action>
        |<action><name>Play</name><argumentList>
        |<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        |<argument><name>Speed</name><direction>in</direction><relatedStateVariable>TransportPlaySpeed</relatedStateVariable></argument>
        |</argumentList></action>
        |<action><name>Pause</name><argumentList>
        |<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        |</argumentList></action>
        |<action><name>Stop</name><argumentList>
        |<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        |</argumentList></action>
        |<action><name>Seek</name><argumentList>
        |<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        |<argument><name>Unit</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SeekMode</relatedStateVariable></argument>
        |<argument><name>Target</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SeekTarget</relatedStateVariable></argument>
        |</argumentList></action>
        |<action><name>GetTransportInfo</name><argumentList>
        |<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        |<argument><name>CurrentTransportState</name><direction>out</direction><relatedStateVariable>TransportState</relatedStateVariable></argument>
        |<argument><name>CurrentTransportStatus</name><direction>out</direction><relatedStateVariable>TransportStatus</relatedStateVariable></argument>
        |<argument><name>CurrentSpeed</name><direction>out</direction><relatedStateVariable>TransportPlaySpeed</relatedStateVariable></argument>
        |</argumentList></action>
        |<action><name>GetPositionInfo</name><argumentList>
        |<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        |<argument><name>Track</name><direction>out</direction><relatedStateVariable>CurrentTrack</relatedStateVariable></argument>
        |<argument><name>TrackDuration</name><direction>out</direction><relatedStateVariable>CurrentTrackDuration</relatedStateVariable></argument>
        |<argument><name>TrackMetaData</name><direction>out</direction><relatedStateVariable>CurrentTrackMetaData</relatedStateVariable></argument>
        |<argument><name>TrackURI</name><direction>out</direction><relatedStateVariable>CurrentTrackURI</relatedStateVariable></argument>
        |<argument><name>RelTime</name><direction>out</direction><relatedStateVariable>RelativeTimePosition</relatedStateVariable></argument>
        |<argument><name>AbsTime</name><direction>out</direction><relatedStateVariable>AbsoluteTimePosition</relatedStateVariable></argument>
        |<argument><name>RelCount</name><direction>out</direction><relatedStateVariable>RelativeCounterPosition</relatedStateVariable></argument>
        |<argument><name>AbsCount</name><direction>out</direction><relatedStateVariable>AbsoluteCounterPosition</relatedStateVariable></argument>
        |</argumentList></action>
        |<action><name>GetMediaInfo</name><argumentList>
        |<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        |<argument><name>NrTracks</name><direction>out</direction><relatedStateVariable>NumberOfTracks</relatedStateVariable></argument>
        |<argument><name>MediaDuration</name><direction>out</direction><relatedStateVariable>CurrentMediaDuration</relatedStateVariable></argument>
        |<argument><name>CurrentURI</name><direction>out</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>
        |<argument><name>CurrentURIMetaData</name><direction>out</direction><relatedStateVariable>AVTransportURIMetaData</relatedStateVariable></argument>
        |<argument><name>NextURI</name><direction>out</direction><relatedStateVariable>NextAVTransportURI</relatedStateVariable></argument>
        |<argument><name>NextURIMetaData</name><direction>out</direction><relatedStateVariable>NextAVTransportURIMetaData</relatedStateVariable></argument>
        |<argument><name>PlayMedium</name><direction>out</direction><relatedStateVariable>PlaybackStorageMedium</relatedStateVariable></argument>
        |<argument><name>RecordMedium</name><direction>out</direction><relatedStateVariable>RecordStorageMedium</relatedStateVariable></argument>
        |<argument><name>WriteStatus</name><direction>out</direction><relatedStateVariable>RecordMediumWriteStatus</relatedStateVariable></argument>
        |</argumentList></action>
        |<action><name>GetTransportSettings</name><argumentList>
        |<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        |<argument><name>PlayMode</name><direction>out</direction><relatedStateVariable>CurrentPlayMode</relatedStateVariable></argument>
        |<argument><name>RecQualityMode</name><direction>out</direction><relatedStateVariable>CurrentRecordQualityMode</relatedStateVariable></argument>
        |</argumentList></action>
        |<action><name>GetDeviceCapabilities</name><argumentList>
        |<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        |<argument><name>PlayMedia</name><direction>out</direction><relatedStateVariable>PossiblePlaybackStorageMedia</relatedStateVariable></argument>
        |<argument><name>RecMedia</name><direction>out</direction><relatedStateVariable>PossibleRecordStorageMedia</relatedStateVariable></argument>
        |<argument><name>RecQualityModes</name><direction>out</direction><relatedStateVariable>PossibleRecordQualityModes</relatedStateVariable></argument>
        |</argumentList></action>
        |<action><name>GetCurrentTransportActions</name><argumentList>
        |<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        |<argument><name>Actions</name><direction>out</direction><relatedStateVariable>CurrentTransportActions</relatedStateVariable></argument>
        |</argumentList></action>
        |</actionList>
        |<serviceStateTable>
        |<stateVariable sendEvents="no"><name>A_ARG_TYPE_InstanceID</name><dataType>ui4</dataType></stateVariable>
        |<stateVariable sendEvents="no"><name>A_ARG_TYPE_SeekMode</name><dataType>string</dataType><allowedValueList><allowedValue>REL_TIME</allowedValue><allowedValue>ABS_TIME</allowedValue><allowedValue>TRACK_NR</allowedValue></allowedValueList></stateVariable>
        |<stateVariable sendEvents="no"><name>A_ARG_TYPE_SeekTarget</name><dataType>string</dataType></stateVariable>
        |<stateVariable sendEvents="no"><name>TransportState</name><dataType>string</dataType><allowedValueList><allowedValue>STOPPED</allowedValue><allowedValue>PLAYING</allowedValue><allowedValue>PAUSED_PLAYBACK</allowedValue><allowedValue>TRANSITIONING</allowedValue><allowedValue>NO_MEDIA_PRESENT</allowedValue></allowedValueList></stateVariable>
        |<stateVariable sendEvents="no"><name>TransportStatus</name><dataType>string</dataType><allowedValueList><allowedValue>OK</allowedValue><allowedValue>ERROR_OCCURRED</allowedValue></allowedValueList></stateVariable>
        |<stateVariable sendEvents="no"><name>TransportPlaySpeed</name><dataType>string</dataType><allowedValueList><allowedValue>1</allowedValue></allowedValueList></stateVariable>
        |<stateVariable sendEvents="no"><name>CurrentTrack</name><dataType>ui4</dataType></stateVariable>
        |<stateVariable sendEvents="no"><name>CurrentTrackDuration</name><dataType>string</dataType></stateVariable>
        |<stateVariable sendEvents="no"><name>CurrentTrackMetaData</name><dataType>string</dataType></stateVariable>
        |<stateVariable sendEvents="no"><name>CurrentTrackURI</name><dataType>string</dataType></stateVariable>
        |<stateVariable sendEvents="no"><name>RelativeTimePosition</name><dataType>string</dataType></stateVariable>
        |<stateVariable sendEvents="no"><name>AbsoluteTimePosition</name><dataType>string</dataType></stateVariable>
        |<stateVariable sendEvents="no"><name>RelativeCounterPosition</name><dataType>i4</dataType></stateVariable>
        |<stateVariable sendEvents="no"><name>AbsoluteCounterPosition</name><dataType>i4</dataType></stateVariable>
        |<stateVariable sendEvents="no"><name>CurrentMediaDuration</name><dataType>string</dataType></stateVariable>
        |<stateVariable sendEvents="no"><name>AVTransportURI</name><dataType>string</dataType></stateVariable>
        |<stateVariable sendEvents="no"><name>AVTransportURIMetaData</name><dataType>string</dataType></stateVariable>
        |<stateVariable sendEvents="no"><name>NextAVTransportURI</name><dataType>string</dataType></stateVariable>
        |<stateVariable sendEvents="no"><name>NextAVTransportURIMetaData</name><dataType>string</dataType></stateVariable>
        |<stateVariable sendEvents="no"><name>NumberOfTracks</name><dataType>ui4</dataType></stateVariable>
        |<stateVariable sendEvents="no"><name>PlaybackStorageMedium</name><dataType>string</dataType><allowedValueList><allowedValue>NONE</allowedValue><allowedValue>NETWORK</allowedValue></allowedValueList></stateVariable>
        |<stateVariable sendEvents="no"><name>RecordStorageMedium</name><dataType>string</dataType><allowedValueList><allowedValue>NOT_IMPLEMENTED</allowedValue></allowedValueList></stateVariable>
        |<stateVariable sendEvents="no"><name>RecordMediumWriteStatus</name><dataType>string</dataType><allowedValueList><allowedValue>NOT_IMPLEMENTED</allowedValue></allowedValueList></stateVariable>
        |<stateVariable sendEvents="no"><name>CurrentPlayMode</name><dataType>string</dataType><allowedValueList><allowedValue>NORMAL</allowedValue></allowedValueList></stateVariable>
        |<stateVariable sendEvents="no"><name>CurrentRecordQualityMode</name><dataType>string</dataType><allowedValueList><allowedValue>NOT_IMPLEMENTED</allowedValue></allowedValueList></stateVariable>
        |<stateVariable sendEvents="no"><name>PossiblePlaybackStorageMedia</name><dataType>string</dataType></stateVariable>
        |<stateVariable sendEvents="no"><name>PossibleRecordStorageMedia</name><dataType>string</dataType></stateVariable>
        |<stateVariable sendEvents="no"><name>PossibleRecordQualityModes</name><dataType>string</dataType></stateVariable>
        |<stateVariable sendEvents="no"><name>CurrentTransportActions</name><dataType>string</dataType></stateVariable>
        |</serviceStateTable>
        |</scpd>
        """.trimMargin()

    /**
     * 生成 RenderingControl 服务描述（SCPD）。
     */
    fun renderRenderingControlScpd(): String =
        """
        |<?xml version="1.0" encoding="utf-8"?>
        |<scpd xmlns="urn:schemas-upnp-org:service-1-0">
        |<specVersion><major>1</major><minor>0</minor></specVersion>
        |<actionList>
        |<action><name>GetVolume</name><argumentList>
        |<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        |<argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>
        |<argument><name>CurrentVolume</name><direction>out</direction><relatedStateVariable>Volume</relatedStateVariable></argument>
        |</argumentList></action>
        |<action><name>SetVolume</name><argumentList>
        |<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        |<argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>
        |<argument><name>DesiredVolume</name><direction>in</direction><relatedStateVariable>Volume</relatedStateVariable></argument>
        |</argumentList></action>
        |<action><name>GetMute</name><argumentList>
        |<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        |<argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>
        |<argument><name>CurrentMute</name><direction>out</direction><relatedStateVariable>Mute</relatedStateVariable></argument>
        |</argumentList></action>
        |<action><name>SetMute</name><argumentList>
        |<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        |<argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>
        |<argument><name>DesiredMute</name><direction>in</direction><relatedStateVariable>Mute</relatedStateVariable></argument>
        |</argumentList></action>
        |</actionList>
        |<serviceStateTable>
        |<stateVariable sendEvents="no"><name>A_ARG_TYPE_InstanceID</name><dataType>ui4</dataType></stateVariable>
        |<stateVariable sendEvents="no"><name>A_ARG_TYPE_Channel</name><dataType>string</dataType><allowedValueList><allowedValue>Master</allowedValue></allowedValueList></stateVariable>
        |<stateVariable sendEvents="no"><name>Volume</name><dataType>ui2</dataType><allowedValueRange><minimum>0</minimum><maximum>100</maximum><step>1</step></allowedValueRange></stateVariable>
        |<stateVariable sendEvents="no"><name>Mute</name><dataType>boolean</dataType></stateVariable>
        |</serviceStateTable>
        |</scpd>
        """.trimMargin()

    /**
     * 将毫秒格式化为 UPnP 要求的 `HH:MM:SS`。
     *
     * @param positionMs 毫秒，负数按 0 处理。
     */
    fun formatDuration(positionMs: Long): String {
        val totalSeconds = (positionMs.coerceAtLeast(0L)) / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    /**
     * 解析 UPnP 时间格式 `HH:MM:SS[.fff]`。
     *
     * 秒部分允许小数，`Seek` 指令常见该格式。
     *
     * @param value 时间字符串。
     * @return 毫秒数，格式不合法返回 null。
     */
    fun parseDuration(value: String?): Long? {
        val parts = value?.trim()?.split(':') ?: return null
        if (parts.size != 3) return null
        val hours = parts[0].toLongOrNull() ?: return null
        val minutes = parts[1].toLongOrNull() ?: return null
        val seconds = parts[2].toDoubleOrNull() ?: return null
        return hours * 3_600_000 + minutes * 60_000 + (seconds * 1000).toLong()
    }

    /**
     * 从 DIDL-Lite 元数据中提取 `dc:title`。
     *
     * @param metadata 原始元数据（可能为空或不是 DIDL）。
     * @return 标题，未找到返回空字符串。
     */
    fun extractTitle(metadata: String?): String {
        if (metadata.isNullOrBlank()) return ""
        val title = TITLE_REGEX.find(metadata)?.groupValues?.getOrNull(1) ?: return ""
        return unescape(title.trim())
    }

    /** PiliNara 扩展元数据命名空间，携带视频标识用于加载完整播放能力。 */
    const val PILI_NAMESPACE = "urn:pilinara:cast:1"

    /**
     * 读取 PiliNara 扩展元数据中的字段（如 `aid`、`cid`、`bvid`）。
     *
     * @param metadata 发送端传入的 DIDL-Lite 元数据。
     * @param tag 字段名，对应 `<pili:tag>` 元素。
     * @return 字段文本，缺失时返回空串。
     */
    fun extractPiliMeta(metadata: String?, tag: String): String {
        if (metadata.isNullOrBlank()) return ""
        val regex = Regex("""<pili:$tag>(.*?)</pili:$tag>""", RegexOption.DOT_MATCHES_ALL)
        val value = regex.find(metadata)?.groupValues?.getOrNull(1) ?: return ""
        return unescape(value.trim())
    }

    /**
     * 转义 XML 特殊字符。
     *
     * @param value 原始文本。
     */
    fun escape(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    /**
     * 反转义 XML 实体。
     *
     * `&amp;` 必须最后替换，避免二次解码。
     *
     * @param value 含实体编码的文本。
     */
    fun unescape(value: String): String =
        value
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&#10;", "\n")
            .replace("&#13;", "\r")
            .replace("&#xA;", "\n")
            .replace("&amp;", "&")
}
