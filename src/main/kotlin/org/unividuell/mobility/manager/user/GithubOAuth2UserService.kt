package org.unividuell.mobility.manager.user

import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Service

/**
 * Loads the GitHub profile via the default user service, then upserts our own
 * [AppUser] record. Exposes a derived `displayName` attribute (GitHub's `name`,
 * falling back to the login handle) so templates can render it directly.
 */
@Service
class GithubOAuth2UserService(
    private val users: AppUserService,
) : DefaultOAuth2UserService() {

    override fun loadUser(userRequest: OAuth2UserRequest): OAuth2User {
        val oauthUser = super.loadUser(userRequest)
        val attributes = oauthUser.attributes

        val githubId = (attributes["id"] as Number).toLong()
        val login = attributes["login"] as String
        val displayName = (attributes["name"] as String?)?.takeIf { it.isNotBlank() } ?: login

        users.upsert(githubId, login, displayName)

        return DefaultOAuth2User(
            oauthUser.authorities,
            attributes + ("displayName" to displayName),
            // keep GitHub's id as the principal name attribute
            "id",
        )
    }
}
