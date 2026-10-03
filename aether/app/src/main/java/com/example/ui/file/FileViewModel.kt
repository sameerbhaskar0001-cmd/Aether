package com.example.ui.file

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.AetherApplication
import com.example.ai.file.FileImportServiceImpl
import com.example.ai.file.model.FileImportRequest
import com.example.ai.file.model.FileImportResult
import com.example.data.model.file.FileDocument
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class FileViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as AetherApplication
    private val importService = FileImportServiceImpl(app.fileRepository)

    val files: StateFlow<List<FileDocument>> = app.fileRepository.getAllFiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun clearMessage() {
        _message.value = null
    }

    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            var imported = 0
            var duplicates = 0
            var unsupported = 0
            var failed = 0

            uris.forEach { uri ->
                when (val result = importUri(uri)) {
                    is FileImportResult.Success -> if (result.isDuplicate) duplicates++ else imported++
                    is FileImportResult.Unsupported -> unsupported++
                    is FileImportResult.InvalidMetadata, is FileImportResult.Error -> failed++
                }
            }

            _message.value = buildString {
                append("Imported $imported")
                if (duplicates > 0) append(" • $duplicates duplicate")
                if (unsupported > 0) append(" • $unsupported unsupported")
                if (failed > 0) append(" • $failed failed")
            }
        }
    }

    private suspend fun importUri(uri: Uri): FileImportResult {
        val resolver = getApplication<Application>().contentResolver
        val fileName = queryDisplayName(uri) ?: "file-${UUID.randomUUID()}"
        val mimeType = resolver.getType(uri)
        val fileSize = querySize(uri)

        if (fileSize <= 0L) {
            return FileImportResult.InvalidMetadata(fileName, "Unable to determine file size")
        }

        return importService.importFile(
            FileImportRequest(
                fileName = fileName,
                mimeType = mimeType,
                fileSize = fileSize,
                localUri = uri.toString(),
                contentStreamProvider = { resolver.openInputStream(uri) ?: error("Unable to open selected file") },
                isIncognito = false
            )
        )
    }

    private fun queryDisplayName(uri: Uri): String? =
        getApplication<Application>().contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun querySize(uri: Uri): Long {
        getApplication<Application>().contentResolver.query(
            uri,
            arrayOf(OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0)
        }
        return getApplication<Application>().contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
    }

    fun deleteFile(id: String) {
        viewModelScope.launch { app.fileRepository.deleteFile(id) }
    }
}
