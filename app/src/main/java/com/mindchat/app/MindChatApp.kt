package com.mindchat.app

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import com.mindchat.app.theme.ACCENT_DEFAULT_KEY
import com.mindchat.app.theme.AccentOptions
import com.mindchat.app.theme.LocalMindChatMotionSpeed
import com.mindchat.app.theme.LocalMindChatStatusColors
import com.mindchat.app.theme.MindChatDockItemShape
import com.mindchat.app.theme.MindChatDockShape
import com.mindchat.app.theme.MindChatMotionScheme
import com.mindchat.app.theme.MindChatTheme
import com.mindchat.app.theme.bubbleContainerColor
import com.mindchat.app.theme.bubbleOutlineColor
import com.mindchat.app.theme.bubbleShape
import com.mindchat.app.theme.chatListBackground
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, gateway) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                lifecycleOwner.lifecycleScope.launch { gateway.persistNow() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(gateway) {
        while (true) {
            gateway.pollTransport()
            delay(750)
        }
    }
    // Per-account accent override: the active account's profile accent seeds
    // the theme, keeping the dynamic/system default when none is chosen.
    val activeAccent = state.profiles[state.activeAccountId]
        ?.accentKey
        ?.let { key -> AccentOptions.firstOrNull { it.key == key }?.color }
    // Global appearance merged with the active account's bubble/background
    // overrides; this resolved profile drives shapes, type, motion and the
    // chat-screen personality.
    val resolvedAppearance = resolveAppearance(state.appearance, state.profiles[state.activeAccountId])
    MindChatTheme(
        appearance = resolvedAppearance,
        dynamicColor = state.dynamicColor,
        accentSeed = activeAccent,
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            if (appLockHost?.appLockState?.blocksContent == true) {
                AppLockedScreen(
                    state = appLockHost.appLockState,
                    onUnlock = appLockHost::requestAppUnlock,
                )
            } else if (state.accounts.isEmpty()) {
                // First-run experience: no account yet, show the full-screen login.
                // Once addAccount succeeds the account list becomes non-empty and
                // this composable is replaced by the main shell automatically.
                LoginScreen(
                    onConnect = gateway::addAccount,
                    onRegister = gateway::registerAccount,
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
    var showAppearance by rememberSaveable { mutableStateOf(false) }
    var reconnectAccountId by rememberSaveable { mutableStateOf<Long?>(null) }
    var renameAccountId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleteAccountId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleteConversationId by rememberSaveable { mutableStateOf<Long?>(null) }
    var profileAccountId by rememberSaveable { mutableStateOf<Long?>(null) }

    // Settings sub-navigation (root -> category -> account settings). The
    // stack is saver-backed so rotation restores where the user was.
    var settingsNavState by rememberSaveable(stateSaver = SettingsNavSaver) {
        mutableStateOf(SettingsNavState())
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    // Transient result feedback (B6): deletes and profile saves surface here.
    val snackbarHostState = remember { SnackbarHostState() }

    val accountDeletedMessage = stringResource(R.string.account_deleted)
    val conversationDeletedMessage = stringResource(R.string.conversation_deleted)
    val profileSavedMessage = stringResource(R.string.profile_saved)

    if (showAddAccount) {
        AddAccountDialog(
            onDismiss = { showAddAccount = false },
            onSave = gateway::addAccount,
        )
    }

    val reconnectAccount = reconnectAccountId?.let { id ->
        state.accounts.firstOrNull { it.id == id }
    }
    if (reconnectAccount != null) {
        ReconnectDialog(
            account = reconnectAccount,
            onDismiss = { reconnectAccountId = null },
            onReconnect = { password ->
                if (gateway.reconnectAccount(reconnectAccount.id, password)) {
                    reconnectAccountId = null
                    true
                } else {
                    false
                }
            },
        )
    }

    if (showNewConversation) {
        NewConversationDialog(
            canCreateGroup = state.accounts
                .firstOrNull { it.id == state.activeAccountId }
                ?.supportsGroupChats == true,
            onDismiss = { showNewConversation = false },
            onSave = { address, title, isGroup ->
                gateway.openConversation(address, title, isGroup) != null
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

    renameAccountId?.let { id ->
        state.accounts.firstOrNull { it.id == id }?.let { account ->
            RenameAccountDialog(
                account = account,
                onDismiss = { renameAccountId = null },
                onRename = { name ->
                    runCatching { gateway.renameAccount(account.id, name) }
                        .onSuccess { renameAccountId = null }
                        .isSuccess
                },
            )
        }
    }

    deleteAccountId?.let { id ->
        state.accounts.firstOrNull { it.id == id }?.let { account ->
            DeleteAccountDialog(
                account = account,
                onDismiss = { deleteAccountId = null },
                onConfirm = {
                    val deleted = runCatching { gateway.deleteAccount(account.id) }.isSuccess
                    if (deleted) {
                        deleteAccountId = null
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                message = accountDeletedMessage,
                                withDismissAction = true,
                                duration = SnackbarDuration.Short,
                            )
                        }
                    }
                    deleted
                },
            )
        }
    }

    deleteConversationId?.let { id ->
        state.conversations.firstOrNull { it.id == id }?.let { conversation ->
            DeleteConversationDialog(
                conversation = conversation,
                onDismiss = { deleteConversationId = null },
                onConfirm = {
                    val deleted = runCatching { gateway.deleteConversation(conversation.id) }.isSuccess
                    if (deleted) {
                        if (selectedConversationId == conversation.id) {
                            selectedConversationId = null
                        }
                        deleteConversationId = null
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                message = conversationDeletedMessage,
                                withDismissAction = true,
                                duration = SnackbarDuration.Short,
                            )
                        }
                    }
                    deleted
                },
            )
        }
    }

    profileAccountId?.let { id ->
        state.accounts.firstOrNull { it.id == id }?.let { account ->
            ProfileSheet(
                account = account,
                profile = state.profiles[id],
                onDismiss = { profileAccountId = null },
                onSave = { updatedProfile, newDisplayName ->
                    val saved = saveProfile(gateway, account, updatedProfile, newDisplayName)
                    if (saved) {
                        profileAccountId = null
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                message = profileSavedMessage,
                                withDismissAction = true,
                                duration = SnackbarDuration.Short,
                            )
                        }
                    }
                    saved
                },
            )
        }
    }

    val resolved = resolveAppearance(state.appearance, state.profiles[state.activeAccountId])
    val selectedConversation = state.conversations.firstOrNull { it.id == selectedConversationId }
    if (selectedConversation != null) {
        // Clear the unread badge once the conversation becomes visible.
        LaunchedEffect(selectedConversation.id) {
            gateway.markConversationRead(selectedConversation.id)
        }
        // System back returns to the conversation list instead of exiting the app.
        BackHandler {
            selectedConversationId = null
        }
        ChatScreen(
            conversation = selectedConversation,
            messages = state.messagesByConversation[selectedConversation.id].orEmpty(),
            bubbleStyle = resolved.bubbleStyle,
            chatBackground = resolved.chatBackground,
            onBack = { selectedConversationId = null },
            onSend = { gateway.sendText(selectedConversation.id, it) },
            onDeleteConversation = { deleteConversationId = selectedConversation.id },
        )
        return
    }

    if (showAppearance) {
        // Full-screen appearance engine; system back leaves it like any other
        // top-level destination.
        BackHandler {
            showAppearance = false
        }
        AppearanceScreen(
            gateway = gateway,
            state = state,
            onBack = { showAppearance = false },
        )
        return
    }

    // System back closes the account drawer before leaving the shell.
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                AccountDrawer(
                    state = state,
                    onAccountSelected = { accountId ->
                        gateway.selectAccount(accountId)
                        scope.launch { drawerState.close() }
                    },
                    onAddAccount = {
                        showAddAccount = true
                        scope.launch { drawerState.close() }
                    },
                    onReconnect = { reconnectAccountId = it },
                    onDisconnect = gateway::disconnectAccount,
                    onRename = { renameAccountId = it },
                    onDelete = { deleteAccountId = it },
                    onEditProfile = { profileAccountId = it },
                )
            }
        },
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                MindChatTopBar(
                    state = state,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onAddAccount = { showAddAccount = true },
                    onReconnect = { reconnectAccountId = it },
                    onCancelConnection = gateway::disconnectAccount,
                )
            },
            bottomBar = {
                FloatingDock(
                    selected = destination,
                    onSelect = { destination = it },
                )
            },
            floatingActionButton = {
                if (state.activeAccountId != 0L) {
                    when (destination) {
                        Destination.Chats -> {
                            ExtendedFloatingActionButton(
                                onClick = { showNewConversation = true },
                                icon = {
                                    Icon(
                                        imageVector = Icons.Filled.Add,
                                        contentDescription = null,
                                    )
                                },
                                text = { Text(stringResource(R.string.new_chat)) },
                            )
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
                    density = resolved.density,
                    contentPadding = padding,
                    onConversationClick = { selectedConversationId = it.id },
                    onMarkRead = { gateway.markConversationRead(it.id) },
                    onOpenAsGroup = { conversation ->
                        gateway.openConversation(conversation.address, conversation.title, group = true)
                            ?.let { selectedConversationId = it }
                    },
                    onDeleteConversation = { deleteConversationId = it.id },
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
                Destination.Settings -> {
                    // System back walks the settings back stack first; at the
                    // root the shell's default (or drawer) handling applies.
                    BackHandler(enabled = settingsNavState.backStack.size > 1) {
                        settingsNavState.back()
                    }
                    SettingsScreen(
                        gateway = gateway,
                        state = state,
                        navState = settingsNavState,
                        contentPadding = padding,
                        onOpenAccountDrawer = {
                            scope.launch { drawerState.open() }
                        },
                        onAddAccount = { showAddAccount = true },
                        onOpenAppearance = { showAppearance = true },
                        appLockHostAvailable = appLockHost?.isAuthenticationAvailable ?: true,
                        onAppLockToggle = { enabled ->
                            if (!enabled || appLockHost?.isAuthenticationAvailable != false) {
                                appLockHost?.setAppLockEnabled(enabled)
                                gateway.toggleAppLock()
                            }
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MindChatTopBar(
    state: MindChatUiState,
    onOpenDrawer: () -> Unit,
    onAddAccount: () -> Unit,
    onReconnect: (Long) -> Unit,
    onCancelConnection: (Long) -> Unit,
) {
    val active = state.accounts.firstOrNull { it.id == state.activeAccountId }
    // A connecting account that has exceeded the stall threshold needs explicit
    // cancel/retry affordances instead of an endless spinner.
    val stalled = active?.connectionStalled == true
    val needsReconnect = active != null &&
        (stalled ||
            active.connectionState == AccountConnectionState.FAILED ||
            active.connectionError != null)
    TopAppBar(
        navigationIcon = {
            val avatarLabel = active?.let {
                stringResource(R.string.account_avatar, it.displayName)
            } ?: stringResource(R.string.open_account_menu)
            // The avatar chip opens the account drawer; the corner dot mirrors
            // the active account's connection state.
            IconButton(
                onClick = onOpenDrawer,
                modifier = Modifier.size(48.dp),
            ) {
                if (active != null) {
                    Box {
                        ProfileAvatar(
                            account = active,
                            avatarUri = state.profiles[active.id]?.avatarUri,
                            contentDescription = avatarLabel,
                            modifier = Modifier.size(40.dp),
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(12.dp)
                                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                                .clip(CircleShape)
                                .background(connectionDotColor(active.connectionState)),
                        )
                    }
                } else {
                    Icon(
                        imageVector = Icons.Filled.Person,
                        contentDescription = stringResource(R.string.open_account_menu),
                    )
                }
            }
        },
        title = {
            Column {
                Text(
                    text = active?.displayName ?: stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = active?.let { account ->
                            "${account.jid} · ${stringResource(accountConnectionLabel(account.connectionState))}"
                        }.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (active?.connectionState == AccountConnectionState.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    if (active?.connectionState == AccountConnectionState.CONNECTING) {
                        Spacer(Modifier.width(6.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
                active?.connectionError?.let { detail ->
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (stalled) {
                    Text(
                        text = stringResource(R.string.connection_stalled),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        actions = {
            // A stalled connection can be cancelled outright; both stalled and
            // failed states offer a retry through the Reconnect dialog.
            if (stalled) {
                TextButton(onClick = { active?.let { onCancelConnection(it.id) } }) {
                    Text(stringResource(R.string.cancel))
                }
            }
            if (needsReconnect) {
                TextButton(onClick = { active?.let { onReconnect(it.id) } }) {
                    Text(stringResource(if (stalled) R.string.retry else R.string.reconnect))
                }
            }
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
}

@Composable
private fun connectionDotColor(state: AccountConnectionState): Color {
    val status = LocalMindChatStatusColors.current
    return when (state) {
        AccountConnectionState.ONLINE -> status.online
        AccountConnectionState.CONNECTING -> status.away
        AccountConnectionState.FAILED -> status.failed
        AccountConnectionState.OFFLINE -> MaterialTheme.colorScheme.outline
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
    density: Density,
    contentPadding: PaddingValues,
    onConversationClick: (ConversationUi) -> Unit,
    onMarkRead: (ConversationUi) -> Unit,
    onOpenAsGroup: (ConversationUi) -> Unit,
    onDeleteConversation: (ConversationUi) -> Unit,
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
        verticalArrangement = Arrangement.spacedBy(density.listSpacing),
    ) {
        if (conversations.isEmpty()) {
            item {
                EmptyConversations()
            }
        }
        items(conversations, key = { it.id }) { conversation ->
            ConversationRow(
                conversation = conversation,
                rowPadding = density.rowPadding,
                onClick = { onConversationClick(conversation) },
                onMarkRead = { onMarkRead(conversation) },
                onOpenAsGroup = { onOpenAsGroup(conversation) },
                onDelete = { onDeleteConversation(conversation) },
            )
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
        // M3E empty state: a tonal medallion pairs display text with
        // iconography instead of bare text (P1 B10).
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Email,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
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
private fun ConversationRow(
    conversation: ConversationUi,
    rowPadding: Dp,
    onClick: () -> Unit,
    onMarkRead: () -> Unit,
    onOpenAsGroup: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.padding(
                start = rowPadding,
                top = rowPadding,
                end = 6.dp,
                bottom = rowPadding,
            ),
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
                        // Encrypted marker is a tintable, describable icon
                        // (M3E P0 B3), not a font-dependent emoji glyph.
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = stringResource(R.string.encrypted),
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
            // Overflow menu: mark read, open as group, delete. The icon button
            // keeps its own 48dp touch target and consumes its own taps.
            // GAP (0.1.5): pinning/archiving are NOT offered here because the
            // domain core has no pin/archive model yet; do not fake them with
            // local-only state until the core supports them.
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.conversation_actions),
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                if (conversation.unreadCount > 0) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.mark_read)) },
                        onClick = {
                            menuExpanded = false
                            onMarkRead()
                        },
                    )
                }
                if (!conversation.isGroup) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.open_as_group)) },
                        onClick = {
                            menuExpanded = false
                            onOpenAsGroup()
                        },
                    )
                }
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.delete_conversation),
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
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(
    conversation: ConversationUi,
    messages: List<MessageUi>,
    bubbleStyle: BubbleStyle,
    chatBackground: ChatBackground,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onDeleteConversation: () -> Unit,
) {
    var draft by rememberSaveable(conversation.id) { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    Scaffold(
        containerColor = chatListBackground(chatBackground, MaterialTheme.colorScheme),
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                actions = {
                    var menuExpanded by remember { mutableStateOf(false) }
                    // Group detail offers "Leave group"; direct chats offer
                    // "Delete conversation". Both remove the local record via
                    // the deleteConversation hook once wired (0.1.5).
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.conversation_actions),
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = stringResource(
                                        if (conversation.isGroup) R.string.leave_group else R.string.delete_conversation,
                                    ),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onDeleteConversation()
                            },
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
                MessageBubble(message, bubbleStyle)
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
                Icon(
                    imageVector = Icons.Filled.Send,
                    contentDescription = stringResource(R.string.send),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(message: MessageUi, style: BubbleStyle) {
    val deliveryText = if (message.delivery != null) deliveryLabel(message.delivery) else null
    val scheme = MaterialTheme.colorScheme
    val containerColor = bubbleContainerColor(style, message.mine, scheme)
    val borderColor = bubbleOutlineColor(style, scheme)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.mine) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.82f)
                .then(
                    if (borderColor != Color.Transparent) {
                        Modifier.border(1.dp, borderColor, bubbleShape(style, message.mine))
                    } else {
                        Modifier
                    },
                ),
            shape = bubbleShape(style, message.mine),
            colors = CardDefaults.cardColors(containerColor = containerColor),
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
        // M3E empty state: tonal medallion with iconography (P1 B10).
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
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

/**
 * 0.1.6 floating navigation dock: a raised, pill-shaped M3 Expressive dock
 * with a tonal surfaceContainerHighest container, a 28dp silhouette, a subtle
 * shadow, safe-area awareness (navigationBarsPadding + bottom offset), and a
 * secondaryContainer pill behind the selected destination. It is centered and
 * floats clear of the screen edges, replacing the edge-to-edge NavigationBar.
 */
@Composable
private fun FloatingDock(
    selected: Destination,
    onSelect: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(top = 8.dp, bottom = 20.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = MindChatDockShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier.padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Destination.entries.forEach { item ->
                    FloatingDockItem(
                        destination = item,
                        selected = selected == item,
                        onClick = { onSelect(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FloatingDockItem(
    destination: Destination,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // The selection pill animates through the app motion scheme, scaled by
    // the appearance engine's animation speed (B1): no bare tween literals.
    val effectsSpec = MindChatMotionScheme.effectsSpecFor<Color>(LocalMindChatMotionSpeed.current)
    val containerColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            Color.Transparent
        },
        animationSpec = effectsSpec,
        label = "floatingDockContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = effectsSpec,
        label = "floatingDockContent",
    )
    Row(
        modifier = Modifier
            .height(52.dp)
            .clip(MindChatDockItemShape)
            .background(containerColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = destinationIcon(destination),
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = destinationLabel(destination),
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
        )
    }
}

@Composable
private fun destinationLabel(destination: Destination): String = stringResource(
    when (destination) {
        Destination.Chats -> R.string.conversations
        Destination.Contacts -> R.string.contacts
        Destination.Settings -> R.string.settings
    },
)

private fun destinationIcon(destination: Destination): ImageVector = when (destination) {
    Destination.Chats -> Icons.Filled.Home
    Destination.Contacts -> Icons.Filled.Star
    Destination.Settings -> Icons.Filled.Settings
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
    val status = LocalMindChatStatusColors.current
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(
                when (presence) {
                    Presence.ONLINE -> status.online
                    Presence.AWAY -> status.away
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
    var serverTouched by rememberSaveable { mutableStateOf(false) }
    var displayName by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var failedToStartSession by remember { mutableStateOf(false) }

    val jidInvalid = !jid.contains('@')
    val serverEmpty = server.isBlank()
    val passwordEmpty = password.isEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(stringResource(R.string.add_account)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = jid,
                    onValueChange = { newJid ->
                        jid = newJid
                        failedToStartSession = false
                        // The server is normally the JID domain; derive it until
                        // the user edits the server field explicitly.
                        val at = newJid.indexOf('@')
                        if (at >= 0 && !serverTouched) {
                            server = newJid.substring(at + 1)
                        }
                    },
                    label = { Text(stringResource(R.string.account_jid)) },
                    singleLine = true,
                    isError = jidInvalid && jid.isNotEmpty(),
                    supportingText = if (jidInvalid && jid.isNotEmpty()) {
                        { Text(stringResource(R.string.login_jid_invalid)) }
                    } else {
                        null
                    },
                )
                OutlinedTextField(
                    value = server,
                    onValueChange = {
                        server = it
                        serverTouched = true
                        failedToStartSession = false
                    },
                    label = { Text(stringResource(R.string.server)) },
                    singleLine = true,
                    isError = serverEmpty,
                    supportingText = if (serverEmpty) {
                        { Text(stringResource(R.string.login_server_required)) }
                    } else {
                        null
                    },
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text(stringResource(R.string.display_name_optional)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        failedToStartSession = false
                    },
                    label = { Text(stringResource(R.string.password)) },
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
                    isError = failedToStartSession || passwordEmpty,
                    supportingText = if (passwordEmpty) {
                        { Text(stringResource(R.string.login_password_required)) }
                    } else {
                        null
                    },
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
            FilledTonalButton(
                onClick = {
                    if (onSave(jid, server, displayName, password)) {
                        onDismiss()
                    } else {
                        failedToStartSession = true
                    }
                },
                enabled = !jidInvalid && !serverEmpty && password.isNotEmpty(),
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
internal fun ReconnectDialog(
    account: AccountUi,
    onDismiss: () -> Unit,
    onReconnect: (password: String) -> Boolean,
) {
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var failedToReconnect by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(stringResource(R.string.reconnect_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = account.jid,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        failedToReconnect = false
                    },
                    label = { Text(stringResource(R.string.password)) },
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
                    isError = failedToReconnect || password.isEmpty(),
                    supportingText = if (password.isEmpty()) {
                        { Text(stringResource(R.string.login_password_required)) }
                    } else {
                        null
                    },
                )
                if (failedToReconnect) {
                    Text(
                        text = stringResource(R.string.account_connection_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = {
                    if (onReconnect(password)) {
                        onDismiss()
                    } else {
                        failedToReconnect = true
                    }
                },
                enabled = password.isNotEmpty(),
            ) { Text(stringResource(R.string.reconnect)) }
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
        shape = MaterialTheme.shapes.extraLarge,
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
    onSave: (address: String, title: String, isGroup: Boolean) -> Boolean,
) {
    var address by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var isGroup by rememberSaveable { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(stringResource(R.string.new_chat)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = address,
                    onValueChange = {
                        address = it
                        failed = false
                    },
                    label = { Text(stringResource(R.string.conversation_address)) },
                    singleLine = true,
                    isError = failed,
                    supportingText = if (failed) {
                        { Text(stringResource(R.string.group_create_failed)) }
                    } else {
                        null
                    },
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
            FilledTonalButton(
                onClick = {
                    if (onSave(address, title, isGroup)) {
                        onDismiss()
                    } else {
                        failed = true
                    }
                },
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

/* ===================================================================== *
 * 0.1.5 account management: drawer, per-account profiles, chat actions. *
 * ===================================================================== */

@Composable
private fun AccountDrawer(
    state: MindChatUiState,
    onAccountSelected: (Long) -> Unit,
    onAddAccount: () -> Unit,
    onReconnect: (Long) -> Unit,
    onDisconnect: (Long) -> Unit,
    onRename: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onEditProfile: (Long) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.accounts),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 24.dp, top = 24.dp, bottom = 8.dp),
        )
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(state.accounts, key = { it.id }) { account ->
                AccountDrawerRow(
                    account = account,
                    profile = state.profiles[account.id],
                    isActive = account.id == state.activeAccountId,
                    onClick = { onAccountSelected(account.id) },
                    onReconnect = { onReconnect(account.id) },
                    onDisconnect = { onDisconnect(account.id) },
                    onRename = { onRename(account.id) },
                    onDelete = { onDelete(account.id) },
                    onEditProfile = { onEditProfile(account.id) },
                )
            }
        }
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        ListItem(
            modifier = Modifier.clickable(onClick = onAddAccount),
            leadingContent = {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            headlineContent = { Text(stringResource(R.string.add_account)) },
        )
    }
}

@Composable
private fun AccountDrawerRow(
    account: AccountUi,
    profile: AccountProfile?,
    isActive: Boolean,
    onClick: () -> Unit,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onEditProfile: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = stringResource(R.string.switch_to_account, account.displayName),
                onClick = onClick,
            )
            .background(
                if (isActive) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
            ),
        leadingContent = {
            Box {
                ProfileAvatar(
                    account = account,
                    avatarUri = profile?.avatarUri,
                    contentDescription = stringResource(R.string.account_avatar, account.displayName),
                    modifier = Modifier.size(44.dp),
                )
                ConnectionStatusIndicator(
                    state = account.connectionState,
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
        },
        headlineContent = {
            Text(
                text = account.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = "${account.jid} · ${stringResource(accountConnectionLabel(account.connectionState))}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.actions_for_account, account.displayName),
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.edit_profile)) },
                    onClick = {
                        menuExpanded = false
                        onEditProfile()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.reconnect)) },
                    onClick = {
                        menuExpanded = false
                        onReconnect()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.disconnect)) },
                    onClick = {
                        menuExpanded = false
                        onDisconnect()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.rename_account)) },
                    onClick = {
                        menuExpanded = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.delete_account),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onDelete()
                    },
                )
            }
        },
    )
}

/** Initials or picked-image avatar for an account, with an accessible label. */
@Composable
internal fun ProfileAvatar(
    account: AccountUi,
    avatarUri: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    if (avatarUri != null) {
        AvatarImage(
            uri = avatarUri,
            contentDescription = contentDescription,
            modifier = modifier,
        )
    } else {
        Box(
            modifier = modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .semantics { contentDescription?.let { this.contentDescription = it } },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = account.displayName.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/**
 * Decodes a local avatar (content URI or copied file path) off the main
 * thread with downsampling so large gallery images stay cheap to render.
 */
@Composable
private fun AvatarImage(
    uri: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { decodeSampledAvatar(context, Uri.parse(uri)) }.getOrNull()
        }
    }
    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = contentDescription,
            modifier = modifier.clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .semantics { contentDescription?.let { this.contentDescription = it } },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun decodeSampledAvatar(context: Context, uri: Uri, targetSize: Int = 256): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, bounds)
    }
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetSize || bounds.outHeight / (sample * 2) >= targetSize) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return context.contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, options)?.asImageBitmap()
    }
}

/**
 * Copies a picked avatar into app-private storage so it survives the picker's
 * temporary read grant. Falls back to the original URI when the copy fails.
 */
private fun persistAvatarCopy(context: Context, accountId: Long, uri: String?): String? {
    if (uri == null) return null
    return runCatching {
        val dir = File(context.filesDir, "avatars").apply { mkdirs() }
        val target = File(dir, "account_$accountId.img")
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
        target.absolutePath
    }.getOrElse { uri }
}

/** Small dot or spinner that mirrors an account's connection state. */
@Composable
internal fun ConnectionStatusIndicator(
    state: AccountConnectionState,
    modifier: Modifier = Modifier,
) {
    when (state) {
        AccountConnectionState.CONNECTING -> CircularProgressIndicator(
            modifier = modifier.size(16.dp),
            strokeWidth = 2.dp,
        )

        else -> Box(
            modifier = modifier
                .size(10.dp)
                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                .clip(CircleShape)
                .background(connectionDotColor(state)),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileSheet(
    account: AccountUi,
    profile: AccountProfile?,
    onDismiss: () -> Unit,
    onSave: (profile: AccountProfile, displayName: String) -> Boolean,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        ProfileEditorContent(
            account = account,
            profile = profile,
            onSave = onSave,
            onCancel = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        )
    }
}

/**
 * Shared profile editor body used by the drawer's [ProfileSheet] and the
 * settings AccountSettings screen: one editor, no duplication (T10).
 */
@Composable
internal fun ProfileEditorContent(
    account: AccountUi,
    profile: AccountProfile?,
    onSave: (profile: AccountProfile, displayName: String) -> Boolean,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var displayName by rememberSaveable(account.id) { mutableStateOf(account.displayName) }
    var statusMessage by rememberSaveable(account.id) { mutableStateOf(profile?.statusMessage.orEmpty()) }
    var accentKey by rememberSaveable(account.id) { mutableStateOf(profile?.accentKey) }
    var bubbleStyle by rememberSaveable(account.id) { mutableStateOf(profile?.bubbleStyle) }
    var chatBackground by rememberSaveable(account.id) { mutableStateOf(profile?.chatBackground) }
    var avatarUri by rememberSaveable(account.id) { mutableStateOf(profile?.avatarUri) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    val avatarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        uri?.let { avatarUri = it.toString() }
    }

    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.profile_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = account.jid,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProfileAvatar(
                account = account,
                avatarUri = avatarUri,
                contentDescription = stringResource(R.string.account_avatar, account.displayName),
                modifier = Modifier.size(72.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column {
                FilledTonalButton(
                    onClick = {
                        avatarPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                ) {
                    Text(stringResource(R.string.change_avatar))
                }
                if (avatarUri != null) {
                    TextButton(onClick = { avatarUri = null }) {
                        Text(stringResource(R.string.remove_avatar))
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = displayName,
            onValueChange = {
                displayName = it
                failed = false
            },
            label = { Text(stringResource(R.string.display_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = statusMessage,
            onValueChange = { statusMessage = it },
            label = { Text(stringResource(R.string.status_message)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.accent_color),
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(8.dp))
        AccentSelector(selectedKey = accentKey, onSelect = { accentKey = it })
        Spacer(Modifier.height(20.dp))
        // Chat personality overrides (0.1.7): null = follow the global
        // appearance profile; saved through the same onSave(AccountProfile).
        ProfileEnumSelectorRow(
            title = stringResource(R.string.chat_bubbles),
            options = BubbleStyle.entries.map { style ->
                style.key to stringResource(bubbleStyleLabelRes(style))
            },
            selectedKey = bubbleStyle?.key,
            onSelect = { key ->
                bubbleStyle = BubbleStyle.entries.firstOrNull { it.key == key }
            },
        )
        Spacer(Modifier.height(16.dp))
        ProfileEnumSelectorRow(
            title = stringResource(R.string.chat_background),
            options = ChatBackground.entries.map { background ->
                background.key to stringResource(chatBackgroundLabelRes(background))
            },
            selectedKey = chatBackground?.key,
            onSelect = { key ->
                chatBackground = ChatBackground.entries.firstOrNull { it.key == key }
            },
        )
        if (failed) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.not_available_yet),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onCancel != null) {
                TextButton(onClick = onCancel, enabled = !saving) {
                    Text(stringResource(R.string.cancel))
                }
                Spacer(Modifier.width(8.dp))
            }
            FilledTonalButton(
                onClick = {
                    saving = true
                    failed = false
                    scope.launch {
                        val persistedAvatar = withContext(Dispatchers.IO) {
                            persistAvatarCopy(context, account.id, avatarUri)
                        }
                        val saved = onSave(
                            AccountProfile(
                                avatarUri = persistedAvatar,
                                statusMessage = statusMessage.trim().ifBlank { null },
                                accentKey = accentKey?.takeIf { it != ACCENT_DEFAULT_KEY },
                                bubbleStyle = bubbleStyle,
                                chatBackground = chatBackground,
                            ),
                            displayName.trim(),
                        )
                        saving = false
                        if (!saved) failed = true
                    }
                },
                enabled = displayName.isNotBlank() && !saving,
            ) {
                Text(
                    text = if (saving) {
                        stringResource(R.string.saving)
                    } else {
                        stringResource(R.string.save)
                    },
                )
            }
        }
    }
}

/**
 * Shared profile save flow behind [ProfileEditorContent]: rename the account
 * first (when the display name changed), then persist the profile. Returns
 * false when the rename was rejected so the editor can surface the failure.
 */
internal fun saveProfile(
    gateway: MindChatGateway,
    account: AccountUi,
    updatedProfile: AccountProfile,
    newDisplayName: String,
): Boolean {
    val trimmedName = newDisplayName.trim()
    val renamed = if (trimmedName.isNotEmpty() && trimmedName != account.displayName) {
        runCatching { gateway.renameAccount(account.id, trimmedName) }.isSuccess
    } else {
        true
    }
    if (renamed) {
        gateway.updateProfile(account.id, updatedProfile)
    }
    return renamed
}

@Composable
private fun AccentSelector(
    selectedKey: String?,
    onSelect: (String) -> Unit,
) {
    val isDefault = selectedKey == null || selectedKey == ACCENT_DEFAULT_KEY
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .clickable { onSelect(ACCENT_DEFAULT_KEY) }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.accent_default),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            if (isDefault) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AccentOptions.forEach { option ->
                val label = stringResource(option.labelRes)
                val selected = option.key == selectedKey
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(option.color)
                            .then(
                                if (selected) {
                                    Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                } else {
                                    Modifier
                                },
                            )
                            .clickable { onSelect(option.key) }
                            .semantics { contentDescription = label },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

@StringRes
private fun bubbleStyleLabelRes(style: BubbleStyle): Int = when (style) {
    BubbleStyle.DEFAULT -> R.string.bubble_default
    BubbleStyle.ROUNDED -> R.string.bubble_rounded
    BubbleStyle.OUTLINED -> R.string.bubble_outlined
}

@StringRes
private fun chatBackgroundLabelRes(background: ChatBackground): Int = when (background) {
    ChatBackground.DEFAULT -> R.string.background_default
    ChatBackground.TINTED -> R.string.background_tinted
}

/**
 * Per-account appearance override row: "Follow global" (null) plus the enum
 * options, mirroring [AccentSelector]'s check-mark pattern. The value is
 * saved through the enclosing profile editor's Save, never applied locally.
 */
@Composable
private fun ProfileEnumSelectorRow(
    title: String,
    options: List<Pair<String, String>>,
    selectedKey: String?,
    onSelect: (String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .clickable { onSelect(null) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.follow_global),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            if (selectedKey == null) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        options.forEach { (key, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { onSelect(key) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                if (selectedKey == key) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
internal fun RenameAccountDialog(
    account: AccountUi,
    onDismiss: () -> Unit,
    onRename: (displayName: String) -> Boolean,
) {
    var name by rememberSaveable(account.id) { mutableStateOf(account.displayName) }
    var failed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(stringResource(R.string.rename_account)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        failed = false
                    },
                    label = { Text(stringResource(R.string.display_name)) },
                    singleLine = true,
                    isError = failed,
                    supportingText = if (failed) {
                        { Text(stringResource(R.string.not_available_yet)) }
                    } else {
                        null
                    },
                )
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = { if (!onRename(name)) failed = true },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
internal fun DeleteAccountDialog(
    account: AccountUi,
    onDismiss: () -> Unit,
    onConfirm: () -> Boolean,
) {
    var failed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(stringResource(R.string.delete_account)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.delete_account_confirm, account.displayName))
                if (failed) {
                    Text(
                        text = stringResource(R.string.not_available_yet),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = { if (!onConfirm()) failed = true },
            ) {
                Text(
                    text = stringResource(R.string.delete_account),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun DeleteConversationDialog(
    conversation: ConversationUi,
    onDismiss: () -> Unit,
    onConfirm: () -> Boolean,
) {
    var failed by remember { mutableStateOf(false) }
    val actionLabel = if (conversation.isGroup) R.string.leave_group else R.string.delete_conversation
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(stringResource(actionLabel)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        if (conversation.isGroup) {
                            R.string.leave_group_confirm
                        } else {
                            R.string.delete_conversation_confirm
                        },
                    ),
                )
                if (failed) {
                    Text(
                        text = stringResource(R.string.not_available_yet),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = { if (!onConfirm()) failed = true },
            ) {
                Text(
                    text = stringResource(actionLabel),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
