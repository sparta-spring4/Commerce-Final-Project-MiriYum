package com.miriyum;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 애플리케이션 컨텍스트가 뜨는지 확인하는 스모크 테스트다. 실제 DB 대신 H2 인메모리 DB를 쓰고,
 * Flyway 대신 Hibernate가 엔티티 매핑으로 스키마를 직접 만들게 해 운영 마이그레이션과 분리한다.
 */
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.autoconfigure.exclude=org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
            "spring.datasource.url=jdbc:h2:mem:miriyum-context-test;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes"
        })
class MiriyumApplicationTests {

    @Test
    void contextLoads() {
    }
}
