package org.unividuell.mobility.manager.user

import jakarta.servlet.http.Cookie
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.unividuell.auth.AuthPrincipal

/** The session principal the auth lib hands out when [user] signs in. */
fun AppUser.principal() = AuthPrincipal(id = accountId, provider = provider, login = login, roles = emptySet())

/** A browser signed in as [user] the way the auth lib signs one in, holding a CSRF token ([withCsrfToken]). */
fun signedInAs(user: AppUser): RequestPostProcessor {
    val login = oauth2Login().oauth2User(user.principal())
    return RequestPostProcessor { request -> withCsrfToken().postProcessRequest(login.postProcessRequest(request)) }
}

/**
 * The CSRF token the way a browser holds it: the auth lib's `XSRF-TOKEN` cookie, echoed in the
 * header htmx sends. Not spring-security-test's csrf(): that swaps the shared CsrfFilter's cookie
 * repository for a session-backed one for good, and every later request in the same context would
 * create a session (see AnonymousSessionIntegrationTest).
 */
fun withCsrfToken(): RequestPostProcessor = RequestPostProcessor { request ->
    request.setCookies(*request.cookies.orEmpty(), Cookie("XSRF-TOKEN", TEST_CSRF_TOKEN))
    request.addHeader("X-XSRF-TOKEN", TEST_CSRF_TOKEN)
    request
}

/** The token [withCsrfToken] holds; pages rendered for such a request carry it. */
const val TEST_CSRF_TOKEN = "test-csrf-token"
