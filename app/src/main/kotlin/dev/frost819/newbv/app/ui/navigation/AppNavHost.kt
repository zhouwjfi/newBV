package dev.frost819.newbv.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import dev.frost819.newbv.app.ui.screen.detail.videoDetailScreen
import dev.frost819.newbv.app.ui.screen.home.homeScreen
import dev.frost819.newbv.app.ui.screen.live.liveAreaScreen
import dev.frost819.newbv.app.ui.screen.live.liveFollowScreen
import dev.frost819.newbv.app.ui.screen.login.loginScreen
import dev.frost819.newbv.app.ui.screen.pgc.pgcFeatureScreen
import dev.frost819.newbv.app.ui.screen.player.castPlayerScreen
import dev.frost819.newbv.app.ui.screen.player.livePlayerScreen
import dev.frost819.newbv.app.ui.screen.player.seasonPlayerScreen
import dev.frost819.newbv.app.ui.screen.player.videoPlayerScreen
import dev.frost819.newbv.app.ui.screen.search.searchResultScreen
import dev.frost819.newbv.app.ui.screen.settings.settingsScreen
import dev.frost819.newbv.app.ui.screen.user.followScreen
import dev.frost819.newbv.app.ui.screen.user.userSpaceScreen
import dev.frost819.newbv.app.ui.screen.user.userSwitchScreen
import dev.frost819.newbv.app.viewmodel.cast.CastLaunchViewModel
import dev.frost819.newbv.core.log.Loggers

/**
 * 应用 Navigation 宿主。
 *
 * 单 Activity 架构：所有页面通过 Navigation-Compose 导航，无新 Activity 启动。
 * 路由定义见 [Routes]。
 *
 * @param navController 导航控制器，默认使用 [rememberNavController]
 * @param startDestination 起始路由，默认 [HomeRoute]
 */
@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController(),
    startDestination: Any = HomeRoute,
) {
    val logger = Loggers.get("AppNavHost")

    // 投屏：发送端下发 Play 后打开投屏播放页
    val castLaunchViewModel: CastLaunchViewModel = hiltViewModel()
    val castLaunch by castLaunchViewModel.pendingLaunch.collectAsStateWithLifecycle()
    LaunchedEffect(castLaunch, navController) {
        val launch = castLaunch ?: return@LaunchedEffect
        castLaunchViewModel.consume(launch.sessionId)
        // 带稿件标识的投屏直接进入普通播放器，复用画质/弹幕/字幕等完整控制
        val destination: Any =
            when {
                launch.liveRoomId > 0 ->
                    LivePlayerRoute(
                        roomId = launch.liveRoomId,
                        title = launch.title,
                        cover = launch.cover,
                    )
                launch.epid > 0 ->
                    SeasonPlayerRoute(
                        epid = launch.epid,
                        sid = launch.sid,
                        title = launch.title,
                        cover = launch.cover,
                    )
                launch.aid > 0 && launch.cid > 0 ->
                    VideoPlayerRoute(
                        aid = launch.aid,
                        cid = launch.cid,
                        bvid = launch.bvid,
                        title = launch.title,
                        cover = launch.cover,
                    )
                else -> CastPlayerRoute(sessionId = launch.sessionId)
            }
        // 每次投屏都替换当前播放页：清掉主页以上的所有页面，再压入新播放页，
        // 避免旧播放页残留在返回栈中（多次投屏后要按很多次返回才能回到主页）
        navController.navigate(destination) {
            popUpTo(HomeRoute) { inclusive = false }
        }
    }

    LaunchedEffect(navController) {
        var previousRoute: String? = null
        navController.currentBackStackEntryFlow.collect { entry ->
            val route = entry.destination.route?.substringAfterLast('.') ?: "unknown"
            if (previousRoute != null && previousRoute != route) {
                logger.info { "[NAV] from=$previousRoute to=$route" }
            }
            previousRoute = route
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        // ── 主流程 ────────────────────────────────────────────────────
        homeScreen(navController)
        searchResultScreen(navController)

        // ── 视频详情 ─────────────────────────────────────────────────
        videoDetailScreen(navController)

        // ── 直播 ─────────────────────────────────────────────────────
        liveAreaScreen(navController)
        liveFollowScreen(navController)

        // ── 播放器 ───────────────────────────────────────────────────
        videoPlayerScreen(navController)
        seasonPlayerScreen(navController)
        livePlayerScreen(navController)
        castPlayerScreen(navController)

        // ── 用户 ─────────────────────────────────────────────────────
        userSpaceScreen(navController)
        followScreen(navController)

        // ── PGC ──────────────────────────────────────────────────────
        pgcFeatureScreen(navController)

        // ── 设置 ─────────────────────────────────────────────────────
        settingsScreen(navController)
        userSwitchScreen(navController)

        // ── 登录 ─────────────────────────────────────────────────────
        loginScreen(navController)
    }
}
