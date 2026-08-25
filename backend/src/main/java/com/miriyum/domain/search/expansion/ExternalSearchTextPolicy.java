package com.miriyum.domain.search.expansion;

import com.miriyum.domain.search.expansion.StructuredFoodEvidence.Dimension;
import com.miriyum.domain.search.interpreter.FoodEvidenceVocabulary;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** 외부 검색 해석 제공자에 전달할 수 있는 음식 표현만 실패 폐쇄로 허용한다. */
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
    private static final Pattern STORE_SEARCH_TOKEN_SEPARATOR = Pattern.compile(
            "[\\p{Zs}\\s,!?%&()'\"_-]+");
    private static final Pattern APPROVED_STORE_SEARCH_TERM = Pattern.compile(
            "(얼큰|얼큰한|매콤|매콤한|매운|순한|담백|담백한|달콤|달콤한|"
                    + "짭짤|짭짤한|새콤|새콤한|고소|고소한|바삭|바삭한|쫄깃|쫄깃한|"
                    + "따뜻한|뜨거운|차가운|시원한|든든한|가벼운|건강한|"
                    + "국물|찌개|전골|탕|국|면|국수|라면|우동|냉면|파스타|"
                    + "밥|덮밥|비빔밥|볶음밥|볶음|구이|튀김|전|만두|죽|"
                    + "샐러드|디저트|음료|커피|차|빵|떡|고기|닭고기|돼지고기|"
                    + "소고기|생선|해산물|초밥|피자|버거|김치찌개|된장찌개|"
                    + "순두부찌개|부대찌개|food|menu|spicy|mild|savory|sweet|"
                    + "sour|crispy|chewy|hot|cold|soup|stew|noodle|noodles|rice|"
                    + "grill|grilled|fried|dumpling|dumplings|salad|dessert|drink|"
                    + "coffee|tea|bread|meat|chicken|pork|beef|fish|seafood|sushi|"
                    + "pizza|pasta|burger)",
            CASE_INSENSITIVE_UNICODE);

    private ExternalSearchTextPolicy() {
    }

    static boolean allowsExternalInterpretation(SearchConceptRequest request) {
        return allowsExternalInterpretation(request, null);
    }

    static boolean allowsExternalInterpretation(
            SearchConceptRequest request,
            FoodEvidenceVocabulary vocabulary
    ) {
        String text = request.text();
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
        return !LONG_NUMBER.matcher(text).find()
                && (request.purpose() != SearchConceptPurpose.STORE_SEARCH
                || containsOnlyApprovedStoreSearchTerms(text, vocabulary));
    }

    private static boolean containsOnlyApprovedStoreSearchTerms(
            String text,
            FoodEvidenceVocabulary vocabulary
    ) {
        String approvedFoodRemoved = vocabulary == null
                ? text
                : removeApprovedFoodTerms(text, vocabulary);
        String normalized = STORE_SEARCH_TOKEN_SEPARATOR
                .matcher(approvedFoodRemoved)
                .replaceAll(" ")
                .trim();
        if (normalized.isEmpty()) {
            return vocabulary != null;
        }
        return Stream.of(normalized.split("\\s+"))
                .allMatch(term -> APPROVED_STORE_SEARCH_TERM.matcher(term).matches());
    }

    private static String removeApprovedFoodTerms(
            String text,
            FoodEvidenceVocabulary vocabulary
    ) {
        List<String> aliases = Stream.of(Dimension.values())
                .flatMap(dimension -> vocabulary.entries(dimension).stream())
                .flatMap(entry -> entry.aliases().stream())
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
        String remaining = text;
        for (String alias : aliases) {
            remaining = Pattern.compile(
                            Pattern.quote(alias),
                            CASE_INSENSITIVE_UNICODE)
                    .matcher(remaining)
                    .replaceAll(" ");
        }
        return remaining;
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
