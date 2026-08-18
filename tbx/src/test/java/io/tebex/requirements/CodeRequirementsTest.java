package io.tebex.requirements;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Requirement tests for the {@code CODE_*} family: static-analysis checks over
 * the {@code tbx} module's main source tree.
 *
 * <p>These tests are written from the requirement text in
 * {@link io.tebex.requirements.Requirements}, not from the implementation. The
 * scan is scoped to {@code src/main/java} (never test source) and every test
 * first asserts the scanned file set is non-empty, so a mis-resolved working
 * directory fails loudly instead of passing vacuously.
 */
class CodeRequirementsTest {

    /**
     * TODO/FIXME tags are, by universal IDE convention, written in upper case.
     * Matching case-sensitively distinguishes real tags from the lower-case
     * words "todo"/"fixme" that appear in the requirement descriptions in the
     * (main-source) {@code Requirements} registry.
     */
    private static final Pattern TODO_TAG = Pattern.compile("\\bTODO\\b");
    private static final Pattern FIXME_TAG = Pattern.compile("\\bFIXME\\b");

    /** A secret-key-like token: a run of 32+ alphanumerics (high entropy). */
    private static final Pattern SECRET_LIKE = Pattern.compile("[A-Za-z0-9]{32,}");

    /**
     * A public token: 4 alphanumerics, a dash, then a run of alphanumerics.
     *
     * <p>The bare "4-dash-alphanumeric" shape in the requirement text also
     * matches ordinary hyphenated words (e.g. {@code fast-forwarded}), so the
     * detector additionally requires the trailing segment to be token-like: at
     * least 16 characters and containing at least one digit. Real Tebex public
     * tokens are a short prefix followed by a long hash that satisfies both
     * constraints, while dictionary words and short identifiers do not.
     */
    private static final Pattern PUBLIC_TOKEN =
            Pattern.compile("\\b[A-Za-z0-9]{4}-(?=[A-Za-z0-9]*[0-9])[A-Za-z0-9]{16,}\\b");

    private static List<Path> sourceFiles() {
        List<Path> files = SourceScanner.mainSourceFiles();
        assertFalse(files.isEmpty(),
                "No .java files were scanned — the source root resolved to an empty tree. "
                        + "This would make every CODE_* check pass vacuously.");
        return files;
    }

    /** Collects "file:line: <line text>" for every line matching the pattern. */
    private static List<String> matches(Pattern pattern) {
        List<String> hits = new ArrayList<>();
        for (Path file : sourceFiles()) {
            List<String> lines = SourceScanner.readLines(file);
            for (int i = 0; i < lines.size(); i++) {
                if (pattern.matcher(lines.get(i)).find()) {
                    hits.add(file.getFileName() + ":" + (i + 1) + ": " + lines.get(i).trim());
                }
            }
        }
        return hits;
    }

    @Test
    @Requirement("CODE_001")
    @DisplayName("CODE_001: no TODO tags in the tbx main source")
    void noTodoTags() {
        List<String> hits = matches(TODO_TAG);
        assertTrue(hits.isEmpty(), "Found TODO tags in tbx main source:\n" + String.join("\n", hits));
    }

    @Test
    @Requirement("CODE_001")
    @DisplayName("CODE_001: TODO detector matches real tags and rejects lower-case prose")
    void todoDetectorBoundaries() {
        // Guards against a vacuous pass if the pattern ever regresses to matching
        // nothing, and locks in the deliberate upper-case-only convention.
        assertTrue(TODO_TAG.matcher("//TODO run task").find(), "an upper-case tag must be detected");
        assertTrue(TODO_TAG.matcher("        // TODO: fix").find(), "a spaced tag must be detected");
        assertFalse(TODO_TAG.matcher("there are no todo tags in the source").find(),
                "lower-case prose must not be treated as a tag");
    }

    @Test
    @Requirement("CODE_002")
    @DisplayName("CODE_002: no FIXME tags in the tbx main source")
    void noFixmeTags() {
        List<String> hits = matches(FIXME_TAG);
        assertTrue(hits.isEmpty(), "Found FIXME tags in tbx main source:\n" + String.join("\n", hits));
    }

    @Test
    @Requirement("CODE_002")
    @DisplayName("CODE_002: FIXME detector matches real tags and rejects lower-case prose")
    void fixmeDetectorBoundaries() {
        assertTrue(FIXME_TAG.matcher("//FIXME broken").find(), "an upper-case tag must be detected");
        assertFalse(FIXME_TAG.matcher("there are no fixme tags in the source").find(),
                "lower-case prose must not be treated as a tag");
    }

    @Test
    @Requirement("CODE_003")
    @DisplayName("CODE_003: every type and member in the tbx package has Javadoc")
    void allTypesAndMethodsHaveJavadoc() {
        List<String> violations = new ArrayList<>();
        for (Path file : sourceFiles()) {
            violations.addAll(JavadocScanner.undocumented(
                    file.getFileName().toString(), SourceScanner.readLines(file)));
        }
        assertTrue(violations.isEmpty(),
                "Undocumented types/members in tbx main source:\n" + String.join("\n", violations));
    }

    @Test
    @Requirement("CODE_003")
    @DisplayName("CODE_003: Javadoc detector flags undocumented declarations and ignores control flow")
    void javadocDetectorBoundaries() {
        List<String> documented = Arrays.asList(
                "/** A type. */",
                "public class Sample {",
                "    /** A method. */",
                "    public void doThing() {",
                "        if (true) { return; }",   // control flow inside a body must be ignored
                "    }",
                "}");
        assertTrue(JavadocScanner.undocumented("Sample.java", documented).isEmpty(),
                "a fully documented type and member must yield no violations");

        List<String> undocumented = Arrays.asList(
                "public class Sample {",           // type missing Javadoc
                "    public void doThing() {",      // member missing Javadoc
                "    }",
                "}");
        List<String> violations = JavadocScanner.undocumented("Sample.java", undocumented);
        assertEquals(2, violations.size(), "expected exactly the type and member: " + violations);
        assertTrue(violations.stream().anyMatch(v -> v.contains("Sample")), "type must be reported");
        assertTrue(violations.stream().anyMatch(v -> v.contains("doThing")), "member must be reported");
    }

    @Test
    @Requirement("CODE_004")
    @DisplayName("CODE_004: no secret-key-like strings in the tbx main source")
    void noSecretKeys() {
        List<String> hits = matches(SECRET_LIKE);
        assertTrue(hits.isEmpty(), "Found secret-key-like tokens in tbx main source:\n" + String.join("\n", hits));
    }

    @Test
    @Requirement("CODE_004")
    @DisplayName("CODE_004: secret-key detector matches keys and rejects ordinary text")
    void secretKeyDetectorBoundaries() {
        // Positive: a 40-char high-entropy token (this fixture lives in test
        // source, which is never scanned, so it cannot poison the scan above).
        assertTrue(SECRET_LIKE.matcher("aB3kf9Xz1QmR7vN2pL0sD4tY6uH8wJ5cE1gK3oI9").find(),
                "A 40-char alphanumeric key should be detected");
        // Negatives: short words, version numbers, and dotted package names.
        assertFalse(SECRET_LIKE.matcher("version").find(), "'version' is not a key");
        assertFalse(SECRET_LIKE.matcher("3.0.0").find(), "a version number is not a key");
        assertFalse(SECRET_LIKE.matcher("io.tebex.requirements").find(), "a package name is not a key");
    }

    @Test
    @Requirement("CODE_005")
    @DisplayName("CODE_005: no public tokens in the tbx main source")
    void noPublicTokens() {
        List<String> hits = matches(PUBLIC_TOKEN);
        assertTrue(hits.isEmpty(), "Found public tokens in tbx main source:\n" + String.join("\n", hits));
    }

    @Test
    @Requirement("CODE_005")
    @DisplayName("CODE_005: public-token detector matches the documented format and rejects near-misses")
    void publicTokenDetectorBoundaries() {
        // Positive: 4 alphanumerics, dash, long alphanumeric run with a digit.
        assertTrue(matchesWhole("abcd-1234567890abcdef"), "documented token format should match");
        assertTrue(matchesWhole("t66x-0a1b2c3d4e5f6a7b"), "documented token format should match");
        // Just outside: fewer than 4 leading chars.
        assertFalse(matchesWhole("abc-1234567890abcdef"), "3 leading chars must be rejected");
        // Just outside: trailing run too short to be a token.
        assertFalse(matchesWhole("abcd-123"), "short trailing run must be rejected");
        // Adversarial: ordinary hyphenated words (no digit) and long all-letter
        // words must not be mistaken for tokens.
        assertFalse(matchesWhole("test-value"), "an ordinary hyphenated word is not a token");
        assertFalse(matchesWhole("fast-forwarded"), "an ordinary hyphenated word is not a token");
        assertFalse(matchesWhole("data-transformation"), "a long all-letter word is not a token");
        // Adversarial: underscore-style ids and UUIDs must not match.
        assertFalse(matchesWhole("CODE_001"), "an underscore requirement id is not a token");
        assertFalse(matchesWhole("12345678-1234-1234-1234-1234567890ab"), "a UUID is not a token");
    }

    private static boolean matchesWhole(String candidate) {
        Matcher matcher = PUBLIC_TOKEN.matcher(candidate);
        return matcher.find();
    }
}
