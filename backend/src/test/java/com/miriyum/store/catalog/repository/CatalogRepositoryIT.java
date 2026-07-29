package com.miriyum.store.catalog.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.store.catalog.domain.CatalogItem;
import com.miriyum.store.catalog.domain.CatalogKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 실제 MySQL에서 Flyway clean-start, 유일 제약, 활성·정렬 조회를 검증한다.
 *
 * <p>Docker가 없는 환경에서는 {@code disabledWithoutDocker}로 자동 비활성화된다. H2 성공을 MySQL 증거로
 * 대체하지 않는다.</p>
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class CatalogRepositoryIT {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"));

    @Autowired
    private CatalogItemRepository catalogItemRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Flyway가 승인된 활성 seed를 종류별로 재현한다")
    void flywayReproducesActiveSeed() {
        assertThat(catalogItemRepository.findByKindAndActiveTrueOrderBySortOrderAsc(CatalogKind.STORE_CATEGORY))
                .hasSize(8);
        assertThat(catalogItemRepository.findByKindAndActiveTrueOrderBySortOrderAsc(CatalogKind.MENU_CATEGORY))
                .hasSize(10);
        assertThat(catalogItemRepository.findByKindAndActiveTrueOrderBySortOrderAsc(CatalogKind.STORE_TAG))
                .hasSize(8);
    }

    @Test
    @DisplayName("활성 항목이 정렬 순서를 유지한다")
    void activeItemsKeepSortOrder() {
        assertThat(catalogItemRepository.findByKindAndActiveTrueOrderBySortOrderAsc(CatalogKind.STORE_CATEGORY))
                .extracting(CatalogItem::getCode)
                .containsExactly("KOREAN", "CHINESE", "JAPANESE", "WESTERN", "ASIAN", "CAFE_BAKERY", "BAR", "ETC");
    }

    @Test
    @DisplayName("catalog_version이 세 종류 모두 1이다")
    void catalogVersionIsOne() {
        Long storeVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM catalog_version WHERE catalog_kind = ?", Long.class, "STORE_CATEGORY");
        Long menuVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM catalog_version WHERE catalog_kind = ?", Long.class, "MENU_CATEGORY");
        Long tagVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM catalog_version WHERE catalog_kind = ?", Long.class, "STORE_TAG");

        assertThat(storeVersion).isEqualTo(1L);
        assertThat(menuVersion).isEqualTo(1L);
        assertThat(tagVersion).isEqualTo(1L);
    }

    @Test
    @DisplayName("종류 안에서 code 유일 제약을 위반하면 무결성 예외가 발생한다")
    void duplicateCodeInKindViolatesUniqueConstraint() {
        assertThatThrownBy(() -> catalogItemRepository.saveAndFlush(
                new CatalogItem(CatalogKind.STORE_CATEGORY, "KOREAN", "한식 중복", true, 99)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 code라도 다른 종류이면 공존한다")
    void sameCodeDifferentKindCoexists() {
        assertThat(catalogItemRepository.existsByKindAndCodeAndActiveTrue(CatalogKind.STORE_CATEGORY, "ETC")).isTrue();
        assertThat(catalogItemRepository.existsByKindAndCodeAndActiveTrue(CatalogKind.MENU_CATEGORY, "ETC")).isTrue();
    }
}
