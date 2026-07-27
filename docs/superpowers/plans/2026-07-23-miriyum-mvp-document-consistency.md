# MiriYum MVP 문서 정합성 구현 계획

> **에이전트 작업자용:** 필수 하위 스킬: superpowers:subagent-driven-development(권장) 또는 superpowers:executing-plans를 사용하여 이 계획을 작업 단위로 구현한다. 단계 추적에는 체크박스(`- [ ]`) 문법을 사용한다.

**목표:** 유예된 TODO 항목을 해소하지 않고, 모든 현재 정책 원본과 MVP 요약이 동일한 정책 집계, 체크인 경계, 웨이팅 팀 경계를 표현하도록 한다.

**아키텍처:** `docs/service-policies/README.md` 행과 최신 상세 도메인 정책을 정본 입력으로 취급한다. 현재 상태 요약에는 기계적인 텍스트 동기화를 적용하고, 명시적으로 역사적인 스냅샷은 보존하며, 실행 가능한 PowerShell 검증문과 Markdown 링크 검사로 결과를 검증한다.

**기술 스택:** Markdown, Git, PowerShell 5.1 호환 검증 명령

## 전역 제약

- `개정 확정`을 기존 공식 상태 `확정`으로 정규화하며, 새 상태를 추가하지 않는다.
- 현재 집계는 `확정 194`, `팀원 상의 필요 8`, `자동 추천 예정 0`, `TODO 15`, 총 217개여야 한다.
- 남은 팀 결정은 정확히 `TASTE-003`, `TASTE-008`, `TASTE-012`, `SUB-001`, `SUB-002`, `SUB-003`, `SUB-007`, `ADS-001`이어야 한다.
- 과거 시점이 명시적으로 표기된 역사 스냅샷을 보존한다.
- 15개 TODO 행을 모두 미해결 상태로 유지하고 API, 데이터베이스, 코드, CI, 법률 계약 또는 Wiki 작업을 추가하지 않는다.

---

### 작업 1: 실패하는 정합성 게이트를 수립한다

**파일:**
- 읽기: `docs/service-policies/README.md`
- 읽기: `docs/service-definition.md`
- 읽기: `docs/technical-architecture.md`
- 읽기: `README.md`
- 읽기: `docs/flowcharts/flowchart.md`
- 읽기: `docs/service-policies/05-waiting.md`

**인터페이스:**
- 입력: 브랜치 `codex/mvp-doc-consistency`의 현재 Markdown
- 출력: 상태, 체크인, 웨이팅 불일치에 대해 재현 가능한 실패 검증문

- [x] **단계 1: 정책 상태 검증문을 실행한다**

실행:

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

예상: `RES-003`~`RES-005`가 `개정 확정`을 사용하고 현재 집계가 일치하지 않으므로 FAIL한다.

- [x] **단계 2: MVP 경계 검증문을 실행한다**

실행:

```powershell
rg -n 'QR·확인번호|QR 또는 일회 확인번호|일회 확인번호와 직원 확인|일행이 같은 순번과 상태를 공유|매장 기능 범위: `STORE-007`|웨이팅 금전: `WAIT-015`' README.md docs/flowcharts/flowchart.md docs/service-policies/README.md
rg -n '원격 웨이팅을 등록하고 일행과 같은 대기 상태를 공유|대표자가 초대한 일행은 거리 제한 없이 기존 웨이팅에 참여' docs/service-definition.md
rg -n '일행과 대기 상태를 공유할 수 있게|참여한 일행은 같은 순번과 예상 대기 시간을 확인' docs/service-policies/05-waiting.md
```

예상: README, 플로차트, 서비스 정의, 웨이팅 정책, 정책 마스터에 해당 오래된 표현이 있으므로 FAIL한다.

---

### 작업 2: 정책 상태와 현재 결정 요약을 정규화한다

**파일:**
- 수정: `docs/service-policies/README.md`
- 수정: `docs/service-definition.md`
- 수정: `docs/technical-architecture.md`
- 수정: `docs/service-policies/00-policy-template.md`
- 수정: `miriyum-service-decisions.md`

**인터페이스:**
- 입력: 217개 정본 마스터 행과 남은 8개 팀 결정 ID
- 출력: 모든 공식 현재 상태 요약이 공유하는 하나의 현재 상태 모델

- [x] **단계 1: 마스터 행 상태를 정규화한다**

`RES-003`, `RES-004`, `RES-005`를 `개정 확정`에서 `확정`으로 변경한다.

- [x] **단계 2: 마스터 논의 큐와 집계를 수정한다**

현재 논의 큐에서 `STORE-007`과 `WAIT-015`를 제거한다. 팀 결정이 8개 남았다고 명시하고 집계를 `194/8/0/15`로 설정한다.

- [x] **단계 3: 공식 요약을 동기화한다**

서비스 정의와 기술 아키텍처의 현재 집계 및 남은 결정 목록을 갱신한다. 정책 템플릿의 날짜가 있는 `184/18` 값을 역사 스냅샷으로 재서술하고 현재 `194/8/0/15` 값을 추가한다.

- [x] **단계 4: 현재 정합성 기록을 추가한다**

이를 새 제품 결정이 아닌 상태 정규화로 기록하고, 남은 8개 팀 결정과 변경되지 않은 15개 TODO ID를 나열하는 날짜가 있는 결정 기록 섹션을 추가한다.

---

### 작업 3: 체크인 MVP 요약을 일치시킨다

**파일:**
- 수정: `README.md`
- 수정: `docs/flowcharts/flowchart.md`
- 읽기: `docs/service-definition.md`
- 읽기: `docs/service-policies/09-checkin-noshow.md`

**인터페이스:**
- 입력: `CHECK-001`~`CHECK-010`의 상세 MVP 경계
- 출력: MVP에서 회전 QR과 인가된 매장 확인만 노출하는 요약 문서

- [x] **단계 1: README 문구를 수정한다**

`QR·확인번호·매장 확인`을 회전 QR과 인가된 매장 확인으로 대체하고, MVP에 존재하는 직접 노쇼 경로만 설명한다.

- [x] **단계 2: 플로차트 본문과 노드를 수정한다**

일회 확인번호 발급을 사전 조건, 단계, 노드, 불변식, 미결 질문에서 제거한다. 미래 경계 메모가 유용한 곳에서는 일회 확인번호를 명시적으로 유예 상태로 유지한다.

- [x] **단계 3: MVP 오류 처리에서 유예된 분쟁 자동화를 제거한다**

정식 이의 제기와 자동 노쇼 확정을 전제하는 오류 행을 MVP 규칙으로 대체한다. 즉, 책임이 불분명하면 사용자의 책임으로 기본 처리할 수 없고 사유 코드가 있는 인가된 매장 확인이 필요하다.

---

### 작업 4: 웨이팅 MVP 요약을 일치시킨다

**파일:**
- 수정: `README.md`
- 수정: `docs/service-definition.md`
- 수정: `docs/service-policies/05-waiting.md`

**인터페이스:**
- 입력: `WAIT-001`~`WAIT-017`의 MVP 표
- 출력: 현재 MVP를 위한 대표자·인원수 및 전역 FIFO 문구

- [x] **단계 1: README와 서비스 가치 문구를 수정한다**

한 명의 대표자와 입력된 일행 인원수가 하나의 대기 순번을 공유한다고 설명하며, 동반자 계정이 참여하거나 현재 상태를 본다고 암시하지 않는다.

- [x] **단계 2: 웨이팅 정책 목적과 일반 흐름을 수정한다**

문서 목적과 현재 흐름 본문이 MVP 대표자·인원수 모델을 확정된 미래 동반자 계정 정책과 구분하도록 한다.

- [x] **단계 3: 미래 정책 규칙을 보존한다**

`WAIT-005`, `WAIT-006`, 테이블 호환성 또는 유예 결정 기록을 삭제하지 않는다. 이들이 미래 구현 경계로 명확히 표시된 상태를 유지한다.

---

### 작업 5: 완료된 정합성을 검증하고 커밋한다

**파일:**
- 검증: 수정된 모든 Markdown
- 커밋: 설계, 계획, 동기화된 정책 문서

**인터페이스:**
- 입력: 작업 2부터 작업 4까지
- 출력: 재현 가능한 증거를 갖춘 깨끗하고 검토 가능한 문서 커밋

- [x] **단계 1: 정책 상태 검증문을 실행한다**

작업 1의 단계 1에 있는 정확한 PowerShell 검증문을 실행한 다음, 다음을 실행한다.

```powershell
$duplicates = @($rows | Group-Object Id | Where-Object Count -gt 1)
if ($duplicates.Count -ne 0) { throw "duplicate policy IDs: $($duplicates.Name -join ', ')" }
```

예상: 고유 행 217개, `확정 194`, `팀원 상의 필요 8`, `TODO 15`, 잘못된 상태 0개 및 중복 ID 0개.

- [x] **단계 2: MVP 경계 검증문을 실행한다**

실행:

```powershell
$hits = rg -n 'QR·확인번호|QR 또는 일회 확인번호|일회 확인번호와 직원 확인|일행이 같은 순번과 상태를 공유|매장 기능 범위: `STORE-007`|웨이팅 금전: `WAIT-015`' README.md docs/flowcharts/flowchart.md docs/service-policies/README.md
if ($LASTEXITCODE -eq 0) { throw "stale MVP summary found:`n$hits" }
$hits = rg -n '원격 웨이팅을 등록하고 일행과 같은 대기 상태를 공유|대표자가 초대한 일행은 거리 제한 없이 기존 웨이팅에 참여' docs/service-definition.md
if ($LASTEXITCODE -eq 0) { throw "stale service waiting summary found:`n$hits" }
$hits = rg -n '일행과 대기 상태를 공유할 수 있게|참여한 일행은 같은 순번과 예상 대기 시간을 확인' docs/service-policies/05-waiting.md
if ($LASTEXITCODE -eq 0) { throw "stale waiting policy summary found:`n$hits" }
```

예상: 현재 MVP가 일회 확인번호를 발급하거나, 동반자 계정을 연결하거나, 테이블 호환 호출을 사용한다는 주장이 없고, 현재 논의 목록에서 `STORE-007`과 `WAIT-015`를 제외한다.

- [x] **단계 3: Markdown 링크와 공백을 점검한다**

실행:

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

예상: 깨진 로컬 링크가 0개이고 공백 오류가 없다.

- [x] **단계 4: 정확한 diff를 검토한다**

diff가 승인된 설계, 계획, 상태 요약, 체크인 요약, 웨이팅 요약 및 새로운 날짜가 있는 정합성 기록만 변경하는지 확인한다.

- [x] **단계 5: 커밋한다**

승인된 파일만 스테이징하고 다음 메시지로 커밋한다.

```text
docs: align MVP policy consistency
```
