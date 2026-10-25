package com.radium.inkwell.ui.nav

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.radium.inkwell.ui.bookshelf.BookshelfScreen
import com.radium.inkwell.ui.bookshelf.BookshelfViewModel
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.Motion
import com.radium.inkwell.ui.components.animationsEnabled
import com.radium.inkwell.ui.components.pagePopTransform
import com.radium.inkwell.ui.components.pagePredictivePopTransform
import com.radium.inkwell.ui.components.pagePushTransform
import com.radium.inkwell.ui.detail.BookDetailScreen
import com.radium.inkwell.ui.explore.ExploreScreen
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.radium.inkwell.ui.explore.ExploreViewModel
import com.radium.inkwell.ui.feedback.FeedbackScreen
import com.radium.inkwell.ui.feedback.FeedbackViewModel
import com.radium.inkwell.ui.preview.BookPreviewScreen
import com.radium.inkwell.ui.preview.BookPreviewViewModel
import com.radium.inkwell.ui.reader.ReaderScreen
import com.radium.inkwell.ui.reader.ReaderViewModel
import com.radium.inkwell.ui.replace.ReplaceRuleScreen
import com.radium.inkwell.ui.replace.ReplaceRuleViewModel
import com.radium.inkwell.ui.rss.RssArticleScreen
import com.radium.inkwell.ui.rss.RssArticleViewModel
import com.radium.inkwell.ui.rss.RssArticlesScreen
import com.radium.inkwell.ui.rss.RssArticlesViewModel
import com.radium.inkwell.ui.rss.RssSourceScreen
import com.radium.inkwell.ui.rss.RssSourceViewModel
import com.radium.inkwell.ui.search.SearchScreen
import com.radium.inkwell.ui.search.SearchViewModel
import com.radium.inkwell.ui.settings.AboutSettingsScreen
import com.radium.inkwell.ui.settings.AppearanceSettingsScreen
import com.radium.inkwell.ui.settings.ReadingSettingsScreen
import com.radium.inkwell.ui.settings.SettingsScreen
import com.radium.inkwell.ui.settings.ThemeSettingsScreen
import com.radium.inkwell.ui.settings.UpdateSettingsScreen
import com.radium.inkwell.ui.sourcedetail.SourceDetailScreen
import com.radium.inkwell.ui.sourcedetail.SourceDetailViewModel
import com.radium.inkwell.ui.sourcemanage.SourceManageScreen
import com.radium.inkwell.ui.sourcemanage.SourceManageViewModel
import com.radium.inkwell.ui.webdav.WebDavSettingsScreen
import com.radium.inkwell.ui.webdav.WebDavViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Navigation 3 + Material 3 Adaptive 入口。
 *
 * - 返回栈自持（[rememberNavBackStack]），前进/返回见 [InkwellNavigator]
 * - ViewModel 只在 `entry` 内用 [koinViewModel]（`org.koin.compose.viewmodel`）创建，绑到
 *   [rememberViewModelStoreNavEntryDecorator] 提供的 NavEntry 作用域；退栈即清
 * - 宽屏：书架|详情、设置|二级 用 [ListDetailSceneStrategy]；阅读器全屏
 * - 转场：常规页 Expressive 共享轴 X（`pagePushTransform` / `pagePopTransform`）；
 *   侧滑返回走线性的 `pagePredictivePopTransform`（按手势进度 seek，spring 不跟手）；
 *   进阅读 shared-axis Z（从书封原点缩放）
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun InkwellNavDisplay() {
    val backStack = rememberNavBackStack(BookshelfRoute)
    val nav = remember(backStack) { InkwellNavigator(backStack) }

    val containerSize = LocalWindowInfo.current.containerSize
    LaunchedEffect(containerSize) { nav.openOrigin.value = TransformOrigin.Center }

    // 页面 / 阅读器 tween 由 MotionDurationScale 乘倍率；这里只区分「关动画 → 瞬间」。
    val animate = animationsEnabled()
    val windowAdaptiveInfo = currentWindowAdaptiveInfoV2()
    val directive = remember(windowAdaptiveInfo) {
        calculatePaneScaffoldDirective(windowAdaptiveInfo)
            .copy(horizontalPartitionSpacerSize = 0.dp)
    }
    val listDetailStrategy = rememberListDetailSceneStrategy<NavKey>(
        directive = directive,
        // PopLatest：一次只退一格。默认的 PopUntilScaffoldValueChange 在「两级都是 detail、
        // 布局不变」时会跨层；三级改成 extraPane 后布局会变，但四级叠在三级上（两个 extra）
        // 时布局又不变，所以仍然必须 PopLatest，不能靠布局变化来决定退几格。
        backNavigationBehavior = BackNavigationBehavior.PopLatest,
    )
    val dualPane = directive.maxHorizontalPartitions > 1

    // Expressive 共享轴横滑（读 MotionScheme）；关动画时主题已换成 InstantMotionScheme，
    // 不必再分支 instant —— 判两遍等于又造两个来源。
    val defaultPush = pagePushTransform()
    val defaultPop = pagePopTransform()
    val defaultPredictivePop = pagePredictivePopTransform()
    // 侧滑手势是否正在进行。只读 dispatcher 的状态流，不注册处理者，不改变手势归谁。
    val gestures = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
    fun gestureInProgress() =
        gestures?.transitionState?.value is NavigationEventTransitionState.InProgress

    val readerMeta = remember(animate, nav) {
        val shrinkBack = {
            if (!animate) {
                ContentTransform(
                    fadeIn(Motion.instantSpec()),
                    fadeOut(Motion.instantSpec()),
                )
            } else {
                EnterTransition.None togetherWith scaleOut(
                    Motion.readerExitSpec(),
                    targetScale = Motion.READER_OPEN_SCALE,
                    transformOrigin = nav.openOrigin.value,
                )
            }
        }
        NavDisplay.transitionSpec {
            if (!animate) {
                ContentTransform(
                    fadeIn(Motion.instantSpec()),
                    fadeOut(Motion.instantSpec()),
                )
            } else {
                scaleIn(
                    Motion.readerEnterSpec(),
                    initialScale = Motion.READER_OPEN_SCALE,
                    transformOrigin = nav.openOrigin.value,
                ) togetherWith ExitTransition.None
            }
        } +
            NavDisplay.popTransitionSpec { shrinkBack() } +
            // 预测性返回也得缩回书位：不显式给，NavDisplay 会退回全局的横向滑动 spec，
            // 于是同一个「关书」动作在按返回键和拖手势下长得不一样。
            NavDisplay.predictivePopTransitionSpec { shrinkBack() }
    }

    // 侧滑返回卡顿的诊断（见 NavTrace）：手势与返回处理者、选中的转场、页面逐帧位置
    NavGestureTrace()
    NavDisplay(
        backStack = backStack,
        onBack = { nav.back() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
            rememberEntryTraceDecorator(),
        ),
        sceneStrategies = listOf(listDetailStrategy),
        // 侧滑手势进行中时，无论 NavDisplay 走到哪个分支都给侧滑转场。
        //
        // 前进转场还没收尾（spatial spring 带回弹，尾巴拖几百毫秒）时就开始侧滑，被退回的那页
        // 还以「退场中」留在 AnimatedContent 里：NavDisplay 先按侧滑取一次转场，紧接着又按「前进」
        // 再取一次 —— 实测是后者生效，于是旧页往左让、底层页把刚才的退场倒着播回来，方向全反，
        // 画面卡在半截直到松手。离上次前进不到半秒的侧滑必中（日志里 9～290ms 全部出错，
        // 790ms 以上全部正常），手快的人进设置就划回来，恰好撞上
        transitionSpec = {
            if (gestureInProgress()) {
                NavTrace.specChosen("前进→改侧滑返回", this)
                defaultPredictivePop
            } else {
                NavTrace.specChosen("前进", this)
                defaultPush
            }
        },
        popTransitionSpec = {
            NavTrace.specChosen("返回", this)
            defaultPop
        },
        // 侧滑时按手势进度 seek，必须是线性的转场才跟手，见 Motion.PREDICTIVE_BACK_MS
        predictivePopTransitionSpec = { edge ->
            NavTrace.specChosen("侧滑返回·${NavTrace.edgeName(edge)}", this)
            defaultPredictivePop
        },
        entryProvider = entryProvider {
            entry<BookshelfRoute>(
                metadata = ListDetailSceneStrategy.listPane(
                    sceneKey = NavPaneGroup.BOOKSHELF,
                    detailPlaceholder = { PanePlaceholder("选择一本书查看详情") },
                ),
            ) {
                val vm = koinViewModel<BookshelfViewModel>()
                BookshelfScreen(
                    onOpenBook = { id, origin -> nav.openBook(id, origin) },
                    onOpenDetail = { nav.go(BookDetailRoute(it)) },
                    onOpenSearch = { nav.go(SearchRoute()) },
                    onOpenExplore = { nav.go(ExploreRoute) },
                    onOpenSettings = { nav.go(SettingsRoute) },
                    viewModel = vm,
                )
            }
            entry<BookDetailRoute>(
                metadata = ListDetailSceneStrategy.detailPane(sceneKey = NavPaneGroup.BOOKSHELF),
            ) { route ->
                BookDetailScreen(
                    bookId = route.bookId,
                    showBack = !dualPane,
                    onRead = { nav.openBook(route.bookId, TransformOrigin.Center) },
                    onBack = { nav.back() },
                )
            }
            entry<ReaderRoute>(metadata = readerMeta) { route ->
                val vm = koinViewModel<ReaderViewModel> { parametersOf(route.bookId) }
                ReaderScreen(bookId = route.bookId, onExit = { nav.back() }, viewModel = vm)
            }
            entry<SearchRoute> {
                val vm = koinViewModel<SearchViewModel>()
                SearchScreen(
                    onBack = { nav.back() },
                    onOpenPreview = { nav.go(BookPreviewRoute.of(it)) },
                    viewModel = vm,
                )
            }
            entry<ExploreRoute> {
                val vm = koinViewModel<ExploreViewModel>()
                ExploreScreen(
                    onBack = { nav.back() },
                    onOpenSourceManage = { nav.go(SourceManageRoute) },
                    onOpenPreview = { nav.go(BookPreviewRoute.of(it)) },
                    viewModel = vm,
                )
            }
            entry<BookPreviewRoute> { route ->
                val vm = koinViewModel<BookPreviewViewModel> { parametersOf(route.results) }
                BookPreviewScreen(
                    results = route.results,
                    onRead = { nav.openBook(it, TransformOrigin.Center) },
                    onBack = { nav.back() },
                    viewModel = vm,
                )
            }
            // 三级及以上一律 extraPane：二级 detail 被盖住时仍保持组合，返回不用冷启动。
            // 两级都标 detail 的话 scaffold 只渲染最后一个 Detail，被盖住的那层直接卸掉。
            entry<SourceManageRoute>(
                metadata = ListDetailSceneStrategy.extraPane(sceneKey = NavPaneGroup.SETTINGS),
            ) {
                val vm = koinViewModel<SourceManageViewModel>()
                SourceManageScreen(
                    onBack = { nav.back() },
                    onOpen = { nav.go(SourceDetailRoute(it)) },
                    viewModel = vm,
                )
            }
            entry<SourceDetailRoute>(
                metadata = ListDetailSceneStrategy.extraPane(sceneKey = NavPaneGroup.SETTINGS),
            ) { route ->
                val vm = koinViewModel<SourceDetailViewModel> { parametersOf(route.sourceId) }
                SourceDetailScreen(
                    sourceId = route.sourceId,
                    onBack = { nav.back() },
                    viewModel = vm,
                )
            }
            entry<SettingsRoute>(
                metadata = ListDetailSceneStrategy.listPane(
                    sceneKey = NavPaneGroup.SETTINGS,
                    detailPlaceholder = { PanePlaceholder("选择一项设置") },
                ),
            ) {
                SettingsScreen(
                    onBack = { nav.back() },
                    onOpenAppearance = { nav.go(AppearanceSettingsRoute) },
                    onOpenReading = { nav.go(ReadingSettingsRoute) },
                    onOpenRss = { nav.go(RssSourceRoute) },
                    onOpenWebDav = { nav.go(WebDavSettingsRoute) },
                    onOpenUpdate = { nav.go(UpdateSettingsRoute) },
                    onOpenAbout = { nav.go(AboutSettingsRoute) },
                )
            }
            entry<AppearanceSettingsRoute>(
                metadata = ListDetailSceneStrategy.detailPane(sceneKey = NavPaneGroup.SETTINGS),
            ) {
                AppearanceSettingsScreen(
                    onBack = { nav.back() },
                    onOpenTheme = { nav.go(ThemeSettingsRoute) },
                )
            }
            entry<ReadingSettingsRoute>(
                metadata = ListDetailSceneStrategy.detailPane(sceneKey = NavPaneGroup.SETTINGS),
            ) {
                ReadingSettingsScreen(
                    onBack = { nav.back() },
                    onOpenSources = { nav.go(SourceManageRoute) },
                    onOpenReplaceRules = { nav.go(ReplaceRuleRoute) },
                )
            }
            entry<UpdateSettingsRoute>(
                metadata = ListDetailSceneStrategy.detailPane(sceneKey = NavPaneGroup.SETTINGS),
            ) {
                UpdateSettingsScreen(onBack = { nav.back() })
            }
            entry<AboutSettingsRoute>(
                metadata = ListDetailSceneStrategy.detailPane(sceneKey = NavPaneGroup.SETTINGS),
            ) {
                AboutSettingsScreen(
                    onBack = { nav.back() },
                    onOpenFeedback = { nav.go(FeedbackRoute) },
                    onOpenDisclaimer = { nav.go(DisclaimerRoute) },
                    onOpenDeveloper = { nav.go(DeveloperSettingsRoute) },
                )
            }
            entry<RssSourceRoute>(
                metadata = ListDetailSceneStrategy.detailPane(sceneKey = NavPaneGroup.SETTINGS),
            ) {
                val vm = koinViewModel<RssSourceViewModel>()
                RssSourceScreen(
                    onBack = { nav.back() },
                    onOpenSource = { nav.go(RssArticlesRoute(it)) },
                    viewModel = vm,
                )
            }
            entry<RssArticlesRoute>(
                metadata = ListDetailSceneStrategy.extraPane(sceneKey = NavPaneGroup.SETTINGS),
            ) { route ->
                val vm = koinViewModel<RssArticlesViewModel> { parametersOf(route.sourceId) }
                RssArticlesScreen(
                    sourceId = route.sourceId,
                    onBack = { nav.back() },
                    onOpenArticle = { article ->
                        nav.go(
                            RssArticleRoute.of(
                                com.radium.inkwell.ui.rss.RssArticleArgs(
                                    sourceId = article.sourceId,
                                    title = article.title,
                                    link = article.link,
                                    description = article.description,
                                    pubDate = article.pubDate,
                                ),
                            ),
                        )
                    },
                    viewModel = vm,
                )
            }
            entry<RssArticleRoute>(
                metadata = ListDetailSceneStrategy.extraPane(sceneKey = NavPaneGroup.SETTINGS),
            ) { route ->
                val vm = koinViewModel<RssArticleViewModel> { parametersOf(route.args) }
                RssArticleScreen(args = route.args, onBack = { nav.back() }, viewModel = vm)
            }
            entry<ReplaceRuleRoute>(
                metadata = ListDetailSceneStrategy.extraPane(sceneKey = NavPaneGroup.SETTINGS),
            ) {
                val vm = koinViewModel<ReplaceRuleViewModel>()
                ReplaceRuleScreen(onBack = { nav.back() }, viewModel = vm)
            }
            entry<ThemeSettingsRoute>(
                metadata = ListDetailSceneStrategy.extraPane(sceneKey = NavPaneGroup.SETTINGS),
            ) {
                ThemeSettingsScreen(onBack = { nav.back() })
            }
            entry<WebDavSettingsRoute>(
                metadata = ListDetailSceneStrategy.detailPane(sceneKey = NavPaneGroup.SETTINGS),
            ) {
                val vm = koinViewModel<WebDavViewModel>()
                WebDavSettingsScreen(onBack = { nav.back() }, viewModel = vm)
            }
            entry<FeedbackRoute>(
                metadata = ListDetailSceneStrategy.extraPane(sceneKey = NavPaneGroup.SETTINGS),
            ) {
                val vm = koinViewModel<FeedbackViewModel>()
                FeedbackScreen(onBack = { nav.back() }, viewModel = vm)
            }
            entry<DisclaimerRoute>(
                metadata = ListDetailSceneStrategy.extraPane(sceneKey = NavPaneGroup.SETTINGS),
            ) {
                com.radium.inkwell.ui.legal.DisclaimerScreen(onBack = { nav.back() })
            }
            entry<DeveloperSettingsRoute>(
                metadata = ListDetailSceneStrategy.extraPane(sceneKey = NavPaneGroup.SETTINGS),
            ) {
                val vm = koinViewModel<com.radium.inkwell.ui.settings.DeveloperViewModel>()
                com.radium.inkwell.ui.settings.DeveloperSettingsScreen(onBack = { nav.back() }, viewModel = vm)
            }
        },
    )
}

@Composable
private fun PanePlaceholder(text: String) {
    Box(
        Modifier.fillMaxSize().padding(Dimens.screenPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
