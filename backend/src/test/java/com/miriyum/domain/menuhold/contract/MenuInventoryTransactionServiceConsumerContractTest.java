package com.miriyum.domain.menuhold.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.miriyum.domain.menuhold.dto.MenuInventoryAcquireCommand;
import com.miriyum.domain.menuhold.dto.MenuInventoryAcquireResult;
import com.miriyum.domain.menuhold.dto.MenuInventoryAcquireSelection;
import com.miriyum.domain.menuhold.dto.MenuInventoryAvailability;
import com.miriyum.domain.menuhold.dto.MenuInventoryAvailabilityQuery;
import com.miriyum.domain.menuhold.dto.MenuInventoryRestoreCommand;
import com.miriyum.domain.menuhold.dto.MenuInventoryRestoreResult;
import com.miriyum.domain.menuhold.error.MenuHoldErrorCode;
import com.miriyum.domain.menuhold.service.MenuInventoryTransactionService;
import com.miriyum.global.exception.ServiceException;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class MenuInventoryTransactionServiceConsumerContractTest {

    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 10);
    private static final LocalTime START_TIME = LocalTime.of(12, 0);
    private static final LocalTime END_TIME = LocalTime.of(13, 0);

    @Test
    @DisplayName("조회는 읽기 전용이고 확보와 복구는 호출자 트랜잭션 참여를 강제한다")
    void exposesTransactionBoundariesForPickupConsumer() throws Exception {
        Method availability = MenuInventoryTransactionService.class.getMethod(
                "findOnlineAvailability", MenuInventoryAvailabilityQuery.class);
        Method acquire = MenuInventoryTransactionService.class.getMethod(
                "acquire", MenuInventoryAcquireCommand.class);
        Method restore = MenuInventoryTransactionService.class.getMethod(
                "restore", MenuInventoryRestoreCommand.class);

        Transactional availabilityTx = availability.getAnnotation(Transactional.class);
        Transactional acquireTx = acquire.getAnnotation(Transactional.class);
        Transactional restoreTx = restore.getAnnotation(Transactional.class);

        assertThat(availabilityTx).isNotNull();
        assertThat(availabilityTx.readOnly()).isTrue();
        assertThat(availability.getGenericReturnType().getTypeName())
                .isEqualTo("java.util.List<com.miriyum.domain.menuhold.dto.MenuInventoryAvailability>");
        assertThat(acquireTx).isNotNull();
        assertThat(acquireTx.propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(acquire.getReturnType()).isEqualTo(MenuInventoryAcquireResult.class);
        assertThat(restoreTx).isNotNull();
        assertThat(restoreTx.propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(restore.getReturnType()).isEqualTo(MenuInventoryRestoreResult.class);
    }

    @Test
    @DisplayName("가용량 조회는 중복 메뉴를 제거하고 안정적인 ID 순서로 정규화한다")
    void availabilityQueryNormalizesMenuIds() {
        MenuInventoryAvailabilityQuery query = new MenuInventoryAvailabilityQuery(
                List.of(3L, 1L, 3L, 2L), SERVICE_DATE, START_TIME, SERVICE_DATE, END_TIME);

        assertThat(query.menuIds()).containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("가용량 조회는 빈 메뉴 목록과 증가하지 않는 구간을 거부한다")
    void availabilityQueryRejectsInvalidScope() {
        assertThatThrownBy(() -> new MenuInventoryAvailabilityQuery(
                List.of(), SERVICE_DATE, START_TIME, SERVICE_DATE, END_TIME))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("menuIds must not be empty");
        assertThatThrownBy(() -> new MenuInventoryAvailabilityQuery(
                List.of(1L), SERVICE_DATE, START_TIME, SERVICE_DATE, START_TIME))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("service time range must be increasing");
    }

    @Test
    @DisplayName("확보 명령은 같은 메뉴와 구간 수량을 하나로 합산한다")
    void acquireCommandNormalizesDuplicateSelections() {
        MenuInventoryAcquireCommand command = new MenuInventoryAcquireCommand(
                "pickup-acquire-01",
                List.of(selection(2L, 2), selection(1L, 1), selection(2L, 3)));

        assertThat(command.selections()).containsExactly(selection(1L, 1), selection(2L, 5));
    }

    @Test
    @DisplayName("확보 명령은 수량 합산 overflow를 거부한다")
    void acquireCommandRejectsQuantityOverflow() {
        assertThatThrownBy(() -> new MenuInventoryAcquireCommand(
                "pickup-acquire-01",
                List.of(selection(1L, Integer.MAX_VALUE), selection(1L, 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("inventory selection quantity sum exceeds integer range");
    }

    @Test
    @DisplayName("공개 결과에는 내부 버킷 식별자와 풀 배분이 없다")
    void publicResultsExposeOnlyConsumerContract() {
        assertThat(MenuInventoryAvailability.class.getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("bucketId", "lockVersion", "onlineHoldQuantity", "sharedQuantity");
        assertThat(MenuInventoryAcquireResult.class.getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("bucketId", "lockVersion", "onlineHoldQuantity", "sharedQuantity");
    }

    @Test
    @DisplayName("복구 명령은 새 operation과 최초 확보 operation을 모두 요구한다")
    void restoreCommandRequiresBothOperations() {
        assertThatThrownBy(() -> new MenuInventoryRestoreCommand(" ", "pickup-acquire-01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("operationId must not be blank");
        assertThatThrownBy(() -> new MenuInventoryRestoreCommand("pickup-restore-01", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sourceAcquireOperationId must not be blank");
    }

    @Test
    @DisplayName("픽업 fixture는 공개 명령을 기록하고 내부 풀 정보 없는 결과를 반환한다")
    void fixtureSupportsPickupConsumerWithoutProductionFake() {
        PickupMenuInventoryContractFixture fixture =
                PickupMenuInventoryContractFixture.succeeding(List.of());
        MenuInventoryAcquireCommand acquire = new MenuInventoryAcquireCommand(
                "pickup-acquire-01", List.of(selection(1L, 2)));
        MenuInventoryRestoreCommand restore = new MenuInventoryRestoreCommand(
                "pickup-restore-01", "pickup-acquire-01");

        MenuInventoryAcquireResult acquired = fixture.acquire(acquire);
        MenuInventoryRestoreResult restored = fixture.restore(restore);

        assertThat(fixture.acquireCommands()).containsExactly(acquire);
        assertThat(fixture.restoreCommands()).containsExactly(restore);
        assertThat(acquired.operationId()).isEqualTo("pickup-acquire-01");
        assertThat(acquired.items()).extracting("menuId", "quantity")
                .containsExactly(tuple(1L, 2));
        assertThat(restored.sourceAcquireOperationId()).isEqualTo("pickup-acquire-01");
    }

    @Test
    @DisplayName("픽업 fixture는 메뉴 수량 오류를 변환하지 않고 전달한다")
    void fixturePreservesMenuInventoryErrors() {
        ServiceException failure = new ServiceException(MenuHoldErrorCode.INSUFFICIENT_QUANTITY);
        PickupMenuInventoryContractFixture fixture =
                PickupMenuInventoryContractFixture.failing(failure);

        assertThatThrownBy(() -> fixture.acquire(new MenuInventoryAcquireCommand(
                "pickup-acquire-01", List.of(selection(1L, 2)))))
                .isSameAs(failure);
    }

    private static MenuInventoryAcquireSelection selection(long menuId, int quantity) {
        return new MenuInventoryAcquireSelection(
                menuId,
                SERVICE_DATE,
                START_TIME,
                SERVICE_DATE,
                END_TIME,
                1L,
                quantity);
    }
}
