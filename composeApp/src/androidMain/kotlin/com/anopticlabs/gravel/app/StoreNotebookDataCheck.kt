package com.anopticlabs.gravel.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [holdsFiles] is the cheap, key-free test that the Notebook directory has any sealed file at all;
 * [hasContent] reads the stores and is only called when it is true, so a phone that never used the
 * Notebook never touches its keystore. A store that cannot be read counts as data: a backup is the
 * safe answer when the Notebook is damaged.
 */
class StoreNotebookDataCheck(
    private val holdsFiles: () -> Boolean,
    private val hasContent: () -> Boolean,
) : NotebookDataCheck {
    override suspend fun hasData(): Boolean = withContext(Dispatchers.IO) {
        try {
            holdsFiles() && hasContent()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            true
        }
    }
}
