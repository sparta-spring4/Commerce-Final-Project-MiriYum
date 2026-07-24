# Frontend AI rules

Before frontend work, read `ai/document-routing.md` in this directory first.

If the change crosses the backend/frontend boundary or changes a shared API
contract, return to the root `AGENTS.md` and `ai/document-routing.md` before
editing.

- Keep frontend changes within React, TypeScript, Vite, accessibility,
  client-boundary behavior, and their local verification.
- Preserve unrelated changes and use the approved task allowlist.
- Report only commands and outcomes actually observed.
- Keep repository-wide product, API, architecture, and quality rules in their
  canonical documents under `../docs/`.
