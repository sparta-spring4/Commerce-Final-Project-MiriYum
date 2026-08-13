package com.miriyum.global.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.global.storage.entity.UtcInstantConverter;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class UtcInstantConverterTest {

    private final UtcInstantConverter converter = new UtcInstantConverter();

    @Test
    void convertsInstantToUtcDatabaseValueAndBack() {
        Instant instant = Instant.parse("2026-08-12T00:00:00Z");

        LocalDateTime databaseValue = converter.convertToDatabaseColumn(instant);

        assertThat(databaseValue).isEqualTo(LocalDateTime.of(2026, 8, 12, 0, 0));
        assertThat(converter.convertToEntityAttribute(databaseValue)).isEqualTo(instant);
    }

    @Test
    void preservesNullValues() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }
}
