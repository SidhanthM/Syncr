package dev.syncr.prototype.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.syncr.prototype.notes.NotesRepository
import dev.syncr.prototype.notes.writeRecovery
import kotlinx.coroutines.launch
import java.io.File

class NotesViewModel(application: Application) : AndroidViewModel(application) {
    val location = File(application.filesDir, "syncr-library").absolutePath
    val repository = NotesRepository(location, thumbnailDir = File(application.cacheDir, "thumbnails"))
    init { run { repository.loadLibrary() } }
    fun run(block: suspend () -> Unit) { viewModelScope.launch { block() } }
    fun export(uri: Uri) = run {
        repository.exportRecovery { data ->
            val output = applicationResolver().openOutputStream(uri, "wt") ?: error("Destination could not be opened")
            output.use { writeRecovery(it, data) }
        }
    }
    private fun applicationResolver() = getApplication<Application>().contentResolver
}
