# Frontend verification gates

Contract status: ACTIVE

## Required order

From `frontend/`, run:

1. `frontend.pnpm.version` — `pnpm --version`
2. `frontend.install` — `pnpm install --frozen-lockfile`
3. `frontend.typecheck` — `pnpm run typecheck`
4. `frontend.test` — `pnpm run test`
5. `frontend.build` — `pnpm run build`

Record the exact command, working directory, exit code, result vocabulary value,
and concise evidence for every gate. A nonzero command is `FAIL`; an unavailable
runtime or required external input is `BLOCKED`. Lint is `NOT CONFIGURED`.

Task 4 may compose only these five `CONFIGURED` command IDs. It must exclude
`frontend.dev`, whose registry state is `NOT CONFIGURED` because startup and
explicit shutdown are unverified, and must not promote lint from
`NOT CONFIGURED`.

## Completion checks

After the commands:

- confirm the lockfile did not change during the frozen install;
- confirm `node_modules/` and `dist/` exist and are ignored by the two exact
  rules in `../.gitignore`;
- compare repository-visible frontend changes with the approved exact
  allowlist;
- confirm the staged index is empty before separate staging approval;
- confirm root and backend paths are unchanged by the frontend task;
- preserve and recheck every pre-existing user-modified path and hash.

Use only the result vocabulary and done-claim fields defined in
`../../ai/verification-and-completion.md`. Do not treat configuration text,
planned commands, or generated artifacts as execution evidence.
