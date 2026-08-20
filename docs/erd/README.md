# 전체 스키마 색인과 주요 관계 ERD

## 기준과 읽는 법

이 문서는 현재 `dev`의 Flyway V1~V67을 기준으로 한 MySQL 전체 테이블 색인과 도메인별 주요 관계 ERD다. 너무 큰 단일 다이어그램에 118개 테이블을 모두 넣어 가독성을 잃지 않도록, 모든 물리 테이블은 색인으로 완전하게 제공하고 다이어그램은 주요 업무 관계만 도메인별로 나눠 표시한다.

- 다이어그램의 모든 관계선은 주요 업무·논리 관계를 설명하며 DB `FOREIGN KEY` 존재를 단정하지 않는다.
- DB FK, UNIQUE, CHECK, 실제 삭제 규칙은 각 Flyway migration을 최종 기준으로 삼는다.
- 객체 키, URL, 토큰, 사업자등록번호, 대표자명 같은 민감값은 표시하지 않는다.
- V67의 사업자등록증 증빙 원장은 Store / Catalog 색인에 포함한다. 증빙의 공개 계약과 보존 정책은 `store-onboarding` 기능 명세가 소유한다.

## 도메인별 주요 관계

| 영역 | 문서 | 주요 테이블 |
| --- | --- | --- |
| 계정·인증·파일 | [Auth / Storage](auth-storage.md) | 계정, 소셜 연결, 요청 제한, 멱등 명령, 파일 메타데이터 |
| 매장·메뉴 | [Store / Catalog](store-catalog.md) | 매장, 일정, 메뉴 version, 재고, 제재 |
| 예약·결제 | [Reservation / Payment](reservation-payment.md) | 예약, 홀드, 픽업, 예약금, 결제·복구 |
| 웨이팅·알림 | [Waiting / Notification](waiting-notification.md) | 웨이팅 팀·원장·설정, 알림 task |
| 플랫폼 운영·지원 | [Platform / Support](platform-support.md) | 운영자 권한, 사건 배정, 제재, 회원 지원, 분석 snapshot |

## 전체 관계 개요

```mermaid
flowchart LR
    AUTH[계정·인증] --> STORE[매장·메뉴]
    AUTH --> RESERVATION[예약·결제]
    STORE --> RESERVATION
    STORE --> WAITING[웨이팅·알림]
    RESERVATION --> PAYMENT[결제·예약금]
    WAITING --> NOTIFICATION[알림]
    PLATFORM[플랫폼 운영·지원] -. 권한·감사 .-> AUTH
    PLATFORM -. 제재·모니터링 .-> STORE
    PLATFORM -. 사건·모니터링 .-> RESERVATION
    STORAGE[파일 메타데이터] -. 파일 식별자 .-> STORE
```

## 검증 방법

1. `backend/src/main/resources/db/migration`의 `CREATE TABLE` 목록과 각 문서의 색인을 대조한다.
2. 관계 해석이 필요하면 해당 Flyway 파일의 FK/UNIQUE/CHECK 제약을 최종 기준으로 삼는다. 다이어그램의 관계선만으로 물리 FK 여부를 판단하지 않는다.
3. 향후 migration이 새 테이블을 만들면 같은 PR에서 해당 도메인 ERD와 이 문서의 색인을 함께 갱신한다.
