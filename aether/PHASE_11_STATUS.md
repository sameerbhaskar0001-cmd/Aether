# Aether — Phase 11: Files & Personal Knowledge

This project snapshot contains the Phase 11 file/personal-knowledge implementation built on the existing 11A foundation.

## Included
- Local file import through Android document picker
- Persistent local file metadata in Room
- SHA-256 duplicate detection
- Incognito isolation for file persistence/search
- File type detection: text, PDF, DOCX, common images
- Local extraction for text, PDF, and DOCX
- Images are indexed as local file metadata but are not sent to an AI provider for visual understanding
- Deterministic lexical file search
- Bounded file-context builder (top 5 results, bounded total characters)
- Provider-neutral file context in `ProviderRequest`
- Gemini and Groq file-context support
- File-grounded answer instructions with insufficient-context guardrail
- Files & Knowledge screen for importing and removing indexed files
- Focused tests for file context, DOCX detection/extraction, and Incognito duplicate isolation

## Privacy boundary
Binary files are not stored in Room. The database stores metadata, local URI, content hash, and extracted text when applicable. File content is only added to a provider request as bounded local search excerpts.

Image understanding remains intentionally outside this phase's provider pipeline; it belongs to the later Vision capability.

## Verification
The source was statically inspected and the pure Kotlin file utilities were compiled with `kotlinc`. The supplied project snapshot does not contain `gradlew`/`gradle-wrapper.jar`, so a full Android Gradle build could not be executed in this environment.
