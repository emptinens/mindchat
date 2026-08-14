package com.mindchat.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Full-screen Material 3 Expressive login shown whenever the account list is
 * empty. Submitting a valid form calls [onConnect] (sign in) or [onRegister]
 * (XEP-0077 in-band registration, no captcha support); [onRegister] returns a
 * UI-safe error detail on failure or null on success. On success the accounts
 * list becomes non-empty and this screen is replaced by the main shell.
 */
@Composable
fun LoginScreen(
    onConnect: (jid: String, server: String, displayName: String, password: String) -> Boolean,
    onRegister: (jid: String, server: String, displayName: String, password: String) -> String?,
) {
    var registerMode by rememberSaveable { mutableStateOf(false) }
    var jid by rememberSaveable { mutableStateOf("") }
    var server by rememberSaveable { mutableStateOf("") }
    var serverTouched by rememberSaveable { mutableStateOf(false) }
    var serverFocused by rememberSaveable { mutableStateOf(false) }
    var displayName by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var connectErrorMessage by remember { mutableStateOf<String?>(null) }
    var connectFailed by remember { mutableStateOf(false) }

    val jidInvalid = !jid.contains('@')
    val serverEmpty = server.isBlank()
    val passwordEmpty = password.isEmpty()
    val formValid = !jidInvalid && !serverEmpty && !passwordEmpty
    // The server field is normally derived from the JID domain, so it is only
    // shown while it has a value, while focused, or once the JID has a domain.
    val serverVisible = server.isNotBlank() || serverFocused || jid.contains('@')

    fun submit() {
        connecting = true
        connectErrorMessage = null
        connectFailed = false
        if (registerMode) {
            val error = onRegister(jid.trim(), server.trim(), displayName.trim(), password)
            connecting = false
            if (error != null) {
                connectErrorMessage = error
            }
        } else {
            val success = onConnect(jid.trim(), server.trim(), displayName.trim(), password)
            connecting = false
            if (!success) {
                connectFailed = true
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_mindchat),
                        contentDescription = stringResource(R.string.app_name),
                        modifier = Modifier.size(40.dp),
                        tint = Color.Unspecified,
                    )
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.login_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !registerMode,
                        onClick = {
                            registerMode = false
                            connectErrorMessage = null
                        connectFailed = false
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) {
                        Text(stringResource(R.string.login_sign_in))
                    }
                    SegmentedButton(
                        selected = registerMode,
                        onClick = {
                            registerMode = true
                            connectErrorMessage = null
                        connectFailed = false
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) {
                        Text(stringResource(R.string.login_register))
                    }
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = jid,
                    onValueChange = { newJid ->
                        jid = newJid
                        connectErrorMessage = null
                        connectFailed = false
                        val at = newJid.indexOf('@')
                        if (at >= 0 && !serverTouched) {
                            server = newJid.substring(at + 1)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.account_jid)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Person,
                            contentDescription = null,
                        )
                    },
                    singleLine = true,
                    isError = jidInvalid && jid.isNotEmpty(),
                    supportingText = if (jidInvalid && jid.isNotEmpty()) {
                        { Text(stringResource(R.string.login_jid_invalid)) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                )
                if (serverVisible) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = server,
                        onValueChange = { newServer ->
                            server = newServer
                            serverTouched = true
                            connectErrorMessage = null
                        connectFailed = false
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { state -> serverFocused = state.isFocused },
                        label = { Text(stringResource(R.string.server)) },
                        singleLine = true,
                        isError = serverEmpty,
                        supportingText = if (serverEmpty) {
                            { Text(stringResource(R.string.login_server_required)) }
                        } else {
                            null
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = displayName,
                    onValueChange = {
                        displayName = it
                        connectErrorMessage = null
                        connectFailed = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.display_name_optional)) },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        connectErrorMessage = null
                        connectFailed = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.password)) },
                    singleLine = true,
                    isError = passwordEmpty || connectErrorMessage != null,
                    supportingText = if (passwordEmpty) {
                        { Text(stringResource(R.string.login_password_required)) }
                    } else {
                        null
                    },
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
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (connecting) {
                    Spacer(Modifier.height(16.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = stringResource(R.string.login_connecting),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (connectErrorMessage != null || connectFailed) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = connectErrorMessage ?: stringResource(R.string.account_connection_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { submit() }) {
                        Text(stringResource(R.string.retry))
                    }
                }
                Spacer(Modifier.height(24.dp))
                FilledTonalButton(
                    onClick = { submit() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    enabled = formValid && !connecting,
                ) {
                    Text(
                        text = stringResource(
                            if (registerMode) R.string.login_register_action else R.string.login_connect,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}
