package org.unividuell.mobility.manager.user

import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Component

/**
 * Resolves the persisted [AppUser] behind the authenticated GitHub principal.
 * The user row always exists by this point — it is upserted on login by
 * [GithubOAuth2UserService].
 */
@Component
class CurrentUser(
    private val repository: AppUserRepository,
) {

    fun require(principal: OAuth2User): AppUser {
        val githubId = (principal.getAttribute<Number>("id")
            ?: error("authenticated principal has no GitHub id attribute")).toLong()
        return repository.findByGithubId(githubId)
            ?: error("no persisted user for GitHub id $githubId")
    }
}
