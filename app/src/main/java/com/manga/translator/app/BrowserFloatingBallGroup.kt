package com.manga.translator.app

import android.util.Log
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.manga.translator.app.ui.MtComicBallIcon
import com.manga.translator.app.ui.MtScanIcon
import com.manga.translator.app.ui.MtWebpageBallIcon
import com.manga.translator.app.ui.theme.MtColors
import com.manga.translator.store.BrowserFloatingBallPosition
import kotlin.math.roundToInt

private const val TAG = "EmbeddedBrowser"

/** 组内图标相对球径的比例（26dp / 48dp），与参考项目的 26/36 图标占比接近。 */
private const val BALL_ICON_RATIO = 26f / 48f

/**
 * 内嵌浏览器翻译悬浮组（2026-11-01 用户要求「网页文字球和漫画球放一块」）。
 *
 * 结构（参考 C:\homework\translator 的 `mt-floating-ball-group`）：
 * - 竖排两颗 48dp 球，间距 6dp：上 = 网页文字，下 = 漫画；
 * - **只有一份位置**：整组拖动、松手吸附左右边缘、按漫画球锚点存比例；
 * - 球上方保留 ⋮ 胶囊，菜单合并「截图翻译 / 原文｜译文 / 网页文字翻译」；
 * - 长按任一球也打开同一个菜单；点菜单外空白、点球、页面跳转都会收起。
 *
 * 状态呈现见 [BrowserFloatingBallPresentation]：图标常驻，忙碌转圈、运行红圈+停止角标、
 * 已翻译绿对勾、截图在屏蓝 ✕ —— 两颗球任何时候都能认出是「漫画」还是「网页」。
 *
 * 几何规则（夹紧 / 吸附 / 菜单与胶囊朝向）全部在 [BrowserFloatingBallPolicy]（纯函数 + 单测），
 * 本组件只负责手势与绘制。
 */
@Composable
internal fun BrowserFloatingBallGroup(
    comicAction: BrowserPrimaryAction?,
    comicBusy: Boolean,
    pageUrl: String,
    viewMode: BrowserViewMode,
    modeToggleEnabled: Boolean,
    modeToggleDisabledReason: String?,
    webpageTextBusy: Boolean,
    webpageTextApplied: Boolean,
    savedPosition: BrowserFloatingBallPosition?,
    onPrimaryTap: () -> Unit,
    onSelectViewMode: (BrowserViewMode) -> Unit,
    onScreenshotTranslate: () -> Unit,
    onWebpageTextTap: () -> Unit,
    onPositionChanged: (Float, Float) -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val containerSize = remember { mutableStateOf(IntSize.Zero) }
    // 拖动中的组左上角；null = 还没初始化（等容器量出尺寸后按策略给默认点）。
    var rawOffset by remember { mutableStateOf<Offset?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }

    // pointerInput(Unit) 里的手势 lambda 只在首次组合时创建：所有会变的外部状态都要
    // rememberUpdatedState 包一层，否则会永远读到首次组合那一刻的值（与旧悬浮球同一教训）。
    val latestComicBusy by rememberUpdatedState(comicBusy)
    val latestComicAction by rememberUpdatedState(comicAction)
    val latestExpanded by rememberUpdatedState(expanded)
    val latestPrimaryTap by rememberUpdatedState(onPrimaryTap)
    val latestWebpageTextBusy by rememberUpdatedState(webpageTextBusy)
    val latestWebpageTextApplied by rememberUpdatedState(webpageTextApplied)
    val latestWebpageTextTap by rememberUpdatedState(onWebpageTextTap)
    val latestOnPositionChanged by rememberUpdatedState(onPositionChanged)

    // 页面跳转后菜单里的「原文/译文」已与新页无关（onPageStarted 也会停会话）：收起。
    LaunchedEffect(pageUrl) { expanded = false }

    val ballSizePx = with(density) { BrowserFloatingBallPolicy.SIZE_DP.dp.toPx() }
    val gapPx = with(density) { BrowserFloatingBallPolicy.GROUP_GAP_DP.dp.toPx() }
    val groupHeightPx = BrowserFloatingBallPolicy.groupHeightPx(ballSizePx, gapPx)
    val comicOffsetPx = BrowserFloatingBallPolicy.comicTopOffsetPx(ballSizePx, gapPx)
    val dockMarginPx = with(density) { BrowserFloatingBallPolicy.DOCK_MARGIN_DP.dp.toPx() }
    val bottomMarginPx =
        with(density) { BrowserFloatingBallPolicy.DEFAULT_BOTTOM_MARGIN_DP.dp.toPx() }
    val menuGapPx = with(density) { BrowserFloatingBallPolicy.MENU_GAP_DP.dp.toPx() }
    val menuWidth = BrowserFloatingBallPolicy.MENU_WIDTH_DP.dp
    val menuWidthPx = with(density) { menuWidth.toPx() }
    val menuHeightPx = with(density) { BrowserFloatingBallPolicy.MENU_HEIGHT_DP.dp.toPx() }
    val capsuleWidth = BrowserFloatingBallPolicy.CAPSULE_WIDTH_DP.dp
    val capsuleHeight = BrowserFloatingBallPolicy.CAPSULE_HEIGHT_DP.dp
    val capsuleWidthPx = with(density) { capsuleWidth.toPx() }
    val capsuleHeightPx = with(density) { capsuleHeight.toPx() }
    val capsuleGapPx = with(density) { BrowserFloatingBallPolicy.CAPSULE_GAP_DP.dp.toPx() }

    val latestBallSizePx by rememberUpdatedState(ballSizePx)
    val latestGroupHeightPx by rememberUpdatedState(groupHeightPx)
    val latestComicOffsetPx by rememberUpdatedState(comicOffsetPx)
    val latestDockMarginPx by rememberUpdatedState(dockMarginPx)

    /**
     * 拖动结束时落盘。
     *
     * 保存的是**下方漫画球的锚点**比例：旧版本只存过漫画球位置，这样升级后不用迁移，
     * 漫画球仍落在用户上次记忆的位置，网页文字球出现在它上方。
     */
    fun persistGroupPosition() {
        val current = rawOffset ?: return
        val size = containerSize.value
        if (size.width <= 0 || size.height <= 0) return
        val width = size.width.toFloat()
        val height = size.height.toFloat()
        val dockedX = BrowserFloatingBallPolicy.dockX(
            current.x, latestBallSizePx, width, latestDockMarginPx
        )
        val comicTop = BrowserFloatingBallPolicy.comicTopForGroup(
            current.y, latestComicOffsetPx
        )
        val ratio = BrowserFloatingBallPolicy.encodePosition(
            Offset(dockedX, comicTop), latestBallSizePx, width, height
        ) ?: return
        latestOnPositionChanged(ratio.x, ratio.y)
        Log.d(
            TAG,
            "ball_group_position_save x=${ratio.x} y=${ratio.y} " +
                "groupTop=${current.y} comicTop=$comicTop groupH=$latestGroupHeightPx " +
                "container=${size.width}x${size.height}"
        )
    }

    Box(modifier = Modifier.fillMaxSize().onSizeChanged { containerSize.value = it }) {
        // 首次布局：优先恢复上次位置；没有记录才给默认停靠点（右边缘、贴内容区底）。
        LaunchedEffect(containerSize.value, savedPosition) {
            if (rawOffset == null && containerSize.value.width > 0 && containerSize.value.height > 0) {
                val width = containerSize.value.width.toFloat()
                val height = containerSize.value.height.toFloat()
                // 旧位置记录的是漫画球锚点：先按单球尺寸解码，再换算成组左上角。
                val comicAnchor = savedPosition?.let { saved ->
                    BrowserFloatingBallPolicy.decodePosition(
                        ratio = Offset(saved.xRatio, saved.yRatio),
                        sizePx = ballSizePx,
                        containerWidthPx = width,
                        containerHeightPx = height
                    )
                }
                rawOffset = if (comicAnchor != null) {
                    BrowserFloatingBallPolicy.clampGroup(
                        raw = Offset(
                            comicAnchor.x,
                            BrowserFloatingBallPolicy.groupTopForComic(
                                comicAnchor.y, comicOffsetPx
                            )
                        ),
                        ballSizePx = ballSizePx,
                        groupHeightPx = groupHeightPx,
                        containerWidthPx = width,
                        containerHeightPx = height
                    )
                } else {
                    BrowserFloatingBallPolicy.defaultGroupPosition(
                        containerWidthPx = width,
                        containerHeightPx = height,
                        ballSizePx = ballSizePx,
                        comicOffsetPx = comicOffsetPx,
                        dockMarginPx = dockMarginPx,
                        bottomMarginPx = bottomMarginPx
                    )
                }
                Log.d(
                    TAG,
                    "ball_group_position_restore saved=${savedPosition != null} " +
                        "groupY=${rawOffset?.y} comicY=${rawOffset?.let { it.y + comicOffsetPx }} " +
                        "groupH=$groupHeightPx container=${containerSize.value.width}x${containerSize.value.height}"
                )
            }
        }
        val raw = rawOffset ?: return@Box
        val clamped = BrowserFloatingBallPolicy.clampGroup(
            raw = raw,
            ballSizePx = ballSizePx,
            groupHeightPx = groupHeightPx,
            containerWidthPx = containerSize.value.width.toFloat(),
            containerHeightPx = containerSize.value.height.toFloat()
        )
        val dockedRight = BrowserFloatingBallPolicy.isDockedRight(
            clamped.x, ballSizePx, containerSize.value.width.toFloat()
        )
        // 拖动时跟手（snap），松手后从当前位置滑到吸附点（tween）。
        val ballX by animateFloatAsState(
            targetValue = if (dragging) {
                clamped.x
            } else {
                BrowserFloatingBallPolicy.dockX(
                    clamped.x, ballSizePx, containerSize.value.width.toFloat(), dockMarginPx
                )
            },
            animationSpec = if (dragging) snap() else tween(durationMillis = 160),
            label = "browserBallGroupX"
        )
        val groupY = clamped.y
        val comicY = BrowserFloatingBallPolicy.comicTopForGroup(groupY, comicOffsetPx)
        val menuLeft = BrowserFloatingBallPolicy.menuLeft(
            ballX = ballX,
            sizePx = ballSizePx,
            menuWidthPx = menuWidthPx,
            containerWidthPx = containerSize.value.width.toFloat(),
            dockedRight = dockedRight,
            menuGapPx = menuGapPx
        )
        val menuTop = BrowserFloatingBallPolicy.menuTop(
            groupTopY = groupY,
            menuHeightPx = menuHeightPx,
            containerHeightPx = containerSize.value.height.toFloat()
        )
        val capsuleLeft = BrowserFloatingBallPolicy.capsuleLeft(
            ballX = ballX,
            ballWidthPx = ballSizePx,
            capsuleWidthPx = capsuleWidthPx
        )
        val capsuleTop = BrowserFloatingBallPolicy.capsuleTop(
            groupTopY = groupY,
            groupHeightPx = groupHeightPx,
            capsuleHeightPx = capsuleHeightPx,
            gapPx = capsuleGapPx
        )

        // 展开时的透明 scrim：点空白收起；同时挡住对网页的误触（菜单开着时页面不该响应）。
        if (expanded) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(Unit) { detectTapGestures { expanded = false } }
            )
        }

        // 合并菜单：球在右半 → 菜单朝左，球在左半 → 朝右；纵向与组顶对齐并夹紧。
        if (expanded) {
            Column(
                modifier = Modifier
                    .offset { IntOffset(menuLeft.roundToInt(), menuTop.roundToInt()) }
                    .width(menuWidth)
                    .shadow(6.dp, RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .background(MtColors.Surface)
                    .border(1.dp, MtColors.Border, RoundedCornerShape(14.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            expanded = false
                            Log.d(TAG, "capture_shortcut_from_ball")
                            onScreenshotTranslate()
                        }
                        .padding(horizontal = 8.dp)
                        .semantics { contentDescription = "截图翻译（悬浮球菜单）" },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = MtScanIcon,
                        contentDescription = null,
                        tint = MtColors.TextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "截图翻译",
                        fontSize = 14.sp,
                        color = MtColors.TextPrimary,
                        maxLines = 1
                    )
                }
                // 与底栏同一个开关组件（同一份状态、同一套禁用规则）；
                // 没有译文可切时整块置灰，点它 toast 说明原因。
                Box(
                    modifier = Modifier.clickable(enabled = !modeToggleEnabled) {
                        Toast.makeText(
                            context,
                            modeToggleDisabledReason ?: "暂时不可用",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                ) {
                    BrowserViewModeToggle(
                        mode = viewMode,
                        enabled = modeToggleEnabled,
                        disabledReason = modeToggleDisabledReason,
                        onSelect = onSelectViewMode
                    )
                }
            }
        }

        // ⋮ 胶囊（画布 `23:167`）：菜单唯一的常驻展开入口，点它开/关。
        // 收起态 = 白底 + 灰描边，展开态 = 浅蓝底 + 蓝描边 + 蓝点（与球形成一组）。
        Box(
            modifier = Modifier
                .offset { IntOffset(capsuleLeft.roundToInt(), capsuleTop.roundToInt()) }
                .size(capsuleWidth, capsuleHeight)
                .shadow(4.dp, RoundedCornerShape(18.dp))
                .clip(RoundedCornerShape(18.dp))
                .background(
                    // 轻微透底：胶囊也只该是一条轻提示，不该像一张白贴片压在画面上。
                    if (expanded) MtColors.BrandTint else MtColors.Surface.copy(alpha = 0.96f)
                )
                .border(
                    width = 1.dp,
                    color = if (expanded) MtColors.BrandInk else MtColors.Border,
                    shape = RoundedCornerShape(18.dp)
                )
                .clickable {
                    expanded = !expanded
                    Log.d(TAG, "ball_capsule_tap expanded=$expanded")
                }
                .semantics {
                    contentDescription = if (expanded) "收起翻译菜单" else "打开翻译菜单"
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = null,
                tint = if (expanded) MtColors.BrandInk else MtColors.TextPrimary,
                modifier = Modifier.size(16.dp)
            )
        }

        // 上球：网页文字（浏览器窗口图标）。
        BrowserActionBall(
            modifier = Modifier.offset {
                IntOffset(ballX.roundToInt(), groupY.roundToInt())
            },
            size = BrowserFloatingBallPolicy.SIZE_DP.dp,
            icon = MtWebpageBallIcon,
            background = MtColors.BrandInk,
            presentation = BrowserFloatingBallPresentation.webpage(
                busy = webpageTextBusy,
                applied = webpageTextApplied
            ),
            contentDescription = when {
                webpageTextBusy -> "网页文字翻译悬浮球：正在翻译页面文字"
                webpageTextApplied -> "网页文字翻译悬浮球：已翻译，点按恢复原文"
                else -> "网页文字翻译悬浮球：点按翻译页面文字"
            },
            onTap = {
                if (latestExpanded) {
                    expanded = false
                } else {
                    Log.d(
                        TAG,
                        "text_ball_tap busy=$latestWebpageTextBusy " +
                            "applied=$latestWebpageTextApplied"
                    )
                    if (!latestWebpageTextBusy) latestWebpageTextTap()
                }
            },
            onLongPress = {
                expanded = true
                Log.d(TAG, "ball_menu_open source=text")
            },
            onDragStart = { dragging = true },
            onDrag = { delta ->
                rawOffset = (rawOffset ?: Offset.Zero) + delta
            },
            onDragEnd = {
                dragging = false
                persistGroupPosition()
            }
        )

        // 下球：漫画翻译（漫画分页图标）。运行中不再是整颗红球：红圈 + 右下角停止角标，
        // 图标保留；忙碌时白圈转圈；截图在屏时右上角蓝 ✕。
        val comicPresentation = BrowserFloatingBallPresentation.comic(
            action = comicAction,
            busy = comicBusy
        )
        BrowserActionBall(
            modifier = Modifier.offset {
                IntOffset(ballX.roundToInt(), comicY.roundToInt())
            },
            size = BrowserFloatingBallPolicy.SIZE_DP.dp,
            icon = MtComicBallIcon,
            background = MtColors.Brand,
            presentation = comicPresentation,
            contentDescription = when {
                comicBusy -> "漫画翻译悬浮球：正在处理，稍后可用"
                comicAction == BrowserPrimaryAction.STOP ->
                    "漫画翻译悬浮球：翻译中，点按停止，长按打开菜单"
                comicAction == BrowserPrimaryAction.RESTORE ->
                    "漫画翻译悬浮球：截图译文显示中，点按恢复原文，长按打开菜单"
                comicAction == null -> "漫画翻译悬浮球：先打开网页"
                else -> "漫画翻译悬浮球：点按开始翻译，长按打开菜单"
            },
            onTap = {
                if (latestExpanded) {
                    expanded = false
                } else {
                    // 忙碌（截图翻译/一次性贴回）时也照常上报：宿主会把原因写进状态行。
                    Log.d(
                        TAG,
                        "ball_primary_tap busy=$latestComicBusy " +
                            "action=${latestComicAction ?: "none"}"
                    )
                    latestPrimaryTap()
                }
            },
            onLongPress = {
                expanded = true
                Log.d(TAG, "ball_menu_open source=comic")
            },
            onDragStart = { dragging = true },
            onDrag = { delta ->
                rawOffset = (rawOffset ?: Offset.Zero) + delta
            },
            onDragEnd = {
                dragging = false
                persistGroupPosition()
            }
        )
    }
}

/**
 * 组内的一颗球：固定图标 + 深色/品牌底 + 状态外圈/角标。
 *
 * 手势分工：单击 = 该球的主动作；长按 = 打开合并菜单；拖动 = 整组移动（由调用方共享偏移）。
 * 拖动和单击放在两个 pointerInput 里（与旧实现一致）：点按不会被拖动阈值吞掉，
 * 拖动一旦成立，tap 检测器会自行取消。
 */
@Composable
private fun BrowserActionBall(
    modifier: Modifier,
    size: Dp,
    icon: ImageVector,
    background: Color,
    presentation: BrowserBallPresentation,
    contentDescription: String,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit
) {
    val latestOnTap by rememberUpdatedState(onTap)
    val latestOnLongPress by rememberUpdatedState(onLongPress)
    val latestOnDragStart by rememberUpdatedState(onDragStart)
    val latestOnDrag by rememberUpdatedState(onDrag)
    val latestOnDragEnd by rememberUpdatedState(onDragEnd)

    Box(
        modifier = modifier
            .size(size)
            .alpha(if (presentation.dimmed) 0.45f else 1f)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { latestOnTap() },
                    onLongPress = { latestOnLongPress() }
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { latestOnDragStart() },
                    onDragEnd = { latestOnDragEnd() },
                    onDragCancel = { latestOnDragEnd() }
                ) { change, dragAmount ->
                    change.consume()
                    latestOnDrag(dragAmount)
                }
            }
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .shadow(6.dp, CircleShape)
                .clip(CircleShape)
                .background(background),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(size * BALL_ICON_RATIO)
            )
        }

        // 运行中：外圈红环（图标保留，右下角再挂停止角标）。
        if (presentation.runningRing) {
            Box(
                modifier = Modifier
                    .size(size + 4.dp)
                    .border(2.dp, MtColors.Danger, CircleShape)
            )
        }

        // 忙碌：白色转圈环（不盖掉图标，只绕在球外）。
        if (presentation.spinner) {
            CircularProgressIndicator(
                modifier = Modifier.size(size + 4.dp),
                color = Color.White,
                strokeWidth = 2.dp
            )
        }

        presentation.badge?.let { BrowserBallBadgeView(it) }
    }
}

/** 状态角标：已翻译 = 右上绿 ✓；运行 = 右下红停止；截图在屏 = 右上蓝 ✕。 */
@Composable
private fun BoxScope.BrowserBallBadgeView(badge: BrowserBallBadge) {
    val isStop = badge == BrowserBallBadge.STOP
    val color = when (badge) {
        BrowserBallBadge.CHECK -> Color(0xFF2E7D32)
        BrowserBallBadge.STOP -> Color(0xFFC62828)
        BrowserBallBadge.RESTORE -> MtColors.BrandInk
    }
    Box(
        modifier = Modifier
            .align(if (isStop) Alignment.BottomEnd else Alignment.TopEnd)
            // 角标向球外探出 3dp；纵向压回球内，避免贴到内容区顶/底时被裁。
            .offset(x = 3.dp, y = if (isStop) (-2).dp else 2.dp)
            .size(18.dp)
            .shadow(3.dp, CircleShape)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center
    ) {
        when (badge) {
            BrowserBallBadge.CHECK -> Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(11.dp)
            )
            BrowserBallBadge.RESTORE -> Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(11.dp)
            )
            BrowserBallBadge.STOP -> Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(Color.White)
            )
        }
    }
}

/**
 * 「原文｜译文」两段式视图开关（2026-10-02）。
 *
 * 与「翻译/停止」主按钮的分工是硬的：这个开关只决定**看什么**（译文层挂不挂在页面上），
 * 不启停任何任务；主按钮只决定**翻不翻**。两者形状也必须不同 —— 分段控件一眼能看出
 * 「当前选中哪个」，而这正是改造前并排两个同款胶囊按钮读不出来的信息。
 *
 * 没有译文可切时（会话没跑且屏上无译文层）**整块置灰禁用**，而不是「能点但点了没反应」。
 */
@Composable
private fun BrowserViewModeToggle(
    mode: BrowserViewMode,
    enabled: Boolean,
    disabledReason: String?,
    onSelect: (BrowserViewMode) -> Unit
) {
    val desc = if (enabled) {
        "原文/译文显示切换"
    } else {
        "原文/译文显示切换（${disabledReason ?: "不可用"}）"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MtColors.SurfaceMuted)
            .padding(3.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .semantics { contentDescription = desc },
        verticalAlignment = Alignment.CenterVertically
    ) {
        BrowserViewModeSegment(
            label = "原文",
            target = BrowserViewMode.ORIGINAL,
            mode = mode,
            enabled = enabled,
            onSelect = onSelect,
            modifier = Modifier.weight(1f)
        )
        BrowserViewModeSegment(
            label = "译文",
            target = BrowserViewMode.TRANSLATED,
            mode = mode,
            enabled = enabled,
            onSelect = onSelect,
            modifier = Modifier.weight(1f)
        )
    }
}

/** 分段开关的一段；选中段用品牌色浅底 + 蓝字（与菜单里的选中态同一套视觉语言）。 */
@Composable
private fun BrowserViewModeSegment(
    label: String,
    target: BrowserViewMode,
    mode: BrowserViewMode,
    enabled: Boolean,
    onSelect: (BrowserViewMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedNow = mode == target
    Box(
        modifier = modifier
            .height(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selectedNow) MtColors.BrandTint else Color.Transparent)
            // 点已选中的那一段不做任何事（它本来就已经是当前状态，不该看起来"没反应"）。
            .clickable(enabled = enabled && !selectedNow) { onSelect(target) }
            .semantics {
                contentDescription = "显示$label"
                selected = selectedNow
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = if (selectedNow) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selectedNow) MtColors.BrandInk else MtColors.TextSecondary
        )
    }
}
