package com.crystalgui.language.java;

import com.crystalgui.language.java.classpath.HostClasspath;
import com.crystalgui.language.java.classpath.TypeIndex;

import org.junit.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A nested type is findable under the name an author writes.
 *
 * <h3>The class ran and the editor said it did not exist</h3>
 *
 * <p>The index dropped every name containing a {@code $}, on the true observation that {@code Map$Entry}
 * cannot be imported under that spelling. {@code Map.Entry} can, and so can
 * {@code net.minecraft.world.WorldSettings.GameType} — so a whole category of classpath type was absent
 * from Go To File, offered by no completion, and unreachable by Ctrl+B, while a script importing one
 * compiled and ran in the next pane. "That type does not exist" from an editor that is executing it is
 * about as misleading as a symptom gets.</p>
 *
 * <p>Asserted against the REAL classpath rather than a fixture, because the bug was in the translation
 * from a class-file path to a writable name, and a fixture that hands over names has already done that
 * translation itself.</p>
 */
public class NestedTypeIndexTest {

    private static TypeIndex index() {
        return JavaLanguageServices.typeIndexFor(HostClasspath.detect());
    }

    private static List<String> qualified(TypeIndex.Match match) {
        return match.entries().stream().map(TypeIndex.Entry::qualifiedName).collect(Collectors.toList());
    }

    /**
     * <b>{@code Map.Entry} is in the index, under the name you would import.</b>
     *
     * <p>Both halves matter: found at all, and found spelled with a DOT. Indexed under its binary name it
     * would still be "present", and inserting that import produces a compile error naming the very type
     * the list just offered — which reads as completion being broken rather than the name being
     * unusable, and is exactly why the original guard chose to drop it instead.</p>
     */
    @Test
    public void aNestedTypeIsIndexedUnderItsSourceName() {
        List<String> found = qualified(index().matching("Entry"));

        assertTrue("Map.Entry is not in the index at all: " + sample(found),
                found.contains("java.util.Map.Entry"));
        assertFalse("indexed under its BINARY name, which cannot be imported",
                found.contains("java.util.Map$Entry"));
    }

    /** It is found by the SIMPLE name, which is what somebody types into Go To File. */
    @Test
    public void aNestedTypeIsFoundByWhatSomebodyTypes() {
        assertTrue("typing the simple name finds nothing",
                qualified(index().matching("Entry")).stream()
                        .anyMatch(name -> name.endsWith(".Map.Entry")));
    }

    /**
     * <b>Anonymous and local classes stay out</b>, which is what the original guard was right about.
     *
     * <p>The JLS numbers an anonymous class ({@code Outer$1}) and prefixes a local one with a number
     * ({@code Outer$1Helper}); neither can be written in any spelling. They are numerous enough to fill a
     * completion list ahead of the type somebody wanted — the reported symptom was {@code Minecraft$1}
     * through {@code Minecraft$16} arriving before {@code Minecraft} itself.</p>
     */
    @Test
    public void anonymousAndLocalClassesAreStillExcluded() {
        for (String name : qualified(index().matching("1"))) {
            for (String segment : name.split("\\.")) {
                assertFalse("an unwritable nested name is in the index: " + name,
                        !segment.isEmpty() && Character.isDigit(segment.charAt(0)));
            }
        }
    }

    private static String sample(List<String> found) {
        return found.size() <= 8 ? found.toString() : found.subList(0, 8) + " (+" + (found.size() - 8) + ")";
    }
}
