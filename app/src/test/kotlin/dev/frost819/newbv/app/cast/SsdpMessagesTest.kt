package dev.frost819.newbv.app.cast

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * [SsdpMessages] 的 JVM 单元测试。
 */
class SsdpMessagesTest {
    @Test
    fun `parseSearchTarget reads ST from M-SEARCH`() {
        val message =
            """
            M-SEARCH * HTTP/1.1
            HOST: 239.255.255.250:1900
            MAN: "ssdp:discover"
            MX: 3
            ST: urn:schemas-upnp-org:device:MediaRenderer:1
            """.trimIndent()

        assertThat(SsdpMessages.parseSearchTarget(message)).isEqualTo(CastSoap.DEVICE_TYPE)
    }

    @Test
    fun `parseSearchTarget ignores non search messages`() {
        val notify = "NOTIFY * HTTP/1.1\r\nNT: upnp:rootdevice\r\nNTS: ssdp:alive\r\n"

        assertThat(SsdpMessages.parseSearchTarget(notify)).isNull()
    }

    @Test
    fun `isSupportedTarget accepts device and service targets`() {
        assertThat(SsdpMessages.isSupportedTarget("ssdp:all")).isTrue()
        assertThat(SsdpMessages.isSupportedTarget(CastSoap.DEVICE_TYPE)).isTrue()
        assertThat(SsdpMessages.isSupportedTarget(CastSoap.AV_TRANSPORT)).isTrue()
        assertThat(SsdpMessages.isSupportedTarget("urn:schemas-upnp-org:service:ContentDirectory:1")).isFalse()
        assertThat(SsdpMessages.isSupportedTarget(null)).isFalse()
    }

    @Test
    fun `responseTarget maps ssdp all to device type`() {
        assertThat(SsdpMessages.responseTarget("ssdp:all")).isEqualTo(CastSoap.DEVICE_TYPE)
        assertThat(SsdpMessages.responseTarget(CastSoap.AV_TRANSPORT)).isEqualTo(CastSoap.AV_TRANSPORT)
    }

    @Test
    fun `buildSearchResponse contains required headers`() {
        val response =
            SsdpMessages.buildSearchResponse(
                location = "http://192.168.1.10:8080/description.xml",
                udn = "uuid:1234",
                st = CastSoap.DEVICE_TYPE,
                server = "Android/1.0 UPnP/1.0 newBV/1.0",
                date = "Thu, 01 Jan 2026 00:00:00 GMT",
            )

        assertThat(response).startsWith("HTTP/1.1 200 OK")
        assertThat(response).contains("LOCATION: http://192.168.1.10:8080/description.xml")
        assertThat(response).contains("ST: ${CastSoap.DEVICE_TYPE}")
        assertThat(response).contains("USN: uuid:1234::${CastSoap.DEVICE_TYPE}")
    }

    @Test
    fun `buildNotify switches NTS`() {
        val alive = SsdpMessages.buildNotify("http://host/description.xml", "uuid:1", CastSoap.DEVICE_TYPE, true, "s")
        val byebye = SsdpMessages.buildNotify("http://host/description.xml", "uuid:1", CastSoap.DEVICE_TYPE, false, "s")

        assertThat(alive).contains("NTS: ssdp:alive")
        assertThat(byebye).contains("NTS: ssdp:byebye")
        assertThat(byebye).startsWith("NOTIFY * HTTP/1.1")
    }
}
