package org.unividuell.mobility.manager.user

import org.springframework.stereotype.Service

@Service
class AppUserService(
    private val repository: AppUserRepository,
) {

    /**
     * Creates the user on first login, or refreshes the mirrored GitHub fields
     * (login, display name) on subsequent logins. Keyed on the stable GitHub id.
     */
    fun upsert(githubId: Long, login: String, displayName: String): AppUser {
        val existing = repository.findByGithubId(githubId)
        val toSave = existing
            ?.copy(login = login, displayName = displayName)
            ?: AppUser(githubId = githubId, login = login, displayName = displayName)
        return repository.save(toSave)
    }
}
