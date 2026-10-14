package com.manga.translator.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val REVEAL_WIDTH_DP = 80.dp
private val DELETE_RED = Color(0xFFF44336)

@Composable
fun SwipeRevealRow(
    modifier: Modifier = Modifier,
    onDelete: () -> Unit,
    content: @Composable RowScope.() -> Unit
) {
    val density = LocalDensity.current
    val revealWidthPx = with(density) { REVEAL_WIDTH_DP.toPx() }
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }

    LaunchedEffect(revealWidthPx) {
        offsetX.updateBounds(lowerBound = -revealWidthPx, upperBound = 0f)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
    ) {
        // 红色背景层：用 drawBehind 直接画，最可靠
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(DELETE_RED)
                }
        )

        // 删除按钮层：固定在右侧
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(REVEAL_WIDTH_DP)
                .align(Alignment.CenterEnd),
            contentAlignment = Alignment.Center
        ) {
            IconButton(onClick = {
                scope.launch { offsetX.animateTo(0f, tween(150)) }
                onDelete()
            }) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "删除",
                    tint = Color.White,
                    modifier = Modifier.size(30.dp)
                )
            }
        }

        // 内容层：可水平拖动。
        // 注意：offset 只能有一处 —— 叠加两个 offset 会让位移变成 2×offsetX（红色层露出量与实际位移错位）。
        // offset 必须在 background 之前，否则背景不随内容移动、永远盖住红色层。
        Row(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .background(MaterialTheme.colorScheme.surface)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val newOffset = (offsetX.value + dragAmount)
                                .coerceIn(-revealWidthPx, 0f)
                            if (newOffset != offsetX.value) {
                                scope.launch { offsetX.snapTo(newOffset) }
                            }
                        },
                        onDragEnd = {
                            val target =
                                if (offsetX.value < -revealWidthPx / 2f) -revealWidthPx else 0f
                            scope.launch { offsetX.animateTo(target, tween(150)) }
                        },
                        onDragCancel = {
                            scope.launch { offsetX.animateTo(0f, tween(150)) }
                        }
                    )
                },
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}
