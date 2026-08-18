package io.tebex.requirements;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Requirement tests for {@code CODE_006}: tbx must compile to Java 8 bytecode so
 * that every platform module can consume it, and must use no API newer than
 * Java 8.
 *
 * <p>Written from the requirement text, not the build script. The requirement has
 * two clauses and each is checked independently:
 * <ul>
 *   <li><b>Bytecode</b> — every compiled main class carries a class-file major
 *       version of at most 52 (Java 8). This is the clause that actually decides
 *       whether a Java 8 module can read the jar: {@code javac} refuses a class
 *       file newer than its own target, so a single 61 (Java 17) class breaks the
 *       consumer.</li>
 *   <li><b>API</b> — no main source file references an API introduced after
 *       Java 8. A Java 8 <em>toolchain</em> already enforces this at compile time,
 *       but the scan states the rule independently of the build, so that
 *       weakening the build (for example to {@code targetCompatibility} alone,
 *       which sets the bytecode version but keeps the newer class library on the
 *       compile classpath) fails here rather than at runtime on an old server.</li>
 * </ul>
 *
 * <p>Both checks guard against a vacuous pass: the bytecode scan fails if it
 * cannot locate the compiled output or finds no classes, and the version detector
 * has positive and negative boundary tests so it cannot regress to accepting
 * everything.
 */
class BytecodeTargetTest {

    /** The class-file major version emitted for Java 8 (the highest allowed). */
    private static final int JAVA_8_MAJOR_VERSION = 52;

    /** The first four bytes of every valid class file. */
    private static final int CLASS_FILE_MAGIC = 0xCAFEBABE;

    /** The number of header bytes needed to read magic, minor and major version. */
    private static final int HEADER_BYTES = 8;

    /**
     * APIs added after Java 8 that a main source file must not reference. Each
     * entry pairs a detector with the Java version that introduced the API, so a
     * failure says what to use instead of merely that something is wrong.
     */
    private static final List<PostJava8Api> POST_JAVA_8_APIS = buildApiDetectors();

    /**
     * Builds the post-Java-8 API detector list.
     *
     * @return the detectors to run over every main source file
     */
    private static List<PostJava8Api> buildApiDetectors() {
        List<PostJava8Api> apis = new ArrayList<>();
        apis.add(new PostJava8Api("java.net.http (Java 11)",
                Pattern.compile("\\bjava\\.net\\.http\\.")));
        apis.add(new PostJava8Api("List.of/Map.of/Set.of factories (Java 9)",
                Pattern.compile("\\b(List|Map|Set)\\.of\\s*\\(")));
        apis.add(new PostJava8Api("var (Java 10)",
                Pattern.compile("(^|[^\\w.])var\\s+\\w+\\s*=")));
        apis.add(new PostJava8Api("String.isBlank/strip/repeat/lines (Java 11)",
                Pattern.compile("\\.(isBlank|strip|stripLeading|stripTrailing|repeat|lines)\\s*\\(")));
        apis.add(new PostJava8Api("Files.readString/writeString (Java 11)",
                Pattern.compile("\\bFiles\\.(readString|writeString)\\s*\\(")));
        // Stream.toList() is Java 16, but Collectors.toList() is Java 8 and reads
        // identically apart from the receiver, so exclude that receiver by name.
        apis.add(new PostJava8Api("Stream.toList (Java 16)",
                Pattern.compile("(?<!Collectors)\\.toList\\s*\\(\\s*\\)")));
        apis.add(new PostJava8Api("InputStream.readAllBytes (Java 9)",
                Pattern.compile("\\.readAllBytes\\s*\\(\\s*\\)")));
        return apis;
    }

    @Test
    @Requirement("CODE_006")
    @DisplayName("CODE_006: every compiled tbx main class is Java 8 bytecode (major version <= 52)")
    void mainClassesAreJava8Bytecode() {
        List<Path> classFiles = compiledMainClasses();
        assertFalse(classFiles.isEmpty(),
                "No .class files were scanned under " + compiledMainClassesRoot()
                        + " — this would make the bytecode check pass vacuously.");

        List<String> violations = new ArrayList<>();
        for (Path classFile : classFiles) {
            int major = majorVersion(readHeader(classFile));
            if (major > JAVA_8_MAJOR_VERSION) {
                violations.add(classFile.getFileName() + ": major version " + major
                        + " (expected " + JAVA_8_MAJOR_VERSION + " or lower)");
            }
        }

        assertTrue(violations.isEmpty(),
                "tbx must compile to Java 8 bytecode so Java 8 platform modules can consume it. "
                        + "Offending classes:\n" + String.join("\n", violations));
    }

    @Test
    @Requirement("CODE_006")
    @DisplayName("CODE_006: bytecode detector accepts Java 8, rejects Java 17, and rejects non-class data")
    void bytecodeDetectorBoundaries() {
        // Lower/at boundary: Java 8 and older are acceptable.
        assertEquals(JAVA_8_MAJOR_VERSION, majorVersion(classHeader(JAVA_8_MAJOR_VERSION)));
        assertTrue(majorVersion(classHeader(49)) <= JAVA_8_MAJOR_VERSION, "Java 5 bytecode is not newer than 8");

        // Just outside: Java 9 and the Java 17 the module used to emit.
        assertTrue(majorVersion(classHeader(53)) > JAVA_8_MAJOR_VERSION, "Java 9 bytecode must be rejected");
        assertTrue(majorVersion(classHeader(61)) > JAVA_8_MAJOR_VERSION, "Java 17 bytecode must be rejected");

        // Adversarial: data that is not a class file must fail loudly rather than
        // being read as some arbitrary version.
        byte[] notAClassFile = new byte[] {0x50, 0x4B, 0x03, 0x04, 0, 0, 0, 52};
        assertThrows(IllegalStateException.class, () -> majorVersion(notAClassFile),
                "a file without the class-file magic must be rejected");

        // Empty/truncated input must also fail loudly.
        assertThrows(IllegalStateException.class, () -> majorVersion(new byte[0]),
                "an empty header must be rejected");
        assertThrows(IllegalStateException.class, () -> majorVersion(new byte[] {(byte) 0xCA, (byte) 0xFE}),
                "a truncated header must be rejected");
    }

    @Test
    @Requirement("CODE_006")
    @DisplayName("CODE_006: no tbx main source references an API newer than Java 8")
    void mainSourceUsesNoPostJava8Api() {
        List<Path> files = SourceScanner.mainSourceFiles();
        assertFalse(files.isEmpty(),
                "No .java files were scanned — this would make the API check pass vacuously.");

        List<String> violations = new ArrayList<>();
        for (Path file : files) {
            // Comments are stripped so that prose naming an API (for example a
            // Javadoc note about java.net.http) is not read as a use of it.
            String code = SourceScanner.stripComments(SourceScanner.read(file));
            for (PostJava8Api api : POST_JAVA_8_APIS) {
                if (api.detector.matcher(code).find()) {
                    violations.add(file.getFileName() + ": uses " + api.description);
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "tbx must not use any API newer than Java 8:\n" + String.join("\n", violations));
    }

    @Test
    @Requirement("CODE_006")
    @DisplayName("CODE_006: post-Java-8 API detectors match real usage and reject Java 8 equivalents")
    void postJava8ApiDetectorBoundaries() {
        assertTrue(detects("import java.net.http.HttpClient;"), "a java.net.http import must be detected");
        assertTrue(detects("List<String> x = List.of(\"a\");"), "List.of must be detected");
        assertTrue(detects("var name = \"value\";"), "var must be detected");
        assertTrue(detects("if (text.isBlank()) { return; }"), "String.isBlank must be detected");
        assertTrue(detects("return stream.toList();"), "Stream.toList must be detected");

        // Java 8 equivalents — the replacements this module actually uses — must
        // not be flagged, or the check would be unsatisfiable.
        assertFalse(detects("import java.net.HttpURLConnection;"), "HttpURLConnection is Java 8");
        assertFalse(detects("List<String> x = Arrays.asList(\"a\");"), "Arrays.asList is Java 8");
        assertFalse(detects("return Collections.emptyList();"), "Collections.emptyList is Java 8");
        assertFalse(detects("if (text.trim().isEmpty()) { return; }"), "trim().isEmpty() is Java 8");
        assertFalse(detects("return new String(Files.readAllBytes(file), UTF_8);"),
                "Files.readAllBytes(Path) is Java 7");
        assertFalse(detects("stream.collect(Collectors.toList());"), "Collectors.toList is Java 8");
        // Adversarial: identifiers that merely contain a flagged word.
        assertFalse(detects("private String variant = compute();"), "'variant' is not the var keyword");
        assertFalse(detects("this.varsityTeam = team;"), "'varsityTeam' is not the var keyword");
    }

    /**
     * Returns whether any post-Java-8 detector matches the given snippet.
     *
     * @param snippet a line of source code
     * @return {@code true} if the snippet uses a post-Java-8 API
     */
    private static boolean detects(String snippet) {
        for (PostJava8Api api : POST_JAVA_8_APIS) {
            if (api.detector.matcher(snippet).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds a synthetic class-file header declaring the given major version.
     *
     * @param major the class-file major version to encode
     * @return an 8-byte header: magic, minor version, major version
     */
    private static byte[] classHeader(int major) {
        return new byte[] {
                (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE,
                0, 0,
                (byte) ((major >> 8) & 0xFF), (byte) (major & 0xFF)
        };
    }

    /**
     * Reads the class-file major version from an 8-byte header.
     *
     * @param header the first bytes of a class file
     * @return the major version
     * @throws IllegalStateException if the header is truncated or is not a class file
     */
    private static int majorVersion(byte[] header) {
        if (header.length < HEADER_BYTES) {
            throw new IllegalStateException("Not a class file: header is only " + header.length + " bytes");
        }
        int magic = ((header[0] & 0xFF) << 24) | ((header[1] & 0xFF) << 16)
                | ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
        if (magic != CLASS_FILE_MAGIC) {
            throw new IllegalStateException("Not a class file: bad magic " + Integer.toHexString(magic));
        }
        return ((header[6] & 0xFF) << 8) | (header[7] & 0xFF);
    }

    /**
     * Reads the first bytes of a class file.
     *
     * @param classFile the file to read
     * @return the file's leading header bytes
     */
    private static byte[] readHeader(Path classFile) {
        try {
            byte[] all = Files.readAllBytes(classFile);
            if (all.length < HEADER_BYTES) {
                return all;
            }
            byte[] header = new byte[HEADER_BYTES];
            System.arraycopy(all, 0, header, 0, HEADER_BYTES);
            return header;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + classFile, e);
        }
    }

    /**
     * Locates the compiled main-classes root by resolving a known main class as a
     * classpath resource and walking back up its package directories.
     *
     * <p>Deliberately not derived from the build script's output layout: the check
     * must hold for however the module is compiled.
     *
     * @return the directory containing the compiled main classes
     * @throws IllegalStateException if the compiled output cannot be located
     */
    private static Path compiledMainClassesRoot() {
        String resourceName = "io/tebex/requirements/Requirements.class";
        URL resource = BytecodeTargetTest.class.getClassLoader().getResource(resourceName);
        if (resource == null) {
            throw new IllegalStateException(
                    "Could not locate " + resourceName + " on the test classpath. "
                            + "The bytecode check needs the compiled main classes.");
        }
        if (!"file".equals(resource.getProtocol())) {
            throw new IllegalStateException(
                    "Expected the compiled main classes as files on disk, but found " + resource
                            + ". Run this check against the module's class output, not a jar.");
        }

        Path classFile;
        try {
            classFile = Paths.get(resource.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Could not resolve " + resource, e);
        }

        // Strip one directory per package segment, plus the file itself.
        Path root = classFile;
        int segments = resourceName.split("/").length;
        for (int i = 0; i < segments; i++) {
            root = root.getParent();
            if (root == null) {
                throw new IllegalStateException("Could not derive the classes root from " + classFile);
            }
        }
        return root;
    }

    /**
     * Returns every compiled main class, including nested and anonymous classes.
     *
     * @return the {@code .class} files of the module's main output
     */
    private static List<Path> compiledMainClasses() {
        Path root = compiledMainClassesRoot();
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".class"))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to walk " + root, e);
        }
    }

    /**
     * A post-Java-8 API paired with the detector that finds its use in source.
     */
    private static final class PostJava8Api {

        /** Human-readable API name and the Java version that introduced it. */
        private final String description;

        /** The pattern that matches a use of the API in source code. */
        private final Pattern detector;

        /**
         * Creates a detector entry.
         *
         * @param description the API name and introducing Java version
         * @param detector    the pattern matching its use
         */
        PostJava8Api(String description, Pattern detector) {
            this.description = description;
            this.detector = detector;
        }
    }
}
