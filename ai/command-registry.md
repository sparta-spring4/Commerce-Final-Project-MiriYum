# Root command composition registry

Contract status: ACTIVE

This registry composes verified endpoint command IDs. It does not own or repeat
literal backend or frontend commands. Resolve each delegate through the linked
endpoint registry and preserve its endpoint evidence:

- [Backend command registry](../backend/ai/command-registry.md)
- [Frontend command registry](../frontend/ai/command-registry.md)

`CONFIGURED` on a root ID means that every ordered delegate is `CONFIGURED` and
the composition contract is usable. It does not mean that a root runner, CI
workflow, cross-end runtime, or the current invocation has passed.

## Root IDs

| Root ID | State | Ordered delegate IDs | Stop condition | Evidence boundary |
|---|---|---|---|---|
| `root.verify.backend` | `CONFIGURED` | 1. `backend.wrapper.version`<br>2. `backend.test`<br>3. `backend.build` | Stop at the first delegate result other than `PASS`; report the unrun remainder as `NOT RUN` | Retain each backend result with the backend evidence. The root result records order, delegate results, risks, and evidence references only. |
| `root.verify.frontend` | `CONFIGURED` | 1. `frontend.pnpm.version`<br>2. `frontend.install`<br>3. `frontend.typecheck`<br>4. `frontend.test`<br>5. `frontend.build` | Stop at the first delegate result other than `PASS`; report the unrun remainder as `NOT RUN` | Retain each frontend result with the frontend evidence. The root result records order, delegate results, risks, and evidence references only. |
| `root.verify.scaffold` | `CONFIGURED` | 1. `root.verify.backend`<br>2. `root.verify.frontend` | Stop when an endpoint composition is not `PASS`; report the unrun endpoint composition as `NOT RUN` | Combine references to both endpoint evidence sets. This is scaffold verification, not cross-end integration evidence. |

## Activation evidence

- `root.verify.backend` is `CONFIGURED` because all three delegates are
  `CONFIGURED` and Task 2 observed each delegate command exit successfully
  against the completed backend scaffold.
- `root.verify.frontend` is `CONFIGURED` because all five delegates are
  `CONFIGURED` and Task 3 observed each delegate command exit successfully
  against the completed frontend scaffold.
- `root.verify.scaffold` is `CONFIGURED` because both endpoint compositions are
  available in a deterministic order. It does not establish API compatibility,
  authentication behavior, data handoff, or an integrated user flow.

`frontend.dev` is excluded because its state is `NOT RUN`. Lint is excluded
because it is `NOT CONFIGURED`.

## Result and evidence rules

Use the result vocabulary and four done-claim fields in
[verification and completion](verification-and-completion.md). A root result is
`PASS` only when every ordered delegate ran for that invocation and returned
`PASS`. A registry entry or prior activation run is not current execution
evidence.

Keep endpoint output, working directory, exit code, and artifacts with the
endpoint evidence. The root evidence records the root ID, ordered delegates,
their observed results, stop point, remaining risks, and links or paths to those
endpoint records. Do not copy endpoint logs into this registry.

Cross-end API, authentication, error, and data-handoff work follows the
[root integration contract](integration-contracts.md). Combined cross-end
integration execution remains `NOT CONFIGURED`.
