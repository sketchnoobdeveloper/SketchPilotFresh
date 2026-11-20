package snd.sketchpilot.core.security

enum class Capability {
    READ,
    WRITE,
    BUILD,
    NETWORK,
    MCP_CONTROL,
}

/** Explicit grants. Absence of a grant always means denial. */
class PermissionPolicy(private val grants: Set<Capability> = emptySet()) {
    fun has(capability: Capability): Boolean = capability in grants

    fun require(capability: Capability) {
        if (!has(capability)) throw SecurityException("missing capability: $capability")
    }
}

data class AuditEntry(
    val timestampMillis: Long,
    val actor: String,
    val action: String,
    val allowed: Boolean,
)

class AuditLog(private val maxEntries: Int = 500) {
    private val entries = ArrayDeque<AuditEntry>()

    @Synchronized
    fun record(actor: String, action: String, allowed: Boolean) {
        entries.addLast(AuditEntry(System.currentTimeMillis(), actor, action, allowed))
        while (entries.size > maxEntries) entries.removeFirst()
    }

    @Synchronized
    fun recent(): List<AuditEntry> = entries.toList()
}
