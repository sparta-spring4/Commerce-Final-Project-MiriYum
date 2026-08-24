package com.miriyum.domain.search.interpreter;

import com.miriyum.domain.search.expansion.StructuredFoodEvidence.Dimension;
import com.miriyum.domain.search.expansion.StructuredFoodEvidence.EvidenceTerm;
import com.miriyum.domain.search.expansion.StructuredFoodEvidenceSource;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** 사람이 검토한 기본 메뉴와 음식 속성을 정규화하는 고정 사전이다. */
@Component
public final class FoodEvidenceVocabulary {

    public static final String VERSION = "food-evidence-v1";

    private final Map<Dimension, List<Entry>> entries;
    private final Map<Dimension, Map<String, Entry>> aliases;

    public FoodEvidenceVocabulary() {
        this.entries = buildEntries();
        this.aliases = buildAliases(entries);
    }

    public String version() {
        return VERSION;
    }

    public List<Entry> entries(Dimension dimension) {
        return entries.getOrDefault(dimension, List.of());
    }

    public Optional<EvidenceTerm> resolve(
            Dimension dimension,
            String surface,
            StructuredFoodEvidenceSource source
    ) {
        if (dimension == null || surface == null || source == null) {
            return Optional.empty();
        }
        Entry entry = aliases.getOrDefault(dimension, Map.of())
                .get(normalize(surface).toLowerCase(Locale.ROOT));
        return entry == null
                ? Optional.empty()
                : Optional.of(entry.toTerm(surface, source));
    }

    private static Map<Dimension, List<Entry>> buildEntries() {
        Map<Dimension, List<Entry>> values = new EnumMap<>(Dimension.class);
        values.put(Dimension.MENU_FAMILY, List.of(
                menu("JJAMPPONG", "짬뽕", "해물짬뽕", "불향 해물 짬뽕", "옛날 짬뽕"),
                menu("BONE_HANGOVER_SOUP", "뼈해장국", "뼈다귀 해장국", "뼈다귀해장국"),
                menu("BANQUET_NOODLES", "잔치국수", "멸치국수", "멸치 육수 국수"),
                menu("SPICY_PORK", "제육볶음", "제육"),
                menu("DAKGALBI", "닭갈비", "춘천닭갈비"),
                menu("PHO", "쌀국수", "포"),
                menu("BULGOGI", "불고기", "소불고기"),
                menu("AMERICANO", "아메리카노", "아아", "아이스 아메리카노"),
                menu("KIMCHI_STEW", "김치찌개", "김치찌게"),
                menu("SOYBEAN_STEW", "된장찌개", "된장찌게"),
                menu("SOFT_TOFU_STEW", "순두부찌개", "순두부"),
                menu("ARMY_STEW", "부대찌개", "부대전골"),
                menu("SHORT_RIB_SOUP", "갈비탕", "갈비 탕"),
                menu("SEOLLEONGTANG", "설렁탕", "설농탕"),
                menu("GOMTANG", "곰탕", "소고기곰탕"),
                menu("SAMGYETANG", "삼계탕", "닭백숙"),
                menu("GAMJATANG", "감자탕", "감자 탕"),
                menu("TTEOKBOKKI", "떡볶이", "떡뽁이"),
                menu("GIMBAP", "김밥", "김밥 한줄"),
                menu("BIBIMBAP", "비빔밥", "비빔 밥"),
                menu("FRIED_RICE", "볶음밥", "볶음 밥"),
                menu("CURRY_RICE", "카레라이스", "카레밥"),
                menu("PORK_CUTLET", "돈가스", "돈까스"),
                menu("HAMBURG_STEAK", "함박스테이크", "함박"),
                menu("PASTA", "파스타", "스파게티"),
                menu("PIZZA", "피자", "피짜"),
                menu("HAMBURGER", "햄버거", "버거"),
                menu("SUSHI", "초밥", "스시"),
                menu("UDON", "우동", "가락국수"),
                menu("RAMEN", "라멘", "일본라면"),
                menu("COLD_NOODLES", "냉면", "물냉면"),
                menu("KALGUKSU", "칼국수", "손칼국수"),
                menu("SUJEBI", "수제비", "손수제비"),
                menu("SOYBEAN_NOODLES", "콩국수", "콩 국수"),
                menu("MALATANG", "마라탕", "마라 탕"),
                menu("JAJANGMYEON", "짜장면", "자장면"),
                menu("SWEET_SOUR_PORK", "탕수육"),
                menu("KKANPUNGGI", "깐풍기", "깐풍치킨"),
                menu("SHABU_SHABU", "샤브샤브", "샤브"),
                menu("VIETNAMESE_ROLL", "월남쌈", "베트남쌈"),
                menu("PAD_THAI", "팟타이", "태국볶음면"),
                menu("STIR_FRIED_WEBFOOT", "쭈꾸미볶음", "주꾸미볶음"),
                menu("STIR_FRIED_OCTOPUS", "낙지볶음", "낙지 볶음"),
                menu("GRILLED_FISH", "생선구이", "고등어구이"),
                menu("CHICKEN", "치킨", "후라이드치킨"),
                menu("DAKGANGJEONG", "닭강정", "강정치킨"),
                menu("BOSSAM", "보쌈", "돼지보쌈"),
                menu("JOKBAL", "족발", "왕족발"),
                menu("SALAD", "샐러드", "야채샐러드"),
                menu("PANCAKE", "팬케이크", "핫케이크")));
        values.put(Dimension.INGREDIENT, entries(
                term("SEAFOOD", "해물", "해산물"),
                term("PORK_BONE", "돼지뼈", "뼈다귀"),
                term("ANCHOVY", "멸치"),
                term("PORK", "돼지고기", "돼지"),
                term("CHICKEN", "닭고기", "닭"),
                term("BEEF", "소고기", "소"),
                term("COFFEE_BEAN", "원두"),
                term("KIMCHI", "김치"),
                term("SOYBEAN_PASTE", "된장"),
                term("TOFU", "두부", "순두부"),
                term("HAM", "햄"),
                term("BEEF_RIB", "소갈비", "갈비"),
                term("RICE_CAKE", "쌀떡", "떡"),
                term("RICE", "쌀"),
                term("VEGETABLE", "나물", "채소", "야채"),
                term("CURRY", "카레"),
                term("WHEAT", "밀", "밀가루"),
                term("CHEESE", "치즈"),
                term("FISH", "생선"),
                term("BUCKWHEAT", "메밀"),
                term("SOYBEAN", "콩"),
                term("SPICE", "향신료"),
                term("CHUNJANG", "춘장"),
                term("RICE_NOODLE", "쌀면"),
                term("WEBFOOT_OCTOPUS", "쭈꾸미", "주꾸미"),
                term("OCTOPUS", "낙지")));
        values.put(Dimension.TASTE, entries(
                term("SPICY_SHARP", "칼칼한", "얼큰한"),
                term("SPICY", "매콤한", "매운"),
                term("NUTTY", "고소한"),
                term("MILD", "담백한"),
                term("RICH", "진한", "진득한"),
                term("SWEET", "달콤한"),
                term("BITTER", "쌉싸름한"),
                term("AROMATIC", "향긋한"),
                term("NUMBING", "얼얼한"),
                term("SOUR", "새콤한", "상큼한"),
                term("SWEET_SOUR", "새콤달콤한"),
                term("SALTY", "짭짤한")));
        values.put(Dimension.BROTH, entries(
                term("BROTHY", "국물", "국물 있는"),
                term("REDUCED", "자작", "자작한")));
        values.put(Dimension.METHOD, entries(
                term("BOIL", "끓임", "끓인", "끓여"),
                term("STIR_FRY", "볶음", "볶은"),
                term("GRILL", "구이", "구운"),
                term("EXTRACT", "추출"),
                term("ROLL", "말이", "말아"),
                term("MIX", "비빔", "비빈"),
                term("FRY", "튀김", "튀긴"),
                term("BAKE", "굽기", "구워"),
                term("RAW", "생식", "회"),
                term("COLD", "냉조리", "차가운"),
                term("BLANCH", "데침", "데친"),
                term("SIMMER", "삶기", "삶은"),
                term("HANDMADE", "수제", "손수"),
                term("DIRECT_FIRE", "직화")));
        values.put(Dimension.AROMA, entries(
                term("SAVORY_AROMA", "구수한 향", "구수한향"),
                term("FIRE_AROMA", "불향", "직화향"),
                term("HERB_AROMA", "허브향"),
                term("GARLIC_AROMA", "마늘향"),
                term("PEPPER_AROMA", "후추향"),
                term("SESAME_AROMA", "참기름향"),
                term("GINGER_AROMA", "생강향"),
                term("SCALLION_AROMA", "파향"),
                term("SOYBEAN_AROMA", "된장향"),
                term("CHILI_AROMA", "고추향"),
                term("LEMON_AROMA", "레몬향"),
                term("SMOKED_AROMA", "훈연향"),
                term("PERILLA_AROMA", "들깨향"),
                term("SEAFOOD_AROMA", "해산물향"),
                term("GRAIN_AROMA", "곡물향")));
        values.put(Dimension.TEXTURE, entries(
                term("SOFT", "부드러운", "보드라운"),
                term("CHEWY", "쫄깃한", "탱글한", "탄력 있는"),
                term("CRISPY", "바삭한"),
                term("CRUNCHY", "아삭한", "사각한"),
                term("MOIST", "촉촉한"),
                term("FLUFFY", "포슬한", "폭신한"),
                term("THICK", "꾸덕한", "진득한"),
                term("TENDER", "몽글한"),
                term("LIGHT", "가벼운"),
                term("PLUMP", "도톰한"),
                term("SEPARATE_GRAINS", "고슬한"),
                term("SMOOTH", "매끈한"),
                term("FIRM", "단단한")));
        values.put(Dimension.FORM, entries(
                term("NOODLE", "면"),
                term("SOUP_FORM", "탕", "국"),
                term("RICE_FORM", "밥")));
        Map<Dimension, List<Entry>> copied = new EnumMap<>(Dimension.class);
        values.forEach((dimension, list) -> copied.put(dimension, List.copyOf(list)));
        return Map.copyOf(copied);
    }

    private static List<Entry> entries(Entry... entries) {
        return List.of(entries);
    }

    private static Entry menu(String id, String... aliases) {
        return term(id, aliases);
    }

    private static Entry term(String id, String... aliases) {
        return new Entry(id, List.of(aliases));
    }

    private static Map<Dimension, Map<String, Entry>> buildAliases(
            Map<Dimension, List<Entry>> entries
    ) {
        Map<Dimension, Map<String, Entry>> result = new EnumMap<>(Dimension.class);
        entries.forEach((dimension, dimensionEntries) -> {
            Map<String, Entry> byAlias = new LinkedHashMap<>();
            for (Entry entry : dimensionEntries) {
                for (String alias : entry.aliases()) {
                    String key = normalize(alias).toLowerCase(Locale.ROOT);
                    Entry previous = byAlias.putIfAbsent(key, entry);
                    if (previous != null && !previous.id().equals(entry.id())) {
                        throw new IllegalStateException("food evidence alias must be unique");
                    }
                }
            }
            result.put(dimension, Map.copyOf(byAlias));
        });
        return Map.copyOf(result);
    }

    private static String normalize(String value) {
        return value.trim().replaceAll("\\s+", " ");
    }

    public record Entry(String id, List<String> aliases) {

        public Entry {
            if (id == null || id.isBlank() || aliases == null || aliases.isEmpty()) {
                throw new IllegalArgumentException("food evidence entry values are required");
            }
            List<String> normalized = new ArrayList<>();
            for (String alias : aliases) {
                if (alias == null || normalize(alias).isBlank()) {
                    throw new IllegalArgumentException("food evidence alias is required");
                }
                String value = normalize(alias);
                if (!normalized.contains(value)) {
                    normalized.add(value);
                }
            }
            aliases = List.copyOf(normalized);
        }

        EvidenceTerm toTerm(
                String surface,
                StructuredFoodEvidenceSource source
        ) {
            return new EvidenceTerm(id, normalize(surface), aliases, source);
        }
    }
}
