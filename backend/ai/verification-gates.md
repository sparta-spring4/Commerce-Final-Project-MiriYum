# Backend verification gates

Contract status: ACTIVE

Use the result vocabulary and done-claim fields from the root [verification and completion contract](../../ai/verification-and-completion.md).

## Gate order

1. Confirm the task allowlist, pre-existing user-path hashes, and empty staged index.
2. Run `backend.wrapper.version`.
3. Run the narrowest relevant automated test; for the scaffold this is `backend.test`.
4. Run `backend.build`.
5. Inspect the exact diff, `git diff --check`, generated path scope, and evidence.

A gate is `PASS` only when its exact command or review ran and produced the expected result. Stop on `FAIL` or `BLOCKED`; correct only within the authorized scope and rerun the original gate.

## Evidence boundary

Backend verification evidence records the exact command, working directory, exit code, observed output, affected tests, remaining risk, and evidence location. A command registry entry is not execution evidence by itself.

The initial context test proves only Spring application wiring with persistence and Flyway auto-configuration excluded. It does not prove MySQL compatibility, migrations, transaction isolation, database constraints, API behavior, authentication, authorization, or cross-end integration.

DB-dependent integration, API smoke, CI, Docker, deployment, and E2E are `NOT CONFIGURED` until their execution surfaces and activation evidence exist.
