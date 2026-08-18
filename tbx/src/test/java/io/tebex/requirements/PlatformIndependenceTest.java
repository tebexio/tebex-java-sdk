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
 * Requirement test for TBX_041: {@code tbx} must never implement or require a
 * Minecraft package.
 *
 * <p>This is the requirement that makes the module reusable — the same SDK is
 * consumed by Bukkit, BungeeCord, Velocity, Forge, Fabric and Sponge plugins, so
 * a single {@code org.bukkit} import here would tie all of them to one platform
 * and stop the module compiling anywhere else.
 *
 * <p>The scan is over the main source tree, in the same style as the other
 * {@code CODE_*} static checks: it looks for the package roots of the Minecraft
 * ecosystem anywhere in the source, not only in import statements, so a
 * fully-qualified reference or a reflective lookup by name is caught too.
 */
class PlatformIndependenceTest {

    /**
     * The package roots that would make this module platform-specific.
     *
     * <p>Includes the server platforms, the game itself, and the mod loaders. It
     * is a deny list rather than an allow list because the module's real
     * dependency set is tiny and stable (the JDK, Gson, and the generated
     * Headless client), and naming the things that must not appear says what the
     * requirement says.
     */
    private static final List<String> FORBIDDEN_PACKAGES = Arrays.asList(
            "org.bukkit",
            "org.spigotmc",
            "net.md_5.bungee",
            "com.velocitypowered",
            "net.minecraft",
            "net.minecraftforge",
            "net.neoforged",
            "net.fabricmc",
            "org.spongepowered",
            "com.mojang",
            "cpw.mods");

    /**
     * Returns every main-source file, failing loudly if the scan would be
     * vacuous.
     *
     * @return the files to scan
     */
    private static List<Path> sourceFiles() {
        List<Path> files = SourceScanner.mainSourceFiles();
        assertFalse(files.isEmpty(), "no sources were scanned — the check would pass vacuously");
        return files;
    }

    @Test
    @Requirement("TBX_041")
    @DisplayName("TBX_041: the tbx module references no Minecraft or platform package")
    void noMinecraftPackagesAreReferenced() {
        List<String> violations = new ArrayList<>();
        for (Path file : sourceFiles()) {
            String source = SourceScanner.stripComments(SourceScanner.read(file));
            for (String forbidden : FORBIDDEN_PACKAGES) {
                if (source.contains(forbidden)) {
                    violations.add(file.getFileName() + " references " + forbidden);
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "tbx must stay platform-agnostic:\n" + String.join("\n", violations));
    }

    @Test
    @Requirement("TBX_041")
    @DisplayName("TBX_041: the only third-party imports are the SDK's own dependencies")
    void thirdPartyImportsAreLimitedToTheSdksDependencies() {
        // A deny list only catches what it was told about, so this is the other
        // half: anything imported from outside the JDK, Gson and the generated
        // Headless client is a new dependency and has to be a deliberate choice.
        Pattern importLine = Pattern.compile("(?m)^import\\s+(?:static\\s+)?([\\w.]+)\\s*;");
        List<String> allowedPrefixes = Arrays.asList(
                "java.", "javax.", "io.tebex.", "com.google.gson.");

        List<String> unexpected = new ArrayList<>();
        for (Path file : sourceFiles()) {
            Matcher matcher = importLine.matcher(SourceScanner.stripComments(SourceScanner.read(file)));
            while (matcher.find()) {
                String imported = matcher.group(1);
                boolean allowed = false;
                for (String prefix : allowedPrefixes) {
                    if (imported.startsWith(prefix)) {
                        allowed = true;
                        break;
                    }
                }
                if (!allowed) {
                    unexpected.add(file.getFileName() + " imports " + imported);
                }
            }
        }

        assertTrue(unexpected.isEmpty(),
                "unexpected third-party imports in tbx main source:\n" + String.join("\n", unexpected));
    }

    @Test
    @Requirement("TBX_041")
    @DisplayName("TBX_041: the detector would actually catch a platform import")
    void detectorCatchesAPlatformImport() {
        // Positive control: without this, a scan that silently matched nothing
        // would look identical to a clean module.
        String offending = "package io.tebex;\nimport org.bukkit.Bukkit;\n";
        List<String> hits = new ArrayList<>();
        for (String forbidden : FORBIDDEN_PACKAGES) {
            if (offending.contains(forbidden)) {
                hits.add(forbidden);
            }
        }

        assertEquals(1, hits.size(), "the deny list must match a real platform import: " + hits);
        assertEquals("org.bukkit", hits.get(0));
    }
}
