package com.miriyum.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.notification.dto.source.NotificationPurpose;
import com.miriyum.domain.notification.dto.source.NotificationResourceType;
import com.miriyum.domain.notification.dto.source.NotificationSourceDomain;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class NotificationOpenApiContractTest {

    private static final Path CONTRACT = Path.of(
            "..", "docs", "specs", "notification", "openapi.yaml");
    private static final Set<String> WAITING_PURPOSES = Set.of(
            "WAITING_ENTRY_IMMINENT",
            "WAITING_CALLED",
            "WAITING_CANCELLED",
            "WAITING_NO_SHOW",
            "WAITING_CHECKED_IN",
            "WAITING_CLOSED_BY_STORE"
    );

    @Test
    void waitingInAppPurposesAndResourceArePublishedWithoutSseContract() throws IOException {
        Map<String, Object> document = load(CONTRACT);
        Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));

        assertThat(list(map(schemas.get("NotificationPurpose")).get("enum")))
                .containsAll(WAITING_PURPOSES);
        assertThat(list(map(schemas.get("NotificationResourceType")).get("enum")))
                .contains("WAITING_TEAM");

        assertThat(map(document.get("paths")).keySet())
                .noneMatch(path -> path.toLowerCase().contains("waiting")
                        || path.toLowerCase().contains("stream")
                        || path.toLowerCase().contains("sse"));
    }

    @Test
    void productionEnumsMatchThePublishedWaitingContract() {
        assertThat(NotificationSourceDomain.valueOf("WAITING"))
                .isEqualTo(NotificationSourceDomain.WAITING);
        assertThat(NotificationResourceType.valueOf("WAITING_TEAM"))
                .isEqualTo(NotificationResourceType.WAITING_TEAM);
        assertThat(java.util.Arrays.stream(NotificationPurpose.values())
                .map(Enum::name))
                .containsAll(WAITING_PURPOSES);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return new Yaml().load(input);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }
}
