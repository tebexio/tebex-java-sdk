package io.tebex.requirements;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Enforces requirement/test traceability in both directions and emits the
 * traceability matrix.
 *
 * <p>The gate fails unless coverage is <em>complete</em>. Three classes of gap
 * are checked and reported together:
 * <ul>
 *   <li><b>Forward</b> — every requirement in the registry (across
 *       <em>all</em> families: {@code TBX_*}, {@code CFG_*}, {@code TASK_*},
 *       {@code CODE_*}) must be covered by at least one {@code @Requirement}
 *       tagged test.</li>
 *   <li><b>Reverse</b> — every {@code @Test} method in the suite must carry at
 *       least one {@code @Requirement} tag (a test without a requirement is a
 *       gap).</li>
 *   <li><b>Unknown</b> — every {@code @Requirement} tag must name an id that
 *       actually exists in the registry.</li>
 * </ul>
 *
 * <p>This deliberately makes the build red while requirement families are only
 * partially covered: per the project methodology, a build cannot be considered
 * successful with any gaps. The generated matrix lists exactly what is missing.
 *
 * <p>The one way out is an explicit one. A requirement the project has decided
 * not to build yet is registered as <em>deferred</em>, with the reason recorded
 * next to it; the forward check skips those, and the matrix lists them and their
 * reasons under their own heading. A deferral with no reason fails the gate, so
 * this cannot be used to quietly delete a gap.
 *
 * <p>Note: coverage is proven by {@code @Requirement} tags, so a requirement
 * counts as covered as soon as one test tags it — this gate does not assert the
 * test <em>fully</em> exercises the requirement's semantics.
 */
class TraceabilityTest {

    // Requirement ids are alphanumeric/underscore (e.g. CODE_001). Constraining
    // the capture to those characters means a stray "@Requirement (" written
    // inside a string literal — such as in this test's own failure messages —
    // cannot be misread as a real tag.
    private static final Pattern TAG = Pattern.compile("@Requirement\\s*\\(\\s*\"([A-Za-z0-9_]+)\"\\s*\\)");

    /** Maps each requirement id to the set of test files that cover it. */
    private static Map<String, Set<String>> coverage() {
        Map<String, Set<String>> coverage = new TreeMap<>();
        for (Path file : SourceScanner.testSourceFiles()) {
            String content = SourceScanner.stripComments(SourceScanner.read(file));
            Matcher matcher = TAG.matcher(content);
            while (matcher.find()) {
                coverage.computeIfAbsent(matcher.group(1), k -> new TreeSet<>())
                        .add(file.getFileName().toString());
            }
        }
        return coverage;
    }

    /**
     * Finds every {@code @Test} method that is not tagged with a
     * {@code @Requirement}. Heuristic, comment-aware: it accumulates the
     * contiguous block of annotations and blank lines preceding a declaration and
     * checks that a block containing {@code @Test} also contains
     * {@code @Requirement}. Assumes annotations are stacked on separate lines
     * (the suite's convention); {@code @Test @Requirement("X")} on one line is
     * outside this heuristic.
     */
    private static List<String> untaggedTests() {
        List<String> violations = new ArrayList<>();
        for (Path file : SourceScanner.testSourceFiles()) {
            String content = SourceScanner.stripComments(SourceScanner.read(file));
            String fileName = file.getFileName().toString();
            boolean sawTest = false;
            boolean sawRequirement = false;
            for (String raw : content.split("\n", -1)) {
                String line = raw.trim();
                if (line.startsWith("@Test")) {
                    sawTest = true;
                } else if (line.startsWith("@Requirement")) {
                    sawRequirement = true;
                }
                // A non-annotation, non-blank line ends the current annotation
                // block (it is the declaration itself, or unrelated code).
                if (!line.isEmpty() && !line.startsWith("@")) {
                    if (sawTest && !sawRequirement) {
                        violations.add(fileName + ": a @Test near '" + line + "' has no @Requirement tag");
                    }
                    sawTest = false;
                    sawRequirement = false;
                }
            }
        }
        return violations;
    }

    @Test
    @Requirement("CODE_001")
    @Requirement("CODE_002")
    @Requirement("CODE_003")
    @Requirement("CODE_004")
    @Requirement("CODE_005")
    @DisplayName("Every requirement is covered by a test and every test traces to a requirement")
    void everythingIsTraceable() {
        Set<String> registered = Requirements.ids();
        Set<String> deferred = Requirements.deferredIds();
        Map<String, Set<String>> coverage = coverage();

        writeMatrix(registered, deferred, coverage);

        // Unknown: no test may tag a requirement id that is not in the registry.
        Set<String> unknownTags = coverage.keySet().stream()
                .filter(id -> !registered.contains(id))
                .collect(Collectors.toCollection(TreeSet::new));

        // Forward: every registered requirement (all families) must be covered,
        // except those the registry records as deliberately deferred — each of
        // which carries the reason it is not built yet.
        Set<String> uncovered = registered.stream()
                .filter(id -> !coverage.containsKey(id))
                .filter(id -> !deferred.contains(id))
                .collect(Collectors.toCollection(TreeSet::new));

        // Reverse: every @Test must trace to a requirement.
        List<String> untagged = untaggedTests();

        // Deferral is only acceptable while it is explained.
        Set<String> unexplainedDeferrals = deferred.stream()
                .filter(id -> Requirements.get(id).getDeferralReason().trim().isEmpty())
                .collect(Collectors.toCollection(TreeSet::new));

        StringBuilder failures = new StringBuilder();
        if (!unexplainedDeferrals.isEmpty()) {
            failures.append("\nDeferred requirements with no recorded reason: ").append(unexplainedDeferrals);
        }
        if (!uncovered.isEmpty()) {
            failures.append("\nRequirements with no covering test (").append(uncovered.size())
                    .append("): ").append(uncovered);
        }
        if (!untagged.isEmpty()) {
            failures.append("\nTests with no requirement tag (").append(untagged.size())
                    .append("): ").append(untagged);
        }
        if (!unknownTags.isEmpty()) {
            failures.append("\nTests tag requirement ids not in the registry: ").append(unknownTags);
        }

        assertTrue(failures.length() == 0,
                "Traceability is incomplete — the build has gaps:" + failures);
    }

    /** Writes the full traceability matrix to build/reports for delivery. */
    private static void writeMatrix(Set<String> registered, Set<String> deferred,
                                    Map<String, Set<String>> coverage) {
        StringBuilder md = new StringBuilder();
        md.append("# Requirements Traceability Matrix\n\n");
        md.append("Generated by `TraceabilityTest` from the requirement registry and ")
                .append("`@Requirement` tags in the tbx test sources.\n\n");
        md.append("| Requirement | Covered | Covering test file(s) |\n");
        md.append("| --- | --- | --- |\n");

        Set<String> allIds = new TreeSet<>(registered);
        allIds.addAll(coverage.keySet());
        int covered = 0;
        for (String id : allIds) {
            boolean known = registered.contains(id);
            Set<String> tests = coverage.getOrDefault(id, Collections.<String>emptySet());
            String status = !known ? "UNKNOWN ID"
                    : !tests.isEmpty() ? "yes"
                    : deferred.contains(id) ? "deferred"
                    : "gap";
            if (known && !tests.isEmpty()) {
                covered++;
            }
            md.append("| ").append(id).append(" | ").append(status).append(" | ")
                    .append(tests.isEmpty() ? "—" : String.join(", ", tests)).append(" |\n");
        }
        md.append("\n**").append(covered).append("/").append(registered.size())
                .append("** registered requirements are covered by a test.\n");

        if (!deferred.isEmpty()) {
            md.append("\n## Deferred\n\nNot expected to have a covering test yet. ")
                    .append("Each carries the reason it has not been built.\n\n");
            for (String id : deferred) {
                md.append("- **").append(id).append("** — ")
                        .append(Requirements.get(id).getDeferralReason()).append('\n');
            }
        }

        try {
            Path reports = SourceScanner.moduleRoot().resolve("build").resolve("reports");
            Files.createDirectories(reports);
            Files.write(reports.resolve("traceability-matrix.md"),
                    md.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write traceability matrix", e);
        }
    }
}
