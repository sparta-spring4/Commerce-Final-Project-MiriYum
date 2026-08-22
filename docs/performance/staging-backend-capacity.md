# staging backend 단일 task 용량 측정

Issue #567의 결과 기록 문서다. 목표는 성능 한계가 아니라 backend task 1개가 CPU 약 60%에서 안정적으로 처리하는 실제 RPS를 산정하는 것이다. production Auto Scaling 설정의 초기 근거로만 사용하며 production에는 부하를 보내지 않는다.

## 실행 상태

`NOT RUN`

이 PR은 하네스와 기록 계약만 추가한다. 실제 staging 실행 결과가 생기기 전에는 아래 표를 추정값으로 채우거나 완료로 바꾸지 않는다.

## 실행 전 증거

| 항목 | 값 |
|---|---|
| 승인 Issue·코멘트 | NOT RUN |
| operator / 독립 observer·중단 담당 | NOT RUN |
| 배포 backend full SHA | NOT RUN |
| k6 harness full SHA | NOT RUN |
| staging smoke run ID | NOT RUN |
| fixture SHA-256 | NOT RUN |
| backend desired/running task 수 = 1 증거 | NOT RUN |
| 테스트 시간대(KST) | NOT RUN |
| 중단 CPU·메모리 임계치 | NOT RUN |
| storeSearch OpenAI 비활성 증거 | NOT RUN |

## 단계별 결과

`CAPACITY_TARGET_RPS`는 계획 메타데이터이고 `ARRIVAL_RATE`는 iteration/s다. 실제 RPS는 k6 JSON의 scenario별 `httpRequests.rate`를 기록한다. CPU·메모리는 같은 run ID와 시간 범위의 CloudWatch ECS service/task 지표를 사용한다.

| 단계 | run ID | 계획 target RPS | arrival rate | 실제 RPS | CPU avg/max | memory avg/max | p50/p95/p99 ms | unexpected 4xx | 5xx | dropped iterations | LLM calls delta | 판정 |
|---:|---|---:|---:|---:|---|---|---|---:|---:|---:|---:|---|
| 1 | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |

## 단계 진행과 중단

- 1단계는 같은 target·두 full SHA·fixture의 성공한 staging smoke JSON을 요구한다.
- 2단계부터는 바로 이전 단계의 성공 JSON을 요구한다. 단계 번호가 연속하지 않거나 target·두 SHA·fixture·시나리오 구성이 다르면 init 단계에서 중단한다. 이전 단계의 scenario별 실제 RPS 합계가 그 단계의 계획 target RPS보다 작아도 다음 단계로 승격하지 않는다.
- unexpected 4xx/5xx, dropped iteration, 장애 징후 또는 사전 합의한 CPU·메모리 임계치 초과 시 즉시 중단한다.
- `storeSearch`는 OpenAI가 비활성인 배포에서만 실행하고 해당 시간대 `miriyum.search.llm.calls` 증가량이 0인지 확인한다.
- 성공·실패·중단 모두 결과 artifact와 CloudWatch 캡처를 동일 run ID와 시간 범위로 연결한다.

## 산정 결과

| 항목 | 값 |
|---|---|
| task 1개 CPU 약 60% 안정 RPS | NOT RUN |
| 관측 CPU·메모리 | NOT RUN |
| 관측 p95·오류율 | NOT RUN |
| 권장 Auto Scaling target CPU | NOT RUN |
| 권장 min/max task 수 | NOT RUN |
| 근거와 한계 | NOT RUN |
