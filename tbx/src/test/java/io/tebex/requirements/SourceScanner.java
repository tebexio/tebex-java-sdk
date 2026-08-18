package io.tebex.requirements;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Locates and reads the {@code tbx} module's <em>main</em> Java sources.
 *
 * <p>The {@code CODE_*} requirements are static-analysis checks over the source
 * tree. Two failure modes are guarded against here:
 * <ul>
 *   <li><strong>Vacuous pass</strong> — if the working directory is wrong the
 *       scanner would walk zero files and every "no violations found" check
 *       would pass because it never looked. {@link #mainSourceFiles()} therefore
 *       fails loudly if it cannot locate the source root, and callers assert the
 *       file set is non-empty.</li>
 *   <li><strong>Self-poisoning</strong> — the scan is scoped strictly to
 *       {@code src/main/java}. Test sources (which deliberately contain the very
 *       tokens the checks look for, as fixtures) are never scanned.</li>
 * </ul>
 */
final class SourceScanner {

    private SourceScanner() {
    }

    /**
     * Resolves the {@code tbx/src/main/java} directory.
     *
     * <p>Gradle runs tests with the working directory set to the module
     * directory, so {@code src/main/java} resolves directly. As a fallback (for
     * example when tests are launched from the repository root) the repository
     * tree is searched for {@code tbx/src/main/java}.
     *
     * @return the absolute path to the main source root
     * @throws IllegalStateException if the source root cannot be located
     */
    static Path mainSourceRoot() {
        Path workingDir = Paths.get("").toAbsolutePath();

        Path direct = workingDir.resolve("src/main/java");
        if (Files.isDirectory(direct)) {
            return direct;
        }

        for (Path candidate = workingDir; candidate != null; candidate = candidate.getParent()) {
            Path nested = candidate.resolve("tbx").resolve("src").resolve("main").resolve("java");
            if (Files.isDirectory(nested)) {
                return nested;
            }
        }

        throw new IllegalStateException(
                "Could not locate tbx/src/main/java starting from " + workingDir
                        + ". Run the requirements suite from the tbx module directory or the repository root.");
    }

    /**
     * Resolves the {@code tbx} module root (the directory that contains
     * {@code src}), derived from the located main source root.
     *
     * @return the absolute path to the module root
     */
    static Path moduleRoot() {
        // mainSourceRoot() == <module>/src/main/java
        return mainSourceRoot().getParent().getParent().getParent();
    }

    /**
     * Returns every {@code .java} file under the main source root.
     *
     * @return the list of main-source Java files (never empty in a valid module)
     */
    static List<Path> mainSourceFiles() {
        return javaFilesUnder(mainSourceRoot());
    }

    /**
     * Returns every {@code .java} file under the test source root, or an empty
     * list if the test source tree does not exist.
     *
     * @return the list of test-source Java files
     */
    static List<Path> testSourceFiles() {
        Path testRoot = moduleRoot().resolve("src").resolve("test").resolve("java");
        if (!Files.isDirectory(testRoot)) {
            return Collections.emptyList();
        }
        return javaFilesUnder(testRoot);
    }

    private static List<Path> javaFilesUnder(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to walk " + root, e);
        }
    }

    /**
     * Reads a source file as UTF-8 text.
     *
     * @param file the file to read
     * @return the file contents
     */
    static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + file, e);
        }
    }

    /**
     * Returns the source text with all comments removed, preserving string and
     * character literals. Used when scanning for annotation tags so that an
     * example such as {@code @Requirement("...")} written inside a Javadoc or
     * comment is not mistaken for a real tag.
     *
     * @param source the raw source text
     * @return the source with line and block comments stripped
     */
    static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);

            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                while (i < n && source.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
                    i++;
                }
                i += 2;
                continue;
            }
            if (c == '"' || c == '\'') {
                char quote = c;
                out.append(c);
                i++;
                while (i < n) {
                    char d = source.charAt(i);
                    out.append(d);
                    i++;
                    if (d == '\\' && i < n) {
                        out.append(source.charAt(i));
                        i++;
                    } else if (d == quote) {
                        break;
                    }
                }
                continue;
            }

            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * Reads a source file as a list of lines, preserving order.
     *
     * @param file the file to read
     * @return the lines of the file
     */
    static List<String> readLines(Path file) {
        try {
            return new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + file, e);
        }
    }
}
