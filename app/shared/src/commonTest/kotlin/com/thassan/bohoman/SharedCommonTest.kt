package com.thassan.bohoman

import com.thassan.bohoman.core.repository.loadAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedCommonTest {
    @Test
    fun fullListLoadingContinuesUntilTheLastPage() = runTest {
        val source = (0 until 205).toList()
        val requestedOffsets = mutableListOf<Int>()

        val loaded = loadAll<Int> { limit, offset ->
            requestedOffsets += offset
            source.drop(offset).take(limit)
        }

        assertEquals(source, loaded)
        assertEquals(listOf(0, 100, 200), requestedOffsets)
    }
}
