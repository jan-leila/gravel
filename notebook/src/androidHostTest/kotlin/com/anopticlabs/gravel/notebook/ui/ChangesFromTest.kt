package com.anopticlabs.gravel.notebook.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class ChangesFromTest {

    @Test
    fun aChangeMadeBeforeCollectingStillComesThrough() = runBlocking<Unit> {
        val state = MutableStateFlow(0)
        val changes = state.changesFrom(state.value)
        state.value = 1
        assertEquals(1, changes.first())
    }

    @Test
    fun theStartingValueAndRepeatsOfItAreSkipped() = runBlocking<Unit> {
        assertEquals(listOf(1, 0, 2), flowOf(0, 0, 1, 1, 0, 2).changesFrom(0).toList())
    }
}
