package com.crystalgui.language.platform;

import com.crystalgui.language.run.ClassReferences;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * <b>A class the engine band loads must never name {@code com.crystalgui.fs}.</b>
 *
 * <h3>The rule, and why this package specifically</h3>
 *
 * <p>{@code EngineClassLoader} is child-first over everything except the JDK, the bridge package and
 * {@code com.crystalgui.text.*}. So a class the band loads — mechanically, any class naming
 * {@code org.eclipse.jdt} or {@code org.mozilla.javascript} — resolves {@code com.crystalgui.fs.Resource}
 * through the <em>band's</em> loader, and if a copy is reachable there the band defines its own. Handing
 * one of those to a host-loaded type fails with the shape this repository has already paid for once:
 * <em>Resource cannot be cast to Resource</em>.</p>
 *
 * <p>{@code com.crystalgui.text.*} was widened into the parent-first set deliberately and that widening
 * is documented on {@code PARENT_FIRST}: it is pure Java with no natives, no GL and no engine, so
 * nothing there can drag a backend across. <b>{@code com.crystalgui.fs} is not that.</b> It is
 * thirty-two files that reach {@code com.crystalgui.net}, {@code com.crystalgui.serialization} and
 * {@code com.crystalgui.core.undo} — a workspace and RPC layer. Widening for it would pull all of that
 * into the shared surface to buy one record component, so the crossing goes the other way:
 * {@code DeclarationSite.inLibrary} takes a {@link String} and constructs the {@code Resource} inside a
 * host-loaded method.</p>
 *
 * <h3>Why a scan, and why it would otherwise be found in production only</h3>
 *
 * <p>Whether the band can actually see a second copy depends on what is on its URLs, and that
 * <b>differs between development and production</b>. {@code EngineHost} adds its own code source: under
 * Gradle that is {@code language/build/classes}, which contains no {@code com.crystalgui.fs} at all, so
 * delegation falls through to the parent and one copy exists. Under LaunchWrapper it is <b>the whole mod
 * jar</b>, which contains every package we ship.</p>
 *
 * <p>So the violation is invisible to every test, every harness run and every dev client, and appears
 * only in a built jar on a real game — the same shape as {@code ScriptNameEnvironment} silently
 * resolving against files for a release, and {@code isPackage} answering wrongly only on an obfuscated
 * host. A constant-pool scan fails at the commit that adds the import instead.</p>
 */
public class BandLoadedCodeAvoidsWorkspaceTypesTest {

    /**
     * What makes a class child-side, asked mechanically.
     *
     * <p>{@code AGENTS.md} states the rule in exactly these terms — "a class that imports
     * {@code org.mozilla.javascript} is child-side" — because the alternative is a maintained list, and
     * a list is a thing to forget an entry from. It had been a table once and it had two entries
     * wrong.</p>
     */
    private static final List<String> BAND_MARKERS = List.of(
            "org/eclipse/jdt/",
            "org/mozilla/javascript/",
            "org/benf/cfr/");

    /** What such a class may not name. */
    private static final String FORBIDDEN = "com/crystalgui/fs/";

    @Test
    public void theScanFindsChildSideClassesAtAll() throws IOException {
        // THE PRECONDITION, for the reason the grammar scan states: with no classes to read, every
        // assertion below passes for the wrong reason.
        assertTrue("no child-side classes found -- the scan is looking at the wrong place",
                childSideClasses().size() > 10);
    }

    @Test
    public void noBandLoadedClassNamesTheWorkspacePackage() throws IOException {
        List<String> offences = new ArrayList<>();
        for (Path file : childSideClasses()) {
            for (String name : ClassReferences.referencesOf(file)) {
                if (name.startsWith(FORBIDDEN)) {
                    offences.add(file.getFileName() + " -> " + name);
                }
            }
        }
        assertTrue("a class the engine band loads names " + FORBIDDEN
                + ", which the band would define its own copy of in a shipped jar"
                + " -- cross with a String and build it host-side, as DeclarationSite.inLibrary does: "
                + offences, offences.isEmpty());
    }

    /**
     * Every compiled class of ours whose constant pool names an engine type.
     *
     * <p>Read from the constant pool rather than from the {@code import} lines, for the same reason the
     * grammar scan does: an import can be absent while a fully-qualified reference is present, and it is
     * the reference that decides what a loader has to resolve.</p>
     */
    private static List<Path> childSideClasses() throws IOException {
        Path root = ClassReferences.mainClassesRoot(BandLoadedCodeAvoidsWorkspaceTypesTest.class);
        assertTrue("the main class output is missing -- run :language:compileJava", Files.isDirectory(root));
        List<Path> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".class"))::iterator) {
                Set<String> names = ClassReferences.referencesOf(file);
                boolean childSide = false;
                for (String name : names) {
                    for (String marker : BAND_MARKERS) {
                        if (name.startsWith(marker)) {
                            childSide = true;
                            break;
                        }
                    }
                    if (childSide) break;
                }
                if (childSide) found.add(file);
            }
        }
        assertFalse("no class names an engine type at all", found.isEmpty());
        return found;
    }
}
