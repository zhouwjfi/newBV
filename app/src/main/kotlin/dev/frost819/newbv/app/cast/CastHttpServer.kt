package dev.frost819.newbv.app.cast

import dev.frost819.newbv.core.log.Loggers
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking

/**
 * 投屏 HTTP 服务的业务处理接口。
 *
 * 由 [CastReceiverController] 实现，[CastHttpServer] 只负责 HTTP 路由与报文收发。
 */
interface CastSoapHandler {
    /**
     * 生成设备描述文档。
     *
     * @param location 设备根地址（`http://ip:port`）。
     */
    fun descriptionXml(location: String): String

    /**
     * 生成指定服务的 SCPD 描述。
     *
     * @param serviceType 服务类型。
     * @return SCPD 内容，服务不支持时返回 null。
     */
    fun scpdXml(serviceType: String): String?

    /**
     * 处理 SOAP 控制请求。
     *
     * @param serviceType 服务类型。
     * @param soapAction `SOAPAction` 请求头。
     * @param body 请求体 XML。
     * @return SOAP 响应 XML。
     */
    fun handleSoap(
        serviceType: String,
        soapAction: String?,
        body: String,
    ): String
}

/**
 * 投屏 HTTP 服务。
 *
 * 使用 Ktor CIO 引擎在随机端口启动，对外提供：
 * - `GET /description.xml` — 设备描述
 * - `GET /scpd/avtransport.xml` / `GET /scpd/renderingcontrol.xml` — 服务描述
 * - `POST /control/avtransport` / `POST /control/renderingcontrol` — SOAP 控制
 *
 * @param handler 业务处理器。
 */
class CastHttpServer(
    private val locationProvider: () -> String?,
    private val handler: CastSoapHandler,
) {
    private val logger = Loggers.get("CastHttpServer")

    @Volatile
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    @Volatile
    private var resolvedPort: Int? = null

    /**
     * 启动 HTTP 服务。
     *
     * @return 实际监听端口，启动失败返回 null。
     */
    fun start(): Int? {
        if (server != null) return resolvedPort
        return try {
            val embedded =
                embeddedServer(CIO, port = 0) {
                    configureRoutes()
                }.also { it.start(wait = false) }
            server = embedded
            resolvedPort =
                runBlocking {
                    embedded.engine.resolvedConnectors().firstOrNull()?.port
                }
            logger.info { "Cast HTTP server started on port $resolvedPort" }
            resolvedPort
        } catch (e: Exception) {
            logger.error(e) { "Cast HTTP server start failed" }
            server = null
            resolvedPort = null
            null
        }
    }

    /** 停止 HTTP 服务。 */
    fun stop() {
        server?.stop(gracePeriodMillis = 1000, timeoutMillis = 2000)
        server = null
        resolvedPort = null
        logger.info { "Cast HTTP server stopped" }
    }

    private fun Application.configureRoutes() {
        routing {
            descriptionRoute()
            scpdRoute()
            avTransportControlRoute()
            renderingControlControlRoute()
        }
    }

    private fun Route.descriptionRoute() {
        get("/description.xml") {
            val location = locationProvider().orEmpty()
            call.respondText(
                text = handler.descriptionXml(location),
                contentType = ContentType.Text.Xml.withCharset(Charsets.UTF_8),
            )
        }
    }

    private fun Route.scpdRoute() {
        get("/scpd/avtransport.xml") {
            val xml = handler.scpdXml(CastSoap.AV_TRANSPORT)
            if (xml == null) {
                call.respondText("not found", status = HttpStatusCode.NotFound)
            } else {
                call.respondText(xml, ContentType.Text.Xml.withCharset(Charsets.UTF_8))
            }
        }
        get("/scpd/renderingcontrol.xml") {
            val xml = handler.scpdXml(CastSoap.RENDERING_CONTROL)
            if (xml == null) {
                call.respondText("not found", status = HttpStatusCode.NotFound)
            } else {
                call.respondText(xml, ContentType.Text.Xml.withCharset(Charsets.UTF_8))
            }
        }
    }

    private fun Route.avTransportControlRoute() {
        post("/control/avtransport") {
            val body = call.receiveText()
            val soapAction = call.request.headers["SOAPAction"]
            val response = handler.handleSoap(CastSoap.AV_TRANSPORT, soapAction, body)
            call.respondText(
                text = response,
                contentType = ContentType.Text.Xml.withCharset(Charsets.UTF_8),
                status = HttpStatusCode.OK,
            )
        }
    }

    private fun Route.renderingControlControlRoute() {
        post("/control/renderingcontrol") {
            val body = call.receiveText()
            val soapAction = call.request.headers["SOAPAction"]
            val response = handler.handleSoap(CastSoap.RENDERING_CONTROL, soapAction, body)
            call.respondText(
                text = response,
                contentType = ContentType.Text.Xml.withCharset(Charsets.UTF_8),
                status = HttpStatusCode.OK,
            )
        }
    }
}
