package com.mindchat.app

import java.util.Locale

/**
 * Shared decision logic behind the [MindChatGateway] contract.
 *
 * Both implementations of the public interface (`NativeMindChatGateway` and
 * `PreviewMindChatGateway`) run exactly the same normalization and validation
 * rules here, so the preview cannot drift from the native behavior: the same
 * validation that guards the FFI registration call also guards the
 * JVM-runnable preview used in debug builds and tests. State-transition and
 * fallback rules (account selection after deletion, stall thresholds) live in
 * [GatewayPolicy] and snapshot-to-UI mapping in [GatewayMapping].
 *
 * The settings section below is the single source of decision logic for the
 * keyed settings store: scope resolution, value sanitizing, snapshot diffing,
 * catalog row derivation and search. Every settings mutation in either gateway
 * flows through these pure functions.
 */

/** UI-safe refusal detail for an invalid XEP-0077 registration request. */
internal sealed interface RegistrationRefusal {
    data object PasswordRequired : RegistrationRefusal

    data object InvalidJid : RegistrationRefusal

    /** The exact UI-safe detail string both gateway implementations return. */
    fun toUiDetail(): String = when (this) {
        PasswordRequired -> "password required"
        InvalidJid -> "invalid JID"
    }
}

/** Outcome of validating raw registration fields. */
internal sealed interface RegistrationValidation {
    data class Valid(val input: RegistrationInput) : RegistrationValidation

    data class Refused(val refusal: RegistrationRefusal) : RegistrationValidation
}

/** Normalized registration inputs, shared by both gateway implementations. */
internal data class RegistrationInput(
    val username: String,
    val server: String,
    val displayName: String,
    val password: String,
    val fullJid: String,
)

/**
 * Validates and normalizes raw registration fields. A [RegistrationValidation.Refused]
 * carries the exact UI-safe detail the gateway must surface; a
 * [RegistrationValidation.Valid] carries inputs that are trimmed and have the
 * display-name fallback applied (username when blank).
 */
internal fun validateRegistration(
    jid: String,
    server: String,
    displayName: String,
    password: String,
): RegistrationValidation {
    if (password.isEmpty()) return RegistrationValidation.Refused(RegistrationRefusal.PasswordRequired)
    val normalizedJid = jid.trim()
    val username = normalizedJid.substringBefore('@')
    if (username.isBlank()) return RegistrationValidation.Refused(RegistrationRefusal.InvalidJid)
    return RegistrationValidation.Valid(
        RegistrationInput(
            username = username,
            server = server.trim(),
            displayName = displayName.trim().ifBlank { username },
            password = password,
            fullJid = normalizedJid,
        ),
    )
}

// --- Settings decision logic ------------------------------------------------

/**
 * Resolves the on-disk key for [key]: per-account keys are namespaced under
 * `account_<id>_<storageKey>` (mirroring today's `profile_<id>_<field>`);
 * global keys keep their flat, immutable storage key.
 */
internal fun settingKeyFor(accountId: Long, key: SettingKey<*>): String =
    MindChatPreferences.accountKey(accountId, key)

/**
 * Clamps/coerces a raw value to the key's declared type before persisting.
 * Garbage or a wrong type falls back to the key's default, so a store write
 * can never carry a value the schema does not understand.
 */
@Suppress("UNCHECKED_CAST")
internal fun <T> sanitizeSetting(key: SettingKey<T>, raw: T): T = when (key) {
    is BooleanKey -> (raw as? Boolean ?: key.default) as T
    is StringKey -> (raw as? String ?: key.default) as T
    is EnumKey<*> -> (if (key.enumClass.isInstance(raw)) raw else key.default) as T
}

/** True when the two snapshots differ; the fast-path diff for the settings store. */
internal fun settingsChanged(a: SettingsSnapshot, b: SettingsSnapshot): Boolean = a != b

/**
 * Pure UI-row descriptions derived from the schema. Screens only render these;
 * they carry resource ids plus the render state (checked/enabled), never
 * callbacks, so the derivation stays deterministic and JVM-testable.
 */
internal sealed interface SettingRowSpec {
    val labelRes: Int

    val supportingRes: Int?

    val enabled: Boolean
}

/** A toggle backed by a schema [BooleanKey] (all 0.1.7 keys are boolean). */
internal data class SettingToggleRowSpec(
    val key: BooleanKey,
    val checked: Boolean,
    override val labelRes: Int,
    override val supportingRes: Int? = null,
    override val enabled: Boolean = true,
    val notImplemented: Boolean = false,
) : SettingRowSpec

/** A row that navigates to another surface or triggers a screen-owned flow. */
internal data class SettingActionRowSpec(
    val action: SettingRowAction,
    override val labelRes: Int,
    override val supportingRes: Int? = null,
    override val enabled: Boolean = true,
) : SettingRowSpec

/** A read-only row. */
internal data class SettingInfoRowSpec(
    override val labelRes: Int,
    override val supportingRes: Int? = null,
) : SettingRowSpec {
    override val enabled: Boolean = true
}

/** Screen-owned flows an action row can trigger. */
internal enum class SettingRowAction {
    /** Opens the active account's profile editor (Appearance accent row). */
    OPEN_ACCENT_PROFILE,
}

/**
 * Derives the rows for one settings category from the catalog. Rows for
 * schema keys render first, then category-specific action rows (the accent
 * link on Appearance). [appLockAvailable] is a device capability the app lock
 * row needs: the toggle stays enabled while app lock is on even when
 * biometrics are unavailable so the user can turn it off. [keys] defaults to
 * [SettingsSchema.all] and is injectable so tests can exercise the 0.1.8
 * recipe (one new key, zero rework) against a test-only catalog.
 */
internal fun catalogRows(
    category: SettingCategory,
    snapshot: SettingsSnapshot,
    activeAccountId: Long,
    appLockAvailable: Boolean = true,
    keys: List<SettingKey<*>> = SettingsSchema.all,
): List<SettingRowSpec> {
    val rows = keys
        .filter { it.category == category && it.scope == SettingScope.GLOBAL }
        .map { key -> key.toRow(snapshot, appLockAvailable) }
    return when (category) {
        SettingCategory.APPEARANCE -> rows + SettingActionRowSpec(
            action = SettingRowAction.OPEN_ACCENT_PROFILE,
            labelRes = R.string.accent_color,
            supportingRes = R.string.accent_per_account,
            enabled = activeAccountId != 0L,
        )

        else -> rows
    }
}

private fun SettingKey<*>.toRow(snapshot: SettingsSnapshot, appLockAvailable: Boolean): SettingRowSpec = when (this) {
    is BooleanKey -> {
        val checked = snapshot.get(this)
        SettingToggleRowSpec(
            key = this,
            checked = checked,
            labelRes = labelRes,
            supportingRes = supportingResFor(this, appLockAvailable),
            enabled = if (this == SettingsSchema.appLockEnabled) {
                appLockAvailable || checked
            } else {
                availability == SettingAvailability.IMPLEMENTED
            },
            notImplemented = availability == SettingAvailability.PENDING_CORE,
        )
    }

    // Non-boolean keys arrive with the 0.1.8 core-backed controls; until then
    // every schema key is a boolean and this branch is unreachable.
    is StringKey, is EnumKey<*> -> SettingInfoRowSpec(
        labelRes = labelRes,
        supportingRes = if (availability == SettingAvailability.PENDING_CORE) {
            R.string.not_implemented_yet
        } else {
            null
        },
    )
}

private fun supportingResFor(key: BooleanKey, appLockAvailable: Boolean): Int? = when (key) {
    SettingsSchema.messageSearch -> R.string.message_search_summary
    SettingsSchema.encryption -> R.string.encryption_summary
    SettingsSchema.appLockEnabled ->
        if (appLockAvailable) R.string.app_lock_summary else R.string.app_lock_unavailable

    else -> null
}

/**
 * Pure settings search (0.1.7 ships the logic; the search UI lands in 0.1.8).
 * Matches the resolved label and keyword resource text, case-insensitively,
 * by substring. [resolveText] is injected so the function stays Android-free
 * and deterministic on the JVM; the future UI resolves resource ids via the
 * app's string table. An empty query matches nothing.
 */
internal fun searchSettings(
    query: String,
    keys: List<SettingKey<*>>,
    resolveText: (Int) -> String,
): List<SettingKey<*>> {
    val needle = query.trim().lowercase(Locale.ROOT)
    if (needle.isEmpty()) return emptyList()
    return keys.filter { key ->
        resolveText(key.labelRes).lowercase(Locale.ROOT).contains(needle) ||
            key.keywords.any { resolveText(it).lowercase(Locale.ROOT).contains(needle) }
    }
}
