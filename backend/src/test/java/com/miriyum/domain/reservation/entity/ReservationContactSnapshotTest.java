package com.miriyum.domain.reservation.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReservationContactSnapshotTest {

    @Test
    @DisplayName("불투명 알림 대상 참조와 확정 당시 연락 가능 상태를 보존한다")
    void preservesOpaqueNotificationTargetAndContactAvailability() {
        // when
        ReservationContactSnapshot snapshot =
                ReservationContactSnapshot.contactable("consumer:11:channel:primary");

        // then
        assertThat(snapshot.getNotificationTargetReference())
                .isEqualTo("consumer:11:channel:primary");
        assertThat(snapshot.isContactAvailableAtConfirmation()).isTrue();
    }

    @Test
    @DisplayName("비어 있거나 허용 길이를 넘는 알림 대상 참조를 거부한다")
    void rejectsInvalidNotificationTargetReference() {
        // when & then
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationContactSnapshot.contactable(" ")
        );
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationContactSnapshot.contactable("a".repeat(513))
        );
    }
}
