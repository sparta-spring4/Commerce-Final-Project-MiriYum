# Staging Valkey 인프라 구성 설계

## 목표

staging EC2의 Docker Compose에 Valkey 8.1 계열을 내부 전용 보조 저장소로 추가한다. 이번 작업에서는 Valkey 컨테이너가 정상적으로 실행되고 재시작·healthcheck·메모리 정책을 적용하는 것까지만 검증한다.

## 범위

포함한다.

- `deploy/docker-compose.prod.yml`에 Valkey 서비스를 추가한다.
- Valkey는 애플리케이션 Docker 네트워크에서만 접근하고 호스트의 6379 포트는 공개하지 않는다.
- AOF persistence, 128 MiB 메모리 상한, `noeviction`, `unless-stopped` 재시작 정책과 healthcheck를 구성한다.
- staging `.env`에서 Valkey 호스트·포트·비밀번호를 주입할 수 있도록 예시와 Compose 환경을 정리한다.
- 배포 문서에 Valkey가 현재는 인프라만 준비되고 Refresh Token 기능은 후속 #140에서 연결된다는 경계를 기록한다.

제외한다.

- Spring Data Redis와 Lettuce 의존성 추가
- Refresh Token 회전·폐기·재사용 탐지 구현
- Valkey 장애 시 인증 실패 폐쇄 코드
- 로컬 테스트 환경과 운영용 ElastiCache 전환

## 구성

Valkey 컨테이너는 `valkey/valkey:8.1-alpine` 이미지를 사용한다. MySQL과 backend가 사용하는 `app` 네트워크에만 연결하고, 고정 내부 주소 `172.29.81.12`를 사용해 기존 주소와 충돌하지 않도록 한다.

컨테이너는 비밀번호 인증을 사용한다. 비밀번호는 저장소나 GitHub Actions에 넣지 않고 EC2의 `/opt/miriyum/.env`에서만 주입한다. Compose의 healthcheck도 같은 비밀번호를 사용해 `PONG` 응답을 확인한다.

Valkey 데이터는 `valkey-data` named volume에 저장한다. AOF는 컨테이너 재시작 시 최근 상태 복구를 돕고, `noeviction`은 인증 상태가 메모리 부족으로 조용히 삭제되는 것을 방지한다. 128 MiB 상한에 도달하면 쓰기가 실패하므로 후속 #140에서 용량과 장애 처리 정책을 별도로 검증한다.

## 데이터 흐름

```text
현재 1차 MVP:
브라우저 -> backend -> 무저장 JWT 검증

#141 이후:
EC2 Docker Compose -> Valkey 컨테이너 실행 준비
backend는 아직 Valkey를 조회하지 않음

#140 이후:
backend -> Spring Data Redis/Lettuce -> Valkey
Refresh Token 해시·세션 상태 저장 및 회전·폐기·재사용 탐지
```

Access JWT와 현재 Refresh JWT의 기존 1차 MVP 동작은 이번 변경으로 바꾸지 않는다. #141이 성공해도 인증 결과가 Valkey에 저장되거나 로그인 정책이 변경되지 않는다.

## 검증

구성 검증은 다음을 확인한다.

1. `docker compose config`가 성공한다.
2. `docker compose ps`에서 Valkey health 상태가 `healthy`다.
3. Valkey 컨테이너 내부의 인증된 `valkey-cli ping`이 `PONG`을 반환한다.
4. `docker port` 결과에 Valkey의 호스트 포트 매핑이 없다.
5. Valkey를 재시작해도 컨테이너가 자동 복구된다.
6. backend와 MySQL의 기존 health 상태와 API health check가 유지된다.

이번 PR에서는 Spring Boot가 Valkey를 사용하지 않으므로 Java 테스트나 Refresh Token 통합 테스트를 추가하지 않는다. 해당 검증은 #140에서 Spring Data Redis 연결과 함께 수행한다.

## 선택 이유와 후속 전환

Valkey와 Refresh Token 구현을 한 PR에 넣으면 인프라 오류와 인증 정책 오류를 구분하기 어렵고 로컬·CI 실행 환경까지 동시에 변경해야 한다. 먼저 내부 컨테이너의 생명주기와 보안 경계를 확인한 뒤, #140에서 클라이언트 의존성·상태 모델·장애 정책을 추가하는 단계적 구성이 안전하다.

이번 방식은 staging 단일 EC2에 적합하지만 고가용성이나 무중단 운영을 제공하지 않는다. 실제 운영 전환 시에는 별도 ElastiCache Valkey 또는 관리형 토폴로지, 백업·복구·용량 기준을 검토한다.
