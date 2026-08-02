package com.miriyum.domain.store.search.service;

import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.store.search.dto.PublicStoreModes;
import com.miriyum.domain.store.search.dto.PublicStoreSummary;
import com.miriyum.domain.store.search.dto.ReservationAvailability;
import com.miriyum.domain.store.search.model.StoreSearchQuery;
import com.miriyum.domain.store.search.repository.StoreSearchCandidate;
import com.miriyum.domain.store.search.repository.StoreSearchRepository;
import com.miriyum.global.exception.ServiceException;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공개 매장 후보 조회와 공개 응답 투영을 조정하는 1단계 검색 서비스다.
 *
 * <p>예약 도메인의 매장별 가용성 계약이 연결되기 전까지 예약 조건이 없는 검색만
 * 실행하며, 모든 결과를 {@link ReservationAvailability#NOT_REQUESTED}로 표시한다.</p>
 */
@Service
public class StoreSearchCoreService {

    private final StoreSearchCatalogPolicy catalogPolicy;
    private final StoreSearchRepository repository;

    /**
     * 공개 카테고리 정책과 매장 후보 저장소로 검색 서비스를 구성한다.
     *
     * @param catalogPolicy 카테고리 활성 상태 경계 정책
     * @param repository 공개 매장 후보 저장소
     */
    public StoreSearchCoreService(
            StoreSearchCatalogPolicy catalogPolicy,
            StoreSearchRepository repository
    ) {
        this.catalogPolicy = catalogPolicy;
        this.repository = repository;
    }

    /**
     * 예약 가용성을 요청하지 않은 공개 매장 검색을 실행한다.
     *
     * <p>카테고리 필터가 있으면 현재 활성 코드인지 먼저 검증한다. 반환 페이지의 후보
     * 필드와 페이지 메타데이터는 보존하고, 예약 가용성은 항상
     * {@link ReservationAvailability#NOT_REQUESTED}로 투영한다.</p>
     *
     * @param query 검증과 정규화를 마친 공개 매장 검색 조건
     * @return 예약 가용성을 요청하지 않은 공개 매장 요약 페이지
     * @throws IllegalStateException 예약 조건이 포함되어 2단계 가용성 계약이 필요한 경우
     * @throws ServiceException 카테고리 코드가 미승인 또는 비활성이어서
     *                          {@link StoreErrorCode#CATALOG_CODE_INVALID}인 경우
     */
    @Transactional(readOnly = true)
    public Page<PublicStoreSummary> searchWithoutAvailability(StoreSearchQuery query) {
        if (query.reservationCondition() != null) {
            throw new IllegalStateException(
                    "reservation availability contract is not connected");
        }
        catalogPolicy.requireActiveStoreCategory(query.storeCategoryCode());
        return repository.search(query).map(this::toSummary);
    }

    private PublicStoreSummary toSummary(StoreSearchCandidate candidate) {
        return new PublicStoreSummary(
                Long.toString(candidate.storeId()),
                candidate.name(),
                candidate.region(),
                candidate.address(),
                candidate.storeCategoryCode(),
                candidate.operationStatus(),
                new PublicStoreModes(
                        candidate.reservationEnabled(),
                        candidate.menuHoldEnabled(),
                        candidate.pickupEnabled()),
                ReservationAvailability.NOT_REQUESTED);
    }
}
