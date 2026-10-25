package com.radium.inkwell.ui.components

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * 动效层。组件内建与浮层帮手都以 [MaterialTheme.motionScheme] 为唯一来源；
 * **整页转场**（前进 / 返回 / 侧滑）与**阅读器开合**是刻意的例外，走下方 [Motion] 里命名的时长与曲线。
 *
 * `InkwellTheme` 把 `motionScheme` 钉成 `MotionScheme.expressive()`，系统开「移除动画」时
 * 整套换成 `InstantMotionScheme`（六个 spec 全 `tween(0)`）。顶栏/底栏/蒙层/展开读令牌，
 * 不再逐个判 [animationsEnabled]；不读令牌的整页转场与阅读器开合自己判。
 *
 * spatial / effects 的分工是 M3 的约定，别混：
 * - **spatial**（带回弹的 spring）管**位置与尺寸**：滑入、展开、缩放
 * - **effects**（不回弹）管**纯视觉属性**：alpha、颜色
 *
 * 用 effects 做位移会发木；用 spatial 做淡入会让透明度过冲，看着像闪了一下。
 * 「退场比入场快」：帮手里退场取 `fast*`、入场取 `default*`。
 *
 * 硬编码 tween 的两处，理由各不相同：
 * - **阅读器开合**：时长与进书 splash 窗口咬合，spring 给不出确定时长（[Motion.READER_ENTER_MS]）；
 * - **整页转场**：spatial spring 的回弹放在整页上就成了终点处「顿一下」，长尾还会和侧滑抢转场
 *   （[Motion.PAGE_ENTER_MS]）；侧滑另需线性才跟手（[Motion.PREDICTIVE_BACK_MS]）。
 */
object Motion {

    /**
     * 「移除动画」时用的 0 时长 spec。阅读器开合不读主题令牌时靠它兜底，
     * 与 [InstantMotionScheme] 行为一致。
     */
    fun <T> instantSpec(): FiniteAnimationSpec<T> = tween(0)

    /** 系统关动画时的页面转场（瞬间淡变，避免残留位移）。 */
    fun instantPageTransform(): ContentTransform =
        ContentTransform(
            fadeIn(instantSpec()),
            fadeOut(instantSpec()),
        )

    /**
     * 进阅读器专用：从被点那本书的位置放大展开（NavDisplay 里 scaleIn，原点定在书上）。
     *
     * 这条**刻意不走** `MotionScheme`：时长要和 [READER_SPLASH_DELAY_MS] 对齐（封面恰好在展开
     * 收尾时才可能出现，不跟展开抢戏），而 spring 给不出确定时长。曲线用 M3「强调减速」：起步更快、
     * 收尾更缓，落定那一下像被接住。200ms 比常规页转场更短 —— 进书首帧排版本就抢手
     * （见 InkwellNavDisplay 的转场注释 / ReaderViewModel.PREFETCH_LEAD_IN_MS），转场只做点到为止的
     * 空间暗示。返回走配对的 [readerExitSpec]。
     */
    const val READER_ENTER_MS = 200
    /** M3「强调减速」：起步快、收尾缓，落定像被接住。阅读器入场与整页转场共用 */
    val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val ReaderEnterEasing: Easing = EmphasizedDecelerate

    fun <T> readerEnterSpec(): FiniteAnimationSpec<T> = tween(READER_ENTER_MS, easing = ReaderEnterEasing)

    /**
     * 阅读页退场。**必须比入场快**，曲线也要和入场配成一对。
     *
     * 从前这里直接借页面转场的 180ms，与 [READER_ENTER_MS]（200）之比高达 90% —— 既违反
     * 「退场比入场快、约取 65%」，也违反 M3 motion 的同一条；而且入场用 M3 强调减速、退场用
     * 通用加速，两条曲线根本不是一套。
     *
     * 130ms ≈ 200 的 65%。曲线取 M3「强调加速」（emphasized accelerate）：慢起快走，
     * 与入场的强调减速（快进慢停）正好互为镜像 —— 进来时被稳稳接住，离开时干脆抽走。
     */
    const val READER_EXIT_MS = 130
    val ReaderExitEasing: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    fun <T> readerExitSpec(): FiniteAnimationSpec<T> = tween(READER_EXIT_MS, easing = ReaderExitEasing)

    /**
     * 进阅读页放大展开的起始缩放（返回时缩回同一值）。
     *
     * 取 0.85 而不是更小：这段动画里阅读页四周会露出书架，缩得越小露得越多、越像"从一个小方块弹出来"，
     * 而我们要的是"窗口从这本书那儿长出来"。0.85 够看出长大，又不至于让书架抢戏。
     */
    const val READER_OPEN_SCALE = 0.85f

    /**
     * 进书 splash（正文没就位时在纸面上显示书封）的**出场等待**。
     *
     * 这个延迟就是「方案 A」的全部要义：绝大多数进书是缓存热的，正文在这段窗口内就已就绪 ——
     * 那样 splash 永不出场，进书一毫秒都不会变慢。只有真的要等，才让封面出来交代「在开哪本书」。
     * 取值对齐 [READER_ENTER_MS]：封面恰好在展开动画收尾时才可能出现，不跟展开抢戏。
     *
     * 不要为了「多看见它」而调小 —— 那等于给每次进书加一道地板，正是方案 B 被否掉的原因。
     */
    const val READER_SPLASH_DELAY_MS = 200L

    /**
     * splash 一旦露面的**保底停留**。
     *
     * 没有它，正文在第 210ms 就绪时封面只闪 10ms，比不显示更难看。露了面就至少待满这么久再走。
     */
    const val READER_SPLASH_MIN_MS = 200L

    /**
     * 整页前进 / 返回转场：时长固定的 tween，不走 [MaterialTheme.motionScheme] 的 spring。
     *
     * Expressive 的位移 spring 带回弹，放在小组件上是手感，放在整页上就成了「顿一下」：
     * NavTrace 逐帧实测，进设置时新页冲过终点 18px 再用约 230ms 爬回 0，被盖的书架目标 -305
     * 却冲到 -334 再抖回来 —— 帧一帧没掉，眼睛看到的是终点处掉头。spring 还有一条长尾：
     * 完全停稳要 400–500ms，这段尾巴里开始侧滑，NavDisplay 会把转场取反（见 InkwellNavDisplay）。
     * tween 不超调、准点结束，前进 / 返回 / 侧滑（[PREDICTIVE_BACK_MS]）也就都是时长制，节奏一致。
     *
     * 曲线两边都用强调减速 [EmphasizedDecelerate]：共享轴里两页同时在动，
     * 被盖页若用「慢起快走」的强调加速，起步那一下像没跟上。被盖页时长取入场的约 65%
     * （「退场比入场快」），它只挪 1/4 屏，早一点停稳不显眼。
     */
    const val PAGE_ENTER_MS = 350
    const val PAGE_EXIT_MS = 230

    /**
     * 侧滑返回（预测性返回）转场的总时长。与 [PAGE_ENTER_MS] 同样不走令牌，但另需**线性**，原因与 spring 的形状有关：
     *
     * 侧滑时 NavDisplay 不「播放」转场，而是按手势进度 **seek** 到转场的某个时刻；松手后再用
     * `tween((1 - 进度) × 总时长)` 把剩下的拖完。spring 的位移曲线前段极陡 —— 实测进度 0.37 时页面
     * 已冲过屏宽（1286 / 1220）再回弹，手指才划了三分之一画面就到头了，之后怎么拖都不动，像卡住。
     * 只有线性 tween 能让「进度 = 位移」，页面跟着手指走。
     *
     * 300ms 只决定松手后剩余部分走多快（剩一半就是 150ms），与返回箭头那条 tween 收尾观感相近。
     */
    const val PREDICTIVE_BACK_MS = 300
}

/**
 * 系统 [Settings.Global.ANIMATOR_DURATION_SCALE]（开发者选项「动画程序时长缩放」）。
 *
 * - `0`：「移除动画」—— 主题换 [InstantMotionScheme]；不读令牌的页面进退由 [animationsEnabled] 自己归零
 * - `0.5` / `1` / `2`…：阅读器与页面进退的 tween 由框架 `MotionDurationScale` 自动乘倍率
 *
 * 用 ContentObserver 实时听，别 `remember {}` 读一次 —— 用户改完设置应立刻生效。
 * 同一组合里只挂一处观察者；[animationsEnabled] 复用本函数，避免双重监听。
 */
@Composable
fun rememberAnimatorDurationScale(): Float {
    val context = LocalContext.current
    val resolver = context.contentResolver

    fun read(): Float =
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

    var scale by remember { mutableStateOf(read()) }
    DisposableEffect(resolver) {
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                scale = read()
            }
        }
        resolver.registerContentObserver(uri, false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return scale
}

/**
 * 系统开了「移除动画」就别动（[rememberAnimatorDurationScale] != 0）。
 *
 * 大部分地方**不需要**直接问它 —— 主题已经把 [MaterialTheme.motionScheme] 换成 0 时长的一套，
 * 走令牌的动画自动静止。留着它是给两类逃在主题之外的场合：翻页容器那套自绘动画（reader 模块，
 * 拿不到 Compose 主题），以及「关了动画就干脆别做这件事」而不只是缩到 0 时长的判断
 * （如 LazyGrid 的 `animateItem` 直接传 null，省掉每帧的插值开销）。
 */
@Composable
fun animationsEnabled(): Boolean = rememberAnimatorDurationScale() != 0f

/**
 * 页面前进：共享轴 X —— 新页整屏从右滑入，旧页左让约 1/4。
 *
 * **不加 fade**：半透明叠字会退化成渐隐渐显。时长与曲线见 [Motion.PAGE_ENTER_MS]：
 * 整页刻意不用主题的 spring（带回弹，终点会掉头）。系统「移除动画」时时长归零 ——
 * 这里不读 motionScheme，主题那层 InstantMotionScheme 管不到它。
 */
@Composable
fun pagePushTransform(): ContentTransform {
    val (enter, exit) = pageDurations()
    return slideInHorizontally(tween(enter, easing = Motion.EmphasizedDecelerate)) { it } togetherWith
        slideOutHorizontally(tween(exit, easing = Motion.EmphasizedDecelerate)) { -it / 4 }
}

/**
 * 页面返回：与 [pagePushTransform] 镜像 —— 当前页右滑出，底层页从左侧 1/4 处回位。
 */
@Composable
fun pagePopTransform(): ContentTransform {
    val (enter, exit) = pageDurations()
    return slideInHorizontally(tween(enter, easing = Motion.EmphasizedDecelerate)) { -it / 4 } togetherWith
        slideOutHorizontally(tween(exit, easing = Motion.EmphasizedDecelerate)) { it }
}

/** (入场, 退场) 时长；关了系统动画就都归零 */
@Composable
private fun pageDurations(): Pair<Int, Int> =
    if (animationsEnabled()) Motion.PAGE_ENTER_MS to Motion.PAGE_EXIT_MS else 0 to 0

/**
 * 侧滑返回：几何与 [pagePopTransform] 相同（当前页右滑出、底层页从左 1/4 回位），
 * 只是两段都换成**线性** tween —— 侧滑时转场是按手势进度 seek 的，理由见 [Motion.PREDICTIVE_BACK_MS]。
 * 系统「移除动画」时时长归零：这里不读 motionScheme，主题那层 InstantMotionScheme 管不到它。
 */
@Composable
fun pagePredictivePopTransform(): ContentTransform {
    val duration = if (animationsEnabled()) Motion.PREDICTIVE_BACK_MS else 0
    return slideInHorizontally(tween(duration, easing = LinearEasing)) { -it / 4 } togetherWith
        slideOutHorizontally(tween(duration, easing = LinearEasing)) { it }
}

/** 顶栏：从上方滑入 + 淡入 */
@Composable
fun topBarEnter(): EnterTransition {
    val motion = MaterialTheme.motionScheme
    return slideInVertically(motion.defaultSpatialSpec()) { -it } + fadeIn(motion.defaultEffectsSpec())
}

@Composable
fun topBarExit(): ExitTransition {
    val motion = MaterialTheme.motionScheme
    return slideOutVertically(motion.fastSpatialSpec()) { -it } + fadeOut(motion.fastEffectsSpec())
}

/** 底栏：从下方滑入 + 淡入 */
@Composable
fun bottomBarEnter(): EnterTransition {
    val motion = MaterialTheme.motionScheme
    return slideInVertically(motion.defaultSpatialSpec()) { it } + fadeIn(motion.defaultEffectsSpec())
}

@Composable
fun bottomBarExit(): ExitTransition {
    val motion = MaterialTheme.motionScheme
    return slideOutVertically(motion.fastSpatialSpec()) { it } + fadeOut(motion.fastEffectsSpec())
}

/** 蒙层等纯淡入淡出的东西：只动 alpha，走 effects（spatial 的回弹会让透明度过冲） */
@Composable
fun scrimEnter(): EnterTransition = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec())

@Composable
fun scrimExit(): ExitTransition = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec())

/**
 * 会撑高/收起、把周围内容顶开的一块面板（书架隐藏区、搜索进度条之类）。
 * 高度展开 + 淡入，而不是硬生生冒出来把下面的内容顶一下。
 */
@Composable
fun expandEnter(): EnterTransition {
    val motion = MaterialTheme.motionScheme
    return expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec())
}

@Composable
fun expandExit(): ExitTransition {
    val motion = MaterialTheme.motionScheme
    return shrinkVertically(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec())
}
