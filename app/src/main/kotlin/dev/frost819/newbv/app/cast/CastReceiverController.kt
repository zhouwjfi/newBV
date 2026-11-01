package dev.frost819.newbv.app.cast

import android.content.Context
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.frost819.newbv.core.log.Loggers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DLNA 投屏接收控制器。
 *
 * 负责：
 * - 启动/停止 SSDP 发现服务与投屏 HTTP 服务
 * - 维护当前投屏会话状态（[session]）
 * - 处理 AVTransport / RenderingControl 的 SOAP 控制指令
 * - 产生待打开播放页的请求（[pendingLaunch]）与播放器控制指令（[commands]）
 *
 * 播放器侧通过 [attachPlayer] / [detachPlayer] / [updatePlayback] 与控制器同步。
 */
@Singleton
class CastReceiverController
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : CastSoapHandler {
        private val logger = Loggers.get("CastReceiver")

        private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private val lock = Any()

        /** 当前投屏会话，UI 与 SOAP 查询均读取该状态。 */
        private val _session = MutableStateFlow(CastSession())

        val session: StateFlow<CastSession> = _session.asStateFlow()

        /** 待打开的播放页请求，UI 消费后调用 [consumePendingLaunch] 清空。 */
        private val _pendingLaunch = MutableStateFlow<CastLaunch?>(null)

        val pendingLaunch: StateFlow<CastLaunch?> = _pendingLaunch.asStateFlow()

        /** 播放器控制指令流，投屏播放页订阅后施加到播放器。 */
        private val _commands = MutableSharedFlow<CastPlayerCommand>(extraBufferCapacity = 8)

        val commands: SharedFlow<CastPlayerCommand> = _commands.asSharedFlow()

        /** 设备唯一标识，由 ANDROID_ID 派生，保证同一安装稳定不变。 */
        private val udn: String by lazy {
            val androidId =
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                    ?: Build.MODEL
            val uuid = UUID.nameUUIDFromBytes("newBV-$androidId".toByteArray())
            "uuid:$uuid"
        }

        /** 设备显示名。 */
        private val friendlyName: String = "newBV (${Build.MODEL})"

        private val httpServer by lazy { CastHttpServer(locationProvider = { location }, handler = this) }

        @Volatile
        private var location: String? = null

        @Volatile
        private var started = false

        private var startJob: Job? = null

        private var ssdpServer: SsdpServer? = null

        private var multicastLock: WifiManager.MulticastLock? = null

        private var currentSessionId = 0L

        /** 已经触发过页面跳转的会话序号，避免重复导航。 */
        private var launchedSessionId = -1L

        @Volatile
        private var playerAttached = false

        /**
         * 启动投屏接收服务。
         *
         * 在后台等待网络就绪后启动 HTTP 服务与 SSDP 监听；重复调用安全。
         */
        fun start() {
            if (started) return
            started = true
            startJob =
                scope.launch {
                    val ip = awaitLocalIp()
                    if (ip == null) {
                        logger.warn { "No local IPv4 address, cast receiver not started" }
                        started = false
                        return@launch
                    }
                    val port = httpServer.start()
                    if (port == null) {
                        logger.warn { "Cast HTTP server not started" }
                        started = false
                        return@launch
                    }
                    location = "http://$ip:$port"
                    acquireMulticastLock()
                    ssdpServer =
                        SsdpServer(location = "$location/description.xml", udn = udn, server = SERVER).also {
                            it.start()
                        }
                    logger.info { "Cast receiver ready at $location" }
                }
        }

        /** 停止投屏接收服务并释放组播锁。 */
        fun stop() {
            if (!started) return
            started = false
            startJob?.cancel()
            startJob = null
            ssdpServer?.stop()
            ssdpServer = null
            httpServer.stop()
            releaseMulticastLock()
            location = null
        }

        /**
         * 标记播放器已就绪。
         *
         * 此后收到的播放控制指令会通过 [commands] 下发给播放器。
         */
        fun attachPlayer() {
            playerAttached = true
        }

        /**
         * 标记播放器已释放。
         *
         * 若会话仍处于播放/暂停状态，同步回落为 `STOPPED`。
         */
        fun detachPlayer() {
            playerAttached = false
            _session.update { session ->
                if (session.transportState == CastTransportState.NO_MEDIA_PRESENT) {
                    session
                } else {
                    session.copy(transportState = CastTransportState.STOPPED, positionMs = 0)
                }
            }
        }

        /**
         * 回写播放器进度，供 `GetPositionInfo` 查询。
         *
         * @param positionMs 当前播放位置（毫秒）。
         * @param durationMs 总时长（毫秒），未知时为 0。
         */
        fun updatePlayback(
            positionMs: Long,
            durationMs: Long,
        ) {
            _session.update { session ->
                if (session.mediaUri.isBlank()) {
                    session
                } else {
                    session.copy(
                        positionMs = positionMs.coerceAtLeast(0),
                        durationMs = durationMs.coerceAtLeast(0),
                    )
                }
            }
        }

        /** 播放结束时同步会话状态。 */
        fun onPlaybackEnded() {
            _session.update { it.copy(transportState = CastTransportState.STOPPED, positionMs = 0) }
        }

        /**
         * 消费待打开请求。
         *
         * 仅当序号匹配时清空，避免清掉后续新产生的请求。
         *
         * @param sessionId 已处理的会话序号。
         */
        fun consumePendingLaunch(sessionId: Long) {
            if (_pendingLaunch.value?.sessionId == sessionId) {
                _pendingLaunch.value = null
            }
        }

        // ── CastSoapHandler ─────────────────────────────────────────────

        override fun descriptionXml(location: String): String = CastSoap.renderDescription(friendlyName, udn, location)

        override fun scpdXml(serviceType: String): String? =
            when (serviceType) {
                CastSoap.AV_TRANSPORT -> CastSoap.renderAvTransportScpd()
                CastSoap.RENDERING_CONTROL -> CastSoap.renderRenderingControlScpd()
                else -> null
            }

        override fun handleSoap(
            serviceType: String,
            soapAction: String?,
            body: String,
        ): String {
            val action =
                CastSoap
                    .parseSoapAction(soapAction)
                    ?.takeIf { it.serviceType == serviceType }
                    ?.action
                    ?: CastSoap.parseActionFromBody(body)
                    ?: return CastSoap.renderFault(ERROR_INVALID_ACTION, "Invalid Action")
            val arguments = CastSoap.parseArguments(body)
            return synchronized(lock) {
                when (serviceType) {
                    CastSoap.AV_TRANSPORT -> handleAvTransport(action, arguments)
                    CastSoap.RENDERING_CONTROL -> handleRenderingControl(action, arguments)
                    else -> CastSoap.renderFault(ERROR_INVALID_ACTION, "Invalid Action")
                }
            }
        }

        // ── AVTransport ─────────────────────────────────────────────────

        private fun handleAvTransport(
            action: String,
            args: Map<String, String>,
        ): String =
            when (action) {
                "SetAVTransportURI" -> setAvTransportUri(args)
                "SetNextAVTransportURI" -> avTransportResponse(action)
                "Play" -> play()
                "Pause" -> pause()
                "Stop" -> stopPlayback()
                "Seek" -> seek(args)
                "Next" -> avTransportResponse(action)
                "Previous" -> avTransportResponse(action)
                "GetTransportInfo" -> transportInfo()
                "GetPositionInfo" -> positionInfo()
                "GetMediaInfo" -> mediaInfo()
                "GetTransportSettings" ->
                    avTransportResponse(
                        action,
                        listOf(
                            "PlayMode" to "NORMAL",
                            "RecQualityMode" to "NOT_IMPLEMENTED",
                        ),
                    )
                "GetDeviceCapabilities" ->
                    avTransportResponse(
                        action,
                        listOf(
                            "PlayMedia" to "NETWORK",
                            "RecMedia" to "NOT_IMPLEMENTED",
                            "RecQualityModes" to "NOT_IMPLEMENTED",
                        ),
                    )
                "GetCurrentTransportActions" -> currentTransportActions()
                else -> CastSoap.renderFault(ERROR_INVALID_ACTION, "Invalid Action")
            }

        private fun setAvTransportUri(args: Map<String, String>): String {
            val uri = args["CurrentURI"].orEmpty()
            val metadata = args["CurrentURIMetaData"].orEmpty()
            val title = CastSoap.extractTitle(metadata)
            currentSessionId += 1
            launchedSessionId = -1
            _session.value =
                CastSession(
                    id = currentSessionId,
                    mediaUri = uri,
                    mediaTitle = title,
                    mediaMetadata = metadata,
                    transportState =
                        if (uri.isBlank()) {
                            CastTransportState.NO_MEDIA_PRESENT
                        } else {
                            CastTransportState.STOPPED
                        },
                    aid = CastSoap.extractPiliMeta(metadata, "aid").toLongOrNull() ?: 0,
                    cid = CastSoap.extractPiliMeta(metadata, "cid").toLongOrNull() ?: 0,
                    bvid = CastSoap.extractPiliMeta(metadata, "bvid"),
                    epid = CastSoap.extractPiliMeta(metadata, "epid").toLongOrNull() ?: 0,
                    sid = CastSoap.extractPiliMeta(metadata, "sid").toLongOrNull() ?: 0,
                    cover = CastSoap.extractPiliMeta(metadata, "cover"),
                    liveRoomId = CastSoap.extractPiliMeta(metadata, "live").toLongOrNull() ?: 0,
                )
            // 新投屏请求会替换当前内容，先停止旧播放
            if (playerAttached) {
                _commands.tryEmit(CastPlayerCommand.Stop)
            }
            logger.info { "SetAVTransportURI: title=$title" }
            return avTransportResponse("SetAVTransportURI")
        }

        private fun play(): String {
            val session = _session.value
            if (session.mediaUri.isBlank()) {
                return CastSoap.renderFault(ERROR_TRANSITION_NOT_AVAILABLE, "Transition not available")
            }
            _session.update { it.copy(transportState = CastTransportState.PLAYING) }
            if (launchedSessionId != session.id) {
                launchedSessionId = session.id
                _pendingLaunch.value =
                    CastLaunch(
                        sessionId = session.id,
                        aid = session.aid,
                        cid = session.cid,
                        bvid = session.bvid,
                        epid = session.epid,
                        sid = session.sid,
                        title = session.mediaTitle,
                        cover = session.cover,
                        liveRoomId = session.liveRoomId,
                    )
            } else if (playerAttached) {
                _commands.tryEmit(CastPlayerCommand.Play)
            }
            return avTransportResponse("Play")
        }

        private fun pause(): String {
            _session.update { session ->
                if (session.transportState == CastTransportState.PLAYING ||
                    session.transportState == CastTransportState.TRANSITIONING
                ) {
                    session.copy(transportState = CastTransportState.PAUSED_PLAYBACK)
                } else {
                    session
                }
            }
            if (playerAttached) {
                _commands.tryEmit(CastPlayerCommand.Pause)
            }
            return avTransportResponse("Pause")
        }

        private fun stopPlayback(): String {
            _session.update { it.copy(transportState = CastTransportState.STOPPED, positionMs = 0) }
            if (playerAttached) {
                _commands.tryEmit(CastPlayerCommand.Stop)
            }
            return avTransportResponse("Stop")
        }

        private fun seek(args: Map<String, String>): String {
            val unit = args["Unit"].orEmpty()
            if (unit == "TRACK_NR") return avTransportResponse("Seek")
            val target = CastSoap.parseDuration(args["Target"])
            if (target == null) {
                return CastSoap.renderFault(ERROR_ILLEGAL_SEEK_TARGET, "Illegal seek target")
            }
            _session.update { it.copy(positionMs = target) }
            if (playerAttached) {
                _commands.tryEmit(CastPlayerCommand.SeekTo(target))
            }
            return avTransportResponse("Seek")
        }

        private fun transportInfo(): String {
            val session = _session.value
            return avTransportResponse(
                "GetTransportInfo",
                listOf(
                    "CurrentTransportState" to session.transportState.name,
                    "CurrentTransportStatus" to "OK",
                    "CurrentSpeed" to "1",
                ),
            )
        }

        private fun positionInfo(): String {
            val session = _session.value
            val position = CastSoap.formatDuration(session.positionMs)
            return avTransportResponse(
                "GetPositionInfo",
                listOf(
                    "Track" to "1",
                    "TrackDuration" to CastSoap.formatDuration(session.durationMs),
                    "TrackMetaData" to session.mediaMetadata,
                    "TrackURI" to session.mediaUri,
                    "RelTime" to position,
                    "AbsTime" to position,
                    "RelCount" to "2147483647",
                    "AbsCount" to "2147483647",
                ),
            )
        }

        private fun mediaInfo(): String {
            val session = _session.value
            return avTransportResponse(
                "GetMediaInfo",
                listOf(
                    "NrTracks" to "1",
                    "MediaDuration" to CastSoap.formatDuration(session.durationMs),
                    "CurrentURI" to session.mediaUri,
                    "CurrentURIMetaData" to session.mediaMetadata,
                    "NextURI" to "",
                    "NextURIMetaData" to "",
                    "PlayMedium" to "NETWORK",
                    "RecordMedium" to "NOT_IMPLEMENTED",
                    "WriteStatus" to "NOT_IMPLEMENTED",
                ),
            )
        }

        private fun currentTransportActions(): String {
            val actions =
                when (_session.value.transportState) {
                    CastTransportState.PLAYING -> "Pause,Stop,Seek"
                    CastTransportState.PAUSED_PLAYBACK -> "Play,Stop,Seek"
                    CastTransportState.STOPPED ->
                        if (_session.value.mediaUri.isBlank()) "Play" else "Play,Seek"
                    else -> "Play"
                }
            return avTransportResponse("GetCurrentTransportActions", listOf("Actions" to actions))
        }

        // ── RenderingControl ────────────────────────────────────────────

        private fun handleRenderingControl(
            action: String,
            args: Map<String, String>,
        ): String =
            when (action) {
                "GetVolume" -> renderingControlResponse(action, listOf("CurrentVolume" to currentVolume().toString()))
                "SetVolume" -> {
                    setVolume(args["DesiredVolume"])
                    renderingControlResponse(action)
                }
                "GetMute" -> renderingControlResponse(action, listOf("CurrentMute" to if (isMuted()) "1" else "0"))
                "SetMute" -> {
                    setMuted(args["DesiredMute"])
                    renderingControlResponse(action)
                }
                "ListPresets" -> renderingControlResponse(action, listOf("CurrentPresetNameList" to "FactoryDefaults"))
                "SelectPreset" -> renderingControlResponse(action)
                else -> CastSoap.renderFault(ERROR_INVALID_ACTION, "Invalid Action")
            }

        private fun currentVolume(): Int {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            return (current * 100 / max).coerceIn(0, 100)
        }

        private fun setVolume(desired: String?) {
            val volume = desired?.toIntOrNull() ?: return
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val target = (volume.coerceIn(0, 100) * max / 100)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        }

        private fun isMuted(): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
            } else {
                @Suppress("DEPRECATION")
                audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
            }

        private fun setMuted(desired: String?) {
            val mute = desired.equals("1", ignoreCase = true) || desired.equals("true", ignoreCase = true)
            val adjustment =
                if (mute) {
                    AudioManager.ADJUST_MUTE
                } else {
                    AudioManager.ADJUST_UNMUTE
                }
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, adjustment, 0)
        }

        // ── 辅助方法 ────────────────────────────────────────────────────

        private fun avTransportResponse(
            action: String,
            args: List<Pair<String, String>> = emptyList(),
        ): String = CastSoap.renderResponse(CastSoap.AV_TRANSPORT, action, args)

        private fun renderingControlResponse(
            action: String,
            args: List<Pair<String, String>> = emptyList(),
        ): String = CastSoap.renderResponse(CastSoap.RENDERING_CONTROL, action, args)

        /** 等待本机获取到 IPv4 地址（网络可能晚于应用启动就绪）。 */
        private suspend fun awaitLocalIp(): String? {
            repeat(IP_RETRY_COUNT) { attempt ->
                findLocalIp()?.let { return it }
                if (attempt < IP_RETRY_COUNT - 1) {
                    delay(IP_RETRY_DELAY_MS)
                }
            }
            return null
        }

        /** 查找本机局域网 IPv4 地址，优先选择 Wi-Fi 网卡。 */
        private fun findLocalIp(): String? {
            val interfaces =
                runCatching { NetworkInterface.getNetworkInterfaces().toList() }
                    .getOrNull()
                    ?: return null
            val candidates = interfaces.filter { it.isUp && !it.isLoopback }
            val sorted = candidates.sortedByDescending { isWifiInterface(it.name) }
            return sorted
                .asSequence()
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress && !it.isLinkLocalAddress }
                ?.hostAddress
        }

        private fun isWifiInterface(name: String?): Boolean {
            val lower = name?.lowercase(Locale.US) ?: return false
            return "wlan" in lower || "wifi" in lower
        }

        private fun acquireMulticastLock() {
            val wifiManager =
                context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    ?: return
            runCatching {
                multicastLock =
                    wifiManager.createMulticastLock(MULTICAST_LOCK_TAG).apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            }.onFailure { logger.warn(it) { "Unable to acquire multicast lock" } }
        }

        private fun releaseMulticastLock() {
            runCatching {
                multicastLock?.takeIf { it.isHeld }?.release()
            }
            multicastLock = null
        }

        private companion object {
            const val SERVER = "Android/1.0 UPnP/1.0 newBV/1.0"
            const val MULTICAST_LOCK_TAG = "newBV-cast"
            const val ERROR_INVALID_ACTION = 401
            const val ERROR_TRANSITION_NOT_AVAILABLE = 701
            const val ERROR_ILLEGAL_SEEK_TARGET = 711
            const val IP_RETRY_COUNT = 10
            const val IP_RETRY_DELAY_MS = 3000L
        }
    }
