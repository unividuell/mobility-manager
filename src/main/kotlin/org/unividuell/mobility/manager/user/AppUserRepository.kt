package org.unividuell.mobility.manager.user

import org.springframework.data.repository.CrudRepository

interface AppUserRepository : CrudRepository<AppUser, Long> {
    fun findByGithubId(githubId: Long): AppUser?
}
