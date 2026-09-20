package com.radium.inkwell.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ExperimentalGraphicsApi
import androidx.compose.ui.graphics.Paint
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
 * 放在这里而不是 [Dimens]：模糊半径 / tint 透明度 / 渐隐带只服务玻璃层这一种效果，
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
     * tint（画布色）盖在模糊层上的**实心区**不透明度。
     *
     * 对齐澎湃 OS 4 设置页顶栏：接近实心，内容只以极淡色晕透出。
     * 0.74 那档太透 —— 书封上的大字能直接读出来，玻璃感变成了「透明亚克力板」。
     * 再高到 0.96+ 就和普通实心顶栏没差别，滚过去看不出效果。
     *
     * 这个值只描述玻璃**上半段**；底边如何溶进内容见 [bottomFade]。
     */
    const val TintAlpha = 0.92f
    /**
     * 底边渐隐带高度。
     *
     * 澎湃 OS 的顶栏不是「一块均匀半透明板 + 直角下边」，而是上实下虚：
     * 玻璃与 tint 在这条带上从实心溶到全透明，内容像从雾里浮出来。
     * 没有这条带，再对的 TintAlpha 也会在栏底切出一刀硬边。
     */
    val bottomFade = 40.dp
}

/**
 * 玻璃顶栏宿主：内容整页铺开并**钻到玻璃下面**滚动；[header] 浮在顶部，
 * 背景是对下方内容层的实时模糊 + 半透明 tint，且**底边纵向渐隐**（见 [Glass.bottomFade]）。
 *
 * 为什么不用 Scaffold.topBar：Scaffold 会把 content 垫到 topBar 之下，书滚不进栏后，
 * 玻璃也就无东西可透。所以这里用 Box 叠层：下层用 [androidx.compose.ui.graphics.layer.GraphicsLayer]
 * 录制整页 → 上层在 header 区域用 [BlurEffect] 重绘该层。
 *
 * 渐变怎么做：模糊层和 tint 先画进 saveLayer，再用纵向 `DstIn` 渐变遮罩把底边擦透明 ——
 * 于是「模糊 + tint」一起从上到下由实变虚，而不是只给 tint 改 alpha（那样底边会露出
 * 未渐隐的模糊内容，照样是一刀切）。
 *
 * 层从 [LocalGraphicsContext] 创建（本 Compose 版本没有 `rememberGraphicsLayer` 便捷 API），
 * 离开组合时必须 release，否则 layer 句柄会堆在 view 的 GraphicsContext 里。
 *
 * 依赖 minSdk 35（本项目已是）：`GraphicsLayer.renderEffect` 与 `BlurEffect` 全量可用，
 * 不必引第三方 backdrop-blur 库。
 *
 * @param tint 玻璃实心区的半透明色，通常 = 页面画布色再降 alpha（见 [Glass.TintAlpha]）
 * @param baseColor tint 之下的实底，空白处（没有书滚过时）仍与页面画布一致
 * @param header 浮在顶部的内容（顶栏、筛选 chip 等）；高度自适应，宿主负责测量
 * @param content 列表等主体；[Dp] 参数是玻璃头 **UI 实高**（不含渐隐带），
 *   列表 contentPadding / 下拉指示器按它让位；渐隐带会盖到列表顶部一截，这是有意的
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
    // 只量 header 本体（顶栏 + chip），不含底边渐隐带 —— 列表让位用这个高度
    var headerPx by remember { mutableIntStateOf(-1) }
    val fallbackHeader =
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
            TopAppBarDefaults.TopAppBarExpandedHeight
    val headerHeight = if (headerPx >= 0) {
        with(density) { headerPx.toDp() }
    } else {
        fallbackHeader
    }
    val blurPx = with(density) { Glass.blurRadius.toPx() }
    val fadePx = with(density) { Glass.bottomFade.toPx() }
    val fallbackHeaderPx = with(density) { fallbackHeader.toPx() }
    // DstIn 遮罩：上段全黑（保留模糊+tint），从实心区底开始溶到透明
    val maskPaint = remember { Paint() }

    Box(modifier.fillMaxSize().background(baseColor)) {
        // 下层：整页内容。每次绘制先录进 GraphicsLayer，再原样画出来 ——
        // 玻璃层读的是这份录制结果，所以列表在栏后滑动时模糊会跟着走。
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

        // 上层：玻璃头。绘制高度 = header UI + 底边渐隐带；
        // 遮罩后的模糊/tint 只出现在这块区域，UI 文字在 restore 之后画，不受遮罩影响。
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .clipToBounds()
                .drawWithContent {
                    val uiHeaderPx = if (headerPx >= 0) headerPx.toFloat() else fallbackHeaderPx
                    // 实心段占整块绘制高度的比例；至少留一点渐变，避免 stops 重叠
                    val solidStop = (uiHeaderPx / size.height).coerceIn(0.15f, 0.88f)
                    val canvas = drawContext.canvas
                    canvas.saveLayer(Rect(0f, 0f, size.width, size.height), maskPaint)
                    backdrop.renderEffect = BlurEffect(blurPx, blurPx, TileMode.Clamp)
                    drawLayer(backdrop)
                    backdrop.renderEffect = null
                    drawRect(color = tint)
                    // DstIn：遮罩不透明处保留层内容，透明处把层内容擦掉 —— 模糊与 tint 一起渐隐
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Black,
                            solidStop to Color.Black,
                            1f to Color.Transparent,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                    canvas.restore()
                    drawContent()
                },
        ) {
            // header 本体：高度单独测量，供列表 contentPadding 使用
            Box(Modifier.fillMaxWidth().onGloballyPositioned { headerPx = it.size.height }) {
                header()
            }
            // 渐隐带：不放内容，只把玻璃绘制区拉长，让底边溶进列表而不是切在 UI 底上
            Spacer(Modifier.height(Glass.bottomFade))
        }
    }
}
