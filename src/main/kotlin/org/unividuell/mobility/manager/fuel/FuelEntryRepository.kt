package org.unividuell.mobility.manager.fuel

import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.PagingAndSortingRepository

interface FuelEntryRepository :
    CrudRepository<FuelEntry, Long>,
    PagingAndSortingRepository<FuelEntry, Long>
