package com.anopticlabs.gravel.notebook.routers

import kotlin.test.Test
import kotlin.test.assertEquals

class ListedRowsTest {

    @Test
    fun aRowNeedsAnIdOfTheGrammarAndALabel() {
        val read = ListedRows.read(
            listOf(
                ListedRow("list-1", "Groceries", "A Vikunja project", "recorded_at"),
                ListedRow("List 2", "Bad id", null, null),
                ListedRow(null, "No id", null, null),
                ListedRow("list-3", " \u0007 ", null, null),
                ListedRow("list-4", "Line\nbreak", " ", "mood"),
            ),
            declared = emptySet(),
        )
        assertEquals(
            listOf(
                ListedEntry("list-1", "Groceries", "A Vikunja project", setOf(Need.RECORDED_AT)),
                ListedEntry("list-4", "Linebreak", null, emptySet()),
            ),
            read,
        )
    }

    /** The receiver's declared id, and an earlier row's, win; a row with a bad label does not take its id from a later one. */
    @Test
    fun anIdIsTakenOnce() {
        val read = ListedRows.read(
            listOf(ListedRow("task", "Shadow", null, null), ListedRow("a", "", null, null), ListedRow("a", "First", null, null), ListedRow("a", "Second", null, null)),
            declared = setOf("task"),
        )
        assertEquals(listOf("First"), read.map { it.label })
    }

    @Test
    fun theListIsBoundedAndSoAreItsTexts() {
        val read = ListedRows.read(
            (0 until RouterApi.MAX_LISTED + 10).map { ListedRow("r$it", "x".repeat(100), "y".repeat(300), null) },
            declared = emptySet(),
        )
        assertEquals(RouterApi.MAX_LISTED, read.size)
        assertEquals(RouterApi.MAX_LABEL_CHARS, read.first().label.length)
        assertEquals(RouterApi.MAX_DESCRIPTION_CHARS, read.first().description!!.length)
    }
}
