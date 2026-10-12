package ru.tgwatch

/**
 * Reduces default-network callbacks to changes that can alter a check result.
 * Repeated capabilities of the same network (signal, bandwidth) are not a change.
 */
class NetworkTransitions {
    private data class Seen(val network: Long, val validated: Boolean, val captivePortal: Boolean)
    private var current: Seen? = null

    /** A new default network starts unvalidated; the same network again is not a new identity. */
    @Synchronized fun available(network: Long): Boolean {
        if (current?.network == network) return false
        current = Seen(network, validated = false, captivePortal = false)
        return true
    }

    /** True for another network or a validated/captive-portal transition of the current one. */
    @Synchronized fun capabilities(network: Long, validated: Boolean, captivePortal: Boolean): Boolean {
        val previous = current
        current = Seen(network, validated, captivePortal)
        return previous != current
    }

    /** Losing the default network always matters to the caller; compare the next one from scratch. */
    @Synchronized fun lost() { current = null }
}
