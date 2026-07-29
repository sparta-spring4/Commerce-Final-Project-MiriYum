package com.miriyum.domain.store.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.miriyum.domain.store.entity.CatalogEntry;
import com.miriyum.domain.store.entity.StoreCategory;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
@SpringBootTest
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
        // 자연키 엔티티는 repository.save가 merge(update)로 동작하므로, DB PK 제약은 raw insert로 검증한다.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO store_category (code, display_name, active, sort_order) VALUES (?, ?, ?, ?)",
                "KOREAN", "한식 중복", true, 99))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("code 비교는 대소문자를 구분한다")
    void codeComparisonIsCaseSensitive() {
        assertThat(storeCategoryRepository.existsByCodeAndActiveTrue("KOREAN")).isTrue();
        assertThat(storeCategoryRepository.existsByCodeAndActiveTrue("korean")).isFalse();
        assertThat(storeCategoryRepository.findByActiveTrueAndCodeIn(List.of("korean"))).isEmpty();
    }

    @Test
    @DisplayName("비활성 항목은 활성 조회에서 제외된다")
    void inactiveItemIsExcluded() {
        storeCategoryRepository.saveAndFlush(new StoreCategory("TEST_INACTIVE", "비활성", false, 99));

        assertThat(storeCategoryRepository.findByActiveTrueOrderBySortOrderAscCodeAsc())
                .extracting(CatalogEntry::getCode)
                .doesNotContain("TEST_INACTIVE");
        assertThat(storeCategoryRepository.existsByCodeAndActiveTrue("TEST_INACTIVE")).isFalse();
    }
}
