# MiriYum

식당 탐색부터 방문 예약, 웨이팅, 한정 메뉴 확보까지 하나의 흐름으로 연결하는 양면 다이닝 플랫폼입니다.

> **현재 상태: 설계·정책 정리 단계**
>
> 이 저장소는 현재 구현 코드보다 제품 범위, 운영 정책, 기술 아키텍처 문서를 먼저 정리하고 있습니다. 아래 기능과 기술은 확정된 제품·기술 방향을 설명하며, 구현 완료나 운영 배포를 뜻하지 않습니다. 실행 가능한 프로젝트 구조와 검증된 명령이 준비되기 전까지 설치·실행·테스트·배포 방법을 제공하지 않습니다.

[GitHub Wiki에서 상세 가이드 보기](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/wiki)

## 왜 MiriYum인가

사용자는 예약에 성공해도 원하는 메뉴의 품절 여부를 방문 전에 알기 어렵고, 예약·웨이팅·한정 메뉴 정보가 여러 채널에 흩어져 있습니다. 매장은 업종과 운영 방식이 다른데도 동일한 예약·재고 관리 방식을 강요받거나, 전체 메뉴 재고를 별도로 관리해야 하는 부담을 겪습니다.

MiriYum은 모든 매장에 같은 기능을 강제하지 않습니다. 매장은 필요한 운영 기능을 선택하고, 사용자는 실제 이용 가능한 매장과 메뉴를 찾은 뒤 방문 수용량과 원하는 메뉴 수량을 일관된 예약 흐름에서 확보할 수 있습니다.

## 핵심 경험

### 1차 MVP 범위

- **매장·메뉴 탐색**: 매장명, 메뉴명, 지역, 카테고리, 예약 가능 여부와 자연어 조건으로 탐색하고 무료 기본 추천을 제공합니다.
- **즉시 확정 방문 예약**: 매장 승인 대기 없이 현재 운영 상태와 수용량을 검증해 예약하는 흐름을 설계합니다.
- **예약과 메뉴 홀드 결합**: 방문 수용량과 선택 메뉴 수량을 하나의 임시 선점 그룹으로 묶어 일부 자원만 확정되는 상황을 방지합니다.
- **현장·원격 웨이팅**: 매장 운영 범위 안에서 대표자와 입력 인원수로 하나의 대기 팀을 만들고 단일 FIFO 순번과 상태를 확인합니다.
- **예약금·취소·환불**: 매장이 예약금 사용 여부를 선택하고, 결제와 예약 상태를 분리해 실패와 복구를 추적합니다.
- **체크인·노쇼**: 회전형 QR과 권한 있는 매장 운영자의 직접 확인으로 방문을 완료하고, 공통 5분 경과 뒤 매장이 노쇼를 직접 확정합니다.
- **취소 자리·수량 승계**: 반환된 예약 기회나 메뉴 수량을 중복 제안 없이 다음 후보에게 전달합니다.
- **매장·플랫폼 운영**: 영업시간, 메뉴, 예약, 웨이팅, 기본 통계와 심사·분쟁·수동 복구 흐름을 다룹니다.

### 1차 MVP 이후 후보

코스·테이스팅, 사용자 구독, 매장 Pro, 광고 상품, 구독자 우선 예약·선공개, POS 실시간 전체 재고 연동은 현재 MVP에 포함되지 않습니다. 관련 정책 문서는 미래 경계를 보존하기 위한 것이며 현재 기능 활성화나 출시 확정을 의미하지 않습니다.

현재 제품 경계의 자세한 기준은 [공식 서비스 정의](docs/service-definition.md)에서 확인할 수 있습니다.

## 기술 방향

현재 기술 문서는 다음 개발·시연 방향을 정의합니다. 저장소에 실제 구조가 생성되기 전까지는 적용 완료 상태로 해석하지 않습니다.

- **백엔드**: Java 21과 Spring Boot 기반의 단일 코드베이스·기본 배포 산출물을 유지하고, Spring Modulith로 도메인 경계를 검증하는 모듈러 모놀리스
- **프론트엔드**: React, TypeScript strict mode와 Vite 기반 SPA
- **데이터 정합성**: MySQL을 확정 거래와 업무 상태의 내구성 원본으로 사용하고, DB 제약·조건부 갱신·원자 연산·멱등성·보상 처리를 조합
- **공유 상태와 확장**: 중앙 세션·임시 선점에는 Valkey, 비동기 이벤트에는 Kafka, 검색에는 OpenSearch를 사용하는 방향
- **운영 확장**: 부하 특성이 다른 API와 작업 영역을 분리하고, 검증된 필요와 비용 조건에 맞춰 AWS 기반 시연·운영 구조를 단계적으로 적용
- **실패 대응**: 외부 결제·알림 장애를 격리하고 중복·순서 역전·인스턴스 재시작을 정상적인 장애 모델로 취급

자세한 기술 선택, 적용 경계와 아직 남은 외부 조건은 [공식 기술 방향](docs/technical-architecture.md)이 정본입니다.

## 문서 지도

제품 정책과 기술 결정의 정본은 이 메인 저장소에 있습니다. Wiki는 상세 설명과 온보딩, 용어 해설, 정본 문서 탐색을 담당합니다.

| 문서 | 역할 |
|---|---|
| [공식 서비스 정의](docs/service-definition.md) | 현재 서비스 구상, 참여자와 1차 MVP 경계 |
| [공식 기술 방향](docs/technical-architecture.md) | 아키텍처 원칙, 도메인 경계와 개발·시연 기술 방향 |
| [서비스 정책 마스터 체크리스트](docs/service-policies/README.md) | 정책별 현재 상태·중요도와 다음 논의 항목의 유일한 색인 |
| [도메인별 상세 정책](docs/service-policies/) | 회원부터 장애 복구까지 18개 영역의 상세 규칙 |
| [서비스 의사결정 기록](miriyum-service-decisions.md) | 확정·변경·보류 이력과 현재 문서 체계 |
| [최초 서비스 블루프린트](miriyum-service-blueprint.md) | 초기 아이디어와 과거 구상 — 현재 정책 정본이 아님 |
| [GitHub Wiki](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/wiki) | 독자별 온보딩, 흐름 해설, 용어와 문서 탐색 |

문서가 충돌하면 정책의 행별 상태·중요도는 정책 마스터 체크리스트를 따릅니다. 정책 내용은 최신 상세 정책과 공식 서비스·기술 문서를 우선하고, 의사결정 기록과 최초 블루프린트는 현재 결론의 맥락과 이력을 확인하는 데 사용합니다.

## 독자별 시작 경로

### 평가자·외부 방문자

1. [프로젝트 개요](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/wiki/Project-Overview)에서 문제, 대상 사용자와 MVP 경계를 확인합니다.
2. [핵심 사용자 여정](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/wiki/Core-User-Journeys)에서 탐색부터 예약·웨이팅·승계까지의 차별점을 살펴봅니다.

### 신규 개발자

1. [개발자 시작 가이드](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/wiki/Getting-Started-for-Developers)에서 현재 단계와 필수 읽기 순서를 확인합니다.
2. [아키텍처 가이드](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/wiki/Architecture-Guide)와 [도메인 가이드](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/wiki/Domain-Guide)로 시스템 경계를 파악합니다.

### 현재 팀원

1. [정책 가이드](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/wiki/Policy-Guide)에서 상태 의미와 정본 우선순위를 확인합니다.
2. [문서 지도](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/wiki/Documentation-Map)와 [팀 워크플로](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/wiki/Team-Workflow)에서 변경할 문서와 검증 근거의 위치를 찾습니다.

## 로드맵

1. **문서·정책 정리** — 현재 단계. 제품 경계, 대표 결정, 상세 정책과 기술 방향을 정합하게 유지합니다.
2. **최소 프로젝트 스캐폴드** — 실행 가능한 백엔드·프론트엔드 구조와 재현 가능한 개발 환경을 만듭니다.
3. **핵심 기능과 검증 게이트** — 탐색, 예약, 웨이팅, 메뉴 홀드와 결제 흐름을 구현하고 정합성·권한·장애 시나리오를 검증합니다.
4. **테스트 완료 후 배포** — 동시성, 부하, 복구와 보안 검증 증거를 갖춘 뒤 시연 환경 배포를 진행합니다.

세부 탐색은 [MiriYum GitHub Wiki Home](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/wiki)에서 시작할 수 있습니다.
