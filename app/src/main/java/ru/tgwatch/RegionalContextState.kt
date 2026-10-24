package ru.tgwatch

import java.util.UUID

/** Raw identity is volatile only. A new connection cannot inherit the previous connection's country. */
class RegionalContextState {
    private var network: Long? = null
    private var configuration: Long? = null
    private var country: String? = null
    private var currentToken = newToken()

    @Synchronized fun token(handle: Long, revision: Long, iso: String?): String {
        require(iso == null || EgressCountry.isValid(iso))
        val replaced = network != handle || configuration != revision
        val changedCountry = !replaced && country != null && iso != null && country != iso
        if (replaced || changedCountry) currentToken = newToken()
        if (replaced) country = null
        network = handle
        configuration = revision
        if (iso != null) country = iso
        return currentToken
    }

    @Synchronized fun invalidate() {
        network = null
        configuration = null
        country = null
        currentToken = newToken()
    }

    private fun newToken(): String = UUID.randomUUID().toString().replace("-", "")
}
