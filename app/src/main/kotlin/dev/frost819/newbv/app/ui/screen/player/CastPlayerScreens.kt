package dev.frost819.newbv.app.ui.screen.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import dev.frost819.newbv.app.ui.navigation.CastPlayerRoute
import dev.frost819.newbv.app.viewmodel.cast.CastPlayerViewModel
import dev.frost819.newbv.player.AbstractVideoPlayer
import dev.frost819.newbv.player.BvVideoPlayer

/**
 * 投屏播放页面注册。
 *
 * 从 [CastPlayerRoute] 取媒体地址，创建 [CastPlayerViewModel] 开始播放，
 * 播放期间保持全屏与屏幕常亮。
 */
fun NavGraphBuilder.castPlayerScreen(navController: NavController) {
    composable<CastPlayerRoute> { backStackEntry ->
        val route = backStackEntry.toRoute<CastPlayerRoute>()
        val viewModel: CastPlayerViewModel = hiltViewModel()
        val videoPlayer by viewModel.videoPlayer.collectAsStateWithLifecycle()
        val error by viewModel.error.collectAsStateWithLifecycle()
        val session by viewModel.session.collectAsStateWithLifecycle()

        LaunchedEffect(route.sessionId) {
            viewModel.start(route.sessionId)
        }

        DisposableEffect(Unit) {
            onDispose { viewModel.detach() }
        }

        PlayerWindowEffect(keepScreenOn = videoPlayer != null)
        PauseOnBackgroundEffect {
            videoPlayer?.pause()
        }

        CastPlayerScreen(
            videoPlayer = videoPlayer,
            title = session.mediaTitle,
            error = error,
        )
    }
}

/**
 * 投屏播放界面。
 *
 * 展示播放画面与媒体标题；支持 TV 遥控器本地控制
 * （确定键播放/暂停，左右键快退/快进）。
 *
 * @param videoPlayer 播放器实例。
 * @param title 媒体标题。
 * @param error 播放错误提示。
 */
@Composable
private fun CastPlayerScreen(
    videoPlayer: AbstractVideoPlayer?,
    title: String,
    error: String?,
) {
    val focusRequester = remember { FocusRequester() }
    var controlsVisible by remember { mutableStateOf(true) }
    var controlsTick by remember { mutableIntStateOf(0) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }

    fun showControls() {
        controlsVisible = true
        controlsTick++
    }

    LaunchedEffect(videoPlayer) {
        focusRequester.requestFocus()
        while (true) {
            videoPlayer?.let {
                positionMs = it.currentPosition
                durationMs = it.duration
            }
            kotlinx.coroutines.delay(500)
        }
    }

    LaunchedEffect(controlsVisible, controlsTick) {
        if (controlsVisible) {
            kotlinx.coroutines.delay(3000)
            controlsVisible = false
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { event ->
                    val player = videoPlayer ?: return@onKeyEvent false
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionCenter, Key.Enter, Key.Spacebar -> {
                            if (player.isPlaying) player.pause() else player.start()
                            showControls()
                            true
                        }
                        Key.DirectionLeft -> {
                            player.seekTo((player.currentPosition - SEEK_STEP_MS).coerceAtLeast(0))
                            showControls()
                            true
                        }
                        Key.DirectionRight -> {
                            val target = player.currentPosition + SEEK_STEP_MS
                            player.seekTo(if (player.duration > 0) target.coerceAtMost(player.duration) else target)
                            showControls()
                            true
                        }
                        else -> false
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { showControls() }
                },
    ) {
        BvVideoPlayer(
            modifier = Modifier.fillMaxSize(),
            videoPlayer = videoPlayer,
        )

        if (title.isNotBlank()) {
            Text(
                text = title,
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(24.dp),
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (videoPlayer != null && controlsVisible) {
            Column(
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(24.dp),
            ) {
                LinearProgressIndicator(
                    progress = {
                        if (durationMs > 0) {
                            (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${formatDuration(positionMs)} / ${formatDuration(durationMs)}",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 14.sp,
                    )
                    Icon(
                        imageVector = if (videoPlayer.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                    )
                }
            }
        }

        if (error != null) {
            Text(
                text = error,
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
                fontSize = 18.sp,
            )
        }
    }
}

private const val SEEK_STEP_MS = 10_000L

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
