package com.miriyum.domain.search.interpreter;

import java.text.Normalizer;

final class SearchInputNormalizer {

    private SearchInputNormalizer() {
    }

    static String normalize(String rawInput) {
        if (rawInput == null) {
            throw new IllegalArgumentException("rawInput must not be null");
        }
        StringBuilder withoutControls = new StringBuilder(rawInput.length());
        rawInput.codePoints().forEach(codePoint -> withoutControls.appendCodePoint(
                Character.isISOControl(codePoint) ? ' ' : codePoint));

        String compatible = Normalizer.normalize(withoutControls, Normalizer.Form.NFKC);
        StringBuilder collapsed = new StringBuilder(compatible.length());
        boolean previousWhitespace = true;
        for (int offset = 0; offset < compatible.length();) {
            int codePoint = compatible.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
                if (!previousWhitespace) {
                    collapsed.append(' ');
                    previousWhitespace = true;
                }
            } else {
                collapsed.appendCodePoint(codePoint);
                previousWhitespace = false;
            }
        }
        int length = collapsed.length();
        if (length > 0 && collapsed.charAt(length - 1) == ' ') {
            collapsed.setLength(length - 1);
        }
        return collapsed.toString();
    }
}
