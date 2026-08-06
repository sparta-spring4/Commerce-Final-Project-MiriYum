package com.miriyum.domain.menuhold.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.miriyum.domain.menuhold.dto.MenuInventoryAcquireCommand;
import com.miriyum.domain.menuhold.dto.MenuInventoryAcquireResult;
import com.miriyum.domain.menuhold.dto.MenuInventoryAcquireSelection;
import com.miriyum.domain.menuhold.dto.MenuInventoryAcquiredItem;
import com.miriyum.domain.menuhold.dto.MenuInventoryAvailability;
import com.miriyum.domain.menuhold.dto.MenuInventoryAvailabilityDateQuery;
import com.miriyum.domain.menuhold.dto.MenuInventoryAvailabilityQuery;
import com.miriyum.domain.menuhold.dto.MenuInventoryRestoreCommand;
import com.miriyum.domain.menuhold.dto.MenuInventoryRestoreResult;
import com.miriyum.domain.menuhold.error.MenuHoldErrorCode;
import com.miriyum.domain.menuhold.service.MenuInventoryTransactionService;
import com.miriyum.global.exception.ServiceException;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
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
        Method availabilityByDate = MenuInventoryTransactionService.class.getMethod(
                "findOnlineAvailabilityByDate", MenuInventoryAvailabilityDateQuery.class);
        Method acquire = MenuInventoryTransactionService.class.getMethod(
                "acquire", MenuInventoryAcquireCommand.class);
        Method restore = MenuInventoryTransactionService.class.getMethod(
                "restore", MenuInventoryRestoreCommand.class);

        Transactional availabilityTx = availability.getAnnotation(Transactional.class);
        Transactional availabilityByDateTx =
                availabilityByDate.getAnnotation(Transactional.class);
        Transactional acquireTx = acquire.getAnnotation(Transactional.class);
        Transactional restoreTx = restore.getAnnotation(Transactional.class);

        assertThat(availabilityTx).isNotNull();
        assertThat(availabilityTx.readOnly()).isTrue();
        assertThat(availability.getGenericReturnType().getTypeName())
                .isEqualTo("java.util.List<com.miriyum.domain.menuhold.dto.MenuInventoryAvailability>");
        assertThat(availabilityByDateTx).isNotNull();
        assertThat(availabilityByDateTx.readOnly()).isTrue();
        assertThat(availabilityByDate.getGenericReturnType().getTypeName())
                .isEqualTo("java.util.List<com.miriyum.domain.menuhold.dto.MenuInventoryAvailability>");
        assertThat(acquireTx).isNotNull();
        assertThat(acquireTx.propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(acquire.getReturnType()).isEqualTo(MenuInventoryAcquireResult.class);
        assertThat(restoreTx).isNotNull();
        assertThat(restoreTx.propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(restore.getReturnType()).isEqualTo(MenuInventoryRestoreResult.class);
    }

    @Test
    @DisplayName("날짜별 조회는 중복 메뉴를 제거해 오름차순으로 정규화한다")
    void dateAvailabilityQueryNormalizesMenuIds() {
        MenuInventoryAvailabilityDateQuery query =
                new MenuInventoryAvailabilityDateQuery(
                        List.of(3L, 1L, 3L, 2L), SERVICE_DATE);

        assertThat(query.menuIds()).containsExactly(1L, 2L, 3L);
        assertThat(query.pickupDate()).isEqualTo(SERVICE_DATE);
    }

    @Test
    @DisplayName("날짜별 조회는 빈 메뉴 목록과 null 날짜를 거부한다")
    void dateAvailabilityQueryRejectsInvalidScope() {
        assertThatThrownBy(() -> new MenuInventoryAvailabilityDateQuery(
                List.of(), SERVICE_DATE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("menuIds must not be empty");
        assertThatThrownBy(() -> new MenuInventoryAvailabilityDateQuery(
                List.of(1L), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("pickupDate must not be null");
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
    @DisplayName("확보 결과는 실제 버킷 식별자만 공개하고 내부 풀 배분은 숨긴다")
    void publicResultsExposeOnlyConsumerContract() {
        assertThat(MenuInventoryAvailability.class.getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("bucketId", "lockVersion", "onlineHoldQuantity", "sharedQuantity");
        assertThat(MenuInventoryAcquireResult.class.getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("bucketId", "lockVersion", "onlineHoldQuantity", "sharedQuantity");
        assertThat(MenuInventoryAcquiredItem.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly(
                        "inventoryBucketId", "menuId", "inventoryPolicyVersion", "quantity")
                .doesNotContain("lockVersion", "onlineHoldQuantity", "sharedQuantity");
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
    @DisplayName("공개 수량 명령은 원장 컬럼보다 긴 operation ID를 거부한다")
    void publicCommandsRejectOperationIdsLongerThanLedgerColumns() {
        String maximumLengthId = "a".repeat(100);
        String tooLongId = "a".repeat(101);

        assertThat(new MenuInventoryAcquireCommand(
                maximumLengthId, List.of(selection(1L, 1))).operationId())
                .isEqualTo(maximumLengthId);
        assertThatThrownBy(() -> new MenuInventoryAcquireCommand(
                tooLongId, List.of(selection(1L, 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("operationId must not exceed 100 characters");
        assertThatThrownBy(() -> new MenuInventoryRestoreCommand(
                tooLongId, maximumLengthId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("operationId must not exceed 100 characters");
        assertThatThrownBy(() -> new MenuInventoryRestoreCommand(
                maximumLengthId, tooLongId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sourceAcquireOperationId must not exceed 100 characters");
    }

    @Test
    @DisplayName("픽업 fixture는 공개 명령을 기록하고 내부 풀 정보 없는 결과를 반환한다")
    void fixtureSupportsPickupConsumerWithoutProductionFake() {
        PickupMenuInventoryContractFixture fixture =
                PickupMenuInventoryContractFixture.succeeding(
                        List.of(), Map.of(selection(1L, 1), 41L));
        MenuInventoryAvailabilityDateQuery availability =
                new MenuInventoryAvailabilityDateQuery(List.of(2L, 1L), SERVICE_DATE);
        MenuInventoryAcquireCommand acquire = new MenuInventoryAcquireCommand(
                "pickup-acquire-01", List.of(selection(1L, 2)));
        MenuInventoryRestoreCommand restore = new MenuInventoryRestoreCommand(
                "pickup-restore-01", "pickup-acquire-01");

        fixture.findOnlineAvailabilityByDate(availability);
        MenuInventoryAcquireResult acquired = fixture.acquire(acquire);
        MenuInventoryRestoreResult restored = fixture.restore(restore);

        assertThat(fixture.dateAvailabilityQueries()).containsExactly(availability);
        assertThat(fixture.acquireCommands()).containsExactly(acquire);
        assertThat(fixture.restoreCommands()).containsExactly(restore);
        assertThat(acquired.operationId()).isEqualTo("pickup-acquire-01");
        assertThat(acquired.items()).extracting("inventoryBucketId", "menuId", "quantity")
                .containsExactly(tuple(41L, 1L, 2));
        assertThat(restored.sourceAcquireOperationId()).isEqualTo("pickup-acquire-01");
    }

    @Test
    @DisplayName("픽업 fixture는 같은 메뉴의 서로 다른 서비스 구간을 별도 버킷으로 반환한다")
    void fixtureMapsInventoryBucketByCompleteBucketKey() {
        LocalTime laterStartTime = LocalTime.of(14, 0);
        LocalTime laterEndTime = LocalTime.of(15, 0);
        MenuInventoryAcquireSelection first = selection(1L, 2);
        MenuInventoryAcquireSelection second = new MenuInventoryAcquireSelection(
                1L, SERVICE_DATE, laterStartTime, SERVICE_DATE, laterEndTime, 1L, 3);
        MenuInventoryAcquireSelection nextPolicy = new MenuInventoryAcquireSelection(
                1L, SERVICE_DATE, START_TIME, SERVICE_DATE, END_TIME, 2L, 4);
        PickupMenuInventoryContractFixture fixture =
                PickupMenuInventoryContractFixture.succeeding(
                        List.of(), Map.of(first, 41L, second, 42L, nextPolicy, 43L));

        MenuInventoryAcquireResult acquired = fixture.acquire(new MenuInventoryAcquireCommand(
                "pickup-acquire-intervals", List.of(second, nextPolicy, first)));

        assertThat(acquired.items()).extracting(
                        "inventoryBucketId", "menuId", "inventoryPolicyVersion", "quantity")
                .containsExactly(
                        tuple(41L, 1L, 1L, 2),
                        tuple(43L, 1L, 2L, 4),
                        tuple(42L, 1L, 1L, 3));
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

    @Test
    @DisplayName("픽업 소비자는 멱등 재생에서 수량 서비스를 다시 호출하지 않는다")
    void pickupConsumerSkipsInventoryCallsOnIdempotentReplay() {
        PickupMenuInventoryContractFixture fixture =
                PickupMenuInventoryContractFixture.succeeding(
                        List.of(), Map.of(selection(1L, 2), 41L));
        PickupConsumer consumer = new PickupConsumer(fixture);

        MenuInventoryAcquireResult firstAcquire =
                consumer.acquire("create-key", List.of(selection(1L, 2)));
        MenuInventoryAcquireResult replayedAcquire =
                consumer.acquire("create-key", List.of(selection(1L, 2)));
        MenuInventoryRestoreResult firstRestore =
                consumer.restore("cancel-key", firstAcquire.operationId());
        MenuInventoryRestoreResult replayedRestore =
                consumer.restore("cancel-key", firstAcquire.operationId());

        assertThat(replayedAcquire).isSameAs(firstAcquire);
        assertThat(replayedRestore).isSameAs(firstRestore);
        assertThat(fixture.acquireCommands()).hasSize(1);
        assertThat(fixture.restoreCommands()).hasSize(1);
    }

    @Test
    @DisplayName("픽업 소비자는 논리 명령마다 서로 다른 operationId를 발급한다")
    void pickupConsumerIssuesUniqueOperationIdPerLogicalCommand() {
        PickupMenuInventoryContractFixture fixture =
                PickupMenuInventoryContractFixture.succeeding(
                        List.of(), Map.of(selection(1L, 1), 41L));
        PickupConsumer consumer = new PickupConsumer(fixture);

        MenuInventoryAcquireResult first =
                consumer.acquire("create-key-1", List.of(selection(1L, 1)));
        MenuInventoryAcquireResult second =
                consumer.acquire("create-key-2", List.of(selection(1L, 1)));
        MenuInventoryRestoreResult restored =
                consumer.restore("cancel-key-1", first.operationId());

        assertThat(List.of(first.operationId(), second.operationId(), restored.operationId()))
                .doesNotHaveDuplicates();
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

    private static final class PickupConsumer {

        private final MenuInventoryTransactionService inventoryService;
        private final AtomicLong operationSequence = new AtomicLong();
        private final Map<String, MenuInventoryAcquireResult> acquireOutcomes = new HashMap<>();
        private final Map<String, MenuInventoryRestoreResult> restoreOutcomes = new HashMap<>();

        private PickupConsumer(MenuInventoryTransactionService inventoryService) {
            this.inventoryService = inventoryService;
        }

        private MenuInventoryAcquireResult acquire(
                String idempotencyKey,
                List<MenuInventoryAcquireSelection> selections
        ) {
            return acquireOutcomes.computeIfAbsent(idempotencyKey, ignored ->
                    inventoryService.acquire(new MenuInventoryAcquireCommand(
                            nextOperationId("acquire"), selections)));
        }

        private MenuInventoryRestoreResult restore(
                String idempotencyKey,
                String sourceAcquireOperationId
        ) {
            return restoreOutcomes.computeIfAbsent(idempotencyKey, ignored ->
                    inventoryService.restore(new MenuInventoryRestoreCommand(
                            nextOperationId("restore"), sourceAcquireOperationId)));
        }

        private String nextOperationId(String commandType) {
            return "pickup:" + commandType + ":" + operationSequence.incrementAndGet();
        }
    }
}
