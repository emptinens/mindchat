package com.mindchat.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mindchat.app.theme.MindChatDockItemShape
import com.mindchat.app.theme.MindChatDockShape
import com.mindchat.app.theme.bubbleContainerColor
import com.mindchat.app.theme.bubbleOutlineColor
import com.mindchat.app.theme.bubbleShape
import com.mindchat.app.theme.chatListBackground

/**
 * 0.1.7 appearance engine screen (global controls).
 *
 * Every control applies immediately through [MindChatGateway.setAppearance]:
 * the whole app re-themes in place (Telegram-style), there is no draft state
 * and no save button. The live preview card reads back `state.appearance`
 * through [resolveAppearance] so the sample bubbles/dock always show what the
 * user just picked. Per-account bubble/background overrides live in the
 * profile editor, not here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(
    gateway: MindChatGateway,
    state: MindChatUiState,
    onBack: () -> Unit,
) {
    var showResetDialog by remember { mutableStateOf(false) }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            shape = MaterialTheme.shapes.extraLarge,
            title = { Text(stringResource(R.string.reset_appearance)) },
            text = { Text(stringResource(R.string.reset_appearance_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        gateway.setAppearance(AppearanceProfile())
                    },
                ) {
                    Text(stringResource(R.string.reset_appearance))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    val appearance = state.appearance
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                title = { Text(stringResource(R.string.appearance)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 8.dp,
                end = 16.dp,
                bottom = 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                AppearancePreviewCard(appearance = appearance)
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.use_dynamic_colors)) },
                    trailingContent = {
                        Switch(
                            checked = state.dynamicColor,
                            onCheckedChange = { gateway.toggleDynamicColor() },
                        )
                    },
                )
            }
            item {
                AppearanceSegmentedRow(
                    title = stringResource(R.string.shape_scale),
                    options = listOf(
                        ShapeScale.COMPACT to stringResource(R.string.shape_compact),
                        ShapeScale.STANDARD to stringResource(R.string.shape_standard),
                        ShapeScale.EXPRESSIVE to stringResource(R.string.shape_expressive),
                    ),
                    selected = appearance.shapeScale,
                    onSelect = { value ->
                        gateway.setAppearance(appearance.copy(shapeScale = value))
                    },
                )
            }
            item {
                AppearanceSegmentedRow(
                    title = stringResource(R.string.density),
                    options = listOf(
                        Density.COMPACT to stringResource(R.string.density_compact),
                        Density.STANDARD to stringResource(R.string.density_standard),
                        Density.COMFORTABLE to stringResource(R.string.density_comfortable),
                    ),
                    selected = appearance.density,
                    onSelect = { value ->
                        gateway.setAppearance(appearance.copy(density = value))
                    },
                )
            }
            item {
                AppearanceSegmentedRow(
                    title = stringResource(R.string.text_size),
                    options = listOf(
                        TextScale.COMPACT to stringResource(R.string.text_compact),
                        TextScale.DEFAULT to stringResource(R.string.text_default),
                        TextScale.LARGE to stringResource(R.string.text_large),
                    ),
                    selected = appearance.textScale,
                    onSelect = { value ->
                        gateway.setAppearance(appearance.copy(textScale = value))
                    },
                )
            }
            item {
                AppearanceSegmentedRow(
                    title = stringResource(R.string.animation_speed),
                    options = listOf(
                        AnimationSpeed.FASTER to stringResource(R.string.animation_faster),
                        AnimationSpeed.DEFAULT to stringResource(R.string.animation_default),
                        AnimationSpeed.SLOWER to stringResource(R.string.animation_slower),
                    ),
                    selected = appearance.animationSpeed,
                    onSelect = { value ->
                        gateway.setAppearance(appearance.copy(animationSpeed = value))
                    },
                )
            }
            item {
                AppearanceSegmentedRow(
                    title = stringResource(R.string.chat_bubbles),
                    options = listOf(
                        BubbleStyle.DEFAULT to stringResource(R.string.bubble_default),
                        BubbleStyle.ROUNDED to stringResource(R.string.bubble_rounded),
                        BubbleStyle.OUTLINED to stringResource(R.string.bubble_outlined),
                    ),
                    selected = appearance.bubbleStyle,
                    onSelect = { value ->
                        gateway.setAppearance(appearance.copy(bubbleStyle = value))
                    },
                )
            }
            item {
                AppearanceSegmentedRow(
                    title = stringResource(R.string.chat_background),
                    options = listOf(
                        ChatBackground.DEFAULT to stringResource(R.string.background_default),
                        ChatBackground.TINTED to stringResource(R.string.background_tinted),
                    ),
                    selected = appearance.chatBackground,
                    onSelect = { value ->
                        gateway.setAppearance(appearance.copy(chatBackground = value))
                    },
                )
            }
            item {
                TextButton(
                    onClick = { showResetDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.reset_appearance),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * Segmented three/two-option row. [options] carries enum values plus their
 * resolved labels; the selected option is highlighted by the M3 segmented
 * button and applied immediately via [onSelect].
 */
@Composable
private fun <T> AppearanceSegmentedRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = options.size,
                    ),
                ) {
                    Text(
                        text = label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Live readback preview: one incoming and one outgoing sample bubble plus a
 * dock-pill mock, rendered through [resolveAppearance] so every control
 * change is visible immediately (bubble style/background, shape, type).
 */
@Composable
private fun AppearancePreviewCard(appearance: AppearanceProfile) {
    val resolved = resolveAppearance(appearance, null)
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = chatListBackground(resolved.chatBackground, scheme),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.appearance_preview),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            PreviewBubble(
                text = stringResource(R.string.sample_message_in),
                mine = false,
                style = resolved.bubbleStyle,
            )
            Spacer(Modifier.height(8.dp))
            PreviewBubble(
                text = stringResource(R.string.sample_message_out),
                mine = true,
                style = resolved.bubbleStyle,
            )
            Spacer(Modifier.height(16.dp))
            Surface(
                shape = MindChatDockShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .height(52.dp)
                            .clip(MindChatDockItemShape)
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Home,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(22.dp),
                        )
                        Text(
                            text = stringResource(R.string.conversations),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewBubble(text: String, mine: Boolean, style: BubbleStyle) {
    val scheme = MaterialTheme.colorScheme
    val borderColor = bubbleOutlineColor(style, scheme)
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .then(
                    if (borderColor != Color.Transparent) {
                        Modifier.border(1.dp, borderColor, bubbleShape(style, mine))
                    } else {
                        Modifier
                    },
                ),
            shape = bubbleShape(style, mine),
            colors = CardDefaults.cardColors(
                containerColor = bubbleContainerColor(style, mine, scheme),
            ),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}
