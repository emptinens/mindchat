package com.mindchat.app

import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val AVATARS_DIR = "avatars"
private const val STATE_FILE_NAME = "mindchat_state.json"
private const val REPOSITORY_URL = "https://github.com/emptinens/mindchat"

/**
 * Sums the app's device-local data used by the settings Storage section:
 * cached profile avatar images plus the persisted core state file.
 */
internal fun estimateLocalDataBytes(filesDir: File): Long {
    var total = 0L
    File(filesDir, AVATARS_DIR)
        .walkTopDown()
        .filter { it.isFile }
        .forEach { total += it.length() }
    File(filesDir, STATE_FILE_NAME).takeIf { it.isFile }?.let { total += it.length() }
    return total
}

/** Deletes cached profile avatar images; returns how many files were removed. */
internal fun clearProfileImages(filesDir: File): Int {
    val dir = File(filesDir, AVATARS_DIR)
    if (!dir.isDirectory) return 0
    return dir.listFiles().orEmpty().count { it.isFile && it.delete() }
}

/**
 * 0.1.6 detailed settings with M3 Expressive categories. The screen is
 * intentionally explicit about what is implemented: toggles with real backing
 * (dynamic color, comfortable layout, app lock), rows that navigate to
 * existing flows (account drawer, profile sheet, add-account dialog), and
 * local-only placeholders with clear "not implemented yet" supporting text
 * where the domain core does not support the feature yet.
 */
@Composable
fun SettingsScreen(
    state: MindChatUiState,
    contentPadding: PaddingValues,
    onDynamicColorChange: () -> Unit,
    onComfortableLayoutChange: () -> Unit,
    appLockAvailable: Boolean,
    onAppLockChange: () -> Unit,
    onOpenAccountDrawer: () -> Unit,
    onOpenActiveProfile: () -> Unit,
    onAddAccount: () -> Unit,
    onClearProfileImages: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activeAccount = state.accounts.firstOrNull { it.id == state.activeAccountId }

    var localDataBytes by remember { mutableLongStateOf(-1L) }
    var refreshNonce by remember { mutableIntStateOf(0) }
    var showClearImagesDialog by remember { mutableStateOf(false) }
    var showLicensesDialog by remember { mutableStateOf(false) }

    LaunchedEffect(refreshNonce) {
        localDataBytes = withContext(Dispatchers.IO) {
            estimateLocalDataBytes(context.filesDir)
        }
    }

    if (showClearImagesDialog) {
        AlertDialog(
            onDismissRequest = { showClearImagesDialog = false },
            shape = MaterialTheme.shapes.extraLarge,
            title = { Text(stringResource(R.string.clear_profile_images)) },
            text = { Text(stringResource(R.string.clear_profile_images_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearImagesDialog = false
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                clearProfileImages(context.filesDir)
                            }
                            // Drop the stored avatar references so the cleared
                            // files are not re-pointed at by stale profiles.
                            onClearProfileImages()
                            refreshNonce++
                        }
                    },
                ) {
                    Text(stringResource(R.string.clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearImagesDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showLicensesDialog) {
        AlertDialog(
            onDismissRequest = { showLicensesDialog = false },
            shape = MaterialTheme.shapes.extraLarge,
            title = { Text(stringResource(R.string.licenses_dialog_title)) },
            text = { Text(stringResource(R.string.licenses_dialog_body)) },
            confirmButton = {
                TextButton(onClick = { showLicensesDialog = false }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
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
        item { SettingsSectionTitle(stringResource(R.string.appearance)) }
        item {
            SettingsSwitch(
                title = stringResource(R.string.use_dynamic_colors),
                checked = state.dynamicColor,
                onCheckedChange = { onDynamicColorChange() },
            )
        }
        item {
            SettingsSwitch(
                title = stringResource(R.string.comfortable_layout),
                checked = state.comfortableLayout,
                onCheckedChange = { onComfortableLayoutChange() },
            )
        }
        item {
            SettingsLinkRow(
                title = stringResource(R.string.accent_color),
                supportingText = stringResource(R.string.accent_per_account),
                enabled = activeAccount != null,
                onClick = onOpenActiveProfile,
            )
        }

        item { SettingsSectionTitle(stringResource(R.string.accounts)) }
        item {
            SettingsSwitch(
                title = stringResource(R.string.app_lock),
                checked = state.appLockEnabled,
                supportingText = stringResource(
                    if (appLockAvailable) R.string.app_lock_summary else R.string.app_lock_unavailable,
                ),
                enabled = appLockAvailable || state.appLockEnabled,
                onCheckedChange = { onAppLockChange() },
            )
        }
        item {
            SettingsLinkRow(
                title = stringResource(R.string.manage_accounts),
                supportingText = stringResource(R.string.manage_accounts_summary),
                onClick = onOpenAccountDrawer,
            )
        }
        item {
            SettingsLinkRow(
                title = stringResource(R.string.add_account),
                supportingText = stringResource(R.string.add_account_summary),
                onClick = onAddAccount,
            )
        }
        item {
            SettingsLinkRow(
                title = stringResource(R.string.edit_profile),
                supportingText = activeAccount?.let {
                    stringResource(R.string.edit_profile_summary, it.displayName)
                },
                enabled = activeAccount != null,
                onClick = onOpenActiveProfile,
            )
        }

        item { SettingsSectionTitle(stringResource(R.string.privacy)) }
        item {
            SettingsSwitch(
                title = stringResource(R.string.message_search),
                supportingText = stringResource(R.string.message_search_summary),
                checked = false,
                enabled = false,
                notImplemented = true,
                onCheckedChange = {},
            )
        }
        item {
            SettingsSwitch(
                title = stringResource(R.string.encryption),
                supportingText = stringResource(R.string.encryption_summary),
                checked = false,
                enabled = false,
                notImplemented = true,
                onCheckedChange = {},
            )
        }

        item { SettingsSectionTitle(stringResource(R.string.notifications)) }
        item {
            SettingsStaticRow(
                title = stringResource(R.string.message_notifications),
                supportingText = stringResource(R.string.coming_in_later_release),
            )
        }
        item {
            SettingsStaticRow(
                title = stringResource(R.string.group_notifications),
                supportingText = stringResource(R.string.coming_in_later_release),
            )
        }

        item { SettingsSectionTitle(stringResource(R.string.storage)) }
        item {
            val sizeText = if (localDataBytes >= 0L) {
                Formatter.formatShortFileSize(context, localDataBytes)
            } else {
                stringResource(R.string.storage_calculating)
            }
            SettingsStaticRow(
                title = stringResource(R.string.local_data_size),
                supportingText = stringResource(R.string.local_data_size_summary, sizeText),
            )
        }
        item {
            SettingsLinkRow(
                title = stringResource(R.string.clear_profile_images),
                supportingText = stringResource(R.string.clear_profile_images_summary),
                onClick = { showClearImagesDialog = true },
            )
        }

        item { SettingsSectionTitle(stringResource(R.string.about)) }
        item {
            SettingsStaticRow(
                title = stringResource(R.string.version),
                supportingText = BuildConfig.VERSION_NAME,
            )
        }
        item {
            SettingsStaticRow(
                title = stringResource(R.string.app_name),
                supportingText = stringResource(R.string.privacy_summary),
            )
        }
        item {
            SettingsLinkRow(
                title = stringResource(R.string.licenses),
                supportingText = stringResource(R.string.licenses_summary),
                onClick = { showLicensesDialog = true },
            )
        }
        item {
            SettingsLinkRow(
                title = stringResource(R.string.repository),
                supportingText = stringResource(R.string.repository_summary),
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(REPOSITORY_URL)),
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun SettingsSectionTitle(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(top = 20.dp, start = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun SettingsSwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    supportingText: String? = null,
    notImplemented: Boolean = false,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = if (supportingText != null || notImplemented) {
            {
                Column {
                    supportingText?.let { Text(it) }
                    if (notImplemented) {
                        Text(
                            text = stringResource(R.string.not_implemented_yet),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            null
        },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
            )
        },
    )
}

@Composable
private fun SettingsLinkRow(
    title: String,
    onClick: () -> Unit,
    supportingText: String? = null,
    enabled: Boolean = true,
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = supportingText?.let { text -> { Text(text) } },
        trailingContent = {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

@Composable
private fun SettingsStaticRow(
    title: String,
    supportingText: String? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = supportingText?.let { text -> { Text(text) } },
    )
}
