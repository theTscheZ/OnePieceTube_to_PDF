package at.aaron.onepiece

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import java.io.File

/** Tap, long-press-and-sweep, and edge scrolling all use the same grid coordinates. */
@Composable
internal fun ChapterGrid(
    files: List<File>, selected: Set<String>, editing: Boolean, enabled: Boolean,
    savedPage: (File) -> Int, readStatus: (File) -> ReadStatus, onOpen: (File) -> Unit, onMore: (File) -> Unit,
    onStartSelection: () -> Unit, onSelection: (Set<String>) -> Unit
) {
    val grid = rememberLazyGridState()
    val keys = remember(files) { files.map { it.name } }
    val latestSelected by rememberUpdatedState(selected)
    val latestEditing by rememberUpdatedState(editing)
    val select by rememberUpdatedState(onSelection)
    val startSelection by rememberUpdatedState(onStartSelection)
    val open by rememberUpdatedState(onOpen)
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val edge = with(density) { 96.dp.toPx() }
    val maxSpeed = with(density) { 1800.dp.toPx() }
    var dragging by remember { mutableStateOf(false) }
    var finger by remember { mutableStateOf<Offset?>(null) }
    var sweep by remember { mutableStateOf<GallerySelection?>(null) }

    fun indexAt(point: Offset, nearest: Boolean): Int? {
        val items = grid.layoutInfo.visibleItemsInfo
        val hit = items.firstOrNull {
            point.x >= it.offset.x && point.x < it.offset.x + it.size.width &&
                point.y >= it.offset.y && point.y < it.offset.y + it.size.height
        }
        if (hit != null || !nearest) return hit?.index
        return items.minByOrNull {
            val dx = point.x - point.x.coerceIn(it.offset.x.toFloat(), (it.offset.x + it.size.width).toFloat())
            val dy = point.y - point.y.coerceIn(it.offset.y.toFloat(), (it.offset.y + it.size.height).toFloat())
            dx * dx + dy * dy
        }?.index
    }
    fun updateSweep(point: Offset) {
        val gesture = sweep ?: return
        val index = indexAt(point, nearest = true) ?: return
        select(gesture.at(index))
    }
    fun tap(index: Int) {
        val file = files.getOrNull(index) ?: return
        if (latestEditing) select(latestSelected.toMutableSet().apply { if (!add(file.name)) remove(file.name) })
        else open(file)
    }
    fun longPress(index: Int) {
        startSelection()
        val gesture = GallerySelection(keys, latestSelected, index)
        sweep = gesture
        select(gesture.at(index))
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    LaunchedEffect(editing, enabled) {
        if (!editing || !enabled) { dragging = false; sweep = null; finger = null }
    }
    LaunchedEffect(dragging) {
        if (!dragging) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (dragging && sweep != null) {
            val now = withFrameNanos { it }
            val point = finger ?: break
            val dt = ((now - previous) / 1_000_000_000f).coerceIn(0f, .032f)
            previous = now
            val velocity = GallerySelection.scrollVelocity(point.y, grid.layoutInfo.viewportSize.height.toFloat(), edge, maxSpeed)
            if (velocity != 0f) grid.scrollBy(velocity * dt)
            // Refresh even for a stationary finger as new items move underneath it.
            updateSweep(point)
        }
    }
    DisposableEffect(keys) {
        onDispose { dragging = false; sweep = null; finger = null }
    }
    LazyVerticalGrid(
        state = grid, columns = GridCells.Adaptive(148.dp),
        userScrollEnabled = enabled && !dragging,
        modifier = Modifier.fillMaxSize().pointerInput(keys, enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown() // Child menu buttons retain their own gestures.
                val index = indexAt(down.position, nearest = false) ?: return@awaitEachGesture
                var timedOut = true
                val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    waitForUpOrCancellation().also { timedOut = false }
                }
                if (up != null) {
                    up.consume()
                    if ((up.position - down.position).getDistance() <= viewConfiguration.touchSlop) tap(index)
                } else {
                    if (!timedOut) return@awaitEachGesture
                    val held = currentEvent.changes.firstOrNull { it.id == down.id && it.pressed && !it.isConsumed }
                        ?: return@awaitEachGesture // A normal scrolling gesture was claimed by the grid.
                    if (currentEvent.changes.count { it.pressed } != 1) return@awaitEachGesture
                    if ((held.position - down.position).getDistance() > viewConfiguration.touchSlop) return@awaitEachGesture
                    held.consume()
                    longPress(index)
                    finger = held.position
                    dragging = true
                    try {
                        drag(held.id) { change ->
                            change.consume()
                            finger = change.position
                            updateSweep(change.position)
                        }
                        currentEvent.changes.filter { !it.pressed }.forEach { it.consume() }
                    } finally {
                        dragging = false; sweep = null; finger = null
                    }
                }
            }
        },
        horizontalArrangement = Arrangement.spacedBy(Space.medium),
        verticalArrangement = Arrangement.spacedBy(Space.medium),
        contentPadding = PaddingValues(bottom = 100.dp)
    ) {
        items(files, key = { it.name }) { file ->
            PdfCard(file, savedPage(file), readStatus(file), onOpen = { if (enabled) tap(keys.indexOf(file.name)) },
                onMore = { onMore(file) }, editing = editing, isSelected = file.name in selected,
                onLongPress = {
                    if (enabled) {
                        longPress(keys.indexOf(file.name))
                        sweep = null // Accessibility long-clicks select without starting a touch drag.
                    }
                })
        }
    }
}
