package org.unividuell.mobility.manager.user

import org.unividuell.auth.AuthPrincipal

/** The session principal the auth lib hands out when [this] user signs in. */
fun AppUser.principal() = AuthPrincipal(id = accountId, provider = provider, login = login, roles = emptySet())
