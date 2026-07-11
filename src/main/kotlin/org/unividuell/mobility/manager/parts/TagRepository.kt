package org.unividuell.mobility.manager.parts

import org.springframework.data.repository.CrudRepository

interface TagRepository : CrudRepository<Tag, Long> {

    /** All tags of a user, alphabetically — backs the suggestion chips. */
    fun findAllByUserIdOrderByName(userId: Long): List<Tag>

    fun findByUserIdAndName(userId: Long, name: String): Tag?
}
