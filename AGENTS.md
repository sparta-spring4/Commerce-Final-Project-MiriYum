# Repository AI rules

Before repository work, read `ai/document-routing.md` and select only the route and documents required for the current scope.

- Preserve the worktree and unrelated user changes; do not overwrite, delete, stage, or commit outside the task allowlist.
- Do not guess missing requirements, repository state, commands, credentials, or external-system state. Stop or report the uncertainty when it affects the result.
- Do not expose sensitive information or cause external side effects without explicit scope and authority.
- Report only commands actually run and results actually observed. A document, template, or planned command is not execution evidence.
- Stage and commit only the frozen task allowlist after the required verification and scope checks pass.
- Keep product, policy, architecture, and quality facts in their canonical documents under `docs/`; link to those owners instead of duplicating them here.
