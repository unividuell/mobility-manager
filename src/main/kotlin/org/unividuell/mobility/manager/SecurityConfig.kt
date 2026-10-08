package org.unividuell.mobility.manager

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint
import org.springframework.security.web.authentication.logout.SimpleUrlLogoutSuccessHandler
import org.springframework.security.web.savedrequest.CookieRequestCache

/**
 * Sign-in, CSRF and logout come from the auth lib (org.unividuell:auth-spring-boot-starter),
 * whose rules run before these. The lib is built for a single-page app; this chain turns the
 * three places where a server-rendered app differs back to page navigation.
 */
@Configuration
class SecurityConfig {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            authorizeHttpRequests {
                authorize("/login", permitAll)
                // health probe for the container/orchestrator; shows only
                // {"status":"UP"} (details default to "never"), no secrets.
                authorize("/actuator/health", permitAll)
                authorize(anyRequest, authenticated)
            }
            exceptionHandling {
                // the lib answers 401 for an SPA to handle; a browser needs the login page
                authenticationEntryPoint = LoginUrlAuthenticationEntryPoint("/login")
            }
            logout {
                // a handler, not logoutSuccessUrl: the URL would lose against the lib's 204 handler
                logoutSuccessHandler = SimpleUrlLogoutSuccessHandler().apply { setDefaultTargetUrl("/login") }
            }
            requestCache {
                // keep the deep-link-after-login redirect in a cookie instead of the
                // session — anonymous hits on protected routes (bots, crawlers) must
                // not persist a session to SQLite just for the 302 to /login.
                requestCache = CookieRequestCache()
            }
        }
        return http.build()
    }
}
