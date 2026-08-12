package com.miriyum.domain.reservation.waiting.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.reservation.waiting.entity.WaitingSource;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeam;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeamStatus;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Tag("integration-shard-b")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.store.schedule.activation-enabled=false",
            "miriyum.reservation.time-policy.activation-enabled=false"
        })
@Transactional
class WaitingTeamRepositoryIT {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(
            DockerImageName.parse("mysql:8.0.40"));

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private WaitingTeamRepository repository;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void disableForeignKeysForOwnedRepositoryFixture() {
        entityManager.createNativeQuery("SET FOREIGN_KEY_CHECKS = 0").executeUpdate();
    }

    @Test
    @DisplayName("keyset 목록은 매장과 선택 상태를 제한하고 순번과 ID로 안정 정렬한다")
    void scopesByStoreAndStatusWithStableTieBreak() {
        WaitingTeam first = save(22L, LocalDate.of(2026, 8, 12), 1L);
        WaitingTeam tiedLater = save(22L, LocalDate.of(2026, 8, 13), 1L);
        WaitingTeam third = save(22L, LocalDate.of(2026, 8, 12), 2L);
        save(23L, LocalDate.of(2026, 8, 12), 1L);
        third.call(0L, Instant.parse("2026-08-12T03:01:00Z"));
        repository.flush();
        entityManager.clear();

        List<WaitingTeam> all = repository.findKeysetPage(22L, null, null, null, 10);
        List<WaitingTeam> waiting = repository.findKeysetPage(
                22L, WaitingTeamStatus.WAITING, null, null, 10);

        assertThat(all).extracting(WaitingTeam::getId)
                .containsExactly(first.getId(), tiedLater.getId(), third.getId());
        assertThat(waiting).extracting(WaitingTeam::getId)
                .containsExactly(first.getId(), tiedLater.getId());
    }

    @Test
    @DisplayName("복합 cursor 페이지 경계는 중복이나 누락 없이 다음 행부터 이어진다")
    void continuesAfterCompositeCursorWithoutDuplicatesOrSkips() {
        WaitingTeam first = save(22L, LocalDate.of(2026, 8, 12), 1L);
        WaitingTeam second = save(22L, LocalDate.of(2026, 8, 13), 1L);
        WaitingTeam third = save(22L, LocalDate.of(2026, 8, 12), 2L);
        repository.flush();
        entityManager.clear();

        List<WaitingTeam> pageOne = repository.findKeysetPage(22L, null, null, null, 2);
        WaitingTeam cursor = pageOne.getLast();
        List<WaitingTeam> pageTwo = repository.findKeysetPage(
                22L, null, cursor.getQueueSequence(), cursor.getId(), 2);

        assertThat(pageOne).extracting(WaitingTeam::getId)
                .containsExactly(first.getId(), second.getId());
        assertThat(pageTwo).extracting(WaitingTeam::getId)
                .containsExactly(third.getId());
    }

    private WaitingTeam save(long storeId, LocalDate businessDate, long sequence) {
        long consumerId = 100_000L + storeId * 100L + sequence * 10L + businessDate.getDayOfMonth();
        return repository.save(WaitingTeam.create(
                storeId,
                consumerId,
                businessDate,
                2,
                WaitingSource.REMOTE,
                sequence,
                Instant.parse("2026-08-12T03:00:00Z")));
    }
}
