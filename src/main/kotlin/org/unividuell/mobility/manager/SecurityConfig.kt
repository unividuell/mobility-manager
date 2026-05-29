package org.unividuell.mobility.manager

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.web.SecurityFilterChain
import org.unividuell.mobility.manager.user.GithubOAuth2UserService

@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val githubOAuth2UserService: GithubOAuth2UserService,
) {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            authorizeHttpRequests {
                authorize("/login", permitAll)
                authorize("/error", permitAll)
                authorize(anyRequest, authenticated)
            }
            oauth2Login {
                loginPage = "/login"
                userInfoEndpoint {
                    userService = githubOAuth2UserService
                }
            }
            logout {
                logoutSuccessUrl = "/login"
            }
            // htmx POSTs don't carry a CSRF token; kept disabled deliberately.
            csrf { disable() }
            formLogin { disable() }
            httpBasic { disable() }
        }
        return http.build()
    }
}
