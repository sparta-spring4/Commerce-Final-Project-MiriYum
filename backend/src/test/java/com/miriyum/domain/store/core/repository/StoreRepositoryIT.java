package com.miriyum.domain.store.core.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.store.core.entity.Store;
import com.miriyum.domain.store.core.enums.BusinessType;
import com.miriyum.domain.store.core.enums.OperationStatus;
import com.miriyum.domain.store.core.enums.Region;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import jakarta.persistence.EntityManager;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes"
        })
class StoreRepositoryIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.40");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private StoreOperatorAccountRepository storeOperatorAccountRepository;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void cleanRows() {
        storeRepository.deleteAll();
        storeOperatorAccountRepository.deleteAll();
    }

    @Test
    @Transactional
    @DisplayName("Flyway 스키마와 JPA 매핑으로 매장과 태그를 저장한다")
    void storesStoreAndTagsWithFlywaySchema() {
        long operatorId = createOperator("owner@example.com");

        Store saved = storeRepository.saveAndFlush(store(
                operatorId, "1234567890", Set.of("DATE", "QUIET")));
        entityManager.clear();

        Store found = storeRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getTagCodes()).containsExactlyInAnyOrder("DATE", "QUIET");
    }

    @Test
    @DisplayName("같은 활성 사업자등록번호는 DB 유일 제약으로 한 건만 저장된다")
    void duplicateActiveBusinessNumberIsRejected() {
        long firstOperator = createOperator("first@example.com");
        long secondOperator = createOperator("second@example.com");
        storeRepository.saveAndFlush(store(firstOperator, "1234567890", Set.of()));

        assertThatThrownBy(() ->
                storeRepository.saveAndFlush(store(secondOperator, "1234567890", Set.of())))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_stores_active_business_number");
    }

    @Test
    @DisplayName("종료된 매장의 사업자등록번호는 새 활성 매장이 다시 귀속할 수 있다")
    void closedStoreReleasesActiveBusinessNumber() {
        long firstOperator = createOperator("first@example.com");
        long secondOperator = createOperator("second@example.com");
        Store closed = storeRepository.saveAndFlush(
                store(firstOperator, "1234567890", Set.of()));
        closed.update(
                null, null, null, null, null, null,
                null, null, null, OperationStatus.CLOSED);
        storeRepository.saveAndFlush(closed);

        Store replacement = storeRepository.saveAndFlush(
                store(secondOperator, "1234567890", Set.of()));

        assertThat(replacement.getId()).isNotNull();
    }

    @Test
    @DisplayName("매장이 참조하는 운영자 계정은 연쇄 삭제되지 않는다")
    void referencedOperatorCannotBeDeleted() {
        long operatorId = createOperator("owner@example.com");
        storeRepository.saveAndFlush(store(operatorId, "1234567890", Set.of()));

        assertThatThrownBy(() -> {
            storeOperatorAccountRepository.deleteById(operatorId);
            storeOperatorAccountRepository.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(storeRepository.count()).isOne();
    }

    @Test
    @DisplayName("승인 catalog에 없는 태그 코드는 DB 외래 키로 거부한다")
    void unknownTagCodeIsRejectedByForeignKey() {
        long operatorId = createOperator("owner@example.com");

        assertThatThrownBy(() ->
                storeRepository.saveAndFlush(store(
                        operatorId, "1234567890", Set.of("UNKNOWN_TAG"))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private long createOperator(String email) {
        return storeOperatorAccountRepository.saveAndFlush(
                StoreOperatorAccount.create(email, "hashed", "운영자")).getId();
    }

    private Store store(long operatorId, String businessNumber, Set<String> tags) {
        return Store.create(
                operatorId,
                businessNumber,
                BusinessType.CAFE,
                "미리윰",
                "",
                Region.SEOUL,
                "서울시 중구",
                "CAFE_BAKERY",
                tags,
                true,
                true,
                true);
    }
}
