# Frontend command registry

Contract status: ACTIVE

Run every command below from `frontend/`.

| Command ID | State | Purpose | Exact command | Required inputs | Expected output | Evidence | Nonzero or missing meaning |
|---|---|---|---|---|---|---|---|
| `frontend.pnpm.version` | `CONFIGURED` | Package-manager version | `pnpm --version` | Frozen Node and Corepack environment | `11.17.0` | Captured command output and exit code | `FAIL`; a missing executable is `BLOCKED` |
| `frontend.install` | `CONFIGURED` | Lockfile-faithful install | `pnpm install --frozen-lockfile` | `package.json`, `pnpm-lock.yaml`, registry access or a complete local store | Dependencies installed without lockfile mutation | Captured install output, exit code, and unchanged lockfile diff | `FAIL`; unavailable registry/store is `BLOCKED` |
| `frontend.dev` | `NOT CONFIGURED` | Development server | `pnpm run dev` | Installed dependencies and an available local port | Vite development server starts | Observed startup output and explicit shutdown | If configured and invoked, nonzero is `FAIL`; an unattempted invocation is `NOT RUN` |
| `frontend.typecheck` | `CONFIGURED` | Type check | `pnpm run typecheck` | Installed dependencies and TypeScript sources/configuration | TypeScript exits without diagnostics | Captured output and exit code | `FAIL` |
| `frontend.test` | `CONFIGURED` | Component tests | `pnpm run test` | Installed dependencies and jsdom-compatible tests | Vitest reports all tests passing | Captured test summary and exit code | `FAIL` |
| `frontend.build` | `CONFIGURED` | Production build | `pnpm run build` | Installed dependencies and type-correct sources | Vite writes `dist/` successfully | Captured build summary, exit code, and ignored `dist/` proof | `FAIL` |

Lint status: `NOT CONFIGURED`. There is no lint script or lint configuration in
this minimal scaffold, so lint must not be reported as `PASS`.
