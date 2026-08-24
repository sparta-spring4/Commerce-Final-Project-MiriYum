-- Local/dev synthetic search corpus for Issue #653.
-- MySQL 8.0.40+, Flyway through V72 required. Never run in production.

DROP PROCEDURE IF EXISTS seed_search_profile_demo_500_stores;
DELIMITER $$

CREATE PROCEDURE seed_search_profile_demo_500_stores()
BEGIN
    DECLARE fixture_number INT DEFAULT 1;
    DECLARE fixture_region_index INT;
    DECLARE fixture_neighborhood_index INT;
    DECLARE fixture_brand_index INT;
    DECLARE fixture_region VARCHAR(20);
    DECLARE fixture_city VARCHAR(20);
    DECLARE fixture_neighborhood VARCHAR(30);
    DECLARE fixture_brand VARCHAR(30);
    DECLARE fixture_category VARCHAR(50);

    IF EXISTS (
        SELECT 1
        FROM stores
        WHERE store_id BETWEEN 8900001 AND 8900500
          AND business_registration_number NOT LIKE '89%'
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'reserved store id range 8900001..8900500 is occupied';
    END IF;

    CREATE TEMPORARY TABLE IF NOT EXISTS search_fixture_stores (
        fixture_number INT NOT NULL PRIMARY KEY,
        operator_id BIGINT NOT NULL,
        store_id BIGINT NOT NULL,
        region VARCHAR(20) NOT NULL,
        city VARCHAR(20) NOT NULL,
        neighborhood VARCHAR(30) NOT NULL,
        brand VARCHAR(30) NOT NULL,
        category_code VARCHAR(50) NOT NULL,
        store_name VARCHAR(100) NOT NULL,
        address VARCHAR(300) NOT NULL,
        latitude DECIMAL(18, 15) NOT NULL,
        longitude DECIMAL(18, 15) NOT NULL,
        UNIQUE KEY uk_search_fixture_store_id (store_id),
        UNIQUE KEY uk_search_fixture_store_name (store_name)
    );
    DELETE FROM search_fixture_stores;

    WHILE fixture_number <= 500 DO
        SET fixture_region_index = FLOOR((fixture_number - 1) / 100);
        SET fixture_neighborhood_index = FLOOR(MOD(fixture_number - 1, 100) / 10);
        SET fixture_brand_index = MOD(fixture_number - 1, 10);
        SET fixture_region = ELT(
                fixture_region_index + 1,
                'SEOUL', 'BUSAN', 'DAEGU', 'DAEJEON', 'GWANGJU');
        SET fixture_city = ELT(
                fixture_region_index + 1,
                '서울특별시', '부산광역시', '대구광역시', '대전광역시', '광주광역시');
        SET fixture_neighborhood = CASE fixture_region_index
            WHEN 0 THEN ELT(fixture_neighborhood_index + 1,
                    '강남', '성수', '연남', '서촌', '잠실',
                    '망원', '을지로', '한남', '여의도', '합정')
            WHEN 1 THEN ELT(fixture_neighborhood_index + 1,
                    '해운대', '광안리', '서면', '남포', '송정',
                    '동래', '전포', '영도', '기장', '센텀')
            WHEN 2 THEN ELT(fixture_neighborhood_index + 1,
                    '동성로', '수성못', '앞산', '칠성', '범어',
                    '반월당', '대봉', '성서', '월성', '팔공산')
            WHEN 3 THEN ELT(fixture_neighborhood_index + 1,
                    '둔산', '은행', '유성', '도안', '관저',
                    '대흥', '탄방', '노은', '송촌', '문지')
            ELSE ELT(fixture_neighborhood_index + 1,
                    '충장로', '상무', '양림', '수완', '첨단',
                    '동명', '봉선', '운암', '금남로', '송정')
        END;
        SET fixture_brand = ELT(
                fixture_brand_index + 1,
                '화로정', '바다국수', '마라공방', '홍등루', '스시하루',
                '우동소리', '오븐테이블', '키친로제', '사이공정원', '모닝브루');
        SET fixture_category = ELT(
                fixture_brand_index + 1,
                'KOREAN', 'KOREAN', 'CHINESE', 'CHINESE', 'JAPANESE',
                'JAPANESE', 'WESTERN', 'WESTERN', 'ASIAN', 'CAFE_BAKERY');

        INSERT INTO search_fixture_stores (
            fixture_number, operator_id, store_id, region, city,
            neighborhood, brand, category_code, store_name, address,
            latitude, longitude
        ) VALUES (
            fixture_number,
            8900000 + fixture_number,
            8900000 + fixture_number,
            fixture_region,
            fixture_city,
            fixture_neighborhood,
            fixture_brand,
            fixture_category,
            CONCAT(
                    ELT(fixture_region_index + 1, '', '부산 ', '대구 ', '대전 ', '광주 '),
                    fixture_neighborhood, ' ', fixture_brand),
            CONCAT(fixture_city, ' ', fixture_neighborhood, ' 맛길 ',
                    10 + fixture_neighborhood_index, '-', fixture_brand_index + 1),
            ELT(fixture_region_index + 1,
                    37.566500000000000, 35.179600000000000, 35.871400000000000,
                    36.350400000000000, 35.159500000000000)
                + fixture_neighborhood_index / 1000
                + fixture_brand_index / 100000,
            ELT(fixture_region_index + 1,
                    126.978000000000000, 129.075600000000000, 128.601400000000000,
                    127.384500000000000, 126.852600000000000)
                + fixture_neighborhood_index / 1000
                + fixture_brand_index / 100000
        );
        SET fixture_number = fixture_number + 1;
    END WHILE;

    CREATE TEMPORARY TABLE IF NOT EXISTS search_fixture_menu_templates (
        category_code VARCHAR(50) NOT NULL,
        menu_slot INT NOT NULL,
        menu_name VARCHAR(100) NOT NULL,
        description VARCHAR(1000) NOT NULL,
        price INT NOT NULL,
        primary_category_code VARCHAR(50) NOT NULL,
        menu_family VARCHAR(60) NOT NULL,
        alias_term VARCHAR(60) NULL,
        ingredient VARCHAR(60) NULL,
        taste VARCHAR(60) NULL,
        broth VARCHAR(60) NULL,
        method VARCHAR(60) NULL,
        aroma VARCHAR(60) NULL,
        texture_term VARCHAR(60) NULL,
        form_term VARCHAR(60) NULL,
        PRIMARY KEY (category_code, menu_slot)
    );
    DELETE FROM search_fixture_menu_templates;

    INSERT INTO search_fixture_menu_templates VALUES
        ('KOREAN',1,'불향 제육볶음','직화로 볶아 불향과 매콤함을 살린 돼지고기 요리',13000,'MEAT','제육볶음','제육','돼지고기','매콤한',NULL,'볶음','불향','부드러운',NULL),
        ('KOREAN',2,'들깨 뼈해장국','돼지뼈를 오래 끓이고 들깨향을 더한 진한 국물',12000,'SOUP_STEW','뼈해장국','뼈다귀 해장국','돼지뼈','진한','국물','끓임','들깨향','부드러운','탕'),
        ('KOREAN',3,'멸치 잔치국수','멸치 국물에 쫄깃한 면을 담은 따뜻한 국수',9000,'NOODLE','잔치국수','멸치국수','멸치','담백한','국물','끓임','구수한 향','쫄깃한','면'),
        ('KOREAN',4,'직화 소불고기','소고기를 직화로 구워 달콤하고 부드럽게 완성',17000,'MEAT','불고기','소불고기','소고기','달콤한',NULL,'직화','불향','부드러운',NULL),
        ('KOREAN',5,'묵은지 김치찌개','묵은지와 돼지고기를 끓인 칼칼한 국물',11000,'SOUP_STEW','김치찌개','김치찌게','김치','칼칼한','국물','끓임','구수한 향','부드러운','국'),
        ('KOREAN',6,'바지락 순두부찌개','해물과 몽글한 순두부가 어우러진 얼큰한 찌개',11000,'SOUP_STEW','순두부찌개','순두부','해물','얼큰한','국물','끓임','해산물향','몽글한','국'),
        ('KOREAN',7,'한방 갈비탕','소갈비를 푹 삶아 담백하고 진한 국물',16000,'SOUP_STEW','갈비탕','갈비 탕','소갈비','담백한','국물','삶기','구수한 향','부드러운','탕'),
        ('KOREAN',8,'들깨 감자탕','돼지뼈와 들깨를 넣어 자작하게 끓인 감자탕',15000,'SOUP_STEW','감자탕','감자 탕','돼지뼈','진한','자작','끓임','들깨향','부드러운','탕'),
        ('KOREAN',9,'산채 비빔밥','나물과 고슬한 밥을 고소하게 비벼 먹는 한 그릇',11000,'RICE','비빔밥','비빔 밥','나물','고소한',NULL,'비빔','참기름향','고슬한','밥'),
        ('KOREAN',10,'들깨 손수제비','손수 만든 수제비를 들깨 국물에 끓인 메뉴',10000,'NOODLE','수제비','손수제비','밀가루','고소한','국물','수제','들깨향','쫄깃한',NULL),
        ('CHINESE',1,'불향 해물 짬뽕','해물과 채소를 직화로 볶아 칼칼하게 끓인 국물면',13000,'NOODLE','짬뽕','옛날 짬뽕','해물','칼칼한','국물','직화','불향','쫄깃한','면'),
        ('CHINESE',2,'옛날 짜장면','춘장을 진하게 볶아 쫄깃한 면에 담은 짜장면',9000,'NOODLE','짜장면','자장면','춘장','진한',NULL,'볶음','구수한 향','쫄깃한','면'),
        ('CHINESE',3,'찹쌀 탕수육','돼지고기를 바삭하게 튀겨 새콤달콤한 소스와 제공',19000,'MEAT','탕수육',NULL,'돼지고기','새콤달콤한',NULL,'튀김',NULL,'바삭한',NULL),
        ('CHINESE',4,'마늘 깐풍기','닭고기를 튀기고 마늘향 소스로 볶은 매콤한 요리',20000,'MEAT','깐풍기','깐풍치킨','닭고기','매콤한',NULL,'튀김','마늘향','바삭한',NULL),
        ('CHINESE',5,'얼얼 마라탕','향신료와 채소를 넣어 얼얼하고 진하게 끓인 국물',14000,'SOUP_STEW','마라탕','마라 탕','향신료','얼얼한','국물','끓임','고추향','아삭한','탕'),
        ('CHINESE',6,'새우 볶음밥','해물과 고슬한 밥을 불향 나게 볶은 한 그릇',11000,'RICE','볶음밥','볶음 밥','해물','고소한',NULL,'볶음','불향','고슬한','밥'),
        ('CHINESE',7,'매운 해물 짬뽕','해산물을 듬뿍 넣어 매운맛을 살린 국물면',14000,'NOODLE','짬뽕','해물짬뽕','해산물','매운','국물','끓임','해산물향','쫄깃한','면'),
        ('CHINESE',8,'사천 제육볶음','돼지고기와 향신료를 매콤하게 볶은 요리',15000,'MEAT','제육볶음','제육','돼지고기','매콤한',NULL,'볶음','고추향','쫄깃한',NULL),
        ('CHINESE',9,'마라 볶음면','향신료와 쫄깃한 면을 얼얼하게 볶은 메뉴',12000,'NOODLE','마라탕','마라 탕','향신료','얼얼한',NULL,'볶음','고추향','쫄깃한','면'),
        ('CHINESE',10,'바삭 닭강정','닭고기를 바삭하게 튀겨 달콤한 소스에 버무린 요리',18000,'MEAT','닭강정','강정치킨','닭고기','달콤한',NULL,'튀김',NULL,'바삭한',NULL),
        ('JAPANESE',1,'모둠 초밥','신선한 생선과 고슬한 밥으로 만든 모둠 초밥',18000,'SEAFOOD','초밥','스시','생선','담백한',NULL,'생식',NULL,'부드러운','밥'),
        ('JAPANESE',2,'돈코츠 라멘','돼지고기 육수를 진하게 끓여 쫄깃한 면과 제공',13000,'NOODLE','라멘','일본라면','돼지고기','진한','국물','끓임','구수한 향','쫄깃한','면'),
        ('JAPANESE',3,'가쓰오 우동','담백한 국물과 매끈한 면이 어우러진 우동',10000,'NOODLE','우동','가락국수','멸치','담백한','국물','끓임','구수한 향','매끈한','면'),
        ('JAPANESE',4,'등심 돈가스','두툼한 돼지고기를 바삭하게 튀긴 돈가스',15000,'MEAT','돈가스','돈까스','돼지고기','고소한',NULL,'튀김',NULL,'바삭한',NULL),
        ('JAPANESE',5,'연어 비빔밥','생연어와 채소를 상큼하게 비벼 먹는 밥',16000,'RICE','비빔밥','비빔 밥','생선','상큼한',NULL,'비빔','참기름향','부드러운','밥'),
        ('JAPANESE',6,'메밀 냉면','메밀면을 차가운 국물에 담아 산뜻하게 제공',11000,'NOODLE','냉면','물냉면','메밀','담백한','국물','냉조리',NULL,'쫄깃한','면'),
        ('JAPANESE',7,'간장 치킨','닭고기를 바삭하게 튀겨 짭짤한 간장 소스와 제공',17000,'MEAT','치킨','후라이드치킨','닭고기','짭짤한',NULL,'튀김',NULL,'바삭한',NULL),
        ('JAPANESE',8,'새우 튀김우동','해물 튀김과 담백한 국물을 함께 즐기는 우동',13000,'NOODLE','우동','가락국수','해물','담백한','국물','튀김','해산물향','바삭한','면'),
        ('JAPANESE',9,'직화 소불고기 덮밥','소고기를 직화로 구워 고슬한 밥 위에 담은 메뉴',15000,'RICE','불고기','소불고기','소고기','달콤한',NULL,'직화','불향','부드러운','밥'),
        ('JAPANESE',10,'일본식 카레라이스','카레를 진하게 끓여 고슬한 밥과 제공',12000,'RICE','카레라이스','카레밥','카레','진한',NULL,'끓임','향긋한','부드러운','밥'),
        ('WESTERN',1,'트러플 크림 파스타','진한 크림과 향긋한 허브향을 살린 꾸덕한 파스타',18000,'NOODLE','파스타','스파게티','밀','진한',NULL,'볶음','허브향','꾸덕한','면'),
        ('WESTERN',2,'토마토 해산물 파스타','해산물과 토마토를 새콤하게 볶은 쫄깃한 파스타',19000,'NOODLE','파스타','스파게티','해산물','새콤한',NULL,'볶음','해산물향','쫄깃한','면'),
        ('WESTERN',3,'고르곤졸라 피자','치즈를 듬뿍 올려 고소하고 바삭하게 구운 피자',20000,'PIZZA_BURGER_SANDWICH','피자','피짜','치즈','고소한',NULL,'굽기',NULL,'바삭한',NULL),
        ('WESTERN',4,'수제 치즈버거','소고기 패티와 치즈를 넣은 육즙 가득 수제 버거',15000,'PIZZA_BURGER_SANDWICH','햄버거','버거','소고기','진한',NULL,'수제','훈연향','촉촉한',NULL),
        ('WESTERN',5,'바삭 등심 돈가스','두툼한 돼지고기를 바삭하게 튀겨 소스와 제공',16000,'MEAT','돈가스','돈까스','돼지고기','고소한',NULL,'튀김',NULL,'바삭한',NULL),
        ('WESTERN',6,'버섯 함박스테이크','소고기 함박을 부드럽게 구워 진한 버섯 소스와 제공',18000,'MEAT','함박스테이크','함박','소고기','진한',NULL,'구이','허브향','부드러운',NULL),
        ('WESTERN',7,'리코타 채소 샐러드','신선한 채소와 치즈를 상큼하게 버무린 샐러드',14000,'ETC','샐러드','야채샐러드','채소','상큼한',NULL,'비빔','허브향','아삭한',NULL),
        ('WESTERN',8,'폭신한 팬케이크','폭신하게 구운 팬케이크에 달콤한 소스를 곁들인 메뉴',13000,'DESSERT','팬케이크','핫케이크','밀가루','달콤한',NULL,'굽기',NULL,'폭신한',NULL),
        ('WESTERN',9,'허브 치킨스테이크','닭고기를 촉촉하게 구워 허브향을 더한 스테이크',18000,'MEAT','치킨','후라이드치킨','닭고기','담백한',NULL,'구이','허브향','촉촉한',NULL),
        ('WESTERN',10,'치즈 볶음밥','치즈와 고슬한 밥을 고소하게 볶은 리조또 스타일 메뉴',16000,'RICE','볶음밥','볶음 밥','치즈','고소한',NULL,'볶음','허브향','고슬한','밥'),
        ('ASIAN',1,'소고기 쌀국수','소고기와 쌀면을 진한 국물에 담은 쌀국수',13000,'NOODLE','쌀국수','포','소고기','진한','국물','끓임','향긋한','매끈한','면'),
        ('ASIAN',2,'새우 팟타이','해물과 쌀면을 새콤달콤하게 볶은 태국식 면요리',14000,'NOODLE','팟타이','태국볶음면','해물','새콤달콤한',NULL,'볶음','파향','쫄깃한','면'),
        ('ASIAN',3,'채소 월남쌈','아삭한 채소를 쌀피에 말아 상큼하게 즐기는 메뉴',16000,'ETC','월남쌈','베트남쌈','채소','상큼한',NULL,'말이','허브향','아삭한',NULL),
        ('ASIAN',4,'얼얼 마라탕','향신료와 채소를 얼얼한 국물에 끓인 메뉴',14000,'SOUP_STEW','마라탕','마라 탕','향신료','얼얼한','국물','끓임','고추향','아삭한','탕'),
        ('ASIAN',5,'해산물 쌀국수','해산물향이 풍부한 칼칼한 국물 쌀국수',15000,'NOODLE','쌀국수','포','해산물','칼칼한','국물','끓임','해산물향','쫄깃한','면'),
        ('ASIAN',6,'향신료 볶음밥','향신료와 고슬한 밥을 불향 나게 볶은 한 그릇',13000,'RICE','볶음밥','볶음 밥','향신료','매콤한',NULL,'볶음','불향','고슬한','밥'),
        ('ASIAN',7,'소고기 샤브샤브','소고기와 채소를 담백한 국물에 데쳐 먹는 메뉴',20000,'SOUP_STEW','샤브샤브','샤브','소고기','담백한','국물','데침','향긋한','부드러운','탕'),
        ('ASIAN',8,'치킨 카레라이스','닭고기와 카레를 진하게 끓여 밥과 제공',14000,'RICE','카레라이스','카레밥','닭고기','진한',NULL,'끓임','향긋한','부드러운','밥'),
        ('ASIAN',9,'직화 쭈꾸미볶음','쭈꾸미를 직화로 매콤하게 볶아 불향을 살린 메뉴',17000,'SEAFOOD','쭈꾸미볶음','주꾸미볶음','쭈꾸미','매콤한',NULL,'직화','불향','쫄깃한',NULL),
        ('ASIAN',10,'바삭 치킨 샌드위치','닭고기와 아삭한 채소를 넣은 바삭한 샌드위치',13000,'PIZZA_BURGER_SANDWICH','치킨','후라이드치킨','닭고기','고소한',NULL,'튀김','허브향','바삭한',NULL),
        ('CAFE_BAKERY',1,'시그니처 아메리카노','고소한 원두를 추출해 향긋하게 즐기는 커피',5000,'BEVERAGE','아메리카노','아이스 아메리카노','원두','고소한',NULL,'추출','곡물향','가벼운',NULL),
        ('CAFE_BAKERY',2,'바닐라 크림 라떼','원두와 우유에 달콤한 바닐라 크림을 더한 음료',6500,'BEVERAGE','아메리카노','아아','원두','달콤한',NULL,'추출','곡물향','부드러운',NULL),
        ('CAFE_BAKERY',3,'폭신 수플레 팬케이크','폭신하고 촉촉하게 구운 달콤한 팬케이크',14000,'DESSERT','팬케이크','핫케이크','밀가루','달콤한',NULL,'굽기',NULL,'폭신한',NULL),
        ('CAFE_BAKERY',4,'치즈 브런치 샌드위치','치즈와 아삭한 채소를 넣은 고소한 샌드위치',11000,'PIZZA_BURGER_SANDWICH','햄버거','버거','치즈','고소한',NULL,'굽기','허브향','아삭한',NULL),
        ('CAFE_BAKERY',5,'리코타 샐러드','채소와 치즈를 상큼하게 버무린 가벼운 샐러드',12000,'ETC','샐러드','야채샐러드','채소','상큼한',NULL,'비빔','허브향','아삭한',NULL),
        ('CAFE_BAKERY',6,'꾸덕 초콜릿 케이크','꾸덕하고 달콤한 초콜릿 풍미의 디저트',7500,'DESSERT','팬케이크','핫케이크','밀가루','달콤한',NULL,'굽기',NULL,'꾸덕한',NULL),
        ('CAFE_BAKERY',7,'버터 크루아상','버터향을 살려 바삭하게 구운 베이커리',4800,'BAKERY','팬케이크','핫케이크','밀가루','고소한',NULL,'굽기','곡물향','바삭한',NULL),
        ('CAFE_BAKERY',8,'상큼 레몬 에이드','레몬향과 탄산을 더해 상큼하게 만든 음료',6000,'BEVERAGE','아메리카노','아아','레몬향','상큼한',NULL,'추출','레몬향','가벼운',NULL),
        ('CAFE_BAKERY',9,'수제 치즈버거','소고기와 치즈를 넣어 촉촉하게 구운 수제 버거',15000,'PIZZA_BURGER_SANDWICH','햄버거','버거','소고기','고소한',NULL,'수제','훈연향','촉촉한',NULL),
        ('CAFE_BAKERY',10,'브런치 고르곤졸라 피자','치즈를 듬뿍 올려 바삭하게 구운 브런치 피자',17000,'PIZZA_BURGER_SANDWICH','피자','피짜','치즈','고소한',NULL,'굽기',NULL,'바삭한',NULL);

    INSERT INTO store_operator_accounts (
        store_operator_account_id, email, password_hash, phone, display_name,
        status, password_reset_required, support_version, created_at, updated_at
    )
    SELECT fixture.operator_id,
           CONCAT('search-fixture-', LPAD(fixture.fixture_number, 4, '0'), '@example.invalid'),
           NULL, NULL, CONCAT(fixture.neighborhood, ' ', fixture.brand, ' 운영자'),
           'ACTIVE', FALSE, 0, '2026-08-25 00:00:00', '2026-08-25 00:00:00'
    FROM search_fixture_stores fixture
    ON DUPLICATE KEY UPDATE
        display_name = VALUES(display_name), updated_at = VALUES(updated_at);

    INSERT INTO stores (
        store_id, store_operator_account_id, business_registration_number,
        business_type, name, description, region, address, time_zone_id,
        dashboard_authority_version, address_version, geocoding_status,
        latitude, longitude, verified_address, geocoding_verified_at,
        geocoding_address_version, store_category_code, verification_status,
        operation_status, reservation_enabled, menu_hold_enabled, pickup_enabled,
        platform_management_allowed, applicant_self_attested_at,
        required_terms_agreed_at, required_terms_version, created_at, updated_at
    )
    SELECT fixture.store_id, fixture.operator_id,
           CONCAT('89', LPAD(fixture.fixture_number, 8, '0')),
           IF(fixture.category_code = 'CAFE_BAKERY', 'CAFE', 'OTHER'),
           fixture.store_name,
           CONCAT(fixture.neighborhood, '에서 제철 재료와 정성스러운 조리법을 선보이는 ', fixture.brand),
           fixture.region, fixture.address, 'Asia/Seoul', 1, 1, 'VERIFIED',
           fixture.latitude, fixture.longitude, fixture.address,
           '2026-08-25 00:00:00', 1,
           fixture.category_code, 'APPROVED', 'OPEN', TRUE, TRUE, TRUE, TRUE,
           '2026-08-25 00:00:00', '2026-08-25 00:00:00',
           'STORE_ONBOARDING_REQUIRED_TERMS_V1',
           '2026-08-25 00:00:00', '2026-08-25 00:00:00'
    FROM search_fixture_stores fixture
    ON DUPLICATE KEY UPDATE
        name = VALUES(name), description = VALUES(description),
        address = VALUES(address), latitude = VALUES(latitude),
        longitude = VALUES(longitude), verified_address = VALUES(verified_address),
        updated_at = VALUES(updated_at);

    INSERT INTO store_tag_assignment (store_id, tag_code)
    SELECT fixture.store_id,
           ELT(MOD(fixture.fixture_number - 1, 8) + 1,
               'DATE','QUIET','GROUP','SOLO','FAMILY','VEGAN_OPTION','ALLERGY_INFO','PET_FRIENDLY')
    FROM search_fixture_stores fixture
    ON DUPLICATE KEY UPDATE tag_code = VALUES(tag_code);

    INSERT INTO store_enforcement_states (
        store_enforcement_state_id, store_id, enforcement_version,
        base_operation_status, base_reservation_enabled, base_menu_hold_enabled,
        base_pickup_enabled, waiting_allowed, store_management_allowed,
        active_enforcements, created_at, updated_at
    )
    SELECT store_id, store_id, 0, 'OPEN', TRUE, TRUE, TRUE, TRUE, TRUE,
           JSON_OBJECT(), '2026-08-25 00:00:00', '2026-08-25 00:00:00'
    FROM search_fixture_stores
    ON DUPLICATE KEY UPDATE updated_at = VALUES(updated_at);

    INSERT INTO store_operating_schedule_versions (
        operating_schedule_version_id, store_id, version_number, status,
        time_zone_id, effective_at, activated_at, change_reason,
        conflict_check_status, conflict_count, created_at, updated_at
    )
    SELECT store_id, store_id, 1, 'ACTIVE', 'Asia/Seoul',
           '2026-08-25 00:00:00', '2026-08-25 00:00:00',
           '합성 검색 데이터 영업시간', 'NOT_EVALUATED', NULL,
           '2026-08-25 00:00:00', '2026-08-25 00:00:00'
    FROM search_fixture_stores
    ON DUPLICATE KEY UPDATE activated_at = VALUES(activated_at);

    INSERT INTO store_reservation_schedule_versions (
        reservation_schedule_version_id, store_id, version_number,
        validated_operating_version_id, status, time_zone_id, effective_at,
        activated_at, change_reason, conflict_check_status, conflict_count,
        created_at, updated_at
    )
    SELECT store_id, store_id, 1, store_id, 'ACTIVE', 'Asia/Seoul',
           '2026-08-25 00:00:00', '2026-08-25 00:00:00',
           '합성 검색 데이터 예약시간', 'NOT_EVALUATED', NULL,
           '2026-08-25 00:00:00', '2026-08-25 00:00:00'
    FROM search_fixture_stores
    ON DUPLICATE KEY UPDATE activated_at = VALUES(activated_at);

    CREATE TEMPORARY TABLE IF NOT EXISTS search_fixture_days (
        day_index INT NOT NULL PRIMARY KEY,
        day_name VARCHAR(10) NOT NULL
    );
    DELETE FROM search_fixture_days;
    INSERT INTO search_fixture_days VALUES
        (0,'MONDAY'),(1,'TUESDAY'),(2,'WEDNESDAY'),(3,'THURSDAY'),
        (4,'FRIDAY'),(5,'SATURDAY'),(6,'SUNDAY');

    INSERT INTO store_operating_schedule_entries (
        operating_schedule_version_id, entry_order, day_of_week,
        interval_kind, start_time, end_time, overnight,
        week_start_minute, week_end_minute
    )
    SELECT fixture.store_id, day_row.day_index, day_row.day_name,
           'BUSINESS_HOURS',
           IF(fixture.category_code = 'CAFE_BAKERY', '09:00:00', '11:00:00'),
           IF(fixture.category_code = 'CAFE_BAKERY', '21:00:00', '22:00:00'),
           FALSE,
           day_row.day_index * 1440
               + IF(fixture.category_code = 'CAFE_BAKERY', 540, 660),
           day_row.day_index * 1440
               + IF(fixture.category_code = 'CAFE_BAKERY', 1260, 1320)
    FROM search_fixture_stores fixture
    CROSS JOIN search_fixture_days day_row
    WHERE TRUE
    ON DUPLICATE KEY UPDATE
        start_time = VALUES(start_time), end_time = VALUES(end_time),
        week_start_minute = VALUES(week_start_minute),
        week_end_minute = VALUES(week_end_minute);

    INSERT INTO store_reservation_schedule_entries (
        reservation_schedule_version_id, entry_order, day_of_week,
        start_time, end_time, overnight, week_start_minute, week_end_minute
    )
    SELECT fixture.store_id, day_row.day_index, day_row.day_name,
           IF(fixture.category_code = 'CAFE_BAKERY', '10:00:00', '12:00:00'),
           IF(fixture.category_code = 'CAFE_BAKERY', '20:00:00', '21:00:00'),
           FALSE,
           day_row.day_index * 1440
               + IF(fixture.category_code = 'CAFE_BAKERY', 600, 720),
           day_row.day_index * 1440
               + IF(fixture.category_code = 'CAFE_BAKERY', 1200, 1260)
    FROM search_fixture_stores fixture
    CROSS JOIN search_fixture_days day_row
    WHERE TRUE
    ON DUPLICATE KEY UPDATE
        start_time = VALUES(start_time), end_time = VALUES(end_time),
        week_start_minute = VALUES(week_start_minute),
        week_end_minute = VALUES(week_end_minute);

    INSERT INTO store_schedule_state (
        store_id, active_operating_schedule_version_id,
        active_reservation_schedule_version_id, next_operating_version,
        next_reservation_version, created_at, updated_at
    )
    SELECT store_id, store_id, store_id, 2, 2,
           '2026-08-25 00:00:00', '2026-08-25 00:00:00'
    FROM search_fixture_stores
    ON DUPLICATE KEY UPDATE
        active_operating_schedule_version_id = VALUES(active_operating_schedule_version_id),
        active_reservation_schedule_version_id = VALUES(active_reservation_schedule_version_id),
        next_operating_version = 2, next_reservation_version = 2,
        updated_at = VALUES(updated_at);

    INSERT INTO menus (
        menu_id, store_id, next_version_number, draft_version_number,
        scheduled_version_number, published_version_number, visibility,
        selling_status, retired, lock_version, created_at, updated_at
    )
    SELECT 89000000 + (fixture.fixture_number - 1) * 10 + template.menu_slot,
           fixture.store_id, 2, NULL, NULL, 1, 'VISIBLE', 'SELLING', FALSE, 0,
           '2026-08-25 00:00:00', '2026-08-25 00:00:00'
    FROM search_fixture_stores fixture
    JOIN search_fixture_menu_templates template
      ON template.category_code = fixture.category_code
    WHERE TRUE
    ON DUPLICATE KEY UPDATE
        published_version_number = 1, visibility = 'VISIBLE',
        selling_status = 'SELLING', retired = FALSE,
        updated_at = VALUES(updated_at);

    INSERT INTO menu_versions (
        menu_version_id, menu_id, version_number, status, name, description,
        price, representative, primary_category_code,
        hold_selection_allowed, pickup_selection_allowed,
        allergen_information_status, origin_information_status, alcoholic,
        created_by_operator_id, created_at, effective_at
    )
    SELECT 89000000 + (fixture.fixture_number - 1) * 10 + template.menu_slot,
           89000000 + (fixture.fixture_number - 1) * 10 + template.menu_slot,
           1, 'PUBLISHED', template.menu_name, template.description,
           template.price, template.menu_slot = 1, template.primary_category_code,
           TRUE, TRUE, 'REGISTERED', 'REGISTERED', FALSE,
           fixture.operator_id, '2026-08-25 00:00:00', '2026-08-25 00:00:00'
    FROM search_fixture_stores fixture
    JOIN search_fixture_menu_templates template
      ON template.category_code = fixture.category_code
    WHERE TRUE
    ON DUPLICATE KEY UPDATE
        name = VALUES(name), description = VALUES(description),
        price = VALUES(price), primary_category_code = VALUES(primary_category_code),
        status = 'PUBLISHED', effective_at = VALUES(effective_at);

    INSERT INTO menu_version_local_tags (menu_version_id, sort_order, tag_value)
    SELECT 89000000 + (fixture.fixture_number - 1) * 10 + template.menu_slot,
           0, LEFT(COALESCE(template.taste, template.ingredient, template.menu_family), 30)
    FROM search_fixture_stores fixture
    JOIN search_fixture_menu_templates template
      ON template.category_code = fixture.category_code
    WHERE TRUE
    ON DUPLICATE KEY UPDATE tag_value = VALUES(tag_value);

    INSERT INTO menu_version_allergen_disclosures (
        menu_version_id, sort_order, allergen_code, disclosure_status
    )
    SELECT 89000000 + (fixture.fixture_number - 1) * 10 + template.menu_slot,
           0, 'WHEAT', 'MAY_CONTAIN'
    FROM search_fixture_stores fixture
    JOIN search_fixture_menu_templates template
      ON template.category_code = fixture.category_code
    WHERE TRUE
    ON DUPLICATE KEY UPDATE disclosure_status = VALUES(disclosure_status);

    INSERT INTO menu_version_origin_disclosures (
        menu_version_id, sort_order, ingredient_name, origin_label
    )
    SELECT 89000000 + (fixture.fixture_number - 1) * 10 + template.menu_slot,
           0, COALESCE(template.ingredient, '주재료'), '합성 원산지 표본'
    FROM search_fixture_stores fixture
    JOIN search_fixture_menu_templates template
      ON template.category_code = fixture.category_code
    WHERE TRUE
    ON DUPLICATE KEY UPDATE
        ingredient_name = VALUES(ingredient_name), origin_label = VALUES(origin_label);

    INSERT INTO menu_inventory_buckets (
        menu_inventory_bucket_id, menu_id, service_date, start_time,
        end_date, end_time, time_zone_id, inventory_policy_version,
        total_supply, online_hold_capacity, online_hold_remaining,
        onsite_capacity, onsite_remaining, shared_capacity, shared_remaining,
        shared_online_allowed, availability_status, lock_version,
        created_at, updated_at
    )
    SELECT 189000000 + (fixture.fixture_number - 1) * 10 + template.menu_slot,
           89000000 + (fixture.fixture_number - 1) * 10 + template.menu_slot,
           CURRENT_DATE + INTERVAL 1 DAY, '12:00:00',
           CURRENT_DATE + INTERVAL 1 DAY, '14:00:00', 'Asia/Seoul', 1,
           40, 15, 15, 15, 15, 10, 10, TRUE, 'AVAILABLE', 0,
           NOW(6), NOW(6)
    FROM search_fixture_stores fixture
    JOIN search_fixture_menu_templates template
      ON template.category_code = fixture.category_code
    WHERE TRUE
    ON DUPLICATE KEY UPDATE
        service_date = VALUES(service_date), end_date = VALUES(end_date),
        online_hold_remaining = 15, onsite_remaining = 15,
        shared_remaining = 10, availability_status = 'AVAILABLE',
        updated_at = VALUES(updated_at);

    INSERT INTO menu_search_profiles (
        menu_search_profile_id, menu_version_id, schema_version,
        created_at, updated_at
    )
    SELECT 99000000 + (fixture.fixture_number - 1) * 10 + template.menu_slot,
           89000000 + (fixture.fixture_number - 1) * 10 + template.menu_slot,
           'food-profile-v1', '2026-08-25 00:00:00', '2026-08-25 00:00:00'
    FROM search_fixture_stores fixture
    JOIN search_fixture_menu_templates template
      ON template.category_code = fixture.category_code
    WHERE TRUE
    ON DUPLICATE KEY UPDATE
        schema_version = VALUES(schema_version), updated_at = VALUES(updated_at);

    INSERT INTO menu_search_profile_terms (
        menu_search_profile_term_id, menu_search_profile_id, dimension,
        normalized_term, confidence, source, created_at
    )
    SELECT 990000000 + ((fixture.fixture_number - 1) * 10 + template.menu_slot) * 10
               + dimension_row.dimension_order,
           99000000 + (fixture.fixture_number - 1) * 10 + template.menu_slot,
           dimension_row.dimension_name,
           dimension_row.term_value,
           1.0000, 'CURATED', '2026-08-25 00:00:00'
    FROM search_fixture_stores fixture
    JOIN search_fixture_menu_templates template
      ON template.category_code = fixture.category_code
    JOIN LATERAL (
        SELECT 1 AS dimension_order, 'MENU_FAMILY' AS dimension_name,
               template.menu_family AS term_value
        UNION ALL SELECT 2, 'ALIAS', template.alias_term
        UNION ALL SELECT 3, 'INGREDIENT', template.ingredient
        UNION ALL SELECT 4, 'TASTE', template.taste
        UNION ALL SELECT 5, 'BROTH', template.broth
        UNION ALL SELECT 6, 'METHOD', template.method
        UNION ALL SELECT 7, 'AROMA', template.aroma
        UNION ALL SELECT 8, 'TEXTURE', template.texture_term
        UNION ALL SELECT 9, 'FORM', template.form_term
    ) AS dimension_row ON dimension_row.term_value IS NOT NULL
    WHERE TRUE
    ON DUPLICATE KEY UPDATE
        normalized_term = VALUES(normalized_term), confidence = 1.0000,
        source = 'CURATED';

    SELECT
        (SELECT COUNT(*) FROM stores WHERE store_id BETWEEN 8900001 AND 8900500)
            AS stores,
        (SELECT COUNT(*) FROM menus WHERE menu_id BETWEEN 89000001 AND 89005000)
            AS menus,
        (SELECT COUNT(*) FROM menu_search_profiles
         WHERE menu_search_profile_id BETWEEN 99000001 AND 99005000)
            AS profiles,
        (SELECT COUNT(*) FROM menu_inventory_buckets
         WHERE menu_inventory_bucket_id BETWEEN 189000001 AND 189005000)
            AS inventory_buckets;
END$$

DELIMITER ;
CALL seed_search_profile_demo_500_stores();
DROP PROCEDURE seed_search_profile_demo_500_stores;
