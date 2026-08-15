package com.mindchat.app

import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
 * 0.1.7 settings platform. The screen is a thin renderer of pure, JVM-tested
 * logic: navigation comes from [SettingsNavState], rows come from
 * [GatewayInput.catalogRows], and every mutation goes through the gateway
 * contract. Rows without core support render honestly disabled with the
 * "not implemented yet" supporting text; nothing here fakes behavior.
 *
 * The shell owns the three cross-cutting flows that live outside settings
 * ([onOpenAccountDrawer], [onAddAccount]) plus the device app-lock capability
 * and the app lock host coordination ([appLockHostAvailable],
 * [onAppLockToggle]); everything else is driven by [gateway] directly.
 */
@Composable
fun SettingsScreen(
    gateway: MindChatGateway,
    state: MindChatUiState,
    navState: SettingsNavState,
    contentPadding: PaddingValues,
    onOpenAccountDrawer: () -> Unit,
    onAddAccount: () -> Unit,
    onOpenAppearance: () -> Unit,
    appLockHostAvailable: Boolean,
    onAppLockToggle: (Boolean) -> Unit,
) {
    val route = navState.backStack.last()
    when (route) {
        SettingsRoute.Root -> SettingsRootScreen(
            state = state,
            contentPadding = contentPadding,
            onCategoryClick = { category ->
                navState.navigate(
                    if (category == SettingCategory.ACCOUNTS) {
                        SettingsRoute.Accounts
                    } else {
                        SettingsRoute.Category(category)
                    },
                )
            },
        )

        is SettingsRoute.Category -> when (route.category) {
            SettingCategory.ACCOUNTS -> SettingsAccountsScreen(
                state = state,
                contentPadding = contentPadding,
                onBack = { navState.back() },
                onAccountClick = { accountId -> navState.navigate(SettingsRoute.AccountSettings(accountId)) },
                onOpenAccountDrawer = onOpenAccountDrawer,
                onAddAccount = onAddAccount,
            )

            SettingCategory.STORAGE -> SettingsStorageScreen(
                state = state,
                gateway = gateway,
                contentPadding = contentPadding,
                onBack = { navState.back() },
            )

            SettingCategory.ABOUT -> SettingsAboutScreen(
                contentPadding = contentPadding,
                onBack = { navState.back() },
            )

            SettingCategory.CONNECTION -> SettingsConnectionScreen(
                gateway = gateway,
                state = state,
                contentPadding = contentPadding,
                onBack = { navState.back() },
            )

            else -> SettingsCategoryScreen(
                category = route.category,
                rows = catalogRows(route.category, state.settings, state.activeAccountId, appLockHostAvailable),
                contentPadding = contentPadding,
                onBack = { navState.back() },
                onOpenAppearance = onOpenAppearance,
                onToggle = { key, checked ->
                    when {
                        key == SettingsSchema.appLockEnabled -> onAppLockToggle(checked)
                        // The legacy 0.1.6 comfortable-layout toggle stays a
                        // real control: it maps onto the 0.1.7 density engine
                        // (COMFORTABLE vs STANDARD), exactly like
                        // densityFromLegacy did.
                        key == SettingsSchema.comfortableLayout -> gateway.setAppearance(
                            state.appearance.copy(
                                density = if (checked) Density.COMFORTABLE else Density.STANDARD,
                            ),
                        )

                        else -> gateway.setSetting(key, checked)
                    }
                },
                onAction = { action ->
                    when (action) {
                        SettingRowAction.OPEN_ACCENT_PROFILE -> navState.navigate(
                            SettingsRoute.AccountSettings(state.activeAccountId),
                        )
                    }
                },
            )
        }

        SettingsRoute.Accounts -> SettingsAccountsScreen(
            state = state,
            contentPadding = contentPadding,
            onBack = { navState.back() },
            onAccountClick = { accountId -> navState.navigate(SettingsRoute.AccountSettings(accountId)) },
            onOpenAccountDrawer = onOpenAccountDrawer,
            onAddAccount = onAddAccount,
        )

        is SettingsRoute.AccountSettings -> SettingsAccountSettingsScreen(
            gateway = gateway,
            state = state,
            accountId = route.accountId,
            contentPadding = contentPadding,
            onBack = { navState.back() },
        )
    }
}

@Composable
private fun SettingsRootScreen(
    state: MindChatUiState,
    contentPadding: PaddingValues,
    onCategoryClick: (SettingCategory) -> Unit,
) {
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
            SettingsLinkRow(
                title = stringResource(R.string.settings_search),
                supportingText = stringResource(R.string.coming_in_later_release),
                enabled = false,
                onClick = {},
            )
        }
        SettingCategory.entries.forEach { category ->
            item {
                SettingsCategoryRow(category = category, onClick = { onCategoryClick(category) })
            }
        }
        item {
            SettingsStaticRow(
                title = stringResource(R.string.version),
                supportingText = BuildConfig.VERSION_NAME,
            )
        }
    }
}

@Composable
private fun SettingsCategoryScreen(
    category: SettingCategory,
    rows: List<SettingRowSpec>,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onOpenAppearance: () -> Unit,
    onToggle: (BooleanKey, Boolean) -> Unit,
    onAction: (SettingRowAction) -> Unit,
) {
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
                title = stringResource(categoryLabelRes(category)),
                onBack = onBack,
            )
        }
        if (category == SettingCategory.APPEARANCE) {
            // 0.1.7: the full appearance engine (shape/density/text/motion/
            // bubbles/background) lives in its own screen; the catalog rows
            // below keep the quick toggles (dynamic color, comfortable
            // layout) plus the per-account accent link.
            item {
                SettingsLinkRow(
                    title = stringResource(R.string.appearance),
                    supportingText = stringResource(R.string.appearance_engine_summary),
                    onClick = onOpenAppearance,
                )
            }
        }
        rows.forEach { row ->
            item {
                when (row) {
                    is SettingToggleRowSpec -> SettingsSwitch(
                        title = stringResource(row.labelRes),
                        checked = row.checked,
                        enabled = row.enabled,
                        supportingText = row.supportingRes?.let { stringResource(it) },
                        notImplemented = row.notImplemented,
                        onCheckedChange = { checked -> onToggle(row.key, checked) },
                    )

                    is SettingActionRowSpec -> SettingsLinkRow(
                        title = stringResource(row.labelRes),
                        supportingText = row.supportingRes?.let { stringResource(it) },
                        enabled = row.enabled,
                        onClick = { onAction(row.action) },
                    )

                    is SettingInfoRowSpec -> SettingsStaticRow(
                        title = stringResource(row.labelRes),
                        supportingText = row.supportingRes?.let { stringResource(it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsAccountsScreen(
    state: MindChatUiState,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onAccountClick: (Long) -> Unit,
    onOpenAccountDrawer: () -> Unit,
    onAddAccount: () -> Unit,
) {
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
                title = stringResource(R.string.accounts),
                onBack = onBack,
            )
        }
        if (state.accounts.isEmpty()) {
            item {
                SettingsStaticRow(
                    title = stringResource(R.string.accounts_empty),
                )
            }
        } else {
            state.accounts.forEach { account ->
                item {
                    SettingsAccountRow(
                        account = account,
                        avatarUri = state.profiles[account.id]?.avatarUri,
                        onClick = { onAccountClick(account.id) },
                    )
                }
            }
        }
        item {
            SettingsLinkRow(
                title = stringResource(R.string.open_account_menu),
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
    }
}

@Composable
private fun SettingsAccountSettingsScreen(
    gateway: MindChatGateway,
    state: MindChatUiState,
    accountId: Long,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
) {
    val account = state.accounts.firstOrNull { it.id == accountId }
    if (account == null) {
        // The account was deleted (from this screen or elsewhere); leave.
        LaunchedEffect(accountId) { onBack() }
        return
    }

    var showReconnect by remember(accountId) { mutableStateOf(false) }
    var showRename by remember(accountId) { mutableStateOf(false) }
    var showDelete by remember(accountId) { mutableStateOf(false) }

    if (showReconnect) {
        ReconnectDialog(
            account = account,
            onDismiss = { showReconnect = false },
            onReconnect = { password ->
                if (gateway.reconnectAccount(account.id, password)) {
                    showReconnect = false
                    true
                } else {
                    false
                }
            },
        )
    }

    if (showRename) {
        RenameAccountDialog(
            account = account,
            onDismiss = { showRename = false },
            onRename = { name ->
                runCatching { gateway.renameAccount(account.id, name) }
                    .onSuccess { showRename = false }
                    .isSuccess
            },
        )
    }

    if (showDelete) {
        DeleteAccountDialog(
            account = account,
            onDismiss = { showDelete = false },
            onConfirm = {
                val deleted = runCatching { gateway.deleteAccount(account.id) }.isSuccess
                if (deleted) {
                    showDelete = false
                    onBack()
                }
                deleted
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
        item {
            SettingsSubHeader(
                title = account.displayName,
                onBack = onBack,
            )
        }
        item {
            Text(
                text = account.jid,
                modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            SettingsSectionTitle(
                title = stringResource(R.string.edit_profile_summary, account.displayName),
            )
        }
        item {
            // Reuses the same editor body as the drawer's ProfileSheet: one
            // editor, no duplication (T10). The save flow mirrors the sheet.
            ProfileEditorContent(
                account = account,
                profile = state.profiles[accountId],
                onSave = { updatedProfile, newDisplayName ->
                    saveProfile(gateway, account, updatedProfile, newDisplayName)
                },
            )
        }
        item {
            SettingsSectionTitle(
                title = stringResource(R.string.actions_for_account, account.displayName),
            )
        }
        item {
            SettingsLinkRow(
                title = stringResource(R.string.reconnect),
                supportingText = stringResource(R.string.reconnect_title),
                onClick = { showReconnect = true },
            )
        }
        item {
            SettingsLinkRow(
                title = stringResource(R.string.disconnect),
                onClick = { gateway.disconnectAccount(account.id) },
            )
        }
        item {
            SettingsLinkRow(
                title = stringResource(R.string.rename_account),
                onClick = { showRename = true },
            )
        }
        item {
            SettingsLinkRow(
                title = stringResource(R.string.delete_account),
                onClick = { showDelete = true },
            )
        }
        // Per-account toggle slots arrive with 0.1.8: rows for
        // SettingsSchema keys with scope == PER_ACCOUNT render here via
        // catalogRows, with zero changes to this screen.
    }
}

@Composable
private fun SettingsStorageScreen(
    state: MindChatUiState,
    gateway: MindChatGateway,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var localDataBytes by remember { mutableLongStateOf(-1L) }
    var refreshNonce by remember { mutableIntStateOf(0) }
    var showClearImagesDialog by remember { mutableStateOf(false) }

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
                            state.profiles.forEach { (accountId, profile) ->
                                gateway.updateProfile(accountId, profile.copy(avatarUri = null))
                            }
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
                title = stringResource(R.string.storage),
                onBack = onBack,
            )
        }
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
    }
}

@Composable
private fun SettingsAboutScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var showLicensesDialog by remember { mutableStateOf(false) }

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
        item {
            SettingsSubHeader(
                title = stringResource(R.string.about),
                onBack = onBack,
            )
        }
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
private fun SettingsCategoryRow(category: SettingCategory, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        leadingContent = {
            Icon(
                imageVector = categoryIcon(category),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        headlineContent = { Text(stringResource(categoryLabelRes(category))) },
        supportingContent = { Text(stringResource(categorySummaryRes(category))) },
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

@Composable
private fun SettingsAccountRow(
    account: AccountUi,
    avatarUri: String?,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        leadingContent = {
            ProfileAvatar(
                account = account,
                avatarUri = avatarUri,
                contentDescription = stringResource(R.string.account_avatar, account.displayName),
                modifier = Modifier.size(40.dp),
            )
        },
        headlineContent = { Text(account.displayName) },
        supportingContent = { Text(account.jid) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ConnectionStatusIndicator(account.connectionState)
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

private fun categoryLabelRes(category: SettingCategory): Int = when (category) {
    SettingCategory.APPEARANCE -> R.string.appearance
    SettingCategory.ACCOUNTS -> R.string.manage_accounts
    SettingCategory.CONNECTION -> R.string.connection
    SettingCategory.PRIVACY_SECURITY -> R.string.privacy
    SettingCategory.NOTIFICATIONS -> R.string.notifications
    SettingCategory.STORAGE -> R.string.storage
    SettingCategory.ABOUT -> R.string.about
}

private fun categorySummaryRes(category: SettingCategory): Int = when (category) {
    SettingCategory.APPEARANCE -> R.string.settings_category_appearance_summary
    SettingCategory.ACCOUNTS -> R.string.manage_accounts_summary
    SettingCategory.CONNECTION -> R.string.settings_category_connection_summary
    SettingCategory.PRIVACY_SECURITY -> R.string.settings_category_privacy_summary
    SettingCategory.NOTIFICATIONS -> R.string.settings_category_notifications_summary
    SettingCategory.STORAGE -> R.string.settings_category_storage_summary
    SettingCategory.ABOUT -> R.string.settings_category_about_summary
}

private fun categoryIcon(category: SettingCategory): ImageVector = when (category) {
    SettingCategory.APPEARANCE -> Icons.Filled.Star
    SettingCategory.ACCOUNTS -> Icons.Filled.Person
    SettingCategory.CONNECTION -> Icons.Filled.Build
    SettingCategory.PRIVACY_SECURITY -> Icons.Filled.Lock
    SettingCategory.NOTIFICATIONS -> Icons.Filled.Notifications
    SettingCategory.STORAGE -> Icons.AutoMirrored.Filled.List
    SettingCategory.ABOUT -> Icons.Filled.Info
}

@Composable
internal fun SettingsSubHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.back),
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
internal fun SettingsSectionTitle(title: String) {
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
internal fun SettingsLinkRow(
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
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

@Composable
internal fun SettingsStaticRow(
    title: String,
    supportingText: String? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = supportingText?.let { text -> { Text(text) } },
    )
}
