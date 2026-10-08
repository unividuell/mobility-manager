package org.unividuell.mobility.manager.user

import org.springframework.stereotype.Component
import org.unividuell.auth.AuthPrincipal

/**
 * Resolves the persisted [AppUser] behind the signed-in principal. The user row
 * always exists by this point — the auth lib provisions it through
 * [AppUserService] at sign-in.
 */
@Component
class CurrentUser(
    private val repository: AppUserRepository,
) {

    fun require(principal: AuthPrincipal): AppUser =
        repository.findByAccountId(principal.id)
            ?: error("no persisted user for account ${principal.id}")
}
