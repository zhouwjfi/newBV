package dev.frost819.newbv.app.viewmodel.cast

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.frost819.newbv.app.cast.CastPlayerCommand
import dev.frost819.newbv.app.cast.CastReceiverController
import dev.frost819.newbv.app.cast.CastSession
import dev.frost819.newbv.app.util.PlaybackGuard
import dev.frost819.newbv.app.util.PlayerConstants
import dev.frost819.newbv.core.log.Loggers
import dev.frost819.newbv.player.VideoPlayerListener
import dev.frost819.newbv.player.VideoPlayerOptions
import dev.frost819.newbv.player.impl.exo.ExoMediaPlayer
import dev.frost819.newbv.player.impl.exo.ExoPlayerFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 投屏播放页的 ViewModel。
 *
 * 用轻量的 [ExoMediaPlayer] 播放发送端下发的直链地址，
 * 并双向同步 [CastReceiverController]：订阅控制指令、回写播放进度。
 *
 * @param playerFactory 播放器工厂。
 * @param castController 投屏接收控制器。
 * @param context 应用上下文。
 */
@HiltViewModel
class CastPlayerViewModel
    @Inject
    constructor(
        private val playerFactory: ExoPlayerFactory,
        private val castController: CastReceiverController,
        @ApplicationContext private val context: Context,
    ) : ViewModel() {
        private val logger = Loggers.get("CastPlayerVM")

        private val _videoPlayer = MutableStateFlow<ExoMediaPlayer?>(null)

        /** 当前播放器实例，UI 绑定 [dev.frost819.newbv.player.BvVideoPlayer] 使用。 */
        val videoPlayer: StateFlow<ExoMediaPlayer?> = _videoPlayer.asStateFlow()

        private val _error = MutableStateFlow<String?>(null)

        /** 播放错误提示，null 表示无错误。 */
        val error: StateFlow<String?> = _error.asStateFlow()

        /** 当前投屏会话（用于展示媒体标题）。 */
        val session: StateFlow<CastSession> = castController.session

        private var commandsJob: Job? = null
        private var progressJob: Job? = null

        /**
         * 开始播放指定会话的媒体。
         *
         * 重复调用安全（已有播放器时直接返回）；会话序号不匹配时忽略，
         * 交由新的导航重建页面。
         *
         * @param sessionId 投屏会话序号。
         */
        fun start(sessionId: Long) {
            if (_videoPlayer.value != null) return
            val current = castController.session.value
            if (current.id != sessionId || current.mediaUri.isBlank()) return
            val url = current.mediaUri
            val player =
                playerFactory.create(
                    context = context,
                    options =
                        VideoPlayerOptions(
                            userAgent = PlayerConstants.WEB_USER_AGENT,
                            referer = PlayerConstants.WEB_REFERER,
                        ),
                )
            player.setPlayerEventListener(
                object : VideoPlayerListener {
                    override fun onError(error: Exception) {
                        logger.error(error) { "Cast playback error" }
                        _error.value = "播放失败：${error.message ?: error.javaClass.simpleName}"
                    }

                    override fun onReady() {}

                    override fun onPlay() {}

                    override fun onPause() {}

                    override fun onBuffering() {}

                    override fun onEnd() {
                        castController.onPlaybackEnded()
                    }

                    override fun onSeekBack(seekBackIncrementMs: Long) {}

                    override fun onSeekForward(seekForwardIncrementMs: Long) {}
                },
            )
            player.playUrl(videoUrl = url)
            player.prepare()
            player.start()
            // 全局单播放：投屏起播前先停掉其它页面仍在播放的播放器
            PlaybackGuard.acquire(this) {
                player.pause()
                player.stop()
            }
            _videoPlayer.value = player
            castController.attachPlayer()

            commandsJob =
                viewModelScope.launch {
                    castController.commands.collect { command ->
                        when (command) {
                            CastPlayerCommand.Play -> {
                                player.prepare()
                                player.start()
                            }
                            CastPlayerCommand.Pause -> player.pause()
                            CastPlayerCommand.Stop -> player.stop()
                            is CastPlayerCommand.SeekTo -> player.seekTo(command.positionMs)
                        }
                    }
                }
            progressJob =
                viewModelScope.launch {
                    while (isActive) {
                        delay(PROGRESS_SYNC_INTERVAL_MS)
                        castController.updatePlayback(
                            positionMs = player.currentPosition,
                            durationMs = player.duration,
                        )
                    }
                }
        }

        /** 释放播放器并解除与投屏控制器的绑定。 */
        fun detach() {
            PlaybackGuard.release(this)
            commandsJob?.cancel()
            commandsJob = null
            progressJob?.cancel()
            progressJob = null
            val player = _videoPlayer.value ?: return
            _videoPlayer.value = null
            castController.detachPlayer()
            player.release()
        }

        override fun onCleared() {
            detach()
        }

        private companion object {
            const val PROGRESS_SYNC_INTERVAL_MS = 1000L
        }
    }
