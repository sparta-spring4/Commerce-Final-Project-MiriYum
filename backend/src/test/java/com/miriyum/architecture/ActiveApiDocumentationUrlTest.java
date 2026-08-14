package com.miriyum.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ActiveApiDocumentationUrlTest {

    private static final List<Path> ACTIVE_DOCUMENTATION_ROOTS = List.of(
            Path.of("..", "docs", "specs"),
            Path.of("..", "handoff")
    );
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern LEGACY_URL = Pattern.compile(
            "/api/v1/consumers/(?:reservations|pickup-reservations|payments)(?![a-z0-9-])"
                    + "|/(?:publication-cancellation|publication|retirement|cancellation|"
                    + "call|arrive|check-in|cancel)(?![a-z0-9-])"
                    + "|/waiting-close-jobs(?![a-z0-9-])"
                    + "|/waiting-settings/disable-impact(?![a-z0-9-])"
                    + "|/disable-impact(?![a-z0-9-])"
                    + "|/alternatives/search(?![a-z0-9-])"
    );

    @Test
    void activeConsumerDocumentationDoesNotReferenceLegacyUrls() throws IOException {
        List<String> violations = new ArrayList<>();

        for (Path root : ACTIVE_DOCUMENTATION_ROOTS) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".md"))
                        .sorted()
                        .toList()) {
                    List<String> lines = Files.readAllLines(file);
                    for (int index = 0; index < lines.size(); index++) {
                        Matcher codeSpan = INLINE_CODE.matcher(lines.get(index));
                        while (codeSpan.find()) {
                            Matcher legacyUrl = LEGACY_URL.matcher(codeSpan.group(1));
                            while (legacyUrl.find()) {
                                violations.add(file + ":" + (index + 1)
                                        + ": " + legacyUrl.group());
                            }
                        }
                    }
                }
            }
        }

        assertThat(violations)
                .as("legacy API URLs in active consumer documentation")
                .isEmpty();
    }
}
