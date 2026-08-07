package com.mindchat.app

import android.os.Build
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private enum class Destination {
    Chats,
    Contacts,
    Settings,
}

@Composable
fun MindChatApp(appLockHost: AppLockHost? = null) {
    val context = LocalContext.current.applicationContext
    val gateway = remember(context) { MindChatGatewayFactory.create(context) }
    MindChatApp(gateway, appLockHost)
}

@Composable
private fun MindChatApp(gateway: MindChatGateway, appLockHost: AppLockHost?) {
    val state = gateway.state
    val context = LocalContext.current
    LaunchedEffect(gateway) {
        while (true) {
            gateway.pollTransport()
            delay(750)
        }
    }
    val colors = when {
        state.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (androidx.compose.foundation.isSystemInDarkTheme()) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)

        androidx.compose.foundation.isSystemInDarkTheme() -> darkColorScheme()
        else -> lightColorScheme()
    }

    MaterialTheme(colorScheme = colors) {
        Surface(modifier = Modifier.fillMaxSize()) {
            if (appLockHost?.appLockState?.blocksContent == true) {
                AppLockedScreen(
                    state = appLockHost.appLockState,
                    onUnlock = appLockHost::requestAppUnlock,
                )
            } else {
                MindChatShell(
                    gateway = gateway,
                    state = state,
                    appLockHost = appLockHost,
                )
            }
        }
    }
}

@Composable
private fun AppLockedScreen(state: AppLockUiState, onUnlock: () -> Unit) {
    LaunchedEffect(state.automaticPromptNonce) {
        if (state.canRequestAuthentication && state.automaticPromptNonce > 0) {
            onUnlock()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.app_locked),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.app_lock_prompt_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onUnlock,
            enabled = state.canRequestAuthentication,
        ) {
            Text(stringResource(R.string.unlock))
        }
    }
}

@Composable
private fun MindChatShell(
    gateway: MindChatGateway,
    state: MindChatUiState,
    appLockHost: AppLockHost?,
) {
    var destination by rememberSaveable { mutableStateOf(Destination.Chats) }
    var selectedConversationId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showAddAccount by rememberSaveable { mutableStateOf(false) }
    var showAddContact by rememberSaveable { mutableStateOf(false) }
    var showNewConversation by rememberSaveable { mutableStateOf(false) }

    if (showAddAccount) {
        AddAccountDialog(
            onDismiss = { showAddAccount = false },
            onSave = gateway::addAccount,
        )
    }

    if (showNewConversation) {
        NewConversationDialog(
            canCreateGroup = state.accounts
                .firstOrNull { it.id == state.activeAccountId }
                ?.supportsGroupChats == true,
            onDismiss = { showNewConversation = false },
            onSave = { address, title, isGroup ->
                gateway.openConversation(address, title, isGroup)
                showNewConversation = false
            },
        )
    }

    if (showAddContact) {
        AddContactDialog(
            onDismiss = { showAddContact = false },
            onSave = { jid, name ->
                gateway.addContact(jid, name)
                showAddContact = false
            },
        )
    }

    val selectedConversation = state.conversations.firstOrNull { it.id == selectedConversationId }
    if (selectedConversation != null) {
        ChatScreen(
            conversation = selectedConversation,
            messages = state.messagesByConversation[selectedConversation.id].orEmpty(),
            onBack = { selectedConversationId = null },
            onSend = { gateway.sendText(selectedConversation.id, it) },
        )
        return
    }

    Scaffold(
        topBar = {
            MindChatTopBar(
                state = state,
                onAccountSelected = gateway::selectAccount,
                onAddAccount = { showAddAccount = true },
            )
        },
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { item ->
                    NavigationBarItem(
                        selected = destination == item,
                        onClick = { destination = item },
                        icon = {
                            Icon(
                                imageVector = when (item) {
                                    Destination.Chats -> Icons.Filled.Home
                                    Destination.Contacts -> Icons.Filled.Star
                                    Destination.Settings -> Icons.Filled.Settings
                                },
                                contentDescription = null,
                            )
                        },
                        label = {
                            Text(
                                when (item) {
                                    Destination.Chats -> stringResource(R.string.conversations)
                                    Destination.Contacts -> stringResource(R.string.contacts)
                                    Destination.Settings -> stringResource(R.string.settings)
                                },
                            )
                        },
                    )
                }
            }
        },
        floatingActionButton = {
            if (state.activeAccountId != 0L) {
                when (destination) {
                    Destination.Chats -> {
                        FloatingActionButton(onClick = { showNewConversation = true }) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = stringResource(R.string.new_chat),
                            )
                        }
                    }

                    Destination.Contacts -> {
                        FloatingActionButton(onClick = { showAddContact = true }) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = stringResource(R.string.add_contact),
                            )
                        }
                    }

                    Destination.Settings -> Unit
                }
            }
        },
    ) { padding ->
        when (destination) {
            Destination.Chats -> ConversationsScreen(
                state = state,
                contentPadding = padding,
                onConversationClick = { selectedConversationId = it.id },
            )

            Destination.Contacts -> ContactsScreen(
                state = state,
                contentPadding = padding,
                onContactClick = { contact ->
                    gateway.openConversation(contact.jid, contact.displayName, group = false)?.let {
                        selectedConversationId = it
                        destination = Destination.Chats
                    }
                },
            )
            Destination.Settings -> SettingsScreen(
                state = state,
                contentPadding = padding,
                onDynamicColorChange = gateway::toggleDynamicColor,
                onComfortableLayoutChange = gateway::toggleComfortableLayout,
                appLockAvailable = appLockHost?.isAuthenticationAvailable ?: true,
                onAppLockChange = {
                    val enabled = !state.appLockEnabled
                    if (!enabled || appLockHost?.isAuthenticationAvailable != false) {
                        appLockHost?.setAppLockEnabled(enabled)
                        gateway.toggleAppLock()
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MindChatTopBar(
    state: MindChatUiState,
    onAccountSelected: (Long) -> Unit,
    onAddAccount: () -> Unit,
) {
    val active = state.accounts.firstOrNull { it.id == state.activeAccountId }
    TopAppBar(
        title = {
            Column {
                Text(
                    text = active?.displayName ?: stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = active?.let { account ->
                        "${account.jid} · ${stringResource(accountConnectionLabel(account.connectionState))}"
                    }.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        actions = {
            IconButton(onClick = onAddAccount) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.add_account),
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    )
    if (state.accounts.size > 1) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.accounts.forEach { account ->
                AssistChip(
                    onClick = { onAccountSelected(account.id) },
                    label = { Text(account.displayName) },
                    leadingIcon = {
                        PresenceDot(account.presence)
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = if (account.id == state.activeAccountId) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                    ),
                )
            }
        }
    }
}

private fun accountConnectionLabel(connectionState: AccountConnectionState): Int = when (connectionState) {
    AccountConnectionState.OFFLINE -> R.string.connection_offline
    AccountConnectionState.CONNECTING -> R.string.connection_connecting
    AccountConnectionState.ONLINE -> R.string.connection_online
    AccountConnectionState.FAILED -> R.string.connection_failed
}

@Composable
private fun ConversationsScreen(
    state: MindChatUiState,
    contentPadding: PaddingValues,
    onConversationClick: (ConversationUi) -> Unit,
) {
    val conversations = state.conversations
        .filter { it.accountId == state.activeAccountId }
        .sortedByDescending { it.lastActivityEpochMs }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(
            start = 12.dp,
            top = 8.dp,
            end = 12.dp,
            bottom = 88.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(if (state.comfortableLayout) 8.dp else 2.dp),
    ) {
        if (conversations.isEmpty()) {
            item {
                EmptyConversations()
            }
        }
        items(conversations, key = { it.id }) { conversation ->
            ConversationRow(conversation = conversation, onClick = { onConversationClick(conversation) })
        }
    }
}

@Composable
private fun EmptyConversations() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 72.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.no_chats),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.no_chats_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConversationRow(conversation: ConversationUi, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(conversation.title, conversation.isGroup)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = conversation.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = conversation.timestamp,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (conversation.encrypted) {
                        Text(
                            text = "🔒",
                            modifier = Modifier.size(15.dp),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        text = conversation.preview,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (conversation.unreadCount > 0) {
                        Spacer(Modifier.width(8.dp))
                        UnreadBadge(conversation.unreadCount)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(
    conversation: ConversationUi,
    messages: List<MessageUi>,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
) {
    var draft by rememberSaveable(conversation.id) { mutableStateOf("") }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(conversation.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = if (conversation.encrypted) {
                                stringResource(R.string.encrypted)
                            } else {
                                conversation.address
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.conversations),
                        )
                    }
                },
            )
        },
        bottomBar = {
            Composer(
                value = draft,
                onValueChange = { draft = it },
                onSend = {
                    onSend(draft)
                    draft = ""
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 12.dp,
            end = 16.dp,
            bottom = 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages, key = { it.id }) { message ->
                MessageBubble(message)
            }
        }
    }
}

@Composable
private fun Composer(value: String, onValueChange: (String) -> Unit, onSend: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.type_message)) },
                maxLines = 5,
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = onSend, enabled = value.isNotBlank()) {
                Text(
                    text = "↑",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(message: MessageUi) {
    val deliveryText = if (message.delivery != null) deliveryLabel(message.delivery) else null
    val colors = if (message.mine) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    } else {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.mine) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.82f),
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = if (message.mine) 20.dp else 4.dp,
                bottomEnd = if (message.mine) 4.dp else 20.dp,
            ),
            colors = colors,
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (!message.mine) {
                    Text(
                        text = message.sender,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(3.dp))
                }
                Text(message.body, style = MaterialTheme.typography.bodyLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = listOfNotNull(message.timestamp, deliveryText).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (message.reactions.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = message.reactions.joinToString("  "),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun deliveryLabel(delivery: MessageDelivery): String = when (delivery) {
    MessageDelivery.PENDING -> stringResource(R.string.delivery_pending)
    MessageDelivery.SENT -> stringResource(R.string.delivery_sent)
    MessageDelivery.DELIVERED -> stringResource(R.string.delivery_delivered)
    MessageDelivery.READ -> stringResource(R.string.delivery_read)
    MessageDelivery.FAILED -> stringResource(R.string.delivery_failed)
}

@Composable
private fun ContactsScreen(
    state: MindChatUiState,
    contentPadding: PaddingValues,
    onContactClick: (ContactUi) -> Unit,
) {
    val active = state.accounts.firstOrNull { it.id == state.activeAccountId }
    val contacts = state.contacts
        .filter { it.accountId == state.activeAccountId }
        .sortedBy { it.displayName.lowercase() }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 16.dp,
            end = 16.dp,
            bottom = 16.dp,
        ),
    ) {
        item {
            Text(
                text = active?.jid.orEmpty(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
        if (contacts.isEmpty()) {
            item {
                EmptyContacts()
            }
        }
        items(contacts, key = { it.jid }) { contact ->
            ListItem(
                modifier = Modifier.clickable { onContactClick(contact) },
                headlineContent = { Text(contact.displayName) },
                supportingContent = {
                    Column {
                        Text(contact.jid)
                        contact.status?.let { status ->
                            Text(
                                text = status,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                },
                leadingContent = { Avatar(contact.displayName, contact.jid.contains("conference")) },
                trailingContent = { PresenceDot(contact.presence) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun EmptyContacts() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 72.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.no_contacts),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.no_contacts_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsScreen(
    state: MindChatUiState,
    contentPadding: PaddingValues,
    onDynamicColorChange: () -> Unit,
    onComfortableLayoutChange: () -> Unit,
    appLockAvailable: Boolean,
    onAppLockChange: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 16.dp,
            end = 16.dp,
            bottom = 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            SettingsSectionTitle(stringResource(R.string.appearance))
            SettingsSwitch(
                title = stringResource(R.string.use_dynamic_colors),
                checked = state.dynamicColor,
                onCheckedChange = { onDynamicColorChange() },
            )
            SettingsSwitch(
                title = stringResource(R.string.comfortable_layout),
                checked = state.comfortableLayout,
                onCheckedChange = { onComfortableLayoutChange() },
            )
        }
        item {
            SettingsSectionTitle(stringResource(R.string.privacy))
            SettingsSwitch(
                title = stringResource(R.string.app_lock),
                checked = state.appLockEnabled,
                supportingText = stringResource(
                    if (appLockAvailable) R.string.app_lock_summary else R.string.app_lock_unavailable,
                ),
                enabled = appLockAvailable || state.appLockEnabled,
                onCheckedChange = { onAppLockChange() },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.diagnostics)) },
                supportingContent = { Text(stringResource(R.string.diagnostics_summary)) },
            )
        }
        item {
            SettingsSectionTitle(stringResource(R.string.about))
            ListItem(
                headlineContent = { Text(stringResource(R.string.app_name)) },
                supportingContent = { Text(stringResource(R.string.privacy_summary)) },
            )
            Text(
                text = stringResource(R.string.coming_soon),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsSectionTitle(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(top = 12.dp, start = 16.dp, bottom = 4.dp),
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
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = supportingText?.let { text -> { Text(text) } },
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
private fun Avatar(label: String, isGroup: Boolean) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (isGroup) "G" else label.take(1).uppercase(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun PresenceDot(presence: Presence) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(
                when (presence) {
                    Presence.ONLINE -> Color(0xFF2E7D32)
                    Presence.AWAY -> Color(0xFFF9A825)
                    Presence.DO_NOT_DISTURB -> MaterialTheme.colorScheme.error
                    Presence.OFFLINE -> MaterialTheme.colorScheme.outline
                },
            ),
    )
}

@Composable
private fun UnreadBadge(count: Int) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

@Composable
private fun AddAccountDialog(
    onDismiss: () -> Unit,
    onSave: (jid: String, server: String, displayName: String, password: String) -> Boolean,
) {
    var jid by rememberSaveable { mutableStateOf("") }
    var server by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var failedToStartSession by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_account)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = jid,
                    onValueChange = { jid = it },
                    label = { Text(stringResource(R.string.account_jid)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it },
                    label = { Text(stringResource(R.string.server)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text(stringResource(R.string.display_name)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        failedToStartSession = false
                    },
                    label = { Text(stringResource(R.string.password)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    isError = failedToStartSession,
                )
                if (failedToStartSession) {
                    Text(
                        text = stringResource(R.string.account_connection_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (onSave(jid, server, displayName, password)) {
                        onDismiss()
                    } else {
                        failedToStartSession = true
                    }
                },
                enabled = jid.contains('@') && server.isNotBlank() && password.isNotEmpty(),
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun AddContactDialog(
    onDismiss: () -> Unit,
    onSave: (jid: String, displayName: String) -> Unit,
) {
    var jid by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_contact)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = jid,
                    onValueChange = { jid = it },
                    label = { Text(stringResource(R.string.contact_jid)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text(stringResource(R.string.display_name)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(jid, displayName) },
                enabled = jid.contains('@'),
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun NewConversationDialog(
    canCreateGroup: Boolean,
    onDismiss: () -> Unit,
    onSave: (address: String, title: String, isGroup: Boolean) -> Unit,
) {
    var address by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var isGroup by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_chat)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text(stringResource(R.string.conversation_address)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.conversation_title)) },
                    singleLine = true,
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.group_chat)) },
                    trailingContent = {
                        Switch(
                            checked = isGroup,
                            onCheckedChange = { isGroup = it },
                            enabled = canCreateGroup,
                        )
                    },
                    supportingContent = if (canCreateGroup) {
                        null
                    } else {
                        { Text(stringResource(R.string.group_chat_unavailable)) }
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(address, title, isGroup) },
                enabled = address.isNotBlank(),
            ) {
                Text(stringResource(R.string.create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
