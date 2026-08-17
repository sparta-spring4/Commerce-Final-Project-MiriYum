# PR #382 Store 제재 리뷰 보완 설계

## 목적

PR #382의 최신 리뷰에서 확인된 네 결함을 보완한다.

- 일반 매장 수정으로 기능 제한을 우회하거나 제재 전 base 설정을 잃지 않는다.
- 제재별 projection에는 그 제재가 직접 기여하는 효과만 저장한다.
- 영구 퇴점은 일반 제재 projection이 아니라 Store 소유 OPER-009 조건부 폐점 명령으로 확정한다.
- admin-store 활성 정본 링크와 exact allowlist를 실제 승인 범위에 맞춘다.

기존 확정 거래의 상태, 결제, 환불은 변경하지 않는다. 제재는 대상 `storeId`에만 적용하며 같은 대표자의 다른 매장에는 전파하지 않는다.

## 선택한 구조

### Store base/effective 분리

`Store`의 현재 운영 상태와 mode는 외부에 노출되는 effective 값으로 유지한다. `StoreEnforcementState`는 운영자가 선택한 최신 base 운영 상태와 mode를 별도로 보존한다.

일반 매장 수정 트랜잭션은 Store 행을 잠근 상태에서 다음 순서로 처리한다.

1. 요청값을 최신 base 설정에 반영한다.
2. 활성 제재 projection의 제한 기능 합집합과 운영 상태 효과를 다시 합성한다.
3. 합성된 effective 값을 Store 공개 필드에 반영한다.

활성 enforcement state가 없으면 현재 Store 수정 동작을 유지한다. `STORE_MANAGEMENT` 제한은 일반 수정 자체를 계속 거부한다. 그 밖의 기능 제한 중에는 일반 설정 변경을 base에 저장하되 effective 제한을 유지한다. 제재 해제 후에는 해제 시점의 최신 base 설정이 나타나야 한다.

신규 Reservation, Waiting, Menu Hold, Pickup 판정은 Store 공개 mode만 신뢰하지 않고 해당 `RestrictedFeature`의 활성 restriction도 확인한다. Store 행과 enforcement state를 같은 Store 범위에서 읽으며 중앙 상태를 확인할 수 없을 때 신규 거래를 허용하지 않는다.

### 제재 projection 정규화

`EnforcementCommand`의 운영 상태 값은 현재 Store effective 상태가 아니라 해당 제재가 직접 기여하는 선택적 효과다.

- `FEATURE_RESTRICTION`: 운영 상태 효과 없음
- `TEMPORARY_SUSPENSION`: `TEMPORARILY_CLOSED`
- `WARNING`: projection을 만들지 않음
- `PERMANENT_EXIT`: 일반 projection을 만들지 않고 OPER-009 폐점 명령 사용

따라서 기능 제한이 임시 중지 중 적용돼도 임시 중지의 상태를 복사하지 않는다. 적용 순서와 해제 순서가 달라도 남은 projection만 합성한다.

### OPER-009 영구 퇴점

Store 도메인은 Entity·Repository를 외부에 노출하지 않는 조건부 폐점 DTO와 Service port를 소유한다. 폐점 명령은 다음 값을 요구한다.

- `storeId`
- 현재 `enforcementVersion`
- sanction ID
- 승인 사건 ID
- 구조화된 폐점 원인 유형
- 정책 버전

Store와 enforcement state를 잠근 뒤 version과 현재 상태를 검증하고, 승인 결속을 한 번만 기록한 다음 `Store.close()`를 호출한다. 결속된 sanction은 일반 `release`에서 제거할 수 없다. #279의 release endpoint는 `PERMANENT_EXIT` 복구 요청을 정책 위반으로 거부한다.

폐점 복구는 이번 PR에 새 endpoint로 추가하지 않는다. OPER-009가 요구하는 별도 복구 권한, 입점·귀속·필수 표시 재검증, 전용 감사 계약이 활성화된 뒤 독립 기능으로 구현한다. 이 결정은 영구 퇴점을 일반 제재 해제로 되돌리는 현재 결함을 제거하면서 승인되지 않은 복구 surface를 만들지 않는다.

### 문서 정본과 allowlist

`docs/05-functional-requirements.md`의 플랫폼 운영자 정책 행에서 `docs/specs/admin-store/spec.md`를 직접 연결한다. admin-store spec의 exact allowlist는 `origin/dev...HEAD` 실제 변경 경로 및 이번 보완 경로와 일치시킨다. Issue #279의 allowlist 댓글도 코드 작성 전에 같은 내용으로 갱신한다.

## 오류와 동시성

- stale enforcement version, 중복 적용, 동시 승인·해제·만료는 기존 conflict 오류로 실패한다.
- 일반 release에서 영구 퇴점을 복구하려 하면 admin-store 정책 위반으로 실패한다.
- 기능 제한과 일반 매장 수정이 경합하면 Store 행 잠금 순서로 직렬화되고, 최종 effective 값은 최신 base와 활성 projection의 합성 결과다.
- OPER-009 폐점과 신규 거래가 경합하면 같은 Store 잠금 기준으로 폐점 또는 거래 확정 중 하나가 먼저 성립한다.
- 기존 거래 상태를 취소·환불·덮어쓰는 호출은 추가하지 않는다.

## 테스트 전략

각 동작은 실패하는 회귀 테스트를 먼저 확인한 뒤 최소 구현으로 통과시킨다.

- `StoreSanctionPolicyCatalogTest`: 기능 제한 projection이 운영 상태를 복사하지 않음
- `StoreAdministrationServiceIT`: 임시 중지와 기능 제한의 양쪽 적용·해제 순서, 최신 base mode 복원, 영구 퇴점 결속과 일반 release 거부
- `StoreTransactionEligibilityServiceTest`: Reservation, Waiting, Menu Hold, Pickup 활성 restriction 차단
- `StoreServiceTest`: 제한 중 일반 수정이 base에는 반영되지만 effective 제한은 유지됨
- `StoreSanctionCommandServiceTest`: 영구 퇴점 승인 시 OPER-009 port 호출, 일반 release 거부
- `StoreSanctionConcurrencyIT`: 실제 MySQL에서 수정·제재 및 영구 퇴점·신규 거래 직렬화
- 문서 routing/OpenAPI의 최소 관련 테스트와 lint

로컬에서는 위 unit test와 영향받는 integration class만 실행한다. `build`, `check`, 전체 `integrationTest`, integration A~D 전체 조합은 실행하지 않으며 전체 회귀는 GitHub CI 결과를 정본으로 삼는다.

## 범위 밖

- 영구 퇴점 복구 endpoint
- 기존 확정 거래의 자동 취소·환불·상태 변경
- Store 운영자 일상 관리 endpoint의 admin-store 재사용
- 계정 단위 session revoke 또는 같은 대표자 소유 다른 Store 변경
- `membersupport/**` 변경
