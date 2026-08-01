package com.miriyum.domain.reservation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * 예약 확정 당시의 성인·아동·영아별 인원 구성이다.
 */
@Embeddable
public class PartyComposition {

    private static final int MAX_COUNT_PER_GROUP = 100;

    @Column(name = "adult_count", nullable = false)
    private int adultCount;

    @Column(name = "child_count", nullable = false)
    private int childCount;

    @Column(name = "infant_count", nullable = false)
    private int infantCount;

    protected PartyComposition() {
    }

    private PartyComposition(int adultCount, int childCount, int infantCount) {
        this.adultCount = requireValidCount(adultCount, "adultCount");
        this.childCount = requireValidCount(childCount, "childCount");
        this.infantCount = requireValidCount(infantCount, "infantCount");
        if (totalCount() < 1) {
            throw new IllegalArgumentException("party total count must be at least 1");
        }
    }

    /**
     * 검증된 연령대별 인원으로 거래 인원 스냅샷을 만든다.
     *
     * @param adultCount 성인 인원 수
     * @param childCount 아동 인원 수
     * @param infantCount 영아 인원 수
     * @return 검증된 인원 구성
     * @throws IllegalArgumentException 각 값이 0~100 범위 밖이거나 합계가 1 미만인 경우
     */
    public static PartyComposition of(int adultCount, int childCount, int infantCount) {
        return new PartyComposition(adultCount, childCount, infantCount);
    }

    public int totalCount() {
        return adultCount + childCount + infantCount;
    }

    private static int requireValidCount(int count, String fieldName) {
        if (count < 0 || count > MAX_COUNT_PER_GROUP) {
            throw new IllegalArgumentException(fieldName + " must be between 0 and 100");
        }
        return count;
    }

    public int getAdultCount() {
        return adultCount;
    }

    public int getChildCount() {
        return childCount;
    }

    public int getInfantCount() {
        return infantCount;
    }
}
