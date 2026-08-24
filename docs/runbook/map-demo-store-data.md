# 지도 시연용 로컬 매장 데이터

## 목적과 범위

지도 시연에 필요한 매장을 로컬 `demo` 프로필에서만 만든다. 운영 migration과 staging,
production 데이터에는 영향을 주지 않는다. `demo` 프로필과
`MIRIYUM_DEMO_MAP_STORES_ENABLED=true`가 함께 있어야만 실행된다.

## 준비되는 데이터

| 매장 | 지역 | 좌표 상태 | 좌표 |
| --- | --- | --- | --- |
| 시연 한강 한식당 | 서울 | VERIFIED | 37.500600, 127.036500 |
| 시연 해운대 식당 | 부산 | VERIFIED | 35.158700, 129.160400 |
| 시연 광주 식당 | 광주 | VERIFIED | 35.146200, 126.922600 |
| 시연 좌표 미검증 매장 | 서울 | UNVERIFIED | 없음 |

모든 매장은 `APPROVED`와 `OPEN` 상태로 생성된다. VERIFIED 매장은 현재 주소 버전에
결합된 `verifiedAddress`, `geocodingVerifiedAt`, `geocodingAddressVersion`을 함께 가진다.

## 실행 방법

로컬 MySQL과 기본 애플리케이션 환경 변수(`MIRIYUM_DB_URL`,
`MIRIYUM_DB_USERNAME`, `MIRIYUM_DB_PASSWORD`, JWT secret 등)를 먼저 준비한다.

PowerShell에서 다음을 실행한다.

```powershell
cd backend
$env:SPRING_PROFILES_ACTIVE = 'demo'
$env:MIRIYUM_DEMO_MAP_STORES_ENABLED = 'true'
.\gradlew.bat bootRun
```

같은 데이터는 사업자등록번호 기준으로 한 번만 저장하므로, 애플리케이션을 다시 시작해도
중복 생성되지 않는다.

## API 검증

애플리케이션을 실행한 다른 PowerShell에서 일반 검색과 통합 검색을 각각 호출한다.

```powershell
$normal = Invoke-RestMethod 'http://localhost:8080/api/v1/stores?keyword=%EC%8B%9C%EC%97%B0&size=20'
$normal.data.items |
  Select-Object name, region, coordinates |
  Format-Table -AutoSize

$integrated = Invoke-RestMethod 'http://localhost:8080/api/v1/stores?searchInput=%EC%8B%9C%EC%97%B0&size=20'
$integrated.data.items |
  Select-Object name, region, coordinates |
  Format-Table -AutoSize
```

두 응답에서 VERIFIED 매장 세 곳은 `coordinates`가 있고, `시연 좌표 미검증 매장`은
`coordinates`가 `null`이어야 한다.

## 제한

- 이 데이터는 프론트의 지도 표시만 돕는다. 이미지, 메뉴, 사업자등록증 증빙은 만들지 않는다.
- `demo` 프로필 또는 enable 값이 없으면 seed runner가 등록되지 않는다.
- staging/production에서 같은 시연이 필요하면 별도 Issue와 운영 데이터 승인 절차를 거친다.
