package com.miriyum.domain.store.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.miriyum.domain.store.entity.CatalogEntry;
import com.miriyum.domain.store.entity.StoreCategory;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 실제 MySQL에서 Flyway clean-start, 전체 seed, 대소문자 구분, 자연키 유일성, 비활성 제외를 검증한다.
 *
 * <p>Docker가 없으면 {@code disabledWithoutDocker}로 자동 비활성화된다(병합 증거는 Docker 환경에서
 * 0 skipped로 수집한다). H2 성공을 MySQL 증거로 대체하지 않는다.</p>
 */
@SpringBootTest(
        properties = {
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.jwt.issuer=miriyum"
        })
@Tag("integration")
@Tag("integration-shard-b")
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class CatalogRepositoryIT {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0.40"));

    @Autowired
    private StoreCategoryRepository storeCategoryRepository;

    @Autowired
    private MenuCategoryRepository menuCategoryRepository;

    @Autowired
    private StoreTagRepository storeTagRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Flyway가 매장 카테고리 seed를 code·표시명·순서까지 재현한다")
    void flywayReproducesStoreCategorySeed() {
        // when & then
        assertThat(storeCategoryRepository.findByActiveTrueOrderBySortOrderAscCodeAsc())
                .extracting(CatalogEntry::getCode, CatalogEntry::getDisplayName)
                .containsExactly(
                        tuple("KOREAN", "한식"),
                        tuple("CHINESE", "중식"),
                        tuple("JAPANESE", "일식"),
                        tuple("WESTERN", "양식"),
                        tuple("ASIAN", "아시아 음식"),
                        tuple("CAFE_BAKERY", "카페·베이커리"),
                        tuple("BAR", "주점"),
                        tuple("ETC", "기타"));
    }

    @Test
    @DisplayName("Flyway가 메뉴 카테고리 seed를 code·표시명·순서까지 재현한다")
    void flywayReproducesMenuCategorySeed() {
        // when & then
        assertThat(menuCategoryRepository.findByActiveTrueOrderBySortOrderAscCodeAsc())
                .extracting(CatalogEntry::getCode, CatalogEntry::getDisplayName)
                .containsExactly(
                        tuple("RICE", "밥요리"),
                        tuple("NOODLE", "면요리"),
                        tuple("SOUP_STEW", "국·탕·찌개"),
                        tuple("MEAT", "고기요리"),
                        tuple("SEAFOOD", "해산물요리"),
                        tuple("PIZZA_BURGER_SANDWICH", "피자·버거·샌드위치"),
                        tuple("BAKERY", "베이커리"),
                        tuple("DESSERT", "디저트"),
                        tuple("BEVERAGE", "음료"),
                        tuple("ETC", "기타"));
    }

    @Test
    @DisplayName("Flyway가 매장 태그 seed를 code·표시명·순서까지 재현한다")
    void flywayReproducesStoreTagSeed() {
        // when & then
        assertThat(storeTagRepository.findByActiveTrueOrderBySortOrderAscCodeAsc())
                .extracting(CatalogEntry::getCode, CatalogEntry::getDisplayName)
                .containsExactly(
                        tuple("DATE", "데이트"),
                        tuple("QUIET", "조용한"),
                        tuple("GROUP", "모임"),
                        tuple("SOLO", "혼밥"),
                        tuple("FAMILY", "가족식사"),
                        tuple("VEGAN_OPTION", "비건 옵션"),
                        tuple("ALLERGY_INFO", "알레르기 안내 제공"),
                        tuple("PET_FRIENDLY", "반려동물 가능"));
    }

    @Test
    @DisplayName("자연키 code 중복 삽입은 무결성 예외가 발생한다")
    void duplicateCodeViolatesPrimaryKey() {
        // when & then
        // 자연키 엔티티는 repository.save가 merge(update)로 동작하므로, DB PK 제약은 raw insert로 검증한다.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO store_category (code, display_name, active, sort_order) VALUES (?, ?, ?, ?)",
                "KOREAN", "한식 중복", true, 99))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("sort_order가 0 이하이면 CHECK 제약으로 거부된다")
    void nonPositiveSortOrderViolatesCheckConstraint() {
        // when & then
        // MySQL CHECK 위반은 SQL state HY000이라 Spring이 UncategorizedSQLException으로 변환한다.
        // 상위 DataAccessException과 제약명으로 실제 CHECK 거부를 검증한다.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO store_category (code, display_name, active, sort_order) VALUES (?, ?, ?, ?)",
                "ZERO_ORDER", "잘못된 순서", true, 0))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_store_category_sort_order");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO store_category (code, display_name, active, sort_order) VALUES (?, ?, ?, ?)",
                "NEG_ORDER", "잘못된 순서", true, -1))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_store_category_sort_order");
    }

    @Test
    @DisplayName("code 형식 CHECK가 잘못된 코드를 거부한다(소문자·숫자시작·특수문자·한글·빈문자열·50자초과)")
    void invalidCodeFormatViolatesCheckConstraint() {
        // when & then
        for (String badCode : new String[] {"korean", "1ABC", "AB-C", "한식", "", "A".repeat(51)}) {
            assertThatThrownBy(() -> jdbcTemplate.update(
                    "INSERT INTO store_category (code, display_name, active, sort_order) VALUES (?, ?, ?, ?)",
                    badCode, "형식 위반", true, 50))
                    .as("bad code=[%s]", badCode)
                    .isInstanceOf(DataAccessException.class);
        }
    }

    @Test
    @DisplayName("code 비교는 대소문자를 구분한다")
    void codeComparisonIsCaseSensitive() {
        // when & then
        assertThat(storeCategoryRepository.existsByCodeAndActiveTrue("KOREAN")).isTrue();
        assertThat(storeCategoryRepository.existsByCodeAndActiveTrue("korean")).isFalse();
        assertThat(storeCategoryRepository.findByActiveTrueAndCodeIn(List.of("korean"))).isEmpty();
    }

    @Test
    @DisplayName("비활성 항목은 활성 조회에서 제외된다")
    void inactiveItemIsExcluded() {
        // given
        storeCategoryRepository.saveAndFlush(StoreCategory.of("TEST_INACTIVE", "비활성", false, 99));

        // when & then
        assertThat(storeCategoryRepository.findByActiveTrueOrderBySortOrderAscCodeAsc())
                .extracting(CatalogEntry::getCode)
                .doesNotContain("TEST_INACTIVE");
        assertThat(storeCategoryRepository.existsByCodeAndActiveTrue("TEST_INACTIVE")).isFalse();
    }
}
