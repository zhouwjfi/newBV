package dev.frost819.newbv.app.util

/**
 * 全局单播放约束。
 *
 * 新旧播放器可能同时存在（播放页被新页面覆盖、退出未及时销毁等），
 * 新播放器启动时通过 [acquire] 先停掉上一个播放者，避免两路视频同时出声。
 */
object PlaybackGuard {
    private var owner: Any? = null
    private var stopAction: (() -> Unit)? = null

    /**
     * 注册当前播放者，并立即停止上一个播放者。
     *
     * @param owner 播放者标识（通常是 ViewModel 实例）。
     * @param stop 停止该播放者的动作，之后有新播放者出现时被调用。
     */
    @Synchronized
    fun acquire(owner: Any, stop: () -> Unit) {
        stopAction?.invoke()
        this.owner = owner
        stopAction = stop
    }

    /** 释放注册，仅当自己仍是当前播放者时生效。 */
    @Synchronized
    fun release(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        stopAction = null
    }
}
