# 로컬 지도 매장 데이터

## 목적과 범위

지도 확인에 필요한 매장을 로컬 `demo` 프로필에서만 만든다. 운영 migration과 staging,
production 데이터에는 영향을 주지 않는다. `demo` 프로필과
`MIRIYUM_DEMO_MAP_STORES_ENABLED=true`가 함께 있어야만 실행된다.

## Staging 시연

staging에서는 `MIRIYUM_STAGING_MAP_DEMO_STORES_ENABLED=true`를 `/opt/miriyum/.env`에 명시하고,
같은 immutable backend SHA로 CD를 다시 실행할 때만 seed runner가 실행된다. 이 설정은
`MIRIYUM_RUNTIME_ENVIRONMENT=staging`일 때만 등록되므로 production runtime에서는 실행되지 않는다.

시연 후에는 해당 값을 `false`로 되돌리고 같은 SHA를 다시 배포한다. 생성 데이터는 사업자등록번호
`9000000001`부터 `9000000006`까지이므로, 정리가 필요하면 staging DB에서 이 번호만 대상으로 삭제한다.

## 준비되는 데이터

| 매장 | 지역 | 좌표 상태 | 좌표 |
| --- | --- | --- | --- |
| 마루 한식당 | 서울 | VERIFIED | 37.500600, 127.036500 |
| 해운대 바다식당 | 부산 | VERIFIED | 35.158700, 129.160400 |
| 동성로 한상 | 대구 | VERIFIED | 35.869400, 128.594000 |
| 중앙로 식탁 | 대전 | VERIFIED | 36.328700, 127.428000 |
| 무등 한상 | 광주 | VERIFIED | 35.146200, 126.922600 |
| 새봄 식당 | 서울 | UNVERIFIED | 없음 |

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

애플리케이션을 실행한 다른 PowerShell에서 각 매장명을 일반 검색과 통합 검색으로
조회한다. VERIFIED 다섯 건의 좌표와 UNVERIFIED 한 건의 `coordinates=null`을 같은 절차에서 확인할 수 있다.

```powershell
$storeNames = @('마루 한식당', '해운대 바다식당', '동성로 한상', '중앙로 식탁', '무등 한상', '새봄 식당')

$storeNames | ForEach-Object {
  $storeName = $_
  $keyword = [uri]::EscapeDataString($storeName)
  $normal = Invoke-RestMethod "http://localhost:8080/api/v1/stores?keyword=$keyword&size=20"
  $integrated = Invoke-RestMethod "http://localhost:8080/api/v1/stores?searchInput=$keyword&size=20"

  Write-Host "[일반 검색] $storeName"
  $normal.data.items |
    Where-Object name -eq $storeName |
    Select-Object name, region, coordinates |
    Format-Table -AutoSize

  Write-Host "[통합 검색] $storeName"
  $integrated.data.items |
    Where-Object name -eq $storeName |
    Select-Object name, region, coordinates |
    Format-Table -AutoSize
}
```

각 `normal`과 `integrated` 결과에서 VERIFIED 매장 다섯 곳은 표의 좌표와 일치해야 하며,
`새봄 식당`은 `coordinates`가 `null`이어야 한다.

## 제한

- 이 데이터는 프론트의 지도 표시만 돕는다. 이미지, 메뉴, 사업자등록증 증빙은 만들지 않는다.
- `demo` 프로필 또는 enable 값이 없으면 seed runner가 등록되지 않는다.
- staging 시연은 명시적 flag와 같은 SHA 재배포가 있어야 하며, production에서는 실행되지 않는다.
