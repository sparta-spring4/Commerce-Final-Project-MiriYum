package com.miriyum.domain.menuhold.service;

import com.miriyum.domain.menuhold.dto.MenuInventoryAcquireCommand;
import com.miriyum.domain.menuhold.dto.MenuInventoryAcquireResult;
import com.miriyum.domain.menuhold.dto.MenuInventoryAvailability;
import com.miriyum.domain.menuhold.dto.MenuInventoryAvailabilityDateQuery;
import com.miriyum.domain.menuhold.dto.MenuInventoryAvailabilityQuery;
import com.miriyum.domain.menuhold.dto.MenuInventoryRestoreCommand;
import com.miriyum.domain.menuhold.dto.MenuInventoryRestoreResult;
import java.util.List;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 픽업 거래가 메뉴 수량 내부 영속 모델 없이 조회·확보·복구하는 공개 계약이다. */
public interface MenuInventoryTransactionService {

    /**
     * 검증된 메뉴와 서비스 구간의 현재 온라인 가용량을 조회한다.
     *
     * @param query Store 계약으로 검증된 메뉴와 정확한 서비스 구간
     * @return 메뉴 ID 오름차순의 현재 정책 가용량
     */
    @Transactional(readOnly = true)
    List<MenuInventoryAvailability> findOnlineAvailability(
            MenuInventoryAvailabilityQuery query);

    /**
     * 검증된 메뉴들의 픽업 날짜에 게시된 현재 온라인 가용 구간을 조회한다.
     *
     * @param query Store 공개 계약으로 검증된 메뉴와 픽업 날짜
     * @return 제공 구간과 메뉴 순서가 안정적인 현재 가용량 목록
     */
    @Transactional(readOnly = true)
    List<MenuInventoryAvailability> findOnlineAvailabilityByDate(
            MenuInventoryAvailabilityDateQuery query);

    /**
     * 모든 선택 메뉴 수량을 호출자 트랜잭션에서 원자적으로 확보한다.
     *
     * @param command 전역 고유 operation과 현재 정책 기준 선택
     * @return 실제 확보 버킷 ID를 포함하고 내부 풀 배분·영속 타입은 제외한 결과
     */
    @Transactional(propagation = Propagation.MANDATORY)
    MenuInventoryAcquireResult acquire(MenuInventoryAcquireCommand command);

    /**
     * 최초 확보 원장의 실제 풀 배분을 호출자 트랜잭션에서 정확히 한 번 복구한다.
     *
     * @param command 새 복구 operation과 최초 확보 operation
     * @return 복구 명령 식별 결과
     */
    @Transactional(propagation = Propagation.MANDATORY)
    MenuInventoryRestoreResult restore(MenuInventoryRestoreCommand command);
}
