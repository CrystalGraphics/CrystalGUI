package com.crystalgui.language.java;

import com.crystalgui.language.java.classpath.HostClasspath;
import com.crystalgui.language.java.classpath.TypeIndex;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Completing a qualified name, which is what an {@code import} line is.
 *
 * <p>The ordinary list matches a <b>simple</b> name, so {@code import net.mine} asked about {@code mine}
 * and matched nothing — the popup opened with no rows, on every classpath rather than only an obfuscated
 * one. These pin the query the import path needs; the popup itself is a widget and not what is under
 * test here.</p>
 */
public class ImportCompletionTest {

    private static TypeIndex index() {
        return JavaLanguageServices.typeIndexFor(HostClasspath.detect());
    }

    /**
     * <b>A half-typed segment matches, which is the whole point.</b>
     *
     * <p>{@code allUnder} already existed and answers on a dot boundary — right for "everything in
     * {@code java.util}", useless for {@code java.ut}, which is not a package and so matches nothing.
     * A completion list that only works once a segment is finished is one that never helps you finish
     * it.</p>
     */
    @Test
    public void aPartialSegmentMatchesOnTheQualifiedName() {
        TypeIndex.Match partial = index().startingWith("java.ut");
        assertFalse("a half-typed package segment matched nothing", partial.entries().isEmpty());
        for (TypeIndex.Entry entry : partial.entries()) {
            assertTrue(entry.qualifiedName(), entry.qualifiedName().startsWith("java.ut"));
        }
    }

    /** And a completed one still matches, so this is a widening rather than a replacement. */
    @Test
    public void aCompleteSegmentStillMatches() {
        TypeIndex.Match whole = index().startingWith("java.util.");
        assertFalse(whole.entries().isEmpty());
        boolean sawList = false;
        for (TypeIndex.Entry entry : whole.entries()) {
            if ("java.util.List".equals(entry.qualifiedName())) sawList = true;
        }
        assertTrue("java.util.List was not under java.util.", sawList
                || whole.truncated());
    }

    @Test
    public void nothingMatchesAnEmptyOrUnknownPrefix() {
        assertTrue(index().startingWith("").entries().isEmpty());
        assertTrue(index().startingWith(null).entries().isEmpty());
        assertTrue(index().startingWith("zzz.no.such.package").entries().isEmpty());
    }

    /**
     * <b>No nested type is ever offered</b>, however the classpath spells it.
     *
     * <p>{@code Map$Entry} cannot be imported under that name, and a row that inserts one produces a
     * compile error naming a type the list just offered. The filter used to run on the on-disk path,
     * which was correct for exactly as long as the two names were the same: 1.7.10 obfuscation gives an
     * inner class a <em>top-level</em> Notch name, so the dollar only appears after translation and the
     * list filled with {@code Minecraft$1} … {@code Minecraft$16} before {@code Minecraft} itself.</p>
     *
     * <p>Asserted over the whole index rather than one case, because the property is "no entry anywhere",
     * and a single example would keep passing if a new source of names skipped the filter.</p>
     */
    @Test
    public void theIndexHoldsNoNestedTypes() {
        for (TypeIndex.Entry entry : index().startingWith("java.").entries()) {
            assertFalse("a nested type reached the index: " + entry.qualifiedName(),
                    entry.qualifiedName().indexOf('$') >= 0);
        }
        for (TypeIndex.Entry entry : index().matching("Entry").entries()) {
            assertFalse("a nested type reached the index: " + entry.qualifiedName(),
                    entry.qualifiedName().indexOf('$') >= 0);
        }
    }
}
