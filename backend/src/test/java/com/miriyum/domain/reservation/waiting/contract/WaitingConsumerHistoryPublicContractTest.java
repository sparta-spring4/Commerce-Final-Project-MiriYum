package com.miriyum.domain.reservation.waiting.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.reservation.waiting.controller.consumer.WaitingConsumerController;
import com.miriyum.domain.reservation.waiting.dto.WaitingConsumerHistoryContracts;
import com.miriyum.domain.reservation.waiting.service.WaitingConsumerHistoryQueryService;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class WaitingConsumerHistoryPublicContractTest {

    @Test
    void publicHistoryRecordsContainNoConsumerLocationLedgerOrAuditFields() {
        for (Class<?> nested : WaitingConsumerHistoryContracts.class.getDeclaredClasses()) {
            if (!nested.isRecord()) {
                continue;
            }
            assertThat(Arrays.stream(nested.getRecordComponents())
                    .map(RecordComponent::getName))
                    .noneMatch(name -> name.toLowerCase().contains("consumer")
                            || name.toLowerCase().contains("account")
                            || name.toLowerCase().contains("location")
                            || name.toLowerCase().contains("ledger")
                            || name.toLowerCase().contains("audit"));
            assertThat(Arrays.stream(nested.getRecordComponents())
                    .map(RecordComponent::getGenericType)
                    .map(Object::toString))
                    .noneMatch(type -> type.contains(".entity.")
                            || type.contains(".repository."));
        }
    }

    @Test
    void controllerHistoryInputHasNoConsumerIdentifier() {
        Method historyMethod = Arrays.stream(WaitingConsumerController.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("getHistory"))
                .findFirst()
                .orElseThrow();

        assertThat(Arrays.stream(historyMethod.getParameters()).map(parameter ->
                parameter.getName().toLowerCase()))
                .noneMatch(name -> name.contains("consumer") || name.contains("account"));
    }

    @Test
    void downstreamConsumersReceiveOnlyPublicQueryServiceMethodAndDtos() {
        assertThat(Arrays.stream(WaitingConsumerHistoryQueryService.class.getDeclaredMethods())
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                .map(Method::toGenericString))
                .singleElement()
                .asString()
                .contains("WaitingConsumerHistoryContracts$HistoryPage")
                .contains("WaitingConsumerHistoryContracts$HistoryQuery")
                .doesNotContain("Repository", "WaitingTeam");
    }
}
