package dev.frost819.newbv.app.cast

/**
 * DLNA 投屏会话的传输状态。
 *
 * 取值与 UPnP AVTransport 规范中的 `TransportState` 一致，
 * 可直接用于 `GetTransportInfo` 的响应。
 */
enum class CastTransportState {
    STOPPED,
    PLAYING,
    PAUSED_PLAYBACK,
    TRANSITIONING,
    NO_MEDIA_PRESENT,
}

/**
 * 当前投屏会话。
 *
 * @param id 会话序号，每次 `SetAVTransportURI` 自增，用于区分多次投屏。
 * @param mediaUri 发送端指定的媒体地址。
 * @param mediaTitle 从 DIDL-Lite 元数据中提取的标题（可能为空）。
 * @param mediaMetadata 发送端传入的原始 DIDL-Lite 元数据。
 * @param transportState 当前传输状态。
 * @param positionMs 当前播放位置（毫秒），由播放器回写。
 * @param durationMs 媒体总时长（毫秒），未知时为 0。
 */
data class CastSession(
    val id: Long = 0,
    val mediaUri: String = "",
    val mediaTitle: String = "",
    val mediaMetadata: String = "",
    val transportState: CastTransportState = CastTransportState.NO_MEDIA_PRESENT,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** 稿件 avid，0 表示发送端未提供。 */
    val aid: Long = 0,
    /** 分P cid，0 表示发送端未提供。 */
    val cid: Long = 0,
    /** 稿件 bvid，空表示发送端未提供。 */
    val bvid: String = "",
    /** 番剧 epid，0 表示非番剧或未提供。 */
    val epid: Long = 0,
    /** 番剧 seasonId，0 表示未提供。 */
    val sid: Long = 0,
    /** 封面地址。 */
    val cover: String = "",
    /** 直播间 roomId，大于 0 表示直播投屏。 */
    val liveRoomId: Long = 0,
)

/**
 * 待打开的投屏播放页请求。
 *
 * 由 [CastReceiverController] 在收到 `Play` 指令时产生，
 * UI 层消费后按会话序号打开投屏播放页（媒体信息保存在 [CastSession]）。
 *
 * @param sessionId 对应的会话序号。
 */
data class CastLaunch(
    val sessionId: Long,
    val aid: Long = 0,
    val cid: Long = 0,
    val bvid: String = "",
    val epid: Long = 0,
    val sid: Long = 0,
    val title: String = "",
    val cover: String = "",
    val liveRoomId: Long = 0,
)

/** 发送端下发给本机播放器的控制指令。 */
sealed interface CastPlayerCommand {
    /** 开始/继续播放。 */
    data object Play : CastPlayerCommand

    /** 暂停播放。 */
    data object Pause : CastPlayerCommand

    /** 停止播放。 */
    data object Stop : CastPlayerCommand

    /**
     * 跳转到指定位置。
     *
     * @param positionMs 目标位置（毫秒）。
     */
    data class SeekTo(val positionMs: Long) : CastPlayerCommand
}
