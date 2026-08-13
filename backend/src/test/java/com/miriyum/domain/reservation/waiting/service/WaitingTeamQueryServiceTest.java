package com.miriyum.domain.reservation.waiting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;

import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.waiting.dto.WaitingTeamListQuery;
import com.miriyum.domain.reservation.waiting.dto.WaitingTeamPage;
import com.miriyum.domain.reservation.waiting.entity.WaitingSource;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeam;
import com.miriyum.domain.reservation.waiting.repository.WaitingTeamRepository;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

class WaitingTeamQueryServiceTest {

    @Mock
    private WaitingStoreAuthorityPort authorityPort;

    @Mock
    private WaitingTeamRepository teamRepository;

    private WaitingTeamQueryService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new WaitingTeamQueryService(authorityPort, teamRepository);
    }

    @Test
    @DisplayName("목록 조회는 권한을 먼저 확인하고 거절되면 repository를 호출하지 않는다")
    void checksAuthorityBeforeListRepositoryAndStopsWhenDenied() {
        ServiceException denied = new ServiceException(CommonErrorCode.CONCURRENT_MODIFICATION);
        given(authorityPort.requireRead(33L, 22L)).willThrow(denied);

        assertThatThrownBy(() -> service.getTeams(
                33L, 22L, WaitingTeamListQuery.from(null, null, null)))
                .isSameAs(denied);

        then(teamRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("상세 조회는 권한 확인 뒤 매장 범위 repository를 호출한다")
    void checksAuthorityBeforeDetailRepository() {
        WaitingTeam team = team(77L, 22L, 1L);
        given(authorityPort.requireRead(33L, 22L))
                .willReturn(new WaitingStoreAuthority(22L, ZoneId.of("Asia/Seoul")));
        given(teamRepository.findByIdAndStoreId(77L, 22L)).willReturn(Optional.of(team));

        service.getTeam(33L, 22L, 77L);

        InOrder order = inOrder(authorityPort, teamRepository);
        order.verify(authorityPort).requireRead(33L, 22L);
        order.verify(teamRepository).findByIdAndStoreId(77L, 22L);
    }

    @Test
    @DisplayName("다른 매장 상세는 실제 조회 Service에서 WAITING_003을 반환한다")
    void hidesCrossStoreDetailAsWaitingTeamNotFound() {
        given(authorityPort.requireRead(33L, 22L))
                .willReturn(new WaitingStoreAuthority(22L, ZoneId.of("Asia/Seoul")));
        given(teamRepository.findByIdAndStoreId(77L, 22L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getTeam(33L, 22L, 77L))
                .isInstanceOfSatisfying(ServiceException.class, failure ->
                        assertThat(failure.getErrorCode())
                                .isEqualTo(ReservationErrorCode.WAITING_TEAM_NOT_FOUND));
    }

    @Test
    @DisplayName("기본 크기 20은 overflow 판정을 위해 repository에서 21개를 요청한다")
    void defaultSizeFetchesTwentyOneRows() {
        authorize();
        given(teamRepository.findKeysetPage(22L, null, null, null, 21))
                .willReturn(List.of());

        WaitingTeamPage page = service.getTeams(
                33L, 22L, WaitingTeamListQuery.from(null, null, null));

        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).isNull();
        then(teamRepository).should().findKeysetPage(22L, null, null, null, 21);
    }

    @Test
    @DisplayName("size+1 결과는 overflow를 자르고 마지막 공개 팀의 복합 cursor를 발급한다")
    void trimsOverflowAndRoundTripsExactCompositeCursor() {
        authorize();
        List<WaitingTeam> fetched = new ArrayList<>();
        for (long id = 101L; id <= 121L; id++) {
            fetched.add(team(id, 22L, id - 100L));
        }
        given(teamRepository.findKeysetPage(22L, null, null, null, 21))
                .willReturn(fetched);

        WaitingTeamPage page = service.getTeams(
                33L, 22L, WaitingTeamListQuery.from(null, null, null));
        WaitingTeamListQuery decoded = WaitingTeamListQuery.from(null, page.nextCursor(), 20);

        assertThat(page.items()).hasSize(20);
        assertThat(page.items().getLast().waitingTeamId()).isEqualTo("120");
        assertThat(decoded.afterQueueSequence()).isEqualTo(20L);
        assertThat(decoded.afterWaitingTeamId()).isEqualTo(120L);
    }

    @Test
    @DisplayName("overflow가 없으면 모든 항목을 반환하고 다음 cursor는 null이다")
    void returnsNullCursorWhenNoNextPageExists() {
        authorize();
        given(teamRepository.findKeysetPage(22L, null, null, null, 21))
                .willReturn(List.of(team(77L, 22L, 4L)));

        WaitingTeamPage page = service.getTeams(
                33L, 22L, WaitingTeamListQuery.from(null, null, null));

        assertThat(page.items()).extracting(item -> item.waitingTeamId())
                .containsExactly("77");
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("잘못된 opaque cursor는 parsing 세부 없이 COMMON_001로 거절한다")
    void rejectsMalformedCursorWithCanonicalValidationError() {
        assertThatThrownBy(() -> WaitingTeamListQuery.from(null, "not-a-cursor", 20))
                .isInstanceOfSatisfying(ServiceException.class, failure ->
                        assertThat(failure.getErrorCode())
                                .isEqualTo(CommonErrorCode.VALIDATION_FAILED));
    }

    private void authorize() {
        given(authorityPort.requireRead(33L, 22L))
                .willReturn(new WaitingStoreAuthority(22L, ZoneId.of("Asia/Seoul")));
    }

    private WaitingTeam team(long id, long storeId, long queueSequence) {
        WaitingTeam team = WaitingTeam.create(
                storeId,
                900L + id,
                LocalDate.of(2026, 8, 12),
                2,
                WaitingSource.REMOTE,
                queueSequence,
                Instant.parse("2026-08-12T03:00:00Z"));
        ReflectionTestUtils.setField(team, "id", id);
        return team;
    }
}
