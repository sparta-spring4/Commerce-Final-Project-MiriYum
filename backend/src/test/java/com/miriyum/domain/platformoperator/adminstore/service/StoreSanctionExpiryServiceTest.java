package com.miriyum.domain.platformoperator.adminstore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.miriyum.domain.platformoperator.adminstore.entity.StoreSanction;
import com.miriyum.domain.platformoperator.adminstore.entity.StoreSanctionCase;
import com.miriyum.domain.platformoperator.adminstore.entity.StoreSanctionEnums.SanctionType;
import com.miriyum.domain.platformoperator.adminstore.repository.StoreSanctionCaseRepository;
import com.miriyum.domain.platformoperator.adminstore.repository.StoreSanctionRepository;
import com.miriyum.domain.platformoperator.dto.authorization.OperatorAuthority;
import com.miriyum.domain.platformoperator.enums.*;
import com.miriyum.domain.platformoperator.service.OperatorAuthorityReader;
import com.miriyum.domain.platformoperator.service.PlatformOperatorAuditWriter;
import com.miriyum.domain.store.dto.administration.StoreAdministrationContracts.EnforcementResult;
import com.miriyum.domain.store.enums.OperationStatus;
import com.miriyum.domain.store.service.StoreAdministrationService;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

class StoreSanctionExpiryServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-17T00:00:00Z");

    @Test
    void oneFailedExpiryDoesNotRollbackOrBlockTheFollowingSanction() {
        StoreSanctionRepository sanctions = mock(StoreSanctionRepository.class);
        StoreSanctionExpiryTransaction expiry = mock(StoreSanctionExpiryTransaction.class);
        given(sanctions.findDueExpiryIds(any(), any(), any(), any(Pageable.class)))
                .willReturn(List.of(1L, 2L));
        given(expiry.expire(any(Long.class), any(Instant.class)))
                .willThrow(new ServiceException(com.miriyum.domain.store.error.StoreErrorCode.STORE_ENFORCEMENT_VERSION_CONFLICT))
                .willReturn(true);
        StoreSanctionExpiryService service = new StoreSanctionExpiryService(
                sanctions, expiry, Clock.fixed(NOW, ZoneOffset.UTC));

        int expired = service.expireDue(2);

        assertThat(expired).isEqualTo(1);
    }

    @Test
    void expiryAppendsImmutableAuditWithAutomationSnapshotInSameOperation() {
        StoreSanctionRepository sanctions=mock(StoreSanctionRepository.class);
        StoreSanctionCaseRepository cases=mock(StoreSanctionCaseRepository.class);
        StoreAdministrationService stores=mock(StoreAdministrationService.class);
        OperatorAuthorityReader authorities=mock(OperatorAuthorityReader.class);
        PlatformOperatorAuditWriter audit=mock(PlatformOperatorAuditWriter.class);
        StoreSanction sanction=StoreSanction.create("case-1",10L,SanctionType.TEMPORARY_SUSPENSION,Set.of(),
                "reason",NOW.minusSeconds(600),NOW.minusSeconds(1),17L,false,1L,NOW.minusSeconds(600));
        ReflectionTestUtils.setField(sanction,"id",1L);
        StoreSanctionCase c=StoreSanctionCase.create(10L,17L,"FRAUD",Set.of("evidence://1"),"ADMIN-007-v1",NOW.minusSeconds(600));
        ReflectionTestUtils.setField(c,"publicId","case-1");
        given(sanctions.findById(1L)).willReturn(Optional.of(sanction));
        given(sanctions.findScopedForUpdate(1L,"case-1",10L)).willReturn(Optional.of(sanction));
        given(cases.findByPublicIdAndStoreId("case-1",10L)).willReturn(Optional.of(c));
        var before=new EnforcementResult(10L,1L,OperationStatus.TEMPORARILY_CLOSED,false,false,false,false,false,Set.of());
        var after=new EnforcementResult(10L,2L,OperationStatus.OPEN,true,true,true,true,true,Set.of());
        given(stores.inspect(10L)).willReturn(before);given(stores.release(any())).willReturn(after);
        given(authorities.currentAuthority(17L)).willReturn(new OperatorAuthority(17L,4L,
                Set.of(PlatformOperatorRole.ENFORCEMENT_OPERATOR),Set.of(PlatformOperatorPermission.STORE_SANCTION)));
        StoreSanctionExpiryTransaction transaction=new StoreSanctionExpiryTransaction(sanctions,stores,cases,authorities,audit);

        assertThat(transaction.expire(1L,NOW)).isTrue();

        ArgumentCaptor<PlatformOperatorAuditWriter.StoreEvent> event=ArgumentCaptor.forClass(PlatformOperatorAuditWriter.StoreEvent.class);
        verify(audit).appendStore(event.capture());
        assertThat(event.getValue().action()).isEqualTo(PlatformOperatorAuditAction.STORE_SANCTION_EXPIRED);
        assertThat(event.getValue().sanctionVersion()).isEqualTo(2L);
        assertThat(event.getValue().storeEnforcementVersion()).isEqualTo(2L);
        assertThat(event.getValue().afterSnapshot()).containsEntry("automation",true);
    }
}
