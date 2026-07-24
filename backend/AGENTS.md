# Backend AI rules

Before backend work, read [`backend/ai/document-routing.md`](ai/document-routing.md) and select only the documents required for the current backend scope.

- Keep backend ownership limited to Spring Boot, Gradle, persistence and migration, server security, server commands, and backend verification.
- For API contracts or work that crosses the backend/frontend boundary, return to the [root document routing](../ai/document-routing.md) and follow the [root integration contract](../ai/integration-contracts.md).
- Preserve unrelated user changes and keep edits, staging, and commits inside the approved task allowlist.
- Do not add product endpoints, empty domain packages, infrastructure, credentials, or deferred tooling without an approved requirement and activation evidence.
- Report only commands actually run and results actually observed, using the repository result vocabulary.
