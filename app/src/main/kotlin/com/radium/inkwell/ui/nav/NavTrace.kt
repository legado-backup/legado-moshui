package com.radium.inkwell.ui.nav

import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import kotlin.math.roundToInt

/**
 * 侧滑返回卡顿的诊断记录（tag = [TAG]，开发者模式的运行日志会录下来）。
 *
 * 现象：点返回箭头回书架是顺的，侧滑返回却会「停在半截、再一下跳到终点」。录屏逐帧看，
 * 侧滑时画面播的是**前进**那套转场（旧页左让 1/4、新页从右进），不是传给 NavDisplay 的
 * predictivePopTransitionSpec。NavDisplay 只有在「它自己认为手势进行中」时才会选侧滑转场，
 * 所以要回答的是：手势进行中这段时间，谁在处理返回、NavDisplay 选了哪套转场、页面实际怎么动。
 *
 * 三类记录，同一时基（uptimeMillis），按时间排起来就是一次侧滑的完整经过：
 * 1. [NavGestureTrace]：手势状态（开始 / 进度 / 结束）与当前接手返回的处理者
 *    —— NavDisplay 自己是 SceneInfo，list-detail 场景的是 ThreePaneScaffoldSceneInfo；
 * 2. [NavTrace.specChosen]：NavDisplay 每次向我们要转场时选中的是哪一套、从哪个场景到哪个场景；
 * 3. [rememberEntryTraceDecorator]：转场进行中每个页面每一帧的实际水平位置。
 *
 * 查清并修好之后整个文件连同调用点一起删掉 —— 它只为这一个问题存在。
 */
internal object NavTrace {
    const val TAG = "NavTrace"

    fun log(message: String) {
        Log.i(TAG, "${SystemClock.uptimeMillis()} $message")
    }

    /** 场景的可读名字：类名 + key + 里面装着哪些页面 */
    fun describe(scene: Scene<NavKey>): String =
        "${scene::class.simpleName}(key=${scene.key}, 页面=${scene.entries.map { it.contentKey }})"

    /**
     * NavDisplay 向我们要转场时调用：记一行「选中了哪套」。它每次转场会调两次
     * （分别取 enter 与 exit），所以同一次转场会看到成对的两行，这是正常的
     */
    fun specChosen(name: String, scope: AnimatedContentTransitionScope<Scene<NavKey>>) {
        log("选中转场「$name」：${describe(scope.initialState)} → ${describe(scope.targetState)}")
    }

    fun edgeName(edge: Int): String = when (edge) {
        NavigationEvent.EDGE_LEFT -> "左缘"
        NavigationEvent.EDGE_RIGHT -> "右缘"
        else -> "无边缘($edge)"
    }
}

/**
 * 只读地跟着系统的返回手势与返回处理者走，不注册任何处理者 —— 不会改变手势归谁。
 * 放在 NavDisplay 外面一层即可：dispatcher 是整个 Activity 共用的那一个。
 */
@Composable
internal fun NavGestureTrace() {
    val dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher ?: return
    LaunchedEffect(dispatcher) {
        dispatcher.transitionState.collect { state ->
            when (state) {
                is NavigationEventTransitionState.Idle -> NavTrace.log("手势：空闲")
                is NavigationEventTransitionState.InProgress -> {
                    val e = state.latestEvent
                    NavTrace.log(
                        "手势：进行中 progress=${"%.3f".format(e.progress)} " +
                            "${NavTrace.edgeName(e.swipeEdge)} touchX=${e.touchX.roundToInt()} " +
                            "direction=${state.direction}",
                    )
                }
            }
        }
    }
    LaunchedEffect(dispatcher) {
        dispatcher.history.collect { history ->
            NavTrace.log(
                "返回处理者：${history.mergedHistory.map { it::class.simpleName }}，" +
                    "当前 #${history.currentIndex}",
            )
        }
    }
}

/**
 * 每个页面包一层：转场进行中（含侧滑时的 seek）逐帧记下它在窗口里的水平位置。
 * 只在转场进行时跑帧循环，停下来就不再请求帧，平时零开销。
 */
@Composable
internal fun rememberEntryTraceDecorator(): NavEntryDecorator<NavKey> = remember {
    NavEntryDecorator { entry ->
        val transition = LocalNavAnimatedContentScope.current.transition
        val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
        val running = transition.currentState != transition.targetState
        LaunchedEffect(running) {
            if (!running) return@LaunchedEffect
            NavTrace.log("页面 ${entry.contentKey} 转场开始：${transition.currentState} → ${transition.targetState}")
            var lastX = Int.MIN_VALUE
            while (true) {
                withFrameNanos { }
                val x = coords[0]?.takeIf { it.isAttached }?.positionInWindow()?.x?.roundToInt() ?: continue
                // 只记变化的帧：停住不动的那段正是要看的，用「两次记录之间隔了多久」就能读出来
                if (x != lastX) {
                    NavTrace.log("页面 ${entry.contentKey} x=$x（${transition.currentState} → ${transition.targetState}）")
                    lastX = x
                }
            }
        }
        // propagateMinConstraints：外面多包这一层，不改变页面原本拿到的约束
        Box(Modifier.onGloballyPositioned { coords[0] = it }, propagateMinConstraints = true) {
            entry.Content()
        }
    }
}
