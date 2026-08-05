package com.miriyum.domain.storeoperator.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@code V6__create_store_operator_accounts.sql}이 실제 MySQL에서 적용되고, 이메일 유일 제약과
 * JPA 매핑이 진짜 DB 스키마와 맞는지 검증한다.
 *
 * <p>다른 테스트는 Flyway를 끄고 H2 {@code ddl-auto=create-drop}으로 스키마를 만들기 때문에
 * migration SQL 자체를 실행하지 않는다. 저장소 검증 계약은 H2를 MySQL 증거로 쓰지 않도록 정하므로
 * ({@code docs/service-policies/18-scale-reliability.md} SCALE-014, 이슈 #63) 여기서는 Flyway가
 * 실제로 적용한 스키마를 {@code ddl-auto=validate}로 확인한다.</p>
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes"
        })
class StoreOperatorAccountMigrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.40");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private StoreOperatorAccountRepository storeOperatorAccountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("V6 migration의 이메일 유일 제약이 실제 MySQL에서 걸린다")
    void v6MigrationEnforcesUniqueEmailConstraint() {
        // given
        String duplicateEmail = "duplicate-owner@example.com";
        storeOperatorAccountRepository.saveAndFlush(
                StoreOperatorAccount.create(duplicateEmail, "hashed", "첫가게"));

        // when & then: 같은 이메일로 두 번째 계정을 만들면 실제 유일 제약 위반으로 거부된다
        assertThatThrownBy(() -> storeOperatorAccountRepository.saveAndFlush(
                StoreOperatorAccount.create(duplicateEmail, "hashed", "둘째가게")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("방식 접두사가 붙은 비밀번호 해시가 잘리지 않고 저장된다")
    void storesPrefixedPasswordHashWithoutTruncation() {
        // given: {sha256-bcrypt} 접두사(15자) + BCrypt 해시(60자) = 75자로 기존 VARCHAR(72)를 넘는다
        String passwordHash = passwordEncoder.encode("Password123!");
        assertThat(passwordHash).startsWith("{sha256-bcrypt}");
        assertThat(passwordHash.length()).isGreaterThan(72);

        // when
        Long accountId = storeOperatorAccountRepository.saveAndFlush(
                StoreOperatorAccount.create("prefixed-hash@example.com", passwordHash, "해시가게")).getId();
        storeOperatorAccountRepository.flush();

        // then: DB에 저장된 값이 그대로 돌아와야 로그인 비교가 성공한다
        String stored = storeOperatorAccountRepository.findById(accountId).orElseThrow().getPasswordHash();
        assertThat(stored).isEqualTo(passwordHash);
        assertThat(passwordEncoder.matches("Password123!", stored)).isTrue();
    }
}
