# 알림 읽음 배포 게이트와 forward rollback

## 목적

알림 읽음 계약을 모르는 구버전 writer가 다시 실행되면 `read_at`과 `change_version`이 함께 갱신되지 않을 수 있다. 이 문서는 자동·수동 배포에서 구버전 진입을 차단하고, 교체가 실제로 끝났다는 관측 증거를 확인하는 절차를 정의한다.

최소 호환 writer revision은 PR #525의 병합 commit인 `515531e122ebbce13d8eead4a3ff15a94c25e0b3`으로 고정한다. staging과 production에 선택한 SHA가 이 commit을 포함하는 Git history에 없으면 배포를 중단한다. SHA 또는 history 증거를 얻지 못한 경우에도 통과시키지 않는다.

이 값은 애플리케이션 환경변수가 아니다. GitHub Actions와 배포 계약에서만 사용하는 공개 Git revision 하한이다.

revision 판정 스크립트와 staging `deploy.sh`는 선택한 후보 SHA에서 가져오지 않는다. 실행 중인 workflow revision의 신뢰된 복사본을 runner 임시 경로에 보존한 뒤 후보 source와 분리해 실행한다. 따라서 하한 commit 자체처럼 게이트 파일이 아직 없던 후보도 같은 최신 배포 통제를 거치며, 후보가 게이트를 약화할 수 없다.

## 적용 순서

1. 알림 읽음 API를 비활성 상태로 배포한 PR 1A가 모든 writer에 반영돼 있어야 한다.
2. 이 배포 게이트로 구 writer의 종료와 새 writer의 교체 완료를 확인한다.
3. 같은 대상 환경·fixture·commit에 결속된 smoke 증거를 확인한 뒤 PR 1B에서 읽음 API를 활성화한다.

한 단계라도 증거가 없거나 서로 다른 대상·commit에서 얻은 증거이면 다음 단계로 진행하지 않는다.

## Staging EC2/Compose 증거

`deploy/deploy.sh`는 값을 출력하지 않고 다음 lifecycle만 판정한다.

1. 배포 전 기존 backend 컨테이너 ID, 40자리 SHA immutable image와 MySQL이 공유하는 `miriyum_app` network IP를 캡처한다. Valkey 전용 network 주소는 DB 연결 판정에 사용하지 않는다.
2. 기존 backend를 중지하고 컨테이너 실행 상태가 `false` 또는 제거 상태인지 확인한다.
3. MySQL `information_schema.processlist`에서 기존 backend IP의 연결 수가 `0`인지 확인한다.
4. 새 backend health가 성공한 뒤 현재 컨테이너 ID가 기존 ID와 다르고, 실행 중이며, `BACKEND_IMAGE`와 정확히 같은 immutable image인지 확인한다.
5. 기존 컨테이너가 다시 실행 중이면 실패한다.

컨테이너 상태, network IP 또는 DB 연결 수를 읽지 못하면 배포는 fail-closed로 종료한다. 로그에는 IP, 컨테이너 ID, DB 접속 정보 또는 환경변수 값을 남기지 않는다.

## Production ECS/ALB 증거

ECS 안정화 확인 뒤 별도 증거 단계에서 다음을 다시 조회한다.

1. PRIMARY deployment와 service task definition이 새 task definition과 정확히 같고 rollout이 `COMPLETED`여야 한다.
2. `runningCount == desiredCount`, `pendingCount == 0`이어야 한다.
3. service update 직전에 service가 단일 완료 rollout이고 `runningCount == desiredCount`, `pendingCount == 0`인지 확인한 뒤 `desiredStatus=RUNNING` task와 `desiredStatus=STOPPED` task를 함께 조회한다. `STOPPED` 요청 task 중 `lastStatus=RUNNING`, `DEACTIVATING`, `STOPPING`처럼 아직 실제 종료되지 않은 task를 기존 RUNNING task와 합친다. task identity·service task definition·desired count가 5초 간격의 연속 두 snapshot에서 같을 때만 교체 전 identity로 확정한다. 교체 뒤 각 task를 직접 조회해 모두 `desiredStatus=STOPPED`, `lastStatus=STOPPED`인지 확인하며, 누락되거나 종료 중인 task가 있으면 실패한다. 이미 `lastStatus=STOPPED`인 과거 이력은 캡처 대상에서 제외한다.
4. 교체 뒤 service의 `desiredStatus=RUNNING` task를 다시 조회하고 전부 새 task definition의 `RUNNING` task이며 수가 desired count와 같은지 확인한다. 각 task의 `ATTACHED` ENI에서 단일 `privateIPv4Address`도 함께 확보한다.
5. service가 사용하는 모든 target group을 조회하고 target 수가 service desired count와 같으며 전부 `healthy`여야 한다. 또한 각 target group의 healthy `Target.Id` 집합은 현재 task의 private IPv4 집합과 정확히 같아야 한다. 새 task 미등록, stale/manual target 혼입, `draining`, `unused`, `unhealthy` 또는 누락된 target health는 deregistration 미완료로 판정한다.

AWS 조회 실패, task lookup failure, target group 누락 또는 빈 증거는 모두 실패다. 임시 증거 JSON은 소유자만 읽고 쓸 수 있게 생성하며 성공·실패와 관계없이 실행 종료 시 삭제한다. ARN, task ID, target 주소는 로그로 출력하지 않는다.

## 롤백

최소 호환 revision보다 오래된 이미지로 되돌리는 in-place rollback은 자동·수동 모두 금지한다. 장애 시에는 최소 호환 revision을 포함하는 새 commit에서 수정한 immutable 이미지를 만들고 정상 배포 절차를 다시 수행하는 forward rollback만 사용한다.

1. 장애 원인을 수정한 새 commit을 생성한다.
2. 해당 commit의 Backend CI 성공을 확인한다.
3. 자동 배포를 사용하거나, CI가 성공한 full SHA image를 수동 선택한다.
4. revision floor와 환경별 교체 증거가 모두 통과하는지 확인한다.

DB 또는 이벤트 계약을 과거 상태로 되돌려야 한다면 이 절차로 임의 수행하지 않고 도메인 소유자와 별도 복구 계획을 승인한다.

## 실행 증거 상태

이 변경의 로컬 계약·행동 테스트 통과는 실제 환경 배포 증거가 아니다. staging EC2와 production ECS/ALB에서 위 lifecycle을 관측하기 전까지 실제 rollout 증거는 `NOT RUN`으로 기록한다.

PR 1B가 모든 환경에 배포되고 최소 호환 writer floor가 지속적으로 강제된 뒤에만 구 worker 호환 reconciliation과 임시 index 제거를 별도 Issue로 진행한다.
