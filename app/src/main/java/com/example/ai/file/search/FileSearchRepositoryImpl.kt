package com.example.ai.file.search

import com.example.data.model.file.ExtractionStatus
import com.example.data.repository.FileRepository
import kotlinx.coroutines.flow.first

class FileSearchRepositoryImpl(
    private val fileRepository: FileRepository
) : FileSearchRepository {

    override suspend fun searchFiles(query: FileSearchQuery): List<FileSearchResult> {
        if (query.queryText.isBlank()) {
            return emptyList()
        }

        // Privacy & Incognito isolation:
        // Incognito must not persist or search persistent files.
        val sourceDocuments = if (query.isIncognito) {
            query.incognitoDocuments
        } else {
            fileRepository.getAllFiles().first()
        }

        val eligibleDocuments = sourceDocuments.filter { doc ->
            doc.extractionStatus == ExtractionStatus.COMPLETED &&
            !doc.extractedText.isNullOrBlank()
        }

        val results = mutableListOf<FileSearchResult>()
        for (doc in eligibleDocuments) {
            val extractedText = doc.extractedText ?: continue
            val score = FileSearchTokenizer.computeScore(query.queryText, doc.fileName, extractedText)
            if (score > 0f) {
                val snippet = FileSearchTokenizer.generateSnippet(query.queryText, extractedText, query.maxSnippetLength)
                results.add(
                    FileSearchResult(
                        fileId = doc.id,
                        fileName = doc.fileName,
                        score = score,
                        snippet = snippet,
                        mimeType = doc.mimeType
                    )
                )
            }
        }

        // Deterministic ordering: score DESC, fileName ASC, fileId ASC
        return results.sortedWith(
            compareByDescending<FileSearchResult> { it.score }
                .thenBy { it.fileName }
                .thenBy { it.fileId }
        ).take(query.maxResults)
    }
}
