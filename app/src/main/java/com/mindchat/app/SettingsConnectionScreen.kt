package com.mindchat.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Fixed chip colors for the three measured latency buckets (M3E palette-free). */
private val ProxyFastColor = Color(0xFF2E7D32)
private val ProxyMediumColor = Color(0xFFF9A825)
private val ProxySlowColor = Color(0xFFC62828)

/**
 * 0.1.8 "Connection" settings screen (ROADMAP 6.3): the proxy library with
 * add/edit/delete/ping and a latency chip derived from the last real probe.
 *
 * Everything rendered here comes from [MindChatUiState] (the gateway owns the
 * library, assignments and persisted [ProxyStatus]); the only local state is
 * transient dialog input (open flags, field text). Passwords are masked while
 * typed and never appear in state or after saving.
 */
@Composable
internal fun SettingsConnectionScreen(
    gateway: MindChatGateway,
    state: MindChatUiState,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var deletingId by remember { mutableStateOf<String?>(null) }
    var pingingId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    if (showAddDialog) {
        ProxyEditorDialog(
            title = stringResource(R.string.add_proxy),
            initial = null,
            onDismiss = { showAddDialog = false },
            onSave = { config, password ->
                if (gateway.addProxy(config, password)) {
                    showAddDialog = false
                    true
                } else {
                    false
                }
            },
        )
    }

    val editing = editingId?.let { id -> state.proxyLibrary.firstOrNull { it.id == id } }
    if (editing != null) {
        ProxyEditorDialog(
            title = stringResource(R.string.edit_proxy),
            initial = editing,
            onDismiss = { editingId = null },
            onSave = { config, password ->
                if (gateway.updateProxy(editing.id, config, password)) {
                    editingId = null
                    true
                } else {
                    false
                }
            },
        )
    } else if (editingId != null) {
        // The edited entry was deleted elsewhere; drop the stale dialog.
        editingId = null
    }

    val deleting = deletingId?.let { id -> state.proxyLibrary.firstOrNull { it.id == id } }
    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { deletingId = null },
            shape = MaterialTheme.shapes.extraLarge,
            title = { Text(stringResource(R.string.delete_proxy)) },
            text = { Text(stringResource(R.string.delete_proxy_confirm, "${deleting.host}:${deleting.port}")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        gateway.deleteProxy(deleting.id)
                        deletingId = null
                    },
                ) {
                    Text(
                        text = stringResource(R.string.delete_proxy),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingId = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    } else if (deletingId != null) {
        deletingId = null
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(
            start = 12.dp,
            top = 8.dp,
            end = 12.dp,
            bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item {
            SettingsSubHeader(
                title = stringResource(R.string.connection),
                onBack = onBack,
            )
        }
        item {
            SettingsSectionTitle(
                title = stringResource(R.string.proxy_library),
            )
        }
        if (state.proxyLibrary.isEmpty()) {
            item {
                SettingsStaticRow(
                    title = stringResource(R.string.proxy_no_library),
                )
            }
        } else {
            items(state.proxyLibrary, key = { it.id }) { entry ->
                ProxyLibraryRow(
                    entry = entry,
                    pinging = pingingId == entry.id,
                    onEdit = { editingId = entry.id },
                    onDelete = { deletingId = entry.id },
                    onPing = {
                        if (pingingId == null) {
                            pingingId = entry.id
                            scope.launch {
                                withContext(Dispatchers.IO) { gateway.pingProxy(entry.id) }
                                pingingId = null
                            }
                        }
                    },
                )
            }
        }
        item {
            SettingsLinkRow(
                title = stringResource(R.string.add_proxy),
                onClick = { showAddDialog = true },
            )
        }
    }
}

@Composable
private fun ProxyLibraryRow(
    entry: ProxyLibraryEntry,
    pinging: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onPing: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit),
        headlineContent = { Text("${entry.host}:${entry.port}") },
        supportingContent = { Text(stringResource(proxyKindLabelRes(entry.kind))) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProxyLatencyChip(status = entry.status, pinging = pinging)
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.proxy_actions),
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ping)) },
                        onClick = {
                            menuExpanded = false
                            onPing()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.edit_proxy)) },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.delete_proxy),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        },
    )
}

/**
 * Latency chip from the persisted [ProxyStatus]: a colored dot plus the
 * bucket label (with the measured milliseconds when a probe succeeded). The
 * bucket is derived purely from the real [FfiProxyProbe] latency; there is
 * never fabricated latency.
 */
@Composable
private fun ProxyLatencyChip(status: ProxyStatus, pinging: Boolean) {
    val dotColor = when {
        pinging -> MaterialTheme.colorScheme.primary
        status.bucket == ProxyLatencyBucket.FAST -> ProxyFastColor
        status.bucket == ProxyLatencyBucket.MEDIUM -> ProxyMediumColor
        status.bucket == ProxyLatencyBucket.SLOW -> ProxySlowColor
        status.error != null -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = when {
        pinging -> stringResource(R.string.proxy_pinging)
        status.error != null -> stringResource(R.string.proxy_test_failed)
        status.latencyMs != null ->
            "${stringResource(latencyBucketLabelRes(status.bucket))} · " +
                stringResource(R.string.proxy_latency_ms, status.latencyMs)

        else -> stringResource(R.string.proxy_latency_unknown)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color = dotColor, shape = CircleShape),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Add/edit dialog with host, port, kind and an optional masked password. */
@Composable
private fun ProxyEditorDialog(
    title: String,
    initial: ProxyLibraryEntry?,
    onDismiss: () -> Unit,
    onSave: (config: ProxyConfig, password: String?) -> Boolean,
) {
    var host by remember { mutableStateOf(initial?.host.orEmpty()) }
    var portText by remember { mutableStateOf(initial?.port?.toString().orEmpty()) }
    var kind by remember { mutableStateOf(initial?.kind ?: ProxyKind.SOCKS5) }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var hostError by remember { mutableStateOf(false) }
    var portError by remember { mutableStateOf(false) }

    val port = portText.toIntOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = {
                        host = it
                        hostError = false
                    },
                    label = { Text(stringResource(R.string.proxy_host)) },
                    singleLine = true,
                    isError = hostError,
                    supportingText = if (hostError) {
                        { Text(stringResource(R.string.proxy_invalid_host)) }
                    } else {
                        null
                    },
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = {
                        portText = it.filter(Char::isDigit)
                        portError = false
                    },
                    label = { Text(stringResource(R.string.proxy_port)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = portError,
                    supportingText = if (portError) {
                        { Text(stringResource(R.string.proxy_invalid_port)) }
                    } else {
                        null
                    },
                )
                Text(
                    text = stringResource(R.string.proxy_kind),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ProxyKind.entries.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = kind == option,
                            onClick = { kind = option },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = ProxyKind.entries.size,
                            ),
                        ) {
                            Text(stringResource(proxyKindLabelRes(option)))
                        }
                    }
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.proxy_password_optional)) },
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        TextButton(onClick = { passwordVisible = !passwordVisible }) {
                            Text(
                                text = stringResource(
                                    if (passwordVisible) R.string.password_hide else R.string.password_show,
                                ),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when (val validation = validateProxyConfig(host, port ?: 0, kind)) {
                        is ProxyValidation.Refused -> {
                            hostError = validation.reason == ProxyRefusal.EMPTY_HOST ||
                                validation.reason == ProxyRefusal.HOST_HAS_WHITESPACE
                            portError = validation.reason == ProxyRefusal.PORT_OUT_OF_RANGE
                        }

                        ProxyValidation.Valid -> {
                            // An empty password means "keep the stored one" (or
                            // none for a new proxy); stored values are never
                            // displayed back.
                            onSave(ProxyConfig(host.trim(), port ?: 0, kind), password.ifEmpty { null })
                        }
                    }
                },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

internal fun proxyKindLabelRes(kind: ProxyKind): Int = when (kind) {
    ProxyKind.SOCKS5 -> R.string.proxy_kind_socks5
    ProxyKind.HTTP_CONNECT -> R.string.proxy_kind_http
}

private fun latencyBucketLabelRes(bucket: ProxyLatencyBucket): Int = when (bucket) {
    ProxyLatencyBucket.FAST -> R.string.proxy_latency_fast
    ProxyLatencyBucket.MEDIUM -> R.string.proxy_latency_medium
    ProxyLatencyBucket.SLOW -> R.string.proxy_latency_slow
    ProxyLatencyBucket.UNKNOWN -> R.string.proxy_latency_unknown
}
