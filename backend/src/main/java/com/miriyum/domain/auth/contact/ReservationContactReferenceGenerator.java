package com.miriyum.domain.auth.contact;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 예약 도메인에 전달할 공급자 중립 연락처 참조를 만든다.
 */
@Component
public class ReservationContactReferenceGenerator {

    public String generate() {
        return UUID.randomUUID().toString();
    }
}
