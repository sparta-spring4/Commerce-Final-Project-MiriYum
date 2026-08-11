package com.miriyum.domain.menu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.miriyum.domain.menu.repository.MenuRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class MenuScheduleWorkerTest {

    @Mock
    private MenuRepository menuRepository;

    @Mock
    private MenuDatabaseClock databaseClock;

    @Mock
    private MenuScheduleActivator activator;

    @Test
    void activatesOnlyBoundedDueCandidates() {
        Instant now = Instant.parse("2026-07-31T00:00:00Z");
        given(databaseClock.now()).willReturn(now);
        given(menuRepository.findDueScheduledIds(
                org.mockito.ArgumentMatchers.eq(now),
                org.mockito.ArgumentMatchers.any(Pageable.class)))
                .willReturn(List.of(3L, 7L));
        given(activator.activateDue(3L)).willReturn(true);
        given(activator.activateDue(7L)).willReturn(false);
        MenuScheduleWorker worker =
                new MenuScheduleWorker(menuRepository, databaseClock, activator, 25);

        int activated = worker.activateDueBatch();

        assertThat(activated).isOne();
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(menuRepository).findDueScheduledIds(
                org.mockito.ArgumentMatchers.eq(now), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(25);
    }
}
