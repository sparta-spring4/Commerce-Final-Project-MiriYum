package com.miriyum.domain.search.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 검색 프로필의 단일 정규화 음식 근거와 신뢰도·출처를 보존한다. */
@Entity
@Table(name = "menu_search_profile_terms")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MenuSearchProfileTerm {

    private static final int MAX_TERM_LENGTH = 60;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "menu_search_profile_term_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "menu_search_profile_id", nullable = false)
    private MenuSearchProfile profile;

    @Enumerated(EnumType.STRING)
    @Column(name = "dimension", nullable = false, length = 30)
    private MenuSearchProfileDimension dimension;

    @Column(name = "normalized_term", nullable = false, length = 60)
    private String normalizedTerm;

    @Column(name = "confidence", nullable = false, precision = 5, scale = 4)
    private BigDecimal confidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private MenuSearchProfileSource source;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private MenuSearchProfileTerm(
            MenuSearchProfile profile,
            MenuSearchProfileDimension dimension,
            String normalizedTerm,
            BigDecimal confidence,
            MenuSearchProfileSource source,
            Instant now
    ) {
        this.profile = Objects.requireNonNull(profile, "profile must not be null");
        this.dimension = Objects.requireNonNull(dimension, "dimension must not be null");
        this.normalizedTerm = normalizeTerm(normalizedTerm);
        this.confidence = validateConfidence(confidence);
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.createdAt = Objects.requireNonNull(now, "now must not be null");
    }

    static MenuSearchProfileTerm create(
            MenuSearchProfile profile,
            MenuSearchProfileDimension dimension,
            String normalizedTerm,
            BigDecimal confidence,
            MenuSearchProfileSource source,
            Instant now
    ) {
        return new MenuSearchProfileTerm(
                profile, dimension, normalizedTerm, confidence, source, now);
    }

    private static String normalizeTerm(String value) {
        if (value == null) {
            throw new IllegalArgumentException("normalizedTerm must not be null");
        }
        String normalized = value.trim().replaceAll("\\s+", " ");
        if (normalized.isEmpty() || normalized.length() > MAX_TERM_LENGTH) {
            throw new IllegalArgumentException("normalizedTerm has invalid length");
        }
        return normalized;
    }

    private static BigDecimal validateConfidence(BigDecimal value) {
        if (value == null
                || value.compareTo(BigDecimal.ZERO) < 0
                || value.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("confidence must be between zero and one");
        }
        return value;
    }
}
