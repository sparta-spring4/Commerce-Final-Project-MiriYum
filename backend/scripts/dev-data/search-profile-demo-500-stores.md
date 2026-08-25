# 검색 프로필 합성 데이터 500개 매장

Issue #653의 로컬·dev 전용 합성 검색 데이터다. 실제 사용자나 실제 매장 정보를 포함하지 않으며 production에서는 실행하지 않는다.

## 생성 범위

- 실제 상호처럼 읽히는 고유 매장명 500개
- 서울·부산·대구·대전·광주 각 100개와 검증 좌표
- 매장별 운영자 계정, 태그, 주 7일 영업시간과 예약 가능 시간
- 매장당 current published visible 메뉴 10개, 총 5,000개
- 메뉴별 다음 날 12:00~14:00 재고 bucket 1개
- 메뉴 버전별 `food-profile-v1` 검색 프로필과 메뉴 계열·별칭·재료·맛·국물·조리법·향·식감·형태 term

상호는 지역명과 업종별 브랜드를 결합한다. 예시는 `성수 화로정`, `부산 해운대 바다국수`, `대구 동성로 마라공방`, `대전 둔산 스시하루`, `광주 양림 모닝브루`다. 매장명과 메뉴명에는 `테스트`, `더미` 또는 단순 순번을 사용하지 않는다.

## 사전 조건과 실행

MySQL 8.0.40 이상과 Flyway V72 적용이 필요하다. 저장소 루트에서 환경 변수로 DB 접속값을 준비한 뒤 MySQL client로 실행한다. 비밀번호는 명령행이나 파일에 직접 기록하지 않는다.

```bash
mysql --host=<host> --user=<user> --password <database> \
  < backend/scripts/dev-data/search-profile-demo-500-stores.sql
```

스크립트는 예약 ID 범위와 결정적인 유니크키를 사용하고 같은 데이터로 upsert하므로 두 번 실행해도 행 수가 증가하지 않는다. 영구 테이블에 쓰기 전에 전체 fixture manifest를 임시 테이블로 만들고 아래 충돌을 모두 검사한다.

- 운영자·매장·제재 상태·일정 버전: `8,900,001..8,900,500`
- 메뉴·메뉴 버전: `89,000,001..89,005,000`
- 재고 bucket: `189,000,001..189,005,000`
- 검색 프로필: `99,000,001..99,005,000`
- 검색 term 예약 범위: `990,000,011..990,050,009`
- 운영자 이메일, 사업자등록번호, 일정의 매장·버전 및 적용 시각, 메뉴 버전, 재고 business key, 검색 프로필·term 유니크키

이미 존재하는 행은 예약 ID뿐 아니라 예상 운영자·매장·메뉴·버전 등 소유 관계와 결정적인 fixture 식별자가 모두 일치할 때만 재실행 대상으로 인정한다. 같은 ID를 다른 데이터가 쓰거나 생성 예정 유니크키를 다른 ID가 쓰면 첫 영구 `INSERT` 전에 `SQLSTATE 45000`으로 중단한다. 이메일·검색 term·로컬 tag는 각 실제 유니크 인덱스의 collation을 기준으로 검사하므로 대소문자나 악센트만 달라도 DB가 같은 키로 판단하는 값은 충돌로 차단한다. 따라서 기존 행을 덮어쓰거나 fixture 일부만 삽입하지 않는다. 충돌 메시지에 나온 테이블의 기존 데이터를 먼저 확인하고, ID를 임의로 변경하거나 충돌 행을 삭제하지 말고 대상 DB와 데이터 소유자를 확인한다.

## 검증 쿼리

```sql
SELECT COUNT(*) AS stores,
       COUNT(DISTINCT name) AS distinct_store_names,
       SUM(geocoding_status = 'VERIFIED') AS verified_coordinates
FROM stores
WHERE store_id BETWEEN 8900001 AND 8900500;

SELECT COUNT(*) AS menus
FROM menus
WHERE menu_id BETWEEN 89000001 AND 89005000
  AND visibility = 'VISIBLE'
  AND retired = FALSE;

SELECT COUNT(*) AS profiles
FROM menu_search_profiles
WHERE menu_search_profile_id BETWEEN 99000001 AND 99005000;

SELECT dimension, COUNT(*) AS terms
FROM menu_search_profile_terms
WHERE menu_search_profile_id BETWEEN 99000001 AND 99005000
GROUP BY dimension
ORDER BY dimension;

SELECT COUNT(*) AS stores_without_schedule
FROM stores store_row
LEFT JOIN store_schedule_state schedule_state
  ON schedule_state.store_id = store_row.store_id
WHERE store_row.store_id BETWEEN 8900001 AND 8900500
  AND (schedule_state.active_operating_schedule_version_id IS NULL
       OR schedule_state.active_reservation_schedule_version_id IS NULL);

SELECT COUNT(*) AS menus_without_inventory
FROM menus menu_row
LEFT JOIN menu_inventory_buckets bucket ON bucket.menu_id = menu_row.menu_id
WHERE menu_row.menu_id BETWEEN 89000001 AND 89005000
  AND bucket.menu_inventory_bucket_id IS NULL;
```

정상값은 매장·고유 매장명·검증 좌표 각각 500, 메뉴·프로필·재고 각각 5,000, 일정 누락과 재고 누락 각각 0이다.

## 정리

FK 순서가 많으므로 자동 삭제 SQL은 제공하지 않는다. 삭제가 필요하면 대상 DB가 local/dev인지 다시 확인한 뒤 별도 승인된 정리 작업으로 수행한다.
