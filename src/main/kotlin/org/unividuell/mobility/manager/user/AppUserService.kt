package org.unividuell.mobility.manager.user

import org.springframework.stereotype.Service
import java.util.UUID

@Service
class AppUserService(
    private val repository: AppUserRepository,
) {

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
