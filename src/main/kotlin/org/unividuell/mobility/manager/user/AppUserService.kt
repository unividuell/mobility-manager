package org.unividuell.mobility.manager.user

import org.springframework.stereotype.Service
import org.unividuell.auth.AccountProvisioner
import org.unividuell.auth.ExternalIdentity
import java.util.UUID

/** The auth lib's account hook: both sign-in doors, GitHub and the local test login, end here. */
@Service
class AppUserService(
    private val repository: AppUserRepository,
) : AccountProvisioner {

    /** No roles are configured, so [roles] is always empty. */
    override fun provision(identity: ExternalIdentity, roles: Set<String>): UUID =
        upsert(provider = identity.provider, subject = identity.subject, login = identity.login, name = identity.name).accountId


    /**
     * Creates the user on first sign-in, or refreshes the mirrored fields (login,
     * display name) on later ones. Keyed on the provider's stable [subject]; the
     * display name falls back to the login when the provider has no name.
     */
    fun upsert(provider: String, subject: String, login: String, name: String?): AppUser {
        val displayName = name?.takeIf { it.isNotBlank() } ?: login
        val existing = repository.findByProviderAndSubject(provider, subject)
        val toSave = existing
            ?.copy(login = login, displayName = displayName)
            ?: AppUser(
                accountId = UUID.randomUUID(),
                provider = provider,
                subject = subject,
                login = login,
                displayName = displayName,
            )
        return repository.save(toSave)
    }
}
