# MVP2 릴리스 계약 보정 설계

## 목표

MVP2의 공개 메뉴 대안 검색 API가 기능별 OpenAPI뿐 아니라 공개 audience 진입점과 전체 aggregate 진입점에서도 발견되도록 보정한다. 지오코딩 마이그레이션 테스트의 표시명은 실제 버전 V29와 일치시킨다.

## 설계

- `public-openapi.yaml`은 메뉴 대안 검색 path item을 `store-search/openapi.yaml`에서 단일 `$ref`로 가져온다.
- `mvp1-openapi.yaml`은 같은 경로를 `public-openapi.yaml`에서 단일 `$ref`로 가져온다.
- `AudienceOpenApiContractTest`는 기능별 공개 경로가 public/aggregate 진입점에 모두 포함되는지 검증해 같은 누락을 방지한다.
- 런타임 API, schema, 생성 TypeScript 클라이언트는 이미 정합하므로 변경하지 않는다.
- `StoreGeocodingMigrationTest`의 두 표시명에서 V23을 V29로 바꾼다. 테스트 동작은 변경하지 않는다.

## 검증

1. 계약 테스트를 먼저 추가하고 기존 명세에서 실패하는지 확인한다.
2. 두 진입점에 `$ref`를 추가한 뒤 계약 테스트를 다시 실행한다.
3. 전체 단위 테스트와 OpenAPI 생성, 프론트 타입 검사·테스트·빌드를 실행한다.

## 범위 제외

- API 런타임 동작 변경
- schema 또는 생성 클라이언트 수동 편집
- Flyway migration 내용 변경
