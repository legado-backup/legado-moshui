package com.radium.inkwell.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ExperimentalGraphicsApi
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 磨砂玻璃视觉令牌。
 *
 * 放在这里而不是 [Dimens]：模糊半径 / tint 透明度只服务玻璃层这一种效果，
 * 和布局间距不是一类参数；真正跨页复用的尺寸仍走 Dimens。半径用 dp 声明、
 * 绘制时再按 density 转 px，避免不同密度下手感不一致。
 */
object Glass {
    /**
     * 模糊半径。
     *
     * 对齐澎湃 OS 那种「几乎实心、只透一点色」的磨砂：半径要够大，
     * 让底下那不到一成的透出只剩色块，读不出书名/封面字。
     */
    val blurRadius = 36.dp
    /**
     * tint（画布色）盖在模糊层上的不透明度。
     *
     * 对齐澎湃 OS 4 设置页顶栏：接近实心，内容只以极淡色晕透出。
     * 0.74 那档太透 —— 书封上的大字能直接读出来，玻璃感变成了「透明亚克力板」。
     * 再高到 0.96+ 就和普通实心顶栏没差别，滚过去看不出效果。
     */
    const val TintAlpha = 0.92f
    /** 底边发丝线的透明度：底色已经接近实心，分界线只需很轻的一笔 */
    const val EdgeAlpha = 0.20f
    /** 发丝线粗细（对齐 M3 Divider 的 1dp，不另发明刻度） */
    val edgeLine = 1.dp
}

/**
 * 玻璃顶栏宿主：内容整页铺开并**钻到玻璃下面**滚动；[header] 浮在顶部，
 * 背景是对下方内容层的实时模糊 + 半透明 tint。
 *
 * 为什么不用 Scaffold.topBar：Scaffold 会把 content 垫到 topBar 之下，书滚不进栏后，
 * 玻璃也就无东西可透。所以这里用 Box 叠层：下层用 [androidx.compose.ui.graphics.layer.GraphicsLayer]
 * 录制整页 → 上层只在 header 区域用 [BlurEffect] 重绘该层。
 *
 * 层从 [LocalGraphicsContext] 创建（本 Compose 版本没有 `rememberGraphicsLayer` 便捷 API），
 * 离开组合时必须 release，否则 layer 句柄会堆在 view 的 GraphicsContext 里。
 *
 * 依赖 minSdk 35（本项目已是）：`GraphicsLayer.renderEffect` 与 `BlurEffect` 全量可用，
 * 不必引第三方 backdrop-blur 库。
 *
 * @param tint 玻璃上的半透明色，通常 = 页面画布色再降 alpha（见 [Glass.TintAlpha]）
 * @param baseColor tint 之下的实底，空白处（没有书滚过时）仍与页面画布一致
 * @param header 浮在顶部的内容（顶栏、筛选 chip 等）；高度自适应，宿主负责测量
 * @param content 列表等主体；[Dp] 参数是玻璃头当前高度，列表 contentPadding / 下拉指示器要让开它
 */
@OptIn(ExperimentalGraphicsApi::class)
@Composable
fun GlassHeaderHost(
    tint: Color,
    header: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
    baseColor: Color = tint.copy(alpha = 1f),
    content: @Composable (headerHeight: Dp) -> Unit,
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val graphicsContext = LocalGraphicsContext.current
    val backdrop = remember(graphicsContext) { graphicsContext.createGraphicsLayer() }
    DisposableEffect(graphicsContext, backdrop) {
        onDispose { graphicsContext.releaseGraphicsLayer(backdrop) }
    }
    var headerPx by remember { mutableIntStateOf(-1) }
    // 未测到之前用「状态栏 + 标准顶栏高」兜底，避免首帧列表顶到状态栏底下再跳一下
    val fallbackHeader =
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
            TopAppBarDefaults.TopAppBarExpandedHeight
    val headerHeight = if (headerPx >= 0) {
        with(density) { headerPx.toDp() }
    } else {
        fallbackHeader
    }
    val blurPx = with(density) { Glass.blurRadius.toPx() }
    val edgePx = with(density) { Glass.edgeLine.toPx() }
    val edgeColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = Glass.EdgeAlpha)

    Box(modifier.fillMaxSize().background(baseColor)) {
        // 下层：整页内容。每次绘制先录进 GraphicsLayer，再原样画出来 ——
        // 玻璃层读的就是这份录制结果，所以列表在栏后滑动时模糊会跟着走。
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    val layerSize = IntSize(
                        this.size.width.roundToInt(),
                        this.size.height.roundToInt(),
                    )
                    backdrop.record(this, layoutDirection, layerSize) {
                        this@drawWithContent.drawContent()
                    }
                    drawLayer(backdrop)
                },
        ) {
            content(headerHeight)
        }

        // 上层：玻璃头。把录制层按 header 区域裁切后做模糊重绘，再叠 tint 与发丝线，
        // 最后才画 header 自己的内容（图标/文字不被模糊）。
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .onGloballyPositioned { headerPx = it.size.height }
                .clipToBounds()
                .drawWithContent {
                    backdrop.renderEffect = BlurEffect(blurPx, blurPx, TileMode.Clamp)
                    drawLayer(backdrop)
                    backdrop.renderEffect = null
                    drawRect(color = tint)
                    drawLine(
                        color = edgeColor,
                        start = Offset(0f, size.height - edgePx),
                        end = Offset(size.width, size.height - edgePx),
                        strokeWidth = edgePx,
                    )
                    drawContent()
                },
            content = header,
        )
    }
}
