package com.crystalgui.language.java;

import com.crystalgui.language.java.classpath.HostClasspath;
import com.crystalgui.language.java.classpath.TypeIndex;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Completing a qualified name, which is what an {@code import} line is.
 *
 * <p>The ordinary list matches a <b>simple</b> name, so {@code import net.mine} asked about {@code mine}
 * and matched nothing — an empty popup on every classpath, not only an obfuscated one. These pin the
 * query the import path needs; the popup itself is a widget and is not what is under test.</p>
 */
public class ImportCompletionTest {

    private static TypeIndex index() {
        return JavaLanguageServices.typeIndexFor(HostClasspath.detect());
    }

    /**
     * <b>Every sub-package, not a sample of them.</b>
     *
     * <p>The failure this pins is the one that shipped: the scan was bounded at forty <em>entries</em>,
     * so the package set was whatever could be derived from the first forty classes — which all live in
     * one sub-package, since entries are walked in name order. {@code net.minecraft} showed nine of its
     * twenty-seven packages and {@code java} would show one.</p>
     *
     * <p>Asserted against {@code java}, whose sub-packages are on any JDK and are numerous enough that a
     * capped scan cannot pass: {@code util}, {@code io}, {@code net}, {@code nio}, {@code text},
     * {@code time}, {@code math}, {@code security} and more, and {@code java.util} alone holds far more
     * than forty classes — so a scan that stopped early would never reach {@code java.time}.</p>
     */
    @Test
    public void everySubPackageIsOfferedRatherThanTheFirstFew() {
        TypeIndex.Children children = index().childrenOf("java", "");
        for (String expected : new String[]{"util", "io", "net", "nio", "text", "time", "math",
                "security", "lang"}) {
            assertTrue("java." + expected + " was not offered: " + children.packages(),
                    children.packages().contains(expected));
        }
    }

    /** A partial segment narrows the packages — which is the point of typing it. */
    @Test
    public void aPartialSegmentNarrowsThePackages() {
        TypeIndex.Children children = index().childrenOf("java", "ut");
        assertTrue(children.packages().contains("util"));
        assertFalse("an unrelated package survived a narrowing prefix",
                children.packages().contains("io"));
    }

    /**
     * <b>Types come from the EXACT package, never from under it.</b>
     *
     * <p>An import names one type; offering {@code java.util.concurrent.Future} while the caret is at
     * {@code java.util.} would insert a name that does not exist at that path.</p>
     */
    @Test
    public void typesAreTheOnesDirectlyInThePackage() {
        TypeIndex.Children children = index().childrenOf("java.util", "");
        assertFalse("no types at all in java.util", children.types().isEmpty());
        for (TypeIndex.Entry entry : children.types()) {
            assertEquals("a type from a nested package was offered: " + entry.qualifiedName(),
                    "java.util", entry.packageName());
        }
        // And the sub-packages are still there beside them.
        assertTrue(children.packages().contains("concurrent"));
    }

    /** Case-insensitive, because a list is matched the way names are typed rather than spelt. */
    @Test
    public void matchingIgnoresCase() {
        assertTrue(index().childrenOf("java", "UT").packages().contains("util"));
        assertFalse(index().childrenOf("java.util", "arrayl").types().isEmpty());
    }

    @Test
    public void anUnknownPrefixOffersNothing() {
        TypeIndex.Children none = index().childrenOf("zzz.no.such.package", "");
        assertTrue(none.packages().isEmpty());
        assertTrue(none.types().isEmpty());
    }

    /**
     * <b>No nested type is ever offered</b>, however the classpath spells it.
     *
     * <p>{@code Map$Entry} cannot be imported under that name, and a row that inserts one produces a
     * compile error naming a type the list just offered. The filter used to run on the on-disk path,
     * which was correct for exactly as long as the two names were the same: 1.7.10 obfuscation gives an
     * inner class a <em>top-level</em> Notch name, so the dollar only appears after translation and the
     * list filled with {@code Minecraft$1} … {@code Minecraft$16} before {@code Minecraft} itself.</p>
     */
    @Test
    public void theIndexHoldsNoNestedTypes() {
        for (TypeIndex.Entry entry : index().childrenOf("java.util", "").types()) {
            assertFalse("a nested type reached the index: " + entry.qualifiedName(),
                    entry.qualifiedName().indexOf('$') >= 0);
        }
        for (TypeIndex.Entry entry : index().matching("Entry").entries()) {
            assertFalse("a nested type reached the index: " + entry.qualifiedName(),
                    entry.qualifiedName().indexOf('$') >= 0);
        }
    }
}
