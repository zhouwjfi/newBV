package dev.frost819.newbv.app.viewmodel.cast

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.frost819.newbv.app.cast.CastLaunch
import dev.frost819.newbv.app.cast.CastReceiverController
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * 投屏启动请求的 ViewModel。
 *
 * 仅用于在导航层观察 [CastReceiverController.pendingLaunch]，
 * 把发送端的播放指令转换为页面跳转，并在跳转后消费请求。
 *
 * @param controller 投屏接收控制器。
 */
@HiltViewModel
class CastLaunchViewModel
    @Inject
    constructor(
        private val controller: CastReceiverController,
    ) : ViewModel() {
        /** 待打开的投屏播放页请求，为 null 表示无待处理请求。 */
        val pendingLaunch: StateFlow<CastLaunch?> = controller.pendingLaunch

        /**
         * 消费已处理的请求。
         *
         * @param sessionId 已打开的会话序号。
         */
        fun consume(sessionId: Long) {
            controller.consumePendingLaunch(sessionId)
        }
    }
