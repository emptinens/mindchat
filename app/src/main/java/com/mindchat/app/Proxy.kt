package com.mindchat.app

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.mindchat.core.FfiProxyConfig
import com.mindchat.core.FfiProxyKind

/**
 * Proxy models and the device-local proxy library store (ROADMAP 6.3).
 *
 * The proxy library is **non-secret** configuration (host/port/kind plus the
 * last real probe result) and lives in `SharedPreferences` exactly like the
 * other device-local UI preferences. Proxy passwords never enter these models
 * or [MindChatUiState]; they go through [ProxyCredentialStore] (Android
 * Keystore) keyed by the stable entry id, and are handed to the core only at
 * probe/connect time.
 */

/** How a proxy tunnel is established; the inverse of the FFI proxy kind. */
enum class ProxyKind { SOCKS5, HTTP_CONNECT }

/** Non-secret proxy configuration, safe to display and persist. */
data class ProxyConfig(
    val host: String,
    val port: Int,
    val kind: ProxyKind,
) {
    /** Maps to the FFI config used by the core (never carries a password). */
    fun toFfi(): FfiProxyConfig = FfiProxyConfig(host, port.toUShort(), kind.toFfi())
}

/**
 * Outcome of a real probe, safe to show in the UI. [latencyMs] is always the
 * measured value from [ProxyProbeResult] (never fabricated); a failed probe
 * keeps it null and carries a UI-safe [error].
 */
data class ProxyStatus(
    val latencyMs: Long? = null,
    val error: String? = null,
) {
    /** Latency bucket derived purely from the measured value. */
    val bucket: ProxyLatencyBucket
        get() = proxyLatencyBucket(latencyMs)

    /** True once a probe result (success or failure) has been recorded. */
    val tested: Boolean
        get() = latencyMs != null || error != null
}

/** One saved proxy in the library with its persisted probe status. */
data class ProxyLibraryEntry(
    val id: String,
    val host: String,
    val port: Int,
    val kind: ProxyKind,
    val status: ProxyStatus = ProxyStatus(),
) {
    fun toConfig(): ProxyConfig = ProxyConfig(host, port, kind)
}

/** Result of a proxy probe; [latencyMs] is the real measured latency. */
data class ProxyProbeResult(
    val ok: Boolean,
    val latencyMs: Long?,
    val error: String? = null,
)

/**
 * Persistence boundary for the non-secret proxy library and the per-account
 * assignment map. Implementations are plain stores with no validation or
 * derivation: the gateway owns the rules ([GatewayInput]) and the core owns
 * the connect-time use of the assignment.
 */
interface ProxyLibraryStore {
    /** The saved proxy library in display order (insertion order). */
    fun readEntries(): List<ProxyLibraryEntry>

    /** Replaces the whole library. */
    fun writeEntries(entries: List<ProxyLibraryEntry>)

    /** accountId -> library entry id; empty when no account has a proxy. */
    fun readAssignments(): Map<Long, String>

    /** Replaces the whole assignment map. */
    fun writeAssignments(assignments: Map<Long, String>)
}

/** Stable next library id (`p1`, `p2`, ...), derived from the current list. */
internal fun nextProxyId(library: List<ProxyLibraryEntry>): String {
    val max = library
        .mapNotNull { it.id.removePrefix("p").toLongOrNull() }
        .maxOrNull() ?: 0L
    return "p${max + 1}"
}

/** Finds the library entry matching a config by its non-secret fields. */
internal fun List<ProxyLibraryEntry>.findByConfig(config: ProxyConfig): ProxyLibraryEntry? =
    firstOrNull { it.host == config.host && it.port == config.port && it.kind == config.kind }

internal fun ProxyKind.toFfi(): FfiProxyKind = when (this) {
    ProxyKind.SOCKS5 -> FfiProxyKind.SOCKS5
    ProxyKind.HTTP_CONNECT -> FfiProxyKind.HTTP_CONNECT
}

/** Preview and JVM-test implementation of the library store. */
class InMemoryProxyLibraryStore : ProxyLibraryStore {
    private var entries: List<ProxyLibraryEntry> = emptyList()
    private var assignments: Map<Long, String> = emptyMap()

    override fun readEntries(): List<ProxyLibraryEntry> = entries

    override fun writeEntries(entries: List<ProxyLibraryEntry>) {
        this.entries = entries
    }

    override fun readAssignments(): Map<Long, String> = assignments

    override fun writeAssignments(assignments: Map<Long, String>) {
        this.assignments = assignments
    }
}

/**
 * Android-backed store for the non-secret proxy library. Entries and the
 * assignment map are serialized to two plain strings with ASCII control
 * separators (unit/record separators cannot appear in hosts, ports or enum
 * names), so no escaping or JSON dependency is needed. The encoding helpers
 * are pure and JVM-tested.
 */
class SharedPreferencesProxyLibraryStore(context: Context) : ProxyLibraryStore {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_FILE,
        Context.MODE_PRIVATE,
    )

    override fun readEntries(): List<ProxyLibraryEntry> =
        preferences.getString(KEY_ENTRIES, null)
            ?.split(RECORD_SEP)
            ?.mapNotNull { decodeProxyEntry(it) }
            .orEmpty()

    override fun writeEntries(entries: List<ProxyLibraryEntry>) {
        preferences.edit { putString(KEY_ENTRIES, entries.joinToString(RECORD_SEP) { encodeProxyEntry(it) }) }
    }

    override fun readAssignments(): Map<Long, String> =
        preferences.getString(KEY_ASSIGNMENTS, null)
            ?.split(RECORD_SEP)
            ?.mapNotNull { record ->
                val accountId = record.substringBefore(FIELD_SEP).toLongOrNull() ?: return@mapNotNull null
                val proxyId = record.substringAfter(FIELD_SEP, missingDelimiterValue = "")
                if (proxyId.isEmpty()) null else accountId to proxyId
            }
            ?.toMap()
            .orEmpty()

    override fun writeAssignments(assignments: Map<Long, String>) {
        preferences.edit {
            putString(
                KEY_ASSIGNMENTS,
                assignments.entries.joinToString(RECORD_SEP) { "${it.key}$FIELD_SEP${it.value}" },
            )
        }
    }

    private companion object {
        const val PREFERENCES_FILE = "mindchat_proxy"
        const val KEY_ENTRIES = "proxy_library"
        const val KEY_ASSIGNMENTS = "proxy_assignments"
    }
}

/** ASCII unit separator: field boundary inside one encoded record. */
private const val FIELD_SEP = "\u001F"

/** ASCII record separator: boundary between encoded library records. */
private const val RECORD_SEP = "\u001E"

/** Pure serialization of one library entry (stable across app versions). */
internal fun encodeProxyEntry(entry: ProxyLibraryEntry): String = listOf(
    entry.id,
    entry.host,
    entry.port.toString(),
    entry.kind.name,
    entry.status.latencyMs?.toString().orEmpty(),
    entry.status.error.orEmpty(),
).joinToString(FIELD_SEP)

/** Pure inverse of [encodeProxyEntry]; null for garbage records. */
internal fun decodeProxyEntry(raw: String): ProxyLibraryEntry? {
    val fields = raw.split(FIELD_SEP)
    if (fields.size < 4) return null
    val port = fields[2].toIntOrNull() ?: return null
    val kind = ProxyKind.entries.firstOrNull { it.name == fields[3] } ?: return null
    return ProxyLibraryEntry(
        id = fields[0],
        host = fields[1],
        port = port,
        kind = kind,
        status = ProxyStatus(
            latencyMs = fields.getOrNull(4)?.takeIf { it.isNotEmpty() }?.toLongOrNull(),
            error = fields.getOrNull(5)?.takeIf { it.isNotEmpty() },
        ),
    )
}
