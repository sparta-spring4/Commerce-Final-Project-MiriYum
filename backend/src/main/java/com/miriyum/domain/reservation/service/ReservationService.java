package com.miriyum.domain.reservation.service;

import com.miriyum.domain.consumer.service.ConsumerAccountService;
import com.miriyum.domain.reservation.dto.request.ReservationHistorySearchRequest;
import com.miriyum.domain.reservation.dto.request.StoreReservationSearchRequest;
import com.miriyum.domain.reservation.dto.response.ReservationHistoryPageResponse;
import com.miriyum.domain.reservation.dto.response.StoreReservationPageResponse;
import com.miriyum.domain.reservation.entity.Reservation;
import com.miriyum.domain.reservation.entity.ReservationStatus;
import com.miriyum.domain.reservation.repository.ReservationRepository;
import com.miriyum.domain.store.core.service.StoreService;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예약 도메인이 소유하는 사용자·매장 운영자 예약 조회 계약이다.
 *
 * <p>인증 계정 식별자는 호출자가 전달하며, 저장소 조회 단계에서 해당 계정의 예약으로 범위를 제한한다.
 */
@Service
public class ReservationService {

    private final ReservationRepository reservationRepository;
    private final ConsumerAccountService consumerAccountService;
    private final StoreService storeService;

    public ReservationService(
            ReservationRepository reservationRepository,
            ConsumerAccountService consumerAccountService,
            StoreService storeService
    ) {
        this.reservationRepository = reservationRepository;
        this.consumerAccountService = consumerAccountService;
        this.storeService = storeService;
    }

    /**
     * 소비자 본인의 예약 내역을 승인된 상태·페이지·정렬 조건으로 조회한다.
     *
     * @param consumerAccountId 인증된 소비자 계정 식별자
     * @param request 예약 도메인이 검증한 조회 조건
     * @return Entity가 노출되지 않는 예약 내역 페이지
     * @throws ServiceException 계정 식별자 또는 조회 조건이 유효하지 않은 경우
     */
    @Transactional(readOnly = true)
    public ReservationHistoryPageResponse getConsumerReservationHistory(
            Long consumerAccountId,
            ReservationHistorySearchRequest request
    ) {
        validateRequest(consumerAccountId, request);
        consumerAccountService.getMe(consumerAccountId);

        Pageable pageable = PageRequest.of(
                request.page(),
                request.size(),
                deterministicSort(request.order())
        );
        Page<Reservation> reservations = request.status() == null
                ? reservationRepository.findAllByConsumerAccountId(
                        consumerAccountId,
                        pageable
                )
                : reservationRepository.findAllByConsumerAccountIdAndStatus(
                        consumerAccountId,
                        ReservationStatus.valueOf(request.status().name()),
                        pageable
                );

        return ReservationHistoryPageResponse.from(reservations);
    }

    /**
     * 인증된 운영자가 관리하는 매장의 예약 목록을 승인된 조건으로 조회한다.
     *
     * @param operatorAccountId 인증된 매장 운영자 계정 식별자
     * @param storeId 대상 매장 식별자
     * @param request 날짜·상태·페이지·정렬 조회 조건
     * @return 대상 매장 범위의 예약 목록 페이지
     * @throws ServiceException 입력이 유효하지 않거나 대상 매장 관리 권한이 없는 경우
     */
    @Transactional(readOnly = true)
    public StoreReservationPageResponse getStoreReservations(
            Long operatorAccountId,
            Long storeId,
            StoreReservationSearchRequest request
    ) {
        validateStoreRequest(operatorAccountId, storeId, request);
        storeService.requireManagementOwnership(operatorAccountId, storeId);

        Pageable pageable = PageRequest.of(
                request.page(),
                request.size(),
                Sort.by(request.order().sortOrders())
        );
        ReservationStatus status = request.status() == null
                ? null
                : ReservationStatus.valueOf(request.status().name());
        Page<Reservation> reservations = findStoreReservations(
                storeId,
                request,
                status,
                pageable
        );

        return StoreReservationPageResponse.from(reservations);
    }

    private Page<Reservation> findStoreReservations(
            Long storeId,
            StoreReservationSearchRequest request,
            ReservationStatus status,
            Pageable pageable
    ) {
        if (request.serviceDate() != null && status != null) {
            return reservationRepository.findAllByStoreIdAndServiceDateAndStatus(
                    storeId,
                    request.serviceDate(),
                    status,
                    pageable
            );
        }
        if (request.serviceDate() != null) {
            return reservationRepository.findAllByStoreIdAndServiceDate(
                    storeId,
                    request.serviceDate(),
                    pageable
            );
        }
        if (status != null) {
            return reservationRepository.findAllByStoreIdAndStatus(
                    storeId,
                    status,
                    pageable
            );
        }
        return reservationRepository.findAllByStoreId(storeId, pageable);
    }

    private void validateRequest(
            Long consumerAccountId,
            ReservationHistorySearchRequest request
    ) {
        if (consumerAccountId == null || consumerAccountId <= 0 || request == null) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
    }

    private void validateStoreRequest(
            Long operatorAccountId,
            Long storeId,
            StoreReservationSearchRequest request
    ) {
        if (operatorAccountId == null || operatorAccountId <= 0
                || storeId == null || storeId <= 0
                || request == null) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
    }

    private Sort deterministicSort(ReservationHistorySearchRequest.Order order) {
        String primaryProperty = switch (order) {
            case CREATED_AT_DESC, CREATED_AT_ASC -> "createdAt";
            case SERVICE_DATE_DESC, SERVICE_DATE_ASC -> "serviceDate";
        };
        Sort.Direction direction = switch (order) {
            case CREATED_AT_DESC, SERVICE_DATE_DESC -> Sort.Direction.DESC;
            case CREATED_AT_ASC, SERVICE_DATE_ASC -> Sort.Direction.ASC;
        };

        return Sort.by(
                new Sort.Order(direction, primaryProperty),
                new Sort.Order(direction, "id")
        );
    }
}
