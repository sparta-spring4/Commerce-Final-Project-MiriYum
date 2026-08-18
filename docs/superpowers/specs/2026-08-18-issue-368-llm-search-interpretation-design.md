# Issue #368 LLM Search Interpretation Design

## Status

- Date: 2026-08-18
- Issue: #368
- Base branch: `dev`
- Feature branch: `feature/368-partial-availability-semantic-search`
- Decision: replace the draft embedding/Qdrant path with a bounded `gpt-4o-mini` Structured Outputs interpreter backed by current MySQL data.

## Problem

The rule interpreter correctly extracts known structure such as location, date, time, party size, price, and registered menu/category vocabulary. It cannot reliably connect an unregistered expression such as `얼큰한 국물` to the current catalog's `김치찌개`.

The search contract also needs partial reservation semantics:

- date, time, and party size are independent inputs;
- no date means reservation availability is not evaluated;
- a date with either time or party size omitted is a valid partial condition evaluated through the public #378 service;
- missing time or party size alone does not create `INCOMPLETE_RESERVATION_CONDITION`.

When a user reaches a desired menu but it is sold out or paused, the system must be able to source similar current menu candidates without bypassing category, price, allergen, inventory, store, or reservation rules.

## Evidence and Decision

Live diagnostics showed that both `text-embedding-3-small` and `text-embedding-3-large` did not rank an ordinary `김치찌개` catalog document above unrelated coffee or cake documents for the short Korean expression `얼큰한 국물`. This is a retrieval mismatch, not a threshold-only problem. Shipping a vector database around that signal would add indexing, rebuild, consistency, and deployment work without satisfying the core acceptance example.

`gpt-4o-mini` produced a small structured interpretation for the same expression in a live diagnostic. The approved design therefore uses the LLM only to translate an otherwise unresolved expression into controlled search concepts. It does not let the LLM generate a user-facing answer, decide availability, or act as a source of truth.

MySQL remains authoritative. The current published menu version, public store state, ordering eligibility, and #378 reservation result decide whether a candidate may be returned.

## Goals

1. Preserve exact MySQL search behavior and ordering as the primary path.
2. Interpret unresolved food language with one bounded LLM call only when exact search under-fills the first page.
3. Use the resulting concepts to query current MySQL data and then apply all existing eligibility rules.
4. Reuse the bounded interpretation path to source alternatives for an unavailable menu.
5. Fail open to the existing exact search response on every external-provider failure.
6. Bound cost, latency, data disclosure, and observability cardinality.

## Non-goals

- User-facing generative answers, explanations, summaries, or conversational search
- LLM reranking of exact results
- LLM inference of allergies, dietary safety, reservation availability, inventory, or store state
- Qdrant, another vector database, embeddings, menu indexing, rebuild jobs, or index deployment
- A persistent cache containing normalized search text in the initial release
- Stable pagination through LLM-only supplemental candidates

## End-to-end Flow

### Integrated store search

1. `RuleInterpreter` independently extracts structured tokens from the normalized request.
2. Recognized location, date, time, party, price, category, and menu tokens are removed from the residual text.
3. The existing MySQL exact/contains query executes first with the structured filters and residual text.
4. LLM expansion is eligible only when all of the following are true:
   - the feature is enabled and configured;
   - this is the first page (`cursor` absent);
   - residual text is nonblank;
   - exact MySQL results after current refresh and reservation filtering are fewer than the requested page size.
5. The LLM adapter returns a validated list of zero to eight controlled concepts.
6. A bounded MySQL supplemental query searches those concepts against current public store/menu fields while retaining the original structured filters.
7. Supplemental rows pass the same current-store refresh and #378 reservation evaluation as exact rows.
8. Exact rows remain first. Supplemental rows fill only remaining slots and are deduplicated by store ID.
9. The response cursor is derived only from the exact MySQL path. LLM supplementation is first-response-only and non-pageable.

This policy prevents a nondeterministic external interpretation from changing the established cursor fingerprint or making later pages skip/duplicate exact rows.

### Partial reservation evaluation

- Date absent: do not call Reservation; expose `NOT_REQUESTED`; reject `availableOnly=true` using the existing public validation error.
- Date only: ask #378 whether any allowed time/party combination is available.
- Date and time: ask #378 whether any allowed party size is available at that time.
- Date and party: ask #378 whether any allowed time is available for that party.
- Date, time, and party: ask #378 for the exact condition.
- A malformed #378 batch response fails the affected search batch closed according to its public contract.

Search imports only the #378 public service and DTO contract, never Reservation entities or repositories.

### Unavailable-menu alternatives

1. Load and validate the source menu through the existing public alternative contract.
2. Build a privacy-safe interpretation input from catalog-owned source fields: current menu name, description, and category names.
3. Request zero to eight controlled concepts when the feature is enabled.
4. Query current published MySQL menu versions using the source tuple and expanded concepts.
5. Prefer same-store candidates; use the existing nearby-store path only when needed.
6. Apply existing category, price, allergen exclusion, inventory/schedule, public-store, ordering, and reservation rules.
7. Deduplicate and return the established alternative DTO.

An LLM response can broaden candidate sourcing only. It cannot mark a candidate safe, orderable, available, or equivalent.

## Component Design

### Configuration

Replace embedding and Qdrant settings with one validated properties object, for example `OpenAiSearchInterpretationProperties`:

- `enabled`: default `false`
- `baseUrl`: default official OpenAI API base
- `apiKey`: environment/Secret only
- `model`: default `gpt-4o-mini`
- `timeout`: short bounded duration
- `maxOutputTokens`: approximately 100 and bounded by validation
- `maxConcepts`: default 8 and bounded by validation
- `supplementCandidateLimit`: bounded internal MySQL pool

Startup must not require an API key while the feature is disabled. Enabling the feature without a usable key is a configuration error or produces a disabled adapter according to the repository's existing configuration convention; the implementation plan must choose the convention already used by external integrations on current `dev`.

### Port and result

The domain-facing port has provider-neutral language:

```java
interface SearchConceptInterpreter {
    SearchConceptExpansion expand(SearchConceptRequest request);
}
```

The request contains only the interpretation text and a purpose enum (`STORE_SEARCH` or `MENU_ALTERNATIVE`). The result contains an immutable concept list and token-usage metadata needed for metrics. Provider exceptions do not escape the orchestration boundary.

Concept validation is deterministic:

- trim and normalize whitespace;
- discard blank values;
- cap each value's length;
- compare case-insensitively for uniqueness while preserving provider order;
- cap the final list at the configured maximum;
- reject a malformed top-level document rather than partially trusting it.

### OpenAI adapter

Use the repository's Spring `RestClient` pattern and the OpenAI Chat Completions structured-output contract already exercised in the diagnostic. Send:

- model `gpt-4o-mini` by default;
- a fixed system instruction that asks only for Korean menu, ingredient, taste, and cooking-form search concepts grounded in the supplied phrase;
- the minimal residual phrase or catalog-owned menu text;
- strict `json_schema` response formatting;
- a low maximum output-token limit.

The strict schema is equivalent to:

```json
{
  "concepts": ["김치찌개", "찌개", "매운 국물"]
}
```

Only `concepts` is allowed, each item is a bounded string, and the array maximum is eight. Refusal, empty choices, truncation, invalid JSON/schema, HTTP errors, timeout, or unexpected response shape are fallback outcomes.

### Orchestration and fallback

`SearchConceptExpansionService` owns enablement, input policy, the provider call, validation, metrics, and failure conversion. Its public method returns an empty expansion on runtime provider failure. Callers therefore continue with exact MySQL results without branching on provider exceptions.

The service must not retry synchronously inside a user request. A single slow provider request is preferable to multiplied latency and cost. Provider-level safe connection retry behavior, if already globally configured on current `dev`, must not cause multiple billable completions.

### MySQL supplemental query

The repository receives the validated concept list and original structured conditions. It builds parameterized OR predicates across only approved public catalog text fields, including menu name, menu description, menu categories, and approved store/local tags when present. It must still require:

- current published menu version;
- correct menu/store ownership;
- public and eligible store state;
- all original structured location, price, category, and mode filters.

The repository returns a bounded candidate pool rather than truncating before current-state and reservation revalidation. The service fills remaining page slots only after those filters, preventing one stale or unavailable supplemental row from under-filling a page while other valid candidates exist.

## Cost Controls

- No LLM call for a blank residual, a cursor page, a disabled feature, or a first page already filled by exact results.
- At most one store-search interpretation call per eligible HTTP request.
- At most one alternative interpretation call per eligible alternative request.
- Strict schema, at most eight concepts, and a small output-token ceiling.
- No synchronous application retry.
- Initial release has no persistent/raw-query cache; this avoids retaining search text and keeps correctness simple. A later cache requires a separate privacy, invalidation, and cost decision based on measured traffic.

At current `gpt-4o-mini` pricing, the observed diagnostic-sized request is far below one won per invocation. Production cost is nevertheless governed by measured input/output token counters and eligible-call rate, not by that estimate alone.

## Privacy and Security

- Store search sends only residual food-language text after recognized structural tokens are removed.
- Menu alternatives send only catalog-owned public menu text.
- Never send user ID, search history, contact data, exact coordinates/address, reservation identity, or allergy/dietary input.
- Do not log raw search text, prompts, full provider request/response bodies, or API keys.
- Log only bounded reason codes, latency, model identifier, token counts, and correlation data already permitted by repository policy.
- API keys come only from environment variables or the deployment Secret mechanism.

## Observability

Record low-cardinality metrics for:

- eligible requests and actual calls by purpose;
- success, empty result, refusal, timeout, malformed response, HTTP/provider failure, and disabled fallback;
- provider latency;
- input, output, and total token counts;
- supplemental MySQL candidate count and final fill count.

Metrics must not label raw query text, concept text, store ID, menu ID, user ID, or exception messages.

## Failure Semantics

- Any OpenAI problem returns the exact MySQL response already computed.
- Invalid concepts are treated as an empty expansion.
- Supplemental MySQL failure follows the repository's existing search error policy; it is not hidden as an OpenAI fallback.
- #378 malformed batch behavior remains fail-closed as specified by Reservation.
- Disabling the feature is the immediate rollback path and requires no data migration or index cleanup.

## Test Strategy

Follow the repository policy: run focused unit tests and only affected integration classes locally; use GitHub CI as the full-suite authority.

Required focused coverage:

1. Rule interpreter tests for independent date/time/party parsing and residual text.
2. Expansion orchestration tests proving every call gate and exact-search fallback.
3. OpenAI HTTP contract tests for strict request schema, success, refusal, timeout, malformed JSON/schema, token usage, and secret/raw-text logging boundaries.
4. Service tests proving exact-first merge, deduplication, post-filter fill, and no LLM call on cursor pages.
5. MySQL integration tests proving current published version/store ownership revalidation and original structured filters.
6. Alternative tests proving same-store priority, nearby fallback, unavailable-source handling, and existing category/price/allergen/inventory/reservation enforcement.
7. One explicitly invoked live OpenAI diagnostic using a developer-provided Secret, proving `얼큰한 국물` yields concepts capable of retrieving a real 김치찌개 fixture. CI's default test path must not require an external Secret.
8. Metrics tests for success, fallback reason, latency, and token counters without high-cardinality labels.

## Migration from the Draft Branch

Retain the partial reservation implementation that conforms to #378. Remove all draft embedding/Qdrant production code, tests, configuration, menu indexing events/listeners, rebuild scheduling, and deployment changes. Rewrite ADR-009 and the search specification to record the LLM-interpreter decision and the non-pageable first-response supplementation policy.

Because the feature branch is substantially behind current `origin/dev`, rebase onto the latest `dev` before implementation and resolve the contract against current code rather than mechanically preserving the old draft.

## Acceptance Summary

The design is complete when the implementation demonstrates:

- `서울 내일 김치찌개` retains `김치찌개` as the residual keyword and evaluates the date-only reservation condition;
- `얼큰한 국물` can use one bounded LLM interpretation to retrieve a current 김치찌개-like candidate from MySQL;
- a sold-out or paused desired menu can source valid current alternatives;
- exact results stay first and existing cursor semantics remain stable;
- provider failure produces a normal exact-search response;
- no vector store, embedding index, or menu indexing lifecycle remains;
- actual calls and token costs are observable and externally disableable.
