package com.crystalgui.text.lang;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link TypeSearchRegistry} — where "which types exist" is asked, and merged.
 *
 * <p>Headless because that is the whole claim of the seam: {@code core/} asks the question and an engine
 * in {@code language/} answers it, so the question itself must be answerable with no engine, no grammar
 * and no GL on the classpath. A test of this in {@code test} would prove nothing about the inversion.</p>
 */
public class TypeSearchRegistryTest {

    @After
    public void clearRegistry() {
        // A STATIC, so it outlives the class. Without this, whichever test runs next inherits whatever
        // providers this one left -- and it would pass or fail by Gradle's ordering, which is re-decided
        // every run. That exact shape cost a full diagnostic round on JavaLanguage.register.
        TypeSearchRegistry.resetForTesting();
    }

    private static TypeSearch.Result type(String pkg, String simple) {
        return new TypeSearch.Result(simple, pkg, "jar:x", SymbolKind.CLASS, false);
    }

    private static TypeSearch answering(TypeSearch.Result... results) {
        return (query, limit) -> new TypeSearch.Results(List.of(results), false);
    }

    private static List<String> namesOf(TypeSearch.Results results) {
        List<String> names = new ArrayList<>();
        for (TypeSearch.Result result : results.results()) names.add(result.qualifiedName());
        return names;
    }

    // ── Absence ─────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>No provider answers empty, and says so through {@link TypeSearchRegistry#hasProvider}.</b>
     *
     * <p>The state a deployment that ships no engine is permanently in, and it has to be a supported one
     * rather than an error — the same three-tier degradation the whole language stack rests on. The
     * separate {@code hasProvider} is what lets a command be <em>absent</em> rather than present and
     * always empty: the capability is missing, not disabled.</p>
     */
    @Test
    public void withNoProviderTheAnswerIsEmptyAndTheAbsenceIsVisible() {
        assertFalse("a fresh registry claims to have a provider", TypeSearchRegistry.hasProvider());
        assertTrue(TypeSearchRegistry.search("Array", 10).isEmpty());
    }

    // ── Merging ─────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>Two providers over one classpath do not list the same type twice.</b>
     *
     * <p>Not hypothetical: {@code TypeIndex}'s own visibility note records that it was widened from
     * package-private precisely so JavaScript could ask the same question about the same classpath. Two
     * engines answering identically is the design, so the duplicate is the default outcome — and it is
     * invisible from the row, since both spellings of {@code java.util.ArrayList} are correct.</p>
     */
    @Test
    public void oneTypeClaimedByTwoProvidersIsListedOnce() {
        TypeSearchRegistry.contribute(answering(type("java.util", "ArrayList")));
        TypeSearchRegistry.contribute(
                answering(type("java.util", "ArrayList"), type("java.awt", "List")));

        assertEquals(List.of("java.util.ArrayList", "java.awt.List"),
                namesOf(TypeSearchRegistry.search("List", 10)));
    }

    /**
     * <b>Same simple name in two packages is two types, not a duplicate.</b>
     *
     * <p>The other half of the row above, and the one a careless dedup key gets wrong.
     * {@code java.util.List} and {@code java.awt.List} are the canonical pair, and collapsing them would
     * hide whichever the user was looking for behind whichever provider answered first.</p>
     */
    @Test
    public void twoTypesSharingASimpleNameAreBothListed() {
        TypeSearchRegistry.contribute(
                answering(type("java.util", "List"), type("java.awt", "List")));

        assertEquals(List.of("java.util.List", "java.awt.List"),
                namesOf(TypeSearchRegistry.search("List", 10)));
    }

    /** <b>Contributing twice does not answer twice.</b> A host that opens two languages registers each. */
    @Test
    public void contributingTheSameProviderTwiceIsIdempotent() {
        TypeSearch provider = answering(type("java.util", "ArrayList"));
        TypeSearchRegistry.contribute(provider);
        TypeSearchRegistry.contribute(provider);

        assertEquals(1, TypeSearchRegistry.search("Arr", 10).results().size());
    }

    // ── Honesty about the cap ───────────────────────────────────────────────────────────────────

    /**
     * <b>Hitting the cap is reported, not silently obeyed.</b>
     *
     * <p>The one that matters most and shows least. Every index here is bounded, and a truncated list is
     * indistinguishable from a complete one unless it says so — so a type that exists but fell past the
     * cap looks exactly like a type that does not exist. That is the worst answer a search can give: it
     * is wrong, and it is wrong in the direction that stops the user looking.</p>
     */
    @Test
    public void reachingTheLimitIsReportedAsTruncation() {
        TypeSearchRegistry.contribute(answering(
                type("a", "One"), type("a", "Two"), type("a", "Three")));

        TypeSearch.Results capped = TypeSearchRegistry.search("T", 2);
        assertEquals(2, capped.results().size());
        assertTrue("the cap bit and nothing said so", capped.truncated());

        TypeSearch.Results whole = TypeSearchRegistry.search("T", 10);
        assertEquals(3, whole.results().size());
        assertFalse("nothing was cut, yet it claims truncation", whole.truncated());
    }

    /** <b>A provider's own truncation propagates</b> — the registry is not the only thing that can cut. */
    @Test
    public void aProvidersOwnTruncationIsCarriedOut() {
        TypeSearchRegistry.contribute(
                (query, limit) -> new TypeSearch.Results(List.of(type("a", "One")), true));

        assertTrue(TypeSearchRegistry.search("One", 10).truncated());
    }

    // ── A broken engine ─────────────────────────────────────────────────────────────────────────

    /**
     * <b>A provider that throws costs its own rows and nothing else.</b>
     *
     * <p>This runs inside a keystroke handler. An exception out of one engine would take the popup down
     * mid-type, which is a far worse outcome than a shorter list — and the failure would be attributed to
     * the picker rather than to whichever engine is broken.</p>
     */
    @Test
    public void aThrowingProviderDoesNotTakeTheSearchWithIt() {
        TypeSearchRegistry.contribute((query, limit) -> {
            throw new IllegalStateException("engine is on fire");
        });
        TypeSearchRegistry.contribute(answering(type("java.util", "ArrayList")));

        assertEquals(List.of("java.util.ArrayList"),
                namesOf(TypeSearchRegistry.search("Arr", 10)));
    }

    /** <b>A provider answering null is treated as answering nothing.</b> */
    @Test
    public void aNullAnswerIsSurvivable() {
        TypeSearchRegistry.contribute((query, limit) -> null);
        TypeSearchRegistry.contribute(answering(type("java.util", "ArrayList")));

        assertEquals(1, TypeSearchRegistry.search("Arr", 10).results().size());
    }

    /** <b>An empty query lists nothing</b> — "every type on the classpath" is not a list anybody wants. */
    @Test
    public void anEmptyQueryListsNothing() {
        TypeSearchRegistry.contribute(answering(type("java.util", "ArrayList")));

        assertTrue(TypeSearchRegistry.search("", 10).isEmpty());
        assertTrue(TypeSearchRegistry.search(null, 10).isEmpty());
    }

    /** <b>A default package leaves the qualified name bare</b>, rather than prefixing it with a dot. */
    @Test
    public void aTypeInTheDefaultPackageIsNamedByItself() {
        assertEquals("Main", new TypeSearch.Result("Main", "", null, SymbolKind.CLASS, false)
                .qualifiedName());
        assertEquals("Main", new TypeSearch.Result("Main", null, null, SymbolKind.CLASS, false)
                .qualifiedName());
    }
}
