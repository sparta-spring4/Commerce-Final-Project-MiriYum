# MiriYum

MiriYum은 사용자가 식당을 탐색하고 방문 예약·웨이팅·한정 메뉴 확보를 한 흐름으로 경험하며, 식당은 필요한 운영 기능을 선택해 운영할 수 있는 양면 다이닝 플랫폼입니다.

## 현재 단계

현재 승인 기준을 `1차 MVP`, `2차 MVP`, `고도화`, `향후 고도화`의 네 단계로 정렬하고 각 단계의 구현·검증 근거를 준비하는 중입니다. 단계 표기는 기능을 구현했거나 runtime이 구성됐다는 뜻이 아니며, 실제 실행 가능 여부는 검증된 명령과 증거로만 판단합니다.

## 저장소 영역

- `docs/`: 제품, 도메인, 흐름, 아키텍처와 운영의 기준 문서
- `docs/service-policies/`: 상세 서비스 정책의 단일 원본
- `docs/adr/`: 여러 문서에 영향을 주는 현재 유효 아키텍처 결정
- `docs/specs/`: 정본이 연결한 기능별 계약과 인수 조건

`README.md`, `CONTRIBUTING.md`와 `ai/`는 사람·AI의 진입, 라우팅과 증거 경계를 소유하며 제품 사실을 소유하지 않습니다. 문서에 등장하는 계획, 경로 또는 명령만으로 실행 가능한 runtime을 추론하지 않습니다.

## 문서 시작

[프로젝트 지식 색인](docs/00-index.md)에서 읽기 순서와 각 문서의 소유 책임을 확인합니다.

Issue, Pull Request 또는 기여 작업은 [기여 가이드](CONTRIBUTING.md)에서 범위, lifecycle, 검증 증거와 인계 원칙을 확인합니다.

## 활성 정본 allowlist

제품·정책·아키텍처 사실의 기본 읽기 대상은 다음 allowlist로 제한합니다.

1. `docs/00-index.md`부터 `docs/09-quality-operations-and-rules.md`까지의 번호 문서
2. 현재 승인 기준 및 단계와 정렬된 `docs/service-policies/` 정책
3. 상태와 날짜별 개정 이력상 현재 유효한 `docs/adr/` 결정
4. `docs/05-functional-requirements.md` 또는 소유 서비스 정책이 명시적으로 연결한 `docs/specs/<feature>/spec.md`

`docs/specs/_template/` 아래의 기능 명세 템플릿은 정본이 아닙니다. 루트의 과거 종합 문서, `docs/superpowers/`, `.superpowers/sdd/`의 계획·spec·draft·report는 실행 과정 또는 과거 기록이므로 기본 라우팅에서 제외합니다. 현재 정본이 명시적으로 근거를 확인하라고 연결한 경우가 아니면 제품 결정의 근거로 사용하지 않습니다.
