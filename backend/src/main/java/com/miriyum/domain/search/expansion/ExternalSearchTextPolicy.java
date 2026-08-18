package com.miriyum.domain.search.expansion;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 외부 검색 해석 제공자에 보낼 수 없는 연락처·민감 표현을 차단한다. */
final class ExternalSearchTextPolicy {

    private static final int CASE_INSENSITIVE_UNICODE =
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final Pattern SAFE_CHARACTERS = Pattern.compile(
            "[\\p{L}\\p{Zs}\\s,!?%&()'\"_-]+");
    private static final Pattern HAS_LETTER = Pattern.compile("\\p{L}");
    private static final Pattern SENSITIVE_EXPRESSION = Pattern.compile(
            "([\\w.+-]+@[\\w.-]+\\.[a-z]{2,}"
                    + "|(?:\\+?82[- .]?)?0?1[016789](?:[- .]?\\d){7,8}"
                    + "|0\\d{1,2}[- .]?\\d{3,4}[- .]?\\d{4}"
                    + "|\\d{6}[- ]?[1-4]\\d{6}"
                    + "|알레르기|알러지|연락처|전화번호|카카오톡|카톡|인스타|instagram"
                    + "|텔레그램|telegram|예약|booking|방문일|방문시간|party\\s*size|guests?"
                    + "|사용자\\s*(?:id|아이디)|user\\s*id|account\\s*id"
                    + "|결제|카드번호|계좌번호|(?:로|길|동|번지)\\s*\\d+"
                    + "|https?://|www\\.)",
            CASE_INSENSITIVE_UNICODE);
    private static final Pattern ALLERGY_OR_EXCLUSION_INTENT = Pattern.compile(
            "(못\\s*먹|먹으면|빼\\s*주|제외해?|안\\s*들어간|피하|함유|혼입|"
                    + "allerg(?:y|ic)|intoleran|cannot\\s+eat|can't\\s+eat|"
                    + "without|avoid|free[- ]?from|contains?|may\\s+contain)",
            CASE_INSENSITIVE_UNICODE);
    private static final Pattern LONG_NUMBER = Pattern.compile("(?:\\d[- ]?){9,19}");
    private static final Pattern CARD_CANDIDATE = Pattern.compile("(?:\\d[ -]?){13,19}");
    private static final Pattern COORDINATE_PAIR = Pattern.compile(
            "([+-]?\\d{1,2}\\.\\d{3,})\\s*[,/ ]\\s*"
                    + "([+-]?\\d{1,3}\\.\\d{3,})");

    private ExternalSearchTextPolicy() {
    }

    static boolean allowsExternalInterpretation(String text) {
        if (text == null || text.isBlank()
                || !SAFE_CHARACTERS.matcher(text).matches()
                || !HAS_LETTER.matcher(text).find()
                || SENSITIVE_EXPRESSION.matcher(text).find()
                || ALLERGY_OR_EXCLUSION_INTENT.matcher(text).find()
                || containsCoordinatePair(text)) {
            return false;
        }
        Matcher cards = CARD_CANDIDATE.matcher(text);
        while (cards.find()) {
            if (passesLuhn(cards.group())) {
                return false;
            }
        }
        return !LONG_NUMBER.matcher(text).find();
    }

    private static boolean containsCoordinatePair(String text) {
        Matcher coordinates = COORDINATE_PAIR.matcher(text);
        while (coordinates.find()) {
            double latitude = Double.parseDouble(coordinates.group(1));
            double longitude = Double.parseDouble(coordinates.group(2));
            if (latitude >= -90 && latitude <= 90
                    && longitude >= -180 && longitude <= 180) {
                return true;
            }
        }
        return false;
    }

    private static boolean passesLuhn(String value) {
        String digits = value.replace(" ", "").replace("-", "");
        if (digits.length() < 13 || digits.length() > 19) {
            return false;
        }
        int sum = 0;
        boolean doubleDigit = false;
        for (int index = digits.length() - 1; index >= 0; index--) {
            int digit = digits.charAt(index) - '0';
            if (doubleDigit) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubleDigit = !doubleDigit;
        }
        return sum % 10 == 0;
    }
}
