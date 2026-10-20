/*
 * Copyright (C) 2026 The Lujarch Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package org.lujarch.installer.sheet

import android.graphics.drawable.Drawable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.launch

/** The line icons of the installer's sheets. */
object InsIcons {
    private fun line(vararg paths: String): ImageVector =
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .apply {
                for (d in paths) {
                    addPath(
                        pathData = addPathNodes(d),
                        stroke = SolidColor(Color.Black),
                        strokeLineWidth = 2f,
                        strokeLineCap = StrokeCap.Round,
                        strokeLineJoin = StrokeJoin.Round,
                    )
                }
            }
            .build()

    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r},${cy}a$r,$r 0 1,0 ${2 * r},0a$r,$r 0 1,0 ${-2 * r},0"

    private fun box(x: Float, y: Float, w: Float, h: Float, r: Float) =
        "M${x + r},${y}h${w - 2 * r}a$r,$r 0 0 1 $r,$r" + "v${h - 2 * r}a$r,$r 0 0 1 ${-r},$r" +
            "h${-(w - 2 * r)}a$r,$r 0 0 1 ${-r},${-r}" + "v${-(h - 2 * r)}a$r,$r 0 0 1 $r,${-r}z"

    val back by lazy { line("M19 12H5M11 6l-6 6 6 6") }
    val forward by lazy { line("M9 6l6 6-6 6") }
    val check by lazy { line("M5 12l5 5 9-10") }
    val file by lazy { line("M6 3h8l4 4v14H6z") }
    val shield by lazy { line("M12 3l7 3v5c0 4.5-3 8.2-7 10-4-1.8-7-5.5-7-10V6z") }
    val shieldCheck by lazy { line("M12 3l7 3v5c0 4.5-3 8.2-7 10-4-1.8-7-5.5-7-10V6z", "M9 12l2 2 4-4") }
    val shieldAlert by lazy { line("M12 3l7 3v5c0 4.5-3 8.2-7 10-4-1.8-7-5.5-7-10V6z", "M12 8.5v4M12 15.5v.5") }
    val lock by lazy { line(box(5f, 10f, 14f, 10f, 2.5f), "M8 10V7a4 4 0 0 1 8 0v3") }
    val parts by lazy {
        line(box(4f, 4f, 7f, 7f, 2f), box(13f, 4f, 7f, 7f, 2f), box(4f, 13f, 7f, 7f, 2f), box(13f, 13f, 7f, 7f, 2f))
    }
    val components by lazy {
        line(circle(6f, 12f, 2.5f), circle(18f, 6f, 2.5f), circle(18f, 18f, 2.5f), "M8.3 11l7.4-3.8M8.3 13l7.4 3.8")
    }
    val options by lazy { line("M4 7h9M17 7h3M4 17h3M11 17h9", circle(15f, 7f, 2f), circle(9f, 17f, 2f)) }
}

/** The installer's text styles, as the sheets' design sets them. */
object InsText {
    val title: TextStyle
        @Composable get() = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium)

    val t1: TextStyle
        @Composable get() = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)

    val t2: TextStyle
        @Composable get() = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp)

    val mono: TextStyle
        @Composable get() = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)
}

/**
 * The sheet over a dimmed screen: it rises when shown and falls when [visible] turns false, then
 * [onGone]; dragging its handle down or tapping outside it dismisses it when [dismissable].
 */
@Composable
fun SheetFrame(
    visible: Boolean,
    dismissable: Boolean,
    onDismiss: () -> Unit,
    onGone: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    val shown = remember { MutableTransitionState(false) }
    shown.targetState = visible
    LaunchedEffect(shown.isIdle, shown.currentState) {
        if (shown.isIdle && !shown.currentState && !visible) onGone()
    }
    val scrim by animateColorAsState(
        if (visible) Color.Black.copy(alpha = 0.32f) else Color.Transparent,
        motion.defaultEffectsSpec(),
        label = "scrim",
    )
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val dismissAt = with(LocalDensity.current) { 120.dp.toPx() }
    BoxWithConstraints(
        contentAlignment = Alignment.BottomCenter,
        modifier =
            Modifier.fillMaxSize()
                .background(scrim)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    if (dismissable) onDismiss()
                },
    ) {
        val maxSheet = maxHeight * 0.92f
        AnimatedVisibility(
            visibleState = shown,
            enter = slideInVertically(motion.defaultSpatialSpec()) { it } + fadeIn(motion.fastEffectsSpec()),
            exit = slideOutVertically(motion.fastSpatialSpec()) { it } + fadeOut(motion.fastEffectsSpec()),
        ) {
            Column(
                modifier =
                    Modifier.widthIn(max = 640.dp)
                        .fillMaxWidth()
                        .heightIn(max = maxSheet)
                        .offset { androidx.compose.ui.unit.IntOffset(0, drag.value.toInt()) }
                        .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                        .navigationBarsPadding()
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier.fillMaxWidth()
                            .height(22.dp)
                            .draggable(
                                orientation = Orientation.Vertical,
                                enabled = dismissable,
                                state = rememberDraggableState { d -> scope.launch { drag.snapTo((drag.value + d).coerceAtLeast(0f)) } },
                                onDragStopped = { velocity ->
                                    if (drag.value > dismissAt || velocity > 2000f) onDismiss()
                                    drag.animateTo(0f, motion.defaultSpatialSpec())
                                },
                            ),
                ) {
                    Box(
                        Modifier.size(width = 32.dp, height = 4.dp)
                            .alpha(0.5f)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.outline)
                    )
                }
                content()
            }
        }
    }
}

/** A page of a sheet: deeper pages come in from the end, the work page from below. */
@Composable
fun <R : Any> SheetPages(route: R, depth: (R) -> Int, rising: (R) -> Boolean, modifier: Modifier = Modifier, content: @Composable (R) -> Unit) {
    val motion = MaterialTheme.motionScheme
    val density = LocalDensity.current
    val shift = with(density) { 64.dp.roundToPx() }
    val rise = with(density) { 40.dp.roundToPx() }
    val ltr = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1
    AnimatedContent(
        targetState = route,
        transitionSpec = {
            if (rising(targetState) && !rising(initialState)) {
                (slideInVertically(motion.defaultSpatialSpec()) { rise } + fadeIn(motion.defaultEffectsSpec()))
                    .togetherWith(slideOutHorizontally(motion.defaultSpatialSpec()) { -ltr * shift } + fadeOut(motion.fastEffectsSpec()))
            } else {
                val side = ltr * if (depth(targetState) >= depth(initialState)) 1 else -1
                (slideInHorizontally(motion.defaultSpatialSpec()) { side * shift } + fadeIn(motion.defaultEffectsSpec()))
                    .togetherWith(slideOutHorizontally(motion.defaultSpatialSpec()) { -side * shift } + fadeOut(motion.fastEffectsSpec()))
            }.using(SizeTransform(clip = true) { _, _ -> motion.defaultSpatialSpec() })
        },
        contentAlignment = Alignment.TopCenter,
        modifier = modifier,
        label = "SheetPages",
    ) {
        content(it)
    }
}

@Composable
fun PageHeader(title: String, onBack: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp, end = 16.dp),
    ) {
        IconButton(onClick = onBack) { Icon(InsIcons.back, contentDescription = stringResource(R.string.ins_cancel)) }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
    }
}

@Composable
fun GroupTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 6.dp).semantics { heading() },
    )
}

enum class TagLook { PLAIN, NEW, ASK, BAD, OK }

@Composable
fun Tag(text: String, look: TagLook = TagLook.PLAIN, mono: Boolean = false) {
    val c = MaterialTheme.colorScheme
    val (bg, fg) =
        when (look) {
            TagLook.PLAIN -> c.surfaceContainerHigh to c.onSurfaceVariant
            TagLook.NEW -> c.tertiaryContainer to c.onTertiaryContainer
            TagLook.ASK, TagLook.OK -> c.primaryContainer to c.onPrimaryContainer
            TagLook.BAD -> c.errorContainer to c.onErrorContainer
        }
    Text(
        text,
        style =
            if (mono) InsText.mono
            else MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
        color = fg,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
fun TitleAndSummary(title: String, summary: String?, modifier: Modifier = Modifier, monoTitle: Boolean = false, monoSummary: Boolean = false, titleColor: Color? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = modifier) {
        Text(
            title,
            style = if (monoTitle) InsText.mono.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium) else InsText.t1,
            color = titleColor ?: MaterialTheme.colorScheme.onSurface,
        )
        if (!summary.isNullOrEmpty()) {
            Text(summary, style = if (monoSummary) InsText.mono else InsText.t2, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A row of a page: its icon, its title and summary, and what follows them. */
@Composable
fun SheetRow(
    title: String,
    summary: String?,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    onClick: (() -> Unit)? = null,
    start: Dp = if (icon == null) 24.dp else 12.dp,
    monoTitle: Boolean = false,
    monoSummary: Boolean = false,
    titleColor: Color? = null,
    chevron: Boolean = onClick != null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 64.dp)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick).semantics { role = Role.Button } else Modifier)
                .padding(start = start, end = 20.dp, top = 8.dp, bottom = 8.dp),
    ) {
        if (icon != null) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp)) {
                Icon(icon, contentDescription = null, tint = iconTint ?: MaterialTheme.colorScheme.onSurface)
            }
        }
        TitleAndSummary(title, summary, Modifier.weight(1f), monoTitle, monoSummary, titleColor)
        trailing()
        if (chevron) {
            Icon(InsIcons.forward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun SwitchRow(title: String, summary: String?, checked: Boolean, enabled: Boolean = true, monoTitle: Boolean = false, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 64.dp)
                .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
                .padding(start = 24.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
    ) {
        TitleAndSummary(title, summary, Modifier.weight(1f).padding(end = 12.dp), monoTitle = monoTitle)
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Two to four choices as connected buttons, with their title above them. */
@Composable
fun <T> Segments(title: String, choices: List<Pair<T, String>>, value: T, onPick: (T) -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
    ) {
        Text(title, style = InsText.t1, color = MaterialTheme.colorScheme.onSurface)
        Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween), modifier = Modifier.fillMaxWidth()) {
            choices.forEachIndexed { i, (v, label) ->
                ToggleButton(
                    checked = v == value,
                    onCheckedChange = { onPick(v) },
                    shapes =
                        when (i) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            choices.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                    colors =
                        ToggleButtonDefaults.toggleButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            checkedContainerColor = MaterialTheme.colorScheme.secondary,
                            checkedContentColor = MaterialTheme.colorScheme.onSecondary,
                        ),
                    modifier = Modifier.weight(1f).height(44.dp),
                ) {
                    Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Four facts in a two-by-two grid whose outer corners are round. */
@Composable
fun Facts(facts: List<Pair<String, String>>) {
    val big = 20.dp
    val small = 6.dp
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
    ) {
        for (row in facts.chunked(2).withIndex()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.value.forEachIndexed { i, (k, v) ->
                    val top = row.index == 0
                    val bottom = row.index == (facts.size - 1) / 2
                    val shape =
                        RoundedCornerShape(
                            topStart = if (top && i == 0) big else small,
                            topEnd = if (top && i == 1) big else small,
                            bottomStart = if (bottom && i == 0) big else small,
                            bottomEnd = if (bottom && i == 1) big else small,
                        )
                    Column(
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier =
                            Modifier.weight(1f)
                                .height(68.dp)
                                .clip(shape)
                                .background(MaterialTheme.colorScheme.surfaceContainer)
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                    ) {
                        Text(k, fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        Text(v, style = InsText.t1, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (row.value.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

enum class BadgeLook { APP, DONE, FAILED }

/** The app's icon, which turns into a tick when it is installed and a mark when it failed. */
@Composable
fun AppBadge(icon: Drawable?, look: BadgeLook, size: Dp = 56.dp) {
    val motion = MaterialTheme.motionScheme
    val c = MaterialTheme.colorScheme
    val corner by animateDpAsState(if (look == BadgeLook.APP) size * 0.32f else size / 2, motion.defaultSpatialSpec(), label = "corner")
    val bg by animateColorAsState(
        when (look) {
            BadgeLook.APP -> c.tertiaryContainer
            BadgeLook.DONE -> c.primary
            BadgeLook.FAILED -> c.errorContainer
        },
        motion.defaultEffectsSpec(),
        label = "badge",
    )
    val fg = if (look == BadgeLook.DONE) c.onPrimary else c.onErrorContainer
    val image = remember(icon) { icon?.toBitmap(192, 192)?.asImageBitmap() }
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size).clip(RoundedCornerShape(corner)).background(bg)) {
        when (look) {
            BadgeLook.APP -> if (image != null) Image(image, contentDescription = null, modifier = Modifier.size(size))
            BadgeLook.DONE -> DrawnLine(listOf("M5 12.5l4.5 4.5L19 7.5"), fg)
            BadgeLook.FAILED -> DrawnLine(listOf("M12 7v6", "M12 16.5v.5"), fg)
        }
    }
}

/** Strokes drawn in as they appear, as a tick is. */
@Composable
private fun DrawnLine(paths: List<String>, color: Color) {
    val motion = MaterialTheme.motionScheme
    val drawn = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(150)
        drawn.animateTo(1f, motion.defaultSpatialSpec())
    }
    val nodes = remember(paths) { paths.map { addPathNodes(it) } }
    Canvas(Modifier.size(28.dp)) {
        scale(size.width / 24f, pivot = Offset.Zero) {
            for (n in nodes) {
                val measure = PathMeasure().apply { setPath(n.toPath(), false) }
                val part = Path()
                measure.getSegment(0f, measure.length * drawn.value.coerceIn(0f, 1f), part, true)
                drawPath(part, color, style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/** The buttons at the bottom of a sheet, side by side. */
@Composable
fun SheetActions(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
        content = content,
    )
}

/** The buttons at the bottom of a sheet, one above the other. */
@Composable
fun SheetActionsColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
        content = content,
    )
}

@Composable
fun SheetSpacer(height: Dp = 8.dp) = Spacer(Modifier.height(height).width(1.dp))
