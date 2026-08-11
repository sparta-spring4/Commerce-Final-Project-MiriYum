package com.miriyum.domain.menu.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.miriyum.domain.menu.dto.contract.MenuHoldSelectableMenu;
import com.miriyum.domain.menu.service.MenuHoldSelectionQueryService;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

class MenuHoldSelectionPublicContractTest {

    private static final List<String> FORBIDDEN_PUBLIC_TYPE_FRAGMENTS = List.of(
            ".entity.",
            ".repository.",
            ".enums.",
            "ManagedMenuResponse");

    @Test
    void exposesOnlyMenuHoldSelectionSnapshotFields() {
        assertThat(MenuHoldSelectableMenu.class.isRecord()).isTrue();
        assertThat(MenuHoldSelectableMenu.class.getRecordComponents())
                .extracting(RecordComponent::getName, RecordComponent::getType)
                .containsExactly(
                        tuple("menuId", long.class),
                        tuple("menuName", String.class),
                        tuple("unitPrice", int.class));
    }

    @Test
    void rejectsInvalidMenuHoldSelectionSnapshot() {
        assertThatThrownBy(() -> new MenuHoldSelectableMenu(0L, "Americano", 5_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("menuId must be positive");
        assertThatThrownBy(() -> new MenuHoldSelectableMenu(1L, " ", 5_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("menuName must not be blank");
        assertThatThrownBy(() -> new MenuHoldSelectableMenu(1L, "Americano", -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unitPrice must not be negative");
    }

    @Test
    void exposesStoreIdOnlyReadOnlySelectionQuery() throws Exception {
        Method method = MenuHoldSelectionQueryService.class.getDeclaredMethod(
                "findSelectableMenus", long.class);

        assertThat(method.getGenericReturnType().getTypeName())
                .isEqualTo("java.util.List<com.miriyum.domain.menu.dto.contract."
                        + "MenuHoldSelectableMenu>");
        assertThat(method.getParameterTypes()).containsExactly(long.class);
        Transactional transactional = method.getAnnotation(Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isTrue();
    }

    @Test
    void publicApiDoesNotExposeStoreInternals() {
        List<Class<?>> publicMethodTypes = Arrays.stream(
                        MenuHoldSelectionQueryService.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .flatMap(method -> Arrays.stream(concat(
                        method.getReturnType(), method.getParameterTypes())))
                .toList();
        List<Class<?>> publicConstructorTypes = Arrays.stream(
                        MenuHoldSelectionQueryService.class.getConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes()))
                .toList();

        assertThat(publicMethodTypes).allSatisfy(this::assertNotStoreInternalType);
        assertThat(publicConstructorTypes).allSatisfy(this::assertNotStoreInternalType);
    }

    private void assertNotStoreInternalType(Class<?> type) {
        assertThat(FORBIDDEN_PUBLIC_TYPE_FRAGMENTS)
                .allSatisfy(fragment -> assertThat(type.getName()).doesNotContain(fragment));
    }

    private static Class<?>[] concat(Class<?> first, Class<?>[] rest) {
        Class<?>[] result = new Class<?>[rest.length + 1];
        result[0] = first;
        System.arraycopy(rest, 0, result, 1, rest.length);
        return result;
    }
}
