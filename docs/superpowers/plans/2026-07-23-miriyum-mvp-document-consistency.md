# MiriYum MVP Document Consistency Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every current policy source and MVP summary express the same policy counts, check-in boundary, and waiting team boundary without resolving deferred TODO items.

**Architecture:** Treat `docs/service-policies/README.md` rows and the latest detailed domain policies as canonical inputs. Apply mechanical text synchronization to current-state summaries, preserve explicitly historical snapshots, and verify the result with executable PowerShell assertions plus Markdown link checks.

**Tech Stack:** Markdown, Git, PowerShell 5.1-compatible validation commands

## Global Constraints

- Normalize `개정 확정` to the existing official state `확정`; do not add a new state.
- Current totals must be `확정 194`, `팀원 상의 필요 8`, `자동 추천 예정 0`, `TODO 15`, total 217.
- Remaining team decisions must be exactly `TASTE-003`, `TASTE-008`, `TASTE-012`, `SUB-001`, `SUB-002`, `SUB-003`, `SUB-007`, `ADS-001`.
- Preserve historical snapshots that are explicitly labeled with their former point in time.
- Keep all 15 TODO rows unresolved and do not add API, database, code, CI, legal-contract, or Wiki work.

---

### Task 1: Establish the failing consistency gate

**Files:**
- Read: `docs/service-policies/README.md`
- Read: `docs/service-definition.md`
- Read: `docs/technical-architecture.md`
- Read: `README.md`
- Read: `docs/flowcharts/flowchart.md`
- Read: `docs/service-policies/05-waiting.md`

**Interfaces:**
- Consumes: Current Markdown on branch `codex/mvp-doc-consistency`
- Produces: Reproducible failing assertions for status, check-in, and waiting inconsistencies

- [x] **Step 1: Run the policy-state assertion**

Run:

```powershell
$allowed = @('미논의','논의 중','자동 추천 예정','TODO','팀원 상의 필요','확정','변경 검토')
$rows = foreach ($line in Get-Content -Encoding utf8 'docs/service-policies/README.md') {
    if ($line -match '^\|\s*([A-Z]+-\d+)\s*\|[^|]*\|\s*([^|]+?)\s*\|\s*([^|]+?)\s*\|') {
        [pscustomobject]@{ Id = $matches[1]; Status = $matches[2].Trim(); Importance = $matches[3].Trim() }
    }
}
$invalid = @($rows | Where-Object Status -notin $allowed)
$confirmed = @($rows | Where-Object Status -eq '확정').Count
$team = @($rows | Where-Object Status -eq '팀원 상의 필요').Count
$todo = @($rows | Where-Object Status -eq 'TODO').Count
if ($rows.Count -ne 217 -or $invalid.Count -ne 0 -or $confirmed -ne 194 -or $team -ne 8 -or $todo -ne 15) {
    throw "policy gate failed: total=$($rows.Count), invalid=$($invalid.Count), confirmed=$confirmed, team=$team, todo=$todo"
}
```

Expected: FAIL because `RES-003`~`RES-005` use `개정 확정` and current totals disagree.

- [x] **Step 2: Run the MVP-boundary assertions**

Run:

```powershell
rg -n 'QR·확인번호|QR 또는 일회 확인번호|일회 확인번호와 직원 확인|일행이 같은 순번과 상태를 공유|매장 기능 범위: `STORE-007`|웨이팅 금전: `WAIT-015`' README.md docs/flowcharts/flowchart.md docs/service-policies/README.md
rg -n '원격 웨이팅을 등록하고 일행과 같은 대기 상태를 공유|대표자가 초대한 일행은 거리 제한 없이 기존 웨이팅에 참여' docs/service-definition.md
rg -n '일행과 대기 상태를 공유할 수 있게|참여한 일행은 같은 순번과 예상 대기 시간을 확인' docs/service-policies/05-waiting.md
```

Expected: FAIL because README, flowchart, service definition, waiting policy, and policy master contain those stale expressions.

---

### Task 2: Normalize policy state and current decision summaries

**Files:**
- Modify: `docs/service-policies/README.md`
- Modify: `docs/service-definition.md`
- Modify: `docs/technical-architecture.md`
- Modify: `docs/service-policies/00-policy-template.md`
- Modify: `miriyum-service-decisions.md`

**Interfaces:**
- Consumes: The 217 canonical master rows and the eight remaining team-decision IDs
- Produces: One current state model shared by all official current-state summaries

- [x] **Step 1: Normalize master row states**

Change `RES-003`, `RES-004`, and `RES-005` from `개정 확정` to `확정`.

- [x] **Step 2: Repair the master discussion queue and totals**

Remove `STORE-007` and `WAIT-015` from the current discussion queue. State that eight team decisions remain and set the totals to `194/8/0/15`.

- [x] **Step 3: Synchronize official summaries**

Update service definition and technical architecture current totals and remaining decision list. Reword the policy template's dated `184/18` value as a historical snapshot and add the current `194/8/0/15` value.

- [x] **Step 4: Append the current consistency record**

Add a dated decision-record section that records this as a state normalization, not a new product decision, and lists the eight remaining team decisions plus 15 unchanged TODO IDs.

---

### Task 3: Align the check-in MVP summaries

**Files:**
- Modify: `README.md`
- Modify: `docs/flowcharts/flowchart.md`
- Read: `docs/service-definition.md`
- Read: `docs/service-policies/09-checkin-noshow.md`

**Interfaces:**
- Consumes: The detailed MVP boundary in `CHECK-001`~`CHECK-010`
- Produces: Summary documents that expose only rotating QR and authorized store confirmation in MVP

- [x] **Step 1: Fix README wording**

Replace `QR·확인번호·매장 확인` with rotating QR and authorized store confirmation, and describe only the direct no-show path that exists in MVP.

- [x] **Step 2: Fix flowchart prose and nodes**

Remove one-time confirmation-number issuance from prerequisites, steps, nodes, invariants, and open questions. Keep one-time confirmation numbers explicitly deferred where a future-boundary note is useful.

- [x] **Step 3: Remove deferred dispute automation from MVP error handling**

Replace the error row that assumes formal appeal and automatic no-show confirmation with the MVP rule: unclear responsibility cannot default to user fault and requires authorized store confirmation with a reason code.

---

### Task 4: Align the waiting MVP summaries

**Files:**
- Modify: `README.md`
- Modify: `docs/service-definition.md`
- Modify: `docs/service-policies/05-waiting.md`

**Interfaces:**
- Consumes: The MVP table for `WAIT-001`~`WAIT-017`
- Produces: Representative-plus-headcount and global-FIFO wording for current MVP

- [x] **Step 1: Fix README and service-value wording**

Describe one representative and entered party size sharing one queue position; do not imply that companion accounts join or view current state.

- [x] **Step 2: Fix the waiting policy purpose and general flow**

Make the document purpose and current-flow prose distinguish the MVP representative-plus-headcount model from the confirmed future companion-account policy.

- [x] **Step 3: Preserve future policy rules**

Do not delete `WAIT-005`, `WAIT-006`, table compatibility, or deferral decision records. Ensure they remain clearly marked as future implementation boundaries.

---

### Task 5: Verify and commit the completed alignment

**Files:**
- Verify: all modified Markdown
- Commit: design, plan, and synchronized policy documentation

**Interfaces:**
- Consumes: Tasks 2 through 4
- Produces: A clean, reviewable documentation commit with reproducible evidence

- [x] **Step 1: Run the policy-state assertion**

Run the exact PowerShell assertion from Task 1 Step 1, then run:

```powershell
$duplicates = @($rows | Group-Object Id | Where-Object Count -gt 1)
if ($duplicates.Count -ne 0) { throw "duplicate policy IDs: $($duplicates.Name -join ', ')" }
```

Expected: 217 unique rows; `확정 194`, `팀원 상의 필요 8`, `TODO 15`; zero invalid statuses and zero duplicate IDs.

- [x] **Step 2: Run the MVP-boundary assertions**

Run:

```powershell
$hits = rg -n 'QR·확인번호|QR 또는 일회 확인번호|일회 확인번호와 직원 확인|일행이 같은 순번과 상태를 공유|매장 기능 범위: `STORE-007`|웨이팅 금전: `WAIT-015`' README.md docs/flowcharts/flowchart.md docs/service-policies/README.md
if ($LASTEXITCODE -eq 0) { throw "stale MVP summary found:`n$hits" }
$hits = rg -n '원격 웨이팅을 등록하고 일행과 같은 대기 상태를 공유|대표자가 초대한 일행은 거리 제한 없이 기존 웨이팅에 참여' docs/service-definition.md
if ($LASTEXITCODE -eq 0) { throw "stale service waiting summary found:`n$hits" }
$hits = rg -n '일행과 대기 상태를 공유할 수 있게|참여한 일행은 같은 순번과 예상 대기 시간을 확인' docs/service-policies/05-waiting.md
if ($LASTEXITCODE -eq 0) { throw "stale waiting policy summary found:`n$hits" }
```

Expected: no current-MVP claim that issues one-time confirmation numbers, connects companion accounts, or uses table-compatible calling; current discussion lists exclude `STORE-007` and `WAIT-015`.

- [x] **Step 3: Check Markdown links and whitespace**

Run:

```powershell
$broken = @()
foreach ($file in Get-ChildItem -Recurse -File -Filter '*.md') {
    $text = Get-Content -Raw -Encoding utf8 -LiteralPath $file.FullName
    foreach ($match in [regex]::Matches($text, '\[[^\]]*\]\(([^)]+)\)')) {
        $target = $match.Groups[1].Value.Trim()
        if ($target -match '^(https?://|mailto:|#)' -or [string]::IsNullOrWhiteSpace($target)) { continue }
        $pathPart = ($target -split '#')[0]
        $resolved = [System.IO.Path]::GetFullPath((Join-Path $file.DirectoryName $pathPart))
        if (-not (Test-Path -LiteralPath $resolved)) {
            $broken += "$($file.FullName): $target"
        }
    }
}
if ($broken.Count -ne 0) { throw "broken Markdown links:`n$($broken -join "`n")" }
git diff --check
if ($LASTEXITCODE -ne 0) { throw 'git diff --check failed' }
```

Expected: zero broken local links and no whitespace errors.

- [x] **Step 4: Review the exact diff**

Confirm the diff changes only the approved design, plan, status summaries, check-in summaries, waiting summaries, and the new dated consistency record.

- [x] **Step 5: Commit**

Stage only the approved files and commit with:

```text
docs: align MVP policy consistency
```
