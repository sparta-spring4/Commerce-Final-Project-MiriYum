# ADR-006: 액세스 JWT와 Valkey 리프레시 토큰 상태 관리

- 상태: Accepted
- 결정일: 2026-07-27

## 배경

MiriYum의 일반 사용자, 식당 대표자와 플랫폼 운영자는 결제와 식당 운영, 플랫폼 관리처럼 영향 범위가 다른 권한을 사용한다. 로그아웃과 계정 정지뿐 아니라 특정 기기의 로그인 종료, 탈취된 리프레시 토큰의 재사용 탐지와 중앙 폐기가 필요하다.

모든 액세스 요청을 중앙 세션 저장소에 의존시키지 않으면서도 리프레시 토큰의 보안 상태는 서버에서 통제해야 한다. 또한 Valkey는 캐시, 속도 제한, 임시 선점과 실시간 전달에도 사용할 예정이므로 짧은 수명과 잦은 변경이 있는 인증 상태를 MySQL 업무 원장에 중복해 두지 않을 책임 경계가 필요하다.

## 결정

- 모든 역할의 인증 API에 Spring Security 기반 액세스 JWT를 사용한다.
- 액세스 토큰의 정상 목록은 애플리케이션 서버, Valkey 또는 다른 서버 저장소에 보관하지 않는다. 요청마다 서명과 토큰에 포함된 검증 항목을 확인한다.
- 리프레시 토큰 원문은 저장하지 않는다. 안전한 해시와 계정 식별자, 로그인 단위 식별자, 토큰 계열 식별자, 만료, 폐기와 교체 상태를 Valkey에 저장한다.
- 리프레시 토큰을 사용할 때마다 기존 토큰을 폐기하고 새 액세스 토큰과 리프레시 토큰으로 회전한다.
- 폐기됐거나 이미 교체된 리프레시 토큰의 재사용을 탐지하면 해당 토큰 계열 전체를 폐기한다.
- 로그아웃과 개별 기기 종료, 전체 로그인 종료, 계정 정지와 권한 회수는 대상 로그인 단위 또는 토큰 계열의 리프레시 상태를 중앙에서 폐기한다.
- MySQL은 계정과 권한을 포함한 업무 원장을 맡고, Valkey는 만료와 회전이 잦은 리프레시 토큰 상태를 맡는다.

### Staging Valkey 시험 운영 경계

- #141의 첫 staging 배포에는 단일 `valkey/valkey:8.1-alpine` 컨테이너를 **시험 후보값**으로 사용한다. `8.1-alpine`, 단일 컨테이너, `maxmemory 128mb`는 운영 확정값이 아니며, ARM64 EC2 기동·health·용량·AOF 복구 검증 전에는 변경할 수 있다. 관리형 ElastiCache와 replica는 현재 트래픽·가용성 요구에 비해 비용과 운영 범위가 커서 이번 시험 범위에 도입하지 않는다.
- Valkey는 호스트 포트를 공개하지 않고, backend와만 공유하는 Docker `backend-valkey` internal network에 둔다. MySQL과 Nginx는 이 네트워크에 연결하지 않는다.
- 시험 구성에서는 AOF, `appendfsync everysec`, `maxmemory 128mb`, `noeviction`을 적용한다. 인증 상태가 메모리 부족으로 조용히 축출되는 대신 새 쓰기가 명시적으로 실패하는지, 실제 사용량이 128MB 후보값에 적합한지는 측정으로 확인한다.
- #141은 컨테이너 실행, 비밀번호 인증 healthcheck, staging `healthy`, 무인증 `NOAUTH`, 인증 `PONG`, host port 미공개 확인까지 담당한다. 배포 SHA·ARM64 환경·검증 결과·실패 시 기능 비활성화 또는 이전 Compose로 되돌리는 조건을 기록한 뒤에만 시험 후보값을 유지하거나 변경한다. Spring Data Redis/Lettuce 연결과 Valkey 장애 시 인증 요청 fail-closed 검증은 #140에서 수행한다.

### 이번 결정에서 확정하지 않는 항목

토큰 유효기간은 [AUTH-007](../service-policies/01-member-auth.md#auth-007-세션토큰기기-관리)을 정본으로 한다. 2026-08-13 결정으로 Access JWT는 15분, Refresh JWT는 14일이며, 이 결정은 2026-07-28의 Access JWT 1시간 결정을 대체한다. Refresh Token 회전이 성공하면 새 발급 시각을 기준으로 다시 14일을 부여하는 sliding 만료를 적용하고, Valkey family TTL도 같은 만료 시각으로 갱신한다. 이 수치 결정은 이 ADR의 단계별 저장·회전·폐기 구조를 변경하지 않는다.

`1차 MVP`에는 계정별 동시 로그인 상한·활성 로그인 목록·기기별 종료를 제공하지 않으며, 과거 중앙 세션 정책의 역할별 상한을 stateless JWT에 변환하지 않는다. 정확한 상한과 초과 시 종료 순서는 Valkey 로그인 상태를 활성화하는 고도화 전에 별도 결정한다.

리프레시 토큰의 브라우저 전달·저장 방식과 CSRF 경계는 2026-07-28 후속 팀 결정으로 확정했다. Access JWT는 응답 본문·shell별 메모리·Bearer 헤더를 사용하고 Refresh JWT는 namespace별 `HttpOnly`, `Secure`, `SameSite=Lax` 쿠키로만 전달한다. 재발급은 동일 Origin의 `POST` JSON 요청과 `Origin`·`Referer` 검증을 요구하며 로그아웃에는 Spring Security CSRF 보호를 적용한다. 이 브라우저 계약은 1차 MVP의 무저장 JWT와 고도화의 Valkey 상태 관리 모두에 유지된다.

## 검토한 대안

### 완전 무상태 리프레시 JWT

서명 검증만으로 갱신할 수 있어 별도 상태 저장소와 만료 정리가 필요 없다는 장점이 있다. 반면 로그아웃, 계정 정지, 특정 기기 종료와 탈취 토큰 폐기를 토큰 만료 전에는 즉시 반영하기 어렵고, 폐기된 토큰의 재사용을 계열 단위로 탐지할 수도 없다. 결제와 운영 권한이 있는 서비스의 통제 요구를 충족하지 못해 채택하지 않았다.

### MySQL 리프레시 상태

내구성과 감사 가능성이 높고 계정 데이터와 함께 관리할 수 있다는 장점이 있다. 반면 갱신마다 발생하는 회전과 폐기, 자동 만료 정리가 업무 원장의 책임에 섞이고, 다른 초기 기능을 위해 운영할 Valkey와 역할이 중복된다. MySQL에는 업무 원장을 유지하고 짧은 수명 인증 상태는 Valkey에 두기로 했다.

### Spring Session Data Redis

중앙 세션 폐기와 브라우저 쿠키 기반 인증을 단순하게 제공한다. 그러나 모든 인증 요청이 중앙 세션 상태에 의존하고, 새 MVP에서 요구하는 액세스 JWT와 리프레시 토큰 회전 구조를 직접 충족하지 않는다. 기존 세션 방식 대신 액세스 JWT와 Valkey 리프레시 상태를 선택했다.

### 외부 인증 제공자 또는 독립 인증 서버

표준 인증 기능과 운영 책임을 위임하거나 여러 서비스의 인증을 통합하기에 유리하다. 현재 단일 제품 MVP에는 별도 비용과 운영 경계, 외부 종속을 추가하며 Spring Security 기반 구현 목표와도 겹친다. 서비스 분리나 통합 인증 요구가 확인되기 전에는 채택하지 않는다.

## 긍정적 결과

- 일반 API 요청은 액세스 토큰 정상 목록을 조회하지 않고 검증할 수 있다.
- 로그아웃, 기기별 종료, 계정 정지와 권한 회수를 리프레시 토큰 상태에 중앙 반영할 수 있다.
- 매 갱신 회전과 폐기 토큰 재사용 탐지로 탈취된 토큰 계열의 확산을 제한할 수 있다.
- Valkey의 만료와 원자적 상태 변경 특성을 활용하면서 MySQL 업무 원장과 책임을 중복하지 않는다.
- 캐시, 속도 제한, 임시 선점과 실시간 전달에 사용할 공용 기반 시설을 함께 활용할 수 있다.

## 부정적 결과

- 로그인, 토큰 갱신과 로그아웃 경로가 Valkey 가용성과 일관된 상태 변경에 의존한다.
- 토큰 계열, 회전 경쟁과 재사용 탐지 규칙을 구현하고 운영해야 한다.
- 액세스 토큰 정상 목록을 저장하지 않으므로 이미 발급된 액세스 토큰을 즉시 개별 폐기하는 기능은 이 결정만으로 제공되지 않는다.
- Valkey 운영, 관측, 복구와 장애 대응 비용이 추가된다.

## 재검토 조건

- 액세스 토큰 만료 전 개별 폐기가 필요한 보안 명령을 현재 계정·권한 검증 경계로 충족하지 못한다는 운영 증거가 생기면 액세스 토큰 폐기 방식을 재검토한다.
- Valkey 장애율, 갱신 지연 또는 운영 비용이 합의된 인증 서비스 기준을 지속해서 넘으면 MySQL이나 외부 인증 제공자를 포함한 상태 저장 방식을 다시 비교한다.
- 여러 서비스의 통합 인증, 표준 연합 로그인 또는 독립적인 인증 운영 조직이 필요해지면 외부 인증 제공자나 독립 인증 서버를 재검토한다.

## 관련 문서

- [JWT·Valkey 인증과 통합 검색 정책 설계](../superpowers/specs/2026-07-27-jwt-valkey-unified-search-design.md)
- [사용자와 권한](../02-users-and-permissions.md)
- [시스템 아키텍처](../06-system-architecture.md)
- [회원·인증 정책](../service-policies/01-member-auth.md)
- [요구 기반의 단계적 기술 도입](ADR-002-staged-technology-adoption.md)

## 2026-07-27 날짜별 개정

### 단계 개정

- 최초 Valkey 기반 Refresh Token 상태 관리 결정과 본문은 목표 아키텍처의 판단 기록으로 보존한다. 현재 합의에서는 적용 시점을 고도화로 이동한다.
- **1차 MVP와 2차 MVP:** Access Token과 Refresh Token 모두 서버 저장소 없는 JWT다. 서명, 만료, 발급자, audience, 계정 유형별 토큰 namespace를 검증하며 정상 토큰 저장소·폐기 목록·Valkey 의존성을 두지 않는다.
- **고도화:** Valkey에 Refresh Token family의 회전, 폐기, 재사용 탐지와 로그인 단위 종료 상태를 둔다. Access Token은 짧은 수명의 stateless JWT를 유지하며 권한·계정 상태는 요청 경계에서 검증한다.
- 일반 사용자, 매장 운영자, 플랫폼 운영자는 계정 테이블·PK·principal·토큰 namespace가 다르다. 다른 계정 유형의 Refresh Token을 교환하거나 같은 subject 문자열로 결합하지 않는다.
- 전체 로그인 종료는 계정별 세션 세대를 증가시키고 Refresh Token 최대 만료 시각까지 보관한다. 로그인 시작 시 읽은 세대와 생성 시점 세대가 다르면 늦게 끝난 로그인은 Refresh Token을 발급받지 못한다.
- 단계가 바뀌어도 브라우저는 Access Token을 shell별 메모리, Refresh Token을 namespace별 `HttpOnly` 쿠키로 다룬다. 고도화 전환은 브라우저 저장 위치를 바꾸지 않고 Refresh Token의 서버 검증에 Valkey 회전·폐기·재사용 탐지를 추가한다.

### 전환과 검증

- 고도화 전환 시점부터 새 Refresh Token에 family 식별자와 회전 상태를 부여한다. 기존 stateless Refresh Token은 family·token 식별자가 없으므로 재발급에 사용하지 않으며, 기존 Access Token은 원래 만료 시각까지 유지하고 사용자는 한 번 다시 로그인한다.
- 1차 MVP는 서명 변조, 만료, audience·계정 유형 불일치, Access/Refresh 용도 교차, 동시 재발급의 계약을 검증한다. 고도화는 회전 경쟁, 이전 토큰 재사용, 전체 family 폐기, Valkey 지연·중단과 복구를 추가 검증한다.
- 초기 단계에는 즉시 세션 폐기와 재사용 탐지가 제한된다는 단점이 있고, 고도화에는 Valkey 가용성·상태 마이그레이션·실패 폐쇄 운영 비용이 추가된다. 이 절은 그 비용을 기능·운영 요구가 생기는 단계로 미룬다.

## 2026-08-13 날짜별 개정

### 절대 세션 수명 적용

- **상태: 확정·적용.** 기존 sliding 만료 결정에 30일 절대 상한을 추가한다.
- Access JWT 15분과 Refresh JWT 최대 14일은 유지한다. Refresh Token 회전 시에는 sliding 만료를 적용하되, 새 Refresh JWT와 Valkey family TTL을 `min(회전 시각 + 14일, 최초 로그인 시각 + 30일)`까지만 연장한다.
- 절대 상한은 최초 로그인부터 30일 뒤의 **새 Access·Refresh 발급 권한**의 상한이다. 상한 직전 회전에서 이미 발급된 stateless Access JWT는 중앙에서 즉시 폐기하지 않으므로 최대 15분 동안 더 통과할 수 있다. 상한 이후에는 새 Access·Refresh를 발급하지 않고 기존 `AUTH_008`로 재로그인을 요구한다.
- 절대 상한 뒤에 별도 tombstone 또는 전용 종료 상태를 보관하지 않는다. Refresh JWT와 Valkey family TTL의 만료 시각은 정확히 일치할 필요가 없다. Refresh JWT가 먼저 만료되면 JWT 검증이, family TTL이 먼저 만료되면 family 부재 결과가 각각 기존 `AUTH_008`로 종료하므로, 어느 순서에서도 정상적인 상한 만료·후속 재시도는 재사용 위험 사건을 만들지 않는다.
- 새 만료 시각이 최초 로그인 + 30일에 의해 잘리는 회전은 민감 식별자 없는 `상한 적용 회전 수` 구조화 로그와 지표로 관측한다. 이 값은 30일 만료에 실제로 도달했거나 재로그인한 세션 수가 아니라, 절대 상한 계산이 적용된 회전 수다. 정책 적용 경로만 확인하며, 만료된 JWT를 다시 파싱하거나 상태를 되살려 종료 원인을 별도 집계하지 않는다.
- 배포 전에 생성되어 `familyCreatedAt`이 없는 legacy family는 backfill하지 않는다. 첫 회전에도 기존 family의 남은 만료 시각을 유지하고 만료를 다시 연장하지 않아, legacy family의 sliding 만료는 배포 직후부터 중지된다. 사용자는 갱신해도 기존 만료 시각에 재로그인해야 하며, 배포 시점의 잔여 수명에 따라 즉시 또는 최대 14일 안에 자연 종료된다. 이후 새 로그인으로 생성된 family에만 30일 절대 상한이 적용된다.
- 구현 통합 테스트에는 `familyCreatedAt`이 없는 legacy family의 회전이 기존 만료 시각을 넘기지 않는 경계값, 새 Refresh 만료가 30일 상한으로 잘리는 경계값, 상한 적용 관측, 만료 Refresh JWT의 `AUTH_008`과 위험 사건 미생성을 포함한다.

완전 sliding 만료는 사용자가 14일 안에 한 번만 갱신해도 세션이 끝나지 않는 장점이 있지만, 탈취되거나 잊힌 로그인 상태가 무기한 남을 수 있다. 반대로 고정 14일 만료는 구현이 단순하지만 정상 사용자가 갱신해도 정확히 14일 뒤 재로그인해야 한다. 이번에는 두 방식의 장단점을 함께 고려해 sliding 14일과 절대 30일 상한을 결합한다.

### 위험 사건 marker 인덱스와 Valkey Cluster 경계

- Refresh Token Lua 스크립트는 family, 계정 index, session epoch, 위험 marker와 pending index를 한 원자적 연산으로 함께 변경한다. 현재 키 구조는 **단일 Valkey 노드만 지원**하며 Valkey Cluster는 지원하지 않는다.
- Valkey Cluster로 전환해야 할 때는 모든 Lua `KEYS`가 같은 hash slot에 놓이도록 Refresh Token과 위험 marker 키 전체를 hash tag 기반으로 재설계한다. 기존 family는 재로그인으로 전환한다.
- pending Set 인덱스를 조회 방식으로 전환하기 전, 배포 스크립트가 매 전진 배포마다 기존 `auth:risk:pending:*` marker를 `auth:risk:pending-index`에 멱등하게 이관한다. 구버전 롤백 중 생성된 marker도 다음 전진 배포에서 다시 등록되며, `SADD`는 이미 등록된 marker를 중복 생성하지 않는다. 후속 전달 경로는 Set만 조회해 평상시 전 keyspace `SCAN`을 사용하지 않는다.
- 전달 작업은 `SSCAN` 커서를 이어서 읽고 한 주기에 marker 100개까지만 처리한다. 한 페이지의 남은 marker는 다음 주기에 먼저 처리해 특정 marker만 반복 조회하지 않는다. marker가 비어 보이면 Lua에서 marker 부재 확인과 `SREM`을 함께 수행하므로, 같은 키의 marker가 전달 중 다시 생성되어도 새 인덱스 연결을 삭제하지 않는다.
- pending Set의 현재 크기는 민감 식별자 없이 `RefreshTokenRiskEventPendingCount` 지표로 관측한다. 이 값은 전달 가능한 이벤트 수가 아니라 stale member를 포함한 인덱스 멤버 수이며, 지속적으로 증가하면 전달 정체 알람과 함께 원인을 점검한다. marker는 최초 생성 시점부터 7일 절대 TTL을 적용하며, 같은 사건 재사용으로 발생 횟수를 누적해도 TTL을 연장하지 않는다. 1시간 이상 남아 있는 marker가 현재 전달 배치에 하나라도 있으면 `RefreshTokenRiskEventMarkerLongStay=1` 존재 신호를 기록하고 5분 합계 알람으로 관측한다. 이 지표는 전체 장기 정체 건수가 아니다.
- 필수 필드가 없거나 숫자 형식이 손상된 pending marker는 인덱스와 함께 제거하고 제한 로그만 남긴다. 손상 marker 하나가 정상 marker 전달을 반복적으로 막지 않게 하며, 계정·family·토큰 식별자는 로그에 넣지 않는다.

### 다중 인스턴스 위험 사건 전달 방침

- 여러 애플리케이션 인스턴스가 실행되면 각 인스턴스의 scheduler가 같은 pending marker를 읽을 수 있다. 이 전달은 정확히 한 번이 아니라 **at-least-once(최소 한 번)** 방식으로 동작하며, 일시적인 MySQL 중복 쓰기는 허용한다.
- 동일 marker를 여러 인스턴스가 전달해도 `auth_risk_events.event_key` 고유키와 upsert가 발생 횟수·마지막 발생 시각을 큰 값으로 수렴시킨다. 따라서 중복 실행이 별도 위험 사건 행을 만들지 않는다.
- marker를 새로 만들 때는 수명 주기를 구분하는 임의 `generation` 값을 한 번 저장한다. 구버전 marker는 읽은 필드 snapshot이 그대로일 때만 generation을 한 번 채워 기존 위험 사건을 보존한다.
- marker는 MySQL 저장이 성공한 뒤에도 읽은 `occurrenceCount`와 `generation`이 모두 같을 때만 Lua로 삭제한다. 전달 중 같은 사건이 누적되거나, 삭제 뒤 같은 키의 marker가 새로 생성되면 이전 worker는 삭제하지 않아 새 위험 사건을 다음 주기에 전달한다.
- 롤링 배포 중 구버전 인스턴스가 남아 있으면 구버전의 count-only 삭제 경로는 generation을 비교하지 못한다. 전진 배포가 완료되어 신버전만 실행된 뒤 generation 기반 ABA 삭제 방어가 완전히 성립하므로, 구·신버전 혼재 시간은 배포 관측과 재배포로 짧게 유지한다.
- 현재는 분산 lock을 추가하지 않는다. lock lease 만료·소유권 확인이라는 새 실패 경로보다, 처리량이 작은 위험 사건을 멱등하게 한 번 더 저장하는 비용이 작기 때문이다. `RefreshTokenRiskEventPendingCount`가 지속적으로 증가하거나 `refresh_token_risk_event_marker_long_stay`, `refresh_token_risk_event_delivery_stalled` 로그가 반복되면 Valkey 기반 lease lock 또는 전용 작업 큐 도입을 별도 결정한다.
