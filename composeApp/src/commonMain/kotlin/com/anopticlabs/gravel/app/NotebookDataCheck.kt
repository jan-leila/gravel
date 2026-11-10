package com.anopticlabs.gravel.app

/** Whether the Notebook holds anything a backup would carry: notes, folders, queued captures, reminders or reminder history. */
fun interface NotebookDataCheck {
    suspend fun hasData(): Boolean
}
