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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 顶栏遮罩视觉令牌。
 *
 * **Material 3 没有官方磨砂玻璃 / backdrop-blur 组件。** 规范里「内容滚到顶栏下」的
 * 做法是实心 tonal 表面（`TopAppBar` 的 `scrolledContainerColor`），用色阶分层表达深度，
 * 不是透视底下内容。自研玻璃若 tint 偏低，彩色封面会和标题搅在一起（对比度也垮）。
 *
 * 这里采用 MD3 允许的渐变形态：**实心画布色 + 底边 scrim**（表面色 → 透明）。
 * 顶栏区完全遮住内容，只在底边一条带上溶进列表 —— 有「从栏下滚过」的层次，无透视噪音。
 */
object Glass {
    /**
     * 底边 scrim 高度。
     *
     * 实心画布色在这条带上溶到全透明，内容从栏底浮出。
     * 取 32dp：比一行字高略多，渐变读得出来；再高会像顶栏软塌一块。
     */
    val bottomFade = 32.dp
}

/**
 * 顶栏遮罩宿主：内容整页铺开并钻到栏下滚动；[header] 浮在顶部。
 *
 * 绘制模型（对齐 MD3，**不做 backdrop-blur**）：
 * - [header] UI 高度内：[baseColor] **完全不透明**，书封不会透进标题区
 * - 其下 [Glass.bottomFade]：同色纵向 scrim 到透明，内容从实心底色里溶出
 *
 * 为什么不用 Scaffold.topBar：Scaffold 会把 content 垫到栏下，滚不进遮罩区。
 * 为什么不用玻璃：见 [Glass] 的 KDoc —— MD3 无此组件，透视会破坏对比度。
 *
 * @param header 浮在顶部的内容（顶栏、筛选 chip 等）；高度自适应，宿主负责测量
 * @param baseColor 顶栏实心底色，应与页面画布一致（书架用 [settingsPageColor]），否则会露出一道色缝
 * @param content 列表等主体；[Dp] 参数是 header **UI 实高**（不含 scrim 带），
 *   列表 contentPadding / 下拉指示器按它让位
 */
@Composable
fun GlassHeaderHost(
    tint: Color,
    header: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
    baseColor: Color = tint.copy(alpha = 1f),
    content: @Composable (headerHeight: Dp) -> Unit,
) {
    val density = LocalDensity.current
    // 只量 header 本体（顶栏 + chip），不含底边 scrim —— 列表让位用这个高度
    var headerPx by remember { mutableIntStateOf(-1) }
    val fallbackHeader =
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
            TopAppBarDefaults.TopAppBarExpandedHeight
    val headerHeight = if (headerPx >= 0) {
        with(density) { headerPx.toDp() }
    } else {
        fallbackHeader
    }
    val fallbackHeaderPx = with(density) { fallbackHeader.toPx() }

    Box(modifier.fillMaxSize().background(baseColor)) {
        content(headerHeight)

        // 上层：实心顶栏 + 底边 scrim。UI 画在实心段上，对比度与普通顶栏一致。
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .drawWithContent {
                    val uiHeaderPx = if (headerPx >= 0) headerPx.toFloat() else fallbackHeaderPx
                    val solidStop = (uiHeaderPx / size.height).coerceIn(0.15f, 0.92f)
                    // 0 → 实心段：baseColor 全遮；solidStop → 1：溶到透明
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to baseColor,
                            solidStop to baseColor,
                            1f to baseColor.copy(alpha = 0f),
                        ),
                        topLeft = Offset.Zero,
                        size = Size(size.width, size.height),
                    )
                    drawContent()
                },
        ) {
            Box(Modifier.fillMaxWidth().onGloballyPositioned { headerPx = it.size.height }) {
                header()
            }
            Spacer(Modifier.height(Glass.bottomFade))
        }
    }
}
