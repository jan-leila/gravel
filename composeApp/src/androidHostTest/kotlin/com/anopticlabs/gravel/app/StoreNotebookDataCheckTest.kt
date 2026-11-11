package com.anopticlabs.gravel.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StoreNotebookDataCheckTest {
    @Test
    fun contentIsNotReadWhenTheNotebookHoldsNoFiles() = runBlocking {
        val check = StoreNotebookDataCheck(holdsFiles = { false }, hasContent = { error("read") })
        assertFalse(check.hasData())
    }

    @Test
    fun filesCountOnlyWhenTheStoresHoldContent() = runBlocking {
        assertTrue(StoreNotebookDataCheck({ true }, { true }).hasData())
        assertFalse(StoreNotebookDataCheck({ true }, { false }).hasData())
    }

    @Test
    fun anUnreadableStoreCountsAsData() = runBlocking {
        assertTrue(StoreNotebookDataCheck({ true }, { throw IllegalStateException("damaged") }).hasData())
    }

    @Test
    fun cancellationIsNotSwallowed() {
        assertFailsWith<CancellationException> {
            runBlocking { StoreNotebookDataCheck({ true }, { throw CancellationException("stop") }).hasData() }
        }
    }
}
