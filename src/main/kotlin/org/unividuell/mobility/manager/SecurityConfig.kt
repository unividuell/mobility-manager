package org.unividuell.mobility.manager

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.web.SecurityFilterChain

/**
 * Sign-in, CSRF, logout and the redirects to /login come from the auth lib
 * (org.unividuell:auth-spring-boot-starter, `frontend: server-rendered` in application.yaml),
 * whose rules run before these.
 */
@Configuration
class SecurityConfig {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            authorizeHttpRequests {
                // health probe for the container/orchestrator; shows only
                // {"status":"UP"} (details default to "never"), no secrets.
                authorize("/actuator/health", permitAll)
                authorize(anyRequest, authenticated)
            }
        }
        return http.build()
    }
}
