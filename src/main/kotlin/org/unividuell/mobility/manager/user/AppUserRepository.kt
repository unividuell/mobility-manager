package org.unividuell.mobility.manager.user

import org.springframework.data.repository.CrudRepository
import java.util.UUID

interface AppUserRepository : CrudRepository<AppUser, Long> {
    fun findByProviderAndSubject(provider: String, subject: String): AppUser?
    fun findByAccountId(accountId: UUID): AppUser?
}
