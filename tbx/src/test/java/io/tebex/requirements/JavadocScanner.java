package io.tebex.requirements;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects type and member declarations that are not preceded by a Javadoc
 * comment, in support of requirement {@code CODE_003}.
 *
 * <p>This is a pragmatic, dependency-free scanner rather than a full Java
 * parser. It performs a comment- and string-aware pass over each line and
 * tracks brace depth so that:
 * <ul>
 *   <li>type declarations ({@code class}/{@code interface}/{@code enum}/
 *       {@code record}) are checked, and</li>
 *   <li>method and constructor declarations are checked only when they appear
 *       directly inside a type body (never for statements inside method
 *       bodies, lambdas, or anonymous classes).</li>
 * </ul>
 *
 * <p>Known limitations (documented deliberately): members of nested types and
 * the {@code class}/{@code TODO}-style keywords appearing inside string
 * literals are outside the scope of this heuristic. The scanner can be replaced
 * by a real parser (for example JavaParser) if stricter guarantees are needed.
 */
final class JavadocScanner {

    /** Keywords that look like a method call but are control-flow statements. */
    private static final Set<String> CONTROL_KEYWORDS = new HashSet<>(Arrays.asList(
            "if", "for", "while", "switch", "catch", "synchronized", "return", "new", "else"));

    private static final Pattern TYPE_DECL = Pattern.compile(
            "\\b(class|interface|enum|record)\\s+([A-Za-z_]\\w*)");

    private static final Pattern METHOD_DECL = Pattern.compile(
            "^(?:(?:public|protected|private|static|final|abstract|synchronized|native|default|strictfp)\\s+)*"
                    + "[A-Za-z_][\\w.<>\\[\\],?&\\s]*\\s+([A-Za-z_]\\w*)\\s*\\([^)]*\\)"
                    + "\\s*(?:throws[\\s\\w.,]+)?\\s*[;{]");

    private static final Pattern CONSTRUCTOR_DECL = Pattern.compile(
            "^(?:(?:public|protected|private)\\s+)*"
                    + "([A-Za-z_]\\w*)\\s*\\([^)]*\\)\\s*(?:throws[\\s\\w.,]+)?\\s*\\{");

    private JavadocScanner() {
    }

    /**
     * Scans a single source file and returns a description of each declaration
     * that is missing a preceding Javadoc comment.
     *
     * @param fileName the display name of the file being scanned
     * @param lines    the lines of the file, in order
     * @return a list of human-readable violations (empty if fully documented)
     */
    static List<String> undocumented(String fileName, List<String> lines) {
        List<String> violations = new ArrayList<>();
        Deque<Integer> typeMemberDepths = new ArrayDeque<>();
        Deque<String> typeNames = new ArrayDeque<>();

        boolean inBlockComment = false;
        boolean blockIsJavadoc = false;
        boolean pendingJavadoc = false;
        int braceDepth = 0;

        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            State state = strip(raw, inBlockComment, blockIsJavadoc);
            inBlockComment = state.inBlockComment;
            blockIsJavadoc = state.blockIsJavadoc;

            String code = state.code.trim();

            if (code.isEmpty()) {
                if (state.javadocClosed) {
                    pendingJavadoc = true;
                }
                continue;
            }

            // Annotation-only lines sit between the Javadoc and the declaration;
            // they neither satisfy nor consume the pending Javadoc.
            if (code.matches("@[\\w.]+(\\([^)]*\\))?")) {
                continue;
            }

            boolean memberLevel = !typeMemberDepths.isEmpty()
                    && braceDepth == typeMemberDepths.peek();

            Matcher typeMatcher = TYPE_DECL.matcher(code);
            if (typeMatcher.find()) {
                String typeName = typeMatcher.group(2);
                if (!pendingJavadoc) {
                    violations.add(fileName + ": type '" + typeName + "' (line " + (i + 1)
                            + ") has no Javadoc");
                }
                int before = braceDepth;
                braceDepth += netBraces(code);
                if (braceDepth > before) {
                    typeMemberDepths.push(braceDepth);
                    typeNames.push(typeName);
                }
                popClosedTypes(typeMemberDepths, typeNames, braceDepth);
                pendingJavadoc = false;
                continue;
            }

            if (memberLevel) {
                String memberName = memberName(code, typeNames.peek());
                if (memberName != null && !pendingJavadoc) {
                    violations.add(fileName + ": member '" + memberName + "' (line " + (i + 1)
                            + ") has no Javadoc");
                }
            }

            braceDepth += netBraces(code);
            popClosedTypes(typeMemberDepths, typeNames, braceDepth);
            pendingJavadoc = false;
        }

        return violations;
    }

    /** Returns the method/constructor name declared on this line, or null. */
    private static String memberName(String code, String enclosingType) {
        if (code.contains("=")) {
            return null; // field initialiser, not a declaration
        }
        Matcher method = METHOD_DECL.matcher(code);
        if (method.find()) {
            String name = method.group(1);
            if (!CONTROL_KEYWORDS.contains(name)) {
                return name;
            }
        }
        Matcher ctor = CONSTRUCTOR_DECL.matcher(code);
        if (ctor.find()) {
            String name = ctor.group(1);
            if (name.equals(enclosingType)) {
                return name;
            }
        }
        return null;
    }

    private static void popClosedTypes(Deque<Integer> depths, Deque<String> names, int braceDepth) {
        while (!depths.isEmpty() && braceDepth < depths.peek()) {
            depths.pop();
            names.pop();
        }
    }

    private static int netBraces(String code) {
        int net = 0;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                net++;
            } else if (c == '}') {
                net--;
            }
        }
        return net;
    }

    /**
     * Removes comments and preserves string/char literals from a single line,
     * carrying block-comment state across lines.
     */
    private static State strip(String line, boolean inBlock, boolean blockIsJavadoc) {
        StringBuilder code = new StringBuilder();
        boolean javadocClosed = false;
        int i = 0;
        int n = line.length();

        while (i < n) {
            char c = line.charAt(i);

            if (inBlock) {
                if (c == '*' && i + 1 < n && line.charAt(i + 1) == '/') {
                    if (blockIsJavadoc) {
                        javadocClosed = true;
                    }
                    inBlock = false;
                    blockIsJavadoc = false;
                    i += 2;
                } else {
                    i++;
                }
                continue;
            }

            if (c == '/' && i + 1 < n && line.charAt(i + 1) == '/') {
                break; // line comment: ignore the rest
            }

            if (c == '/' && i + 1 < n && line.charAt(i + 1) == '*') {
                boolean isJavadoc = i + 2 < n && line.charAt(i + 2) == '*'
                        && !(i + 3 < n && line.charAt(i + 3) == '/');
                inBlock = true;
                blockIsJavadoc = isJavadoc;
                i += 2;
                continue;
            }

            if (c == '"' || c == '\'') {
                char quote = c;
                code.append(c);
                i++;
                while (i < n) {
                    char d = line.charAt(i);
                    code.append(d);
                    i++;
                    if (d == '\\' && i < n) {
                        code.append(line.charAt(i));
                        i++;
                    } else if (d == quote) {
                        break;
                    }
                }
                continue;
            }

            code.append(c);
            i++;
        }

        return new State(code.toString(), inBlock, blockIsJavadoc, javadocClosed);
    }

    /** Result of stripping comments from one line. */
    private static final class State {
        final String code;
        final boolean inBlockComment;
        final boolean blockIsJavadoc;
        final boolean javadocClosed;

        State(String code, boolean inBlockComment, boolean blockIsJavadoc, boolean javadocClosed) {
            this.code = code;
            this.inBlockComment = inBlockComment;
            this.blockIsJavadoc = blockIsJavadoc;
            this.javadocClosed = javadocClosed;
        }
    }
}
