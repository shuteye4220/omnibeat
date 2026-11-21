package omnibeat.app.ui

import omnibeat.app.R
import omnibeat.app.model.Station
import omnibeat.app.model.StationDividerStyle

import omnibeat.app.data.StationArtworkCache
import omnibeat.app.data.appString
import android.content.res.Resources
import android.view.View
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.painterResource
import omnibeat.app.ui.appStringResource as stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.flow.collectLatest
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun EmptyStationsState(modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.padding(horizontal = RadioSpacing.page),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(RadioSpacing.small),
            modifier = Modifier.semantics(mergeDescendants = true) {},
        ) {
            Text(
                text = stringResource(R.string.empty_stations_prefix),
                color = RadioTextMuted,
                fontSize = RadioTextSizes.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(stringResource(R.string.empty_stations_suffix_start), color = RadioTextMuted, fontSize = RadioTextSizes.bodyLarge)
                Icon(
                    painter = painterResource(R.drawable.ic_more_vert),
                    contentDescription = stringResource(R.string.a11y_more_options),
                    tint = RadioTextMuted,
                    modifier = Modifier
                        .padding(horizontal = RadioSpacing.compact)
                        .size(RadioSizes.iconInline),
                )
                Text(stringResource(R.string.empty_stations_suffix_end), color = RadioTextMuted, fontSize = RadioTextSizes.bodyLarge)
            }
        }
    }
}

@Composable
fun EmptyFavoritesState(modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.padding(horizontal = RadioSpacing.page),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(RadioSpacing.small),
            modifier = Modifier.semantics(mergeDescendants = true) {},
        ) {
            Text(
                text = stringResource(R.string.empty_favorites_prefix),
                color = RadioTextMuted,
                fontSize = RadioTextSizes.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(R.string.empty_favorites_suffix),
                    color = RadioTextMuted,
                    fontSize = RadioTextSizes.bodyLarge,
                )
                Icon(
                    painter = painterResource(R.drawable.ic_favorite_border),
                    contentDescription = stringResource(R.string.a11y_state_favorite),
                    tint = RadioTextMuted,
                    modifier = Modifier
                        .padding(start = RadioSpacing.compact)
                        .size(RadioSizes.iconInline),
                )
            }
        }
    }
}

@Composable
@Suppress("FrequentlyChangedStateReadInComposition")
fun StationList(
    stations: List<Station>,
    selectedIndex: Int,
    playingStationId: String?,
    scrollToSelectedRequest: Int,
    scrollToStationId: String?,
    showArtwork: Boolean,
    showPlaybackIndicator: Boolean,
    showFavoriteButtons: Boolean,
    dividerStyle: StationDividerStyle = StationDividerStyle.Inset,
    enabled: Boolean = true,
    reordering: Boolean = false,
    onMove: (Int, Int) -> Unit = { _, _ -> },
    onFavoriteClick: (Int, Station) -> Unit,
    onStationEdit: (Int, Station) -> Unit,
    onStationClick: (Int, Station) -> Unit,
) {
    val listState = rememberLazyListState()
    val reorderableListState = rememberReorderableLazyListState(listState) { from, to ->
        onMove(from.index, to.index)
    }
    val reorderAccessibility = rememberReorderAccessibility()
    val stationOrderSignature = stations.joinToString(separator = "|") { it.id }
    val previousStationOrderSignature = remember { mutableStateOf(stationOrderSignature) }
    val wasReordering = remember { mutableStateOf(reordering) }

    val enteringReorder = reordering && !wasReordering.value
    if ((!reordering || enteringReorder) && previousStationOrderSignature.value != stationOrderSignature) {
        listState.requestScrollToItem(
            index = listState.firstVisibleItemIndex.coerceIn(0, stations.lastIndex.coerceAtLeast(0)),
            scrollOffset = listState.firstVisibleItemScrollOffset,
        )
    }

    SideEffect {
        previousStationOrderSignature.value = stationOrderSignature
        wasReordering.value = reordering
    }

    LaunchedEffect(scrollToSelectedRequest) {
        val targetIndex = stations.indexOfFirst { it.id == scrollToStationId }
        if (scrollToSelectedRequest > 0 && targetIndex in stations.indices) {
            val visibleIndexes = listState.layoutInfo.visibleItemsInfo.map { it.index }
            if (targetIndex !in visibleIndexes) {
                listState.animateScrollToItem(targetIndex)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            userScrollEnabled = enabled || reordering,
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(
                items = stations,
                key = { _, station -> station.id },
            ) { index, station ->
                ReorderableItem(
                    state = reorderableListState,
                    key = station.id,
                    enabled = reordering,
                ) { isDragging ->
                    StationRow(
                        station = station,
                        selected = selectedIndex == index,
                        playing = station.id == playingStationId,
                        enabled = enabled,
                        reordering = reordering,
                        showArtwork = showArtwork,
                        showPlaybackIndicator = showPlaybackIndicator,
                        showFavoriteButtons = showFavoriteButtons,
                        dragging = isDragging,
                        showDivider = index < stations.lastIndex,
                        dividerStyle = dividerStyle,
                        modifier = Modifier.animateItem(),
                        dragHandleModifier = if (reordering) {
                            Modifier.draggableHandle()
                        } else {
                            Modifier
                        },
                        moveActions = if (reordering) {
                            reorderAccessibility.moveActions(index, stations.size, station.title, onMove)
                        } else {
                            emptyList()
                        },
                        positionText = if (reordering) {
                            reorderAccessibility.position(index, stations.size)
                        } else {
                            null
                        },
                        onFavoriteClick = { onFavoriteClick(index, station) },
                        onClick = { onStationClick(index, station) },
                        onLongClick = { onStationEdit(index, station) },
                    )
                }
            }
        }
        OmniLazyListScrollIndicator(
            listState = listState,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = RadioSpacing.extraSmall),
        )
    }
}

@Composable
fun StationListItem(
    title: String,
    tags: List<String>,
    imageUrl: String?,
    showArtwork: Boolean,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    startPadding: androidx.compose.ui.unit.Dp = RadioSpacing.page,
    showDivider: Boolean = true,
    dividerStyle: StationDividerStyle = StationDividerStyle.Inset,
    onClickLabel: String? = null,
    onLongClickLabel: String? = null,
    accessibilityActions: List<CustomAccessibilityAction> = emptyList(),
    accessibilityState: String? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(if (selected) RadioSurfaceHigh else RadioBackground)
                .then(
                    if (enabled) {
                        Modifier.combinedClickable(
                            onClickLabel = onClickLabel,
                            onLongClickLabel = onLongClickLabel.takeIf { onLongClick != null },
                            onClick = onClick,
                            onLongClick = onLongClick,
                        )
                    } else {
                        // Not clickable (e.g. while reordering), but still one readable
                        // item so its accessibility actions stay reachable.
                        Modifier.semantics(mergeDescendants = true) {}
                    },
                )
                .semantics {
                    if (accessibilityActions.isNotEmpty()) customActions = accessibilityActions
                    if (!accessibilityState.isNullOrEmpty()) stateDescription = accessibilityState
                }
                .padding(
                    start = startPadding,
                    end = if (trailingContent == null) RadioSpacing.page else RadioSpacing.small,
                    top = RadioSpacing.rowVertical,
                    bottom = RadioSpacing.rowVertical,
                ),
        ) {
            leadingContent?.invoke()
            if (showArtwork) {
                StationArtwork(
                    imageUrl = imageUrl,
                    modifier = Modifier.padding(end = RadioSpacing.medium),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = RadioText,
                    fontSize = RadioTextSizes.stationTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                StationTagPills(tags = tags, selected = selected)
            }
            trailingContent?.invoke()
        }
        if (showDivider && dividerStyle != StationDividerStyle.None) {
            HorizontalDivider(
                thickness = 1.dp,
                color = RadioOutline.copy(alpha = 0.65f),
                modifier = Modifier.padding(
                    start = when (dividerStyle) {
                        StationDividerStyle.Inset, StationDividerStyle.InsetRight -> RadioSpacing.page
                        else -> 0.dp
                    },
                    end = when (dividerStyle) {
                        StationDividerStyle.Inset -> RadioSpacing.page
                        StationDividerStyle.InsetRight -> RadioSpacing.page + RadioSizes.playerCollapseButton
                        else -> 0.dp
                    },
                ),
            )
        }
    }
}

@Composable
fun StationTagPills(
    tags: List<String>,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    if (tags.isEmpty()) {
        return
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(RadioSpacing.extraSmall),
        verticalArrangement = Arrangement.spacedBy(RadioSpacing.extraSmall),
        modifier = modifier
            .fillMaxWidth()
            .padding(top = RadioSpacing.labelGap),
    ) {
        tags.forEach { tag ->
            Text(
                text = tag,
                color = if (selected) RadioText else RadioTextMuted,
                fontSize = RadioTextSizes.badge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .background(
                        color = if (selected) {
                            RadioPrimary.copy(alpha = 0.30f)
                        } else {
                            RadioSurfaceHigh.copy(alpha = 0.72f)
                        },
                        shape = RoundedCornerShape(percent = 50),
                    )
                    .padding(horizontal = RadioSpacing.compact, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun StationArtwork(
    imageUrl: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val normalizedUrl = imageUrl?.takeIf { it.isNotBlank() }
    var artworkSize by remember { mutableStateOf(IntSize.Zero) }
    var imageBitmap by remember(normalizedUrl, artworkSize) {
        mutableStateOf(normalizedUrl?.let { url ->
            StationArtworkCache.get(url, artworkSize.width, artworkSize.height)
        })
    }

    LaunchedEffect(normalizedUrl, artworkSize) {
        if (normalizedUrl == null || artworkSize.width <= 0 || artworkSize.height <= 0) {
            return@LaunchedEffect
        }
        StationArtworkCache.updates(normalizedUrl).collectLatest {
            StationArtworkCache.load(context, normalizedUrl, artworkSize.width, artworkSize.height).collect {
                imageBitmap = it
            }
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(RadioSizes.artwork)
            .onSizeChanged { artworkSize = it }
            .clip(RoundedCornerShape(RadioCorners.small))
            .background(RadioSurfaceHigh.copy(alpha = 0.72f)),
    ) {
        if (imageBitmap != null) {
            Image(
                bitmap = imageBitmap!!,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_radio_button_unchecked),
                contentDescription = null,
                tint = RadioTextMuted,
                modifier = Modifier.size(RadioSizes.icon),
            )
        }
    }
}

@Composable
private fun StationRow(
    station: Station,
    selected: Boolean,
    playing: Boolean,
    enabled: Boolean,
    reordering: Boolean,
    showArtwork: Boolean,
    showPlaybackIndicator: Boolean,
    showFavoriteButtons: Boolean,
    dragging: Boolean,
    showDivider: Boolean,
    dividerStyle: StationDividerStyle,
    modifier: Modifier = Modifier,
    dragHandleModifier: Modifier,
    moveActions: List<CustomAccessibilityAction>,
    positionText: String?,
    onFavoriteClick: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val favoriteActionLabel = stringResource(
        if (station.isFavorite) R.string.a11y_remove_from_favorites else R.string.a11y_add_to_favorites,
    )
    val editLabel = stringResource(R.string.a11y_edit_station)
    val playingLabel = stringResource(R.string.a11y_state_playing)
    val favoriteLabel = stringResource(R.string.a11y_state_favorite)
    val stateText = listOfNotNull(
        playingLabel.takeIf { playing },
        favoriteLabel.takeIf { station.isFavorite },
        positionText,
    ).joinToString(", ")
    val rowActions = when {
        reordering -> moveActions
        enabled -> listOf(
            CustomAccessibilityAction(favoriteActionLabel) { onFavoriteClick(); true },
            CustomAccessibilityAction(editLabel) { onLongClick(); true },
        )
        else -> emptyList()
    }
    StationListItem(
        title = station.title,
        tags = station.tags,
        imageUrl = station.imageUrl,
        showArtwork = showArtwork,
        selected = selected,
        enabled = enabled,
        onClick = onClick,
        onLongClick = onLongClick,
        onClickLabel = stringResource(R.string.a11y_play_station),
        onLongClickLabel = editLabel,
        accessibilityActions = rowActions,
        accessibilityState = stateText,
        startPadding = if (reordering) RadioSpacing.small else RadioSpacing.page,
        showDivider = showDivider,
        dividerStyle = dividerStyle,
        leadingContent = if (reordering) {
            {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .padding(end = RadioSpacing.small)
                    .size(RadioSizes.iconLarge)
                    .then(dragHandleModifier),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_drag_indicator),
                    contentDescription = null,
                    tint = if (dragging) RadioPrimary else RadioTextMuted,
                    modifier = Modifier.size(RadioSizes.icon),
                )
            }
            }
        } else {
            null
        },
        trailingContent = if (showPlaybackIndicator || showFavoriteButtons) {
            {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(RadioSpacing.small),
                    modifier = Modifier.padding(start = RadioSpacing.medium),
                ) {
                    if (showPlaybackIndicator) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(
                                width = if (showFavoriteButtons) RadioSizes.signalWidth else RadioSizes.button,
                                height = RadioSizes.signalHeight,
                            ),
                        ) {
                            if (playing && !reordering) {
                                StationPlayingIndicator()
                            }
                        }
                    }
                    if (showFavoriteButtons) {
                        OmniIconButton(
                            painter = painterResource(
                                if (station.isFavorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border,
                            ),
                            contentDescription = favoriteActionLabel,
                            enabled = enabled && !reordering,
                            onClick = onFavoriteClick,
                            tint = if (station.isFavorite) RadioPrimary else RadioText,
                        )
                    }
                }
            }
        } else {
            null
        },
        modifier = modifier,
    )
}

@Composable
private fun StationPlayingIndicator(modifier: Modifier = Modifier) {
    val indicatorColor = RadioPrimary
    val transition = rememberInfiniteTransition(label = "station-playing-indicator")
    val barFractions = List(3) { index ->
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 420 + index * 90, delayMillis = index * 70),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "station-playing-bar-$index",
        ).value
    }

    Canvas(modifier = modifier.size(width = RadioSizes.signalWidth, height = RadioSizes.signalHeight)) {
        val barWidth = 3.dp.toPx()
        val gap = 2.dp.toPx()
        val totalWidth = barWidth * barFractions.size + gap * (barFractions.size - 1)
        val startX = (size.width - totalWidth) / 2f
        val radius = CornerRadius(barWidth / 2f, barWidth / 2f)

        barFractions.forEachIndexed { index, fraction ->
            val height = size.height * fraction.coerceIn(0.25f, 1f)
            val left = startX + index * (barWidth + gap)
            drawRoundRect(
                color = indicatorColor,
                topLeft = Offset(left, size.height - height),
                size = Size(barWidth, height),
                cornerRadius = radius,
            )
        }
    }
}

/**
 * Screen-reader alternatives to drag-and-drop reordering: "Move up/down/to top/to bottom"
 * actions plus a spoken confirmation of the new position.
 */
internal class ReorderAccessibility(
    private val view: View,
    private val resources: Resources,
) {
    fun moveActions(
        index: Int,
        count: Int,
        title: String,
        onMove: (Int, Int) -> Unit,
    ): List<CustomAccessibilityAction> {
        fun move(target: Int): Boolean {
            if (target == index || target !in 0 until count) return false
            onMove(index, target)
            @Suppress("DEPRECATION")
            view.announceForAccessibility(
                resources.appString(R.string.a11y_moved_format, title, target + 1, count),
            )
            return true
        }
        return buildList {
            if (index > 0) {
                add(CustomAccessibilityAction(resources.appString(R.string.a11y_move_up)) { move(index - 1) })
            }
            if (index < count - 1) {
                add(CustomAccessibilityAction(resources.appString(R.string.a11y_move_down)) { move(index + 1) })
            }
            if (index > 1) {
                add(CustomAccessibilityAction(resources.appString(R.string.a11y_move_to_top)) { move(0) })
            }
            if (index < count - 2) {
                add(CustomAccessibilityAction(resources.appString(R.string.a11y_move_to_bottom)) { move(count - 1) })
            }
        }
    }

    fun position(index: Int, count: Int): String =
        resources.appString(R.string.a11y_position_format, index + 1, count)
}

@Composable
internal fun rememberReorderAccessibility(): ReorderAccessibility {
    val view = LocalView.current
    val resources = LocalResources.current
    return remember(view, resources) { ReorderAccessibility(view, resources) }
}
