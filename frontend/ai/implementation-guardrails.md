# Frontend implementation guardrails

Contract status: ACTIVE

## Technology boundary

- Use the versions frozen in `package.json` and the committed
  `pnpm-lock.yaml`.
- Keep TypeScript strict and preserve the Vite/React entry boundary.
- Organize future product code by approved feature scope; do not pre-create
  feature folders, routing, state tools, UI libraries, or API clients.
- Add a dependency only when an approved requirement needs it and its
  verification is defined.

## UI and accessibility

Use semantic HTML, visible and predictable keyboard focus, associated labels,
and assistive-technology-readable status and error messages. Do not communicate
meaning through color alone. Apply the canonical rules in
`../../docs/08-ui-and-frontend-guidelines.md` when user-facing behavior exists.

## Client boundary

Do not guess server endpoints, payloads, authentication, error codes, time or
money representations. Link changes to `../../docs/07-data-and-api-contracts.md`
and the owning feature specification. A cross-end change must follow the root
integration route before implementation.

## Change isolation

Preserve unrelated user changes. Edit, stage, and commit only an approved
allowlist after the applicable commands in `verification-gates.md` pass. Never
store credentials, generated dependencies, or production build output in Git.
