package dev.frost819.newbv.app.cast

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * [CastSoap] 的 JVM 单元测试。
 */
class CastSoapTest {
    @Test
    fun `parseSoapAction parses quoted header`() {
        val action = CastSoap.parseSoapAction("\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"")

        assertThat(action).isNotNull()
        assertThat(action?.serviceType).isEqualTo(CastSoap.AV_TRANSPORT)
        assertThat(action?.action).isEqualTo("SetAVTransportURI")
    }

    @Test
    fun `parseSoapAction returns null for malformed header`() {
        assertThat(CastSoap.parseSoapAction(null)).isNull()
        assertThat(CastSoap.parseSoapAction("SetAVTransportURI")).isNull()
        assertThat(CastSoap.parseSoapAction("urn:test#")).isNull()
    }

    @Test
    fun `parseActionFromBody extracts action name`() {
        val body = "<s:Body><u:Play xmlns:u=\"${CastSoap.AV_TRANSPORT}\"><InstanceID>0</InstanceID></u:Play></s:Body>"

        assertThat(CastSoap.parseActionFromBody(body)).isEqualTo("Play")
    }

    @Test
    fun `parseArguments decodes entities`() {
        val body =
            """
            <s:Body>
            <u:SetAVTransportURI xmlns:u="${CastSoap.AV_TRANSPORT}">
            <InstanceID>0</InstanceID>
            <CurrentURI>http://example.com/video.mp4?a=1&amp;b=2</CurrentURI>
            <CurrentURIMetaData>&lt;DIDL-Lite&gt;&lt;item&gt;&lt;dc:title&gt;测试视频&lt;/dc:title&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;</CurrentURIMetaData>
            </u:SetAVTransportURI>
            </s:Body>
            """.trimIndent()

        val args = CastSoap.parseArguments(body)

        assertThat(args["InstanceID"]).isEqualTo("0")
        assertThat(args["CurrentURI"]).isEqualTo("http://example.com/video.mp4?a=1&b=2")
        assertThat(args["CurrentURIMetaData"]).contains("<dc:title>测试视频</dc:title>")
    }

    @Test
    fun `extractTitle reads dc title`() {
        val metadata = "<DIDL-Lite><item><dc:title>标题 &amp; 副标题</dc:title></item></DIDL-Lite>"

        assertThat(CastSoap.extractTitle(metadata)).isEqualTo("标题 & 副标题")
        assertThat(CastSoap.extractTitle(null)).isEmpty()
        assertThat(CastSoap.extractTitle("not didl")).isEmpty()
    }

    @Test
    fun `renderResponse escapes values`() {
        val xml =
            CastSoap.renderResponse(
                CastSoap.AV_TRANSPORT,
                "GetPositionInfo",
                listOf("TrackURI" to "http://example.com/a?x=1&y=<2>"),
            )

        assertThat(xml).contains("<u:GetPositionInfo xmlns:u=\"${CastSoap.AV_TRANSPORT}\">")
        assertThat(xml).contains("http://example.com/a?x=1&amp;y=&lt;2&gt;")
        assertThat(xml).contains("</s:Envelope>")
    }

    @Test
    fun `renderFault contains error code`() {
        val xml = CastSoap.renderFault(401, "Invalid Action")

        assertThat(xml).contains("<errorCode>401</errorCode>")
        assertThat(xml).contains("<errorDescription>Invalid Action</errorDescription>")
    }

    @Test
    fun `renderDescription exposes both services`() {
        val xml =
            CastSoap.renderDescription(
                friendlyName = "newBV",
                udn = "uuid:0000",
                urlBase = "http://192.168.1.10:8080",
            )

        assertThat(xml).contains("<deviceType>${CastSoap.DEVICE_TYPE}</deviceType>")
        assertThat(xml).contains("<serviceId>${CastSoap.AV_TRANSPORT_SERVICE_ID}</serviceId>")
        assertThat(xml).contains("<controlURL>/control/avtransport</controlURL>")
        assertThat(xml).contains("<serviceId>${CastSoap.RENDERING_CONTROL_SERVICE_ID}</serviceId>")
        assertThat(xml).contains("<controlURL>/control/renderingcontrol</controlURL>")
        assertThat(xml).contains("<URLBase>http://192.168.1.10:8080</URLBase>")
    }

    @Test
    fun `scpd documents contain required actions`() {
        assertThat(CastSoap.renderAvTransportScpd()).contains("<name>SetAVTransportURI</name>")
        assertThat(CastSoap.renderAvTransportScpd()).contains("<name>GetPositionInfo</name>")
        assertThat(CastSoap.renderRenderingControlScpd()).contains("<name>SetVolume</name>")
        assertThat(CastSoap.renderRenderingControlScpd()).contains("<name>GetMute</name>")
    }

    @Test
    fun `formatDuration produces upnp time`() {
        assertThat(CastSoap.formatDuration(0)).isEqualTo("00:00:00")
        assertThat(CastSoap.formatDuration(3_723_000)).isEqualTo("01:02:03")
    }

    @Test
    fun `parseDuration parses upnp time`() {
        assertThat(CastSoap.parseDuration("01:02:03")).isEqualTo(3_723_000)
        assertThat(CastSoap.parseDuration("00:00:02.500")).isEqualTo(2_500)
        assertThat(CastSoap.parseDuration("bad")).isNull()
    }
}
