package com.crystalgui.language.run;

import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * §19.5 — the policy decides by the MOST SPECIFIC rule, and can speak about members.
 *
 * <p>The model this replaced was "a denial is a veto, asked first". That ordering existed for a reason
 * worth keeping — {@code allow java.lang} must not undo {@code deny java.lang.reflect} — and specificity
 * keeps it, because the denial there is the narrower statement. What a veto could not express is the other
 * direction, which is the feature: an exception has to be sayable both ways.</p>
 */
public class ScriptPolicyTest {

    // ── The two directions ──────────────────────────────────────────────────────────────────────

    /** Deny a class, allow one member back. */
    @Test
    public void aMemberSurvivesTheDenialOfItsClass() {
        ScriptPolicy policy = ScriptPolicy.builder()
                .allow("java.util")
                .deny("java.util.List")
                .allow("java.util.List#size")
                .build();

        assertTrue("the one permitted member", policy.allowsMember("java.util.List", "size"));
        assertFalse("everything else on it", policy.allowsMember("java.util.List", "add"));
        assertTrue("a class the package still allows", policy.allowsClass("java.util.ArrayList"));
    }

    /**
     * <b>...and the class itself stays reachable, which is the part that is easy to get wrong.</b>
     *
     * <p>A name has to load and a type has to be nameable for a member of it to be called at all. Refusing
     * the class here would leave the permitted member unreachable and the rule inert — a control that
     * appears to work and does nothing, which is worse than not having it.</p>
     */
    @Test
    public void aClassWithAPermittedMemberIsStillReachable() {
        ScriptPolicy policy = ScriptPolicy.builder()
                .allow("java.util")
                .deny("java.util.List")
                .allow("java.util.List#size")
                .build();

        assertTrue("the type must be nameable for its member to be callable",
                policy.allowsClass("java.util.List"));
    }

    /** Allow a class, deny one member of it. */
    @Test
    public void aMemberCanBeTakenOutOfAnAllowedClass() {
        ScriptPolicy policy = ScriptPolicy.builder()
                .allow("java.lang.System")
                .deny("java.lang.System#exit")
                .build();

        assertTrue(policy.allowsClass("java.lang.System"));
        assertTrue("the console survives", policy.allowsMember("java.lang.System", "out"));
        assertFalse("the exit does not", policy.allowsMember("java.lang.System", "exit"));
    }

    // ── The property the old veto had, kept ─────────────────────────────────────────────────────

    /**
     * <b>An allow does not re-permit a narrower denial.</b>
     *
     * <p>The reason the old model asked denials first, and the thing specificity had to keep or
     * {@link ScriptPolicy#UNSAFE} would mean whatever the two lists happened to say about each other.</p>
     */
    @Test
    public void aBroadAllowDoesNotUndoANarrowerDeny() {
        ScriptPolicy policy = ScriptPolicy.builder()
                .allow("java.lang")
                .deny("java.lang.reflect")
                .build();

        assertTrue(policy.allowsClass("java.lang.String"));
        assertFalse(policy.allowsClass("java.lang.reflect.Method"));
    }

    /** ...and the deployment can still say the opposite, deliberately, by being narrower still. */
    @Test
    public void aNarrowerAllowDoesUndoABroaderDeny() {
        ScriptPolicy policy = ScriptPolicy.builder()
                .deny(ScriptPolicy.UNSAFE)
                .allow("java.lang.reflect.Array")
                .build();

        assertFalse(policy.allowsClass("java.lang.reflect.Method"));
        assertTrue("named more precisely than the denial that covers it",
                policy.allowsClass("java.lang.reflect.Array"));
    }

    /** Two rules of equal reach that disagree: the refusing one is the safe reading. */
    @Test
    public void aTieGoesToTheDenial() {
        ScriptPolicy policy = ScriptPolicy.builder()
                .allow("java.util.List")
                .deny("java.util.List")
                .build();

        assertFalse(policy.allowsClass("java.util.List"));
    }

    /** Order is not a signal — the same rules mean the same thing however a file is sorted. */
    @Test
    public void orderDoesNotMatter() {
        ScriptPolicy written = ScriptPolicy.builder()
                .allow("java.util").deny("java.util.List").allow("java.util.List#size").build();
        ScriptPolicy reversed = ScriptPolicy.builder()
                .allow("java.util.List#size").deny("java.util.List").allow("java.util").build();

        assertEquals(written.allowsClass("java.util.List"), reversed.allowsClass("java.util.List"));
        assertEquals(written.allowsMember("java.util.List", "size"),
                reversed.allowsMember("java.util.List", "size"));
        assertEquals(written.allowsMember("java.util.List", "add"),
                reversed.allowsMember("java.util.List", "add"));
    }

    // ── Patterns ────────────────────────────────────────────────────────────────────────────────

    /** A shape, wherever it appears — and it loses to anything named. */
    @Test
    public void aPatternIsTheBackgroundThatNamedRulesAreReadAgainst() {
        ScriptPolicy policy = ScriptPolicy.builder()
                .deny("~.*\\.internal\\..*")
                .allow("com.acme.internal.Supported")
                .build();

        assertFalse(policy.allowsClass("com.acme.internal.Hidden"));
        assertFalse(policy.allowsClass("org.other.internal.Thing"));
        assertTrue("named, so it beats the shape", policy.allowsClass("com.acme.internal.Supported"));
    }

    /** A member pattern, for a family of accessors. */
    @Test
    public void membersCanBeMatchedByPattern() {
        ScriptPolicy policy = ScriptPolicy.builder()
                .allow("com.acme")
                .deny("com.acme.Thing#~set.*")
                .build();

        assertTrue(policy.allowsMember("com.acme.Thing", "getValue"));
        assertFalse(policy.allowsMember("com.acme.Thing", "setValue"));
    }

    /** {@code #*} is "every member", written so it carries member specificity. */
    @Test
    public void everyMemberCanBeNamedExplicitly() {
        ScriptPolicy policy = ScriptPolicy.builder()
                .allow("com.acme")
                .deny("com.acme.Thing#*")
                .allow("com.acme.Thing#name")
                .build();

        assertFalse(policy.allowsMember("com.acme.Thing", "wipe"));
        assertTrue(policy.allowsMember("com.acme.Thing", "name"));
    }

    /** A pattern that does not compile is refused, not dropped. */
    @Test(expected = IllegalArgumentException.class)
    public void aMalformedPatternIsRefused() {
        ScriptPolicy.builder().deny("~[unclosed").build();
    }

    // ── The floor, which none of this reaches ───────────────────────────────────────────────────

    /** No rule of any specificity can permit the machinery that enforces policies. */
    @Test
    public void nothingCanPermitTheFloor() {
        ScriptPolicy policy = ScriptPolicy.builder()
                .allow("com.crystalgui.language.java.JavaLanguage")
                .allow("com.crystalgui.language.java.JavaLanguage#restrictTo")
                .build();

        assertFalse(policy.allowsClass("com.crystalgui.language.java.JavaLanguage"));
        assertFalse(policy.allowsMember("com.crystalgui.language.java.JavaLanguage", "restrictTo"));
    }

    /** ...but a policy that restricts nothing is not a policy to relax. */
    @Test
    public void theFloorDoesNotApplyToAllowAll() {
        assertTrue(ScriptPolicy.allowAll().allowsClass("com.crystalgui.language.java.JavaLanguage"));
    }

    // ── What the older factories always meant ───────────────────────────────────────────────────

    @Test
    public void theOlderFactoriesStillMeanWhatTheyMeant() {
        ScriptPolicy only = ScriptPolicy.of(List.of("java.util"));
        assertTrue(only.allowsClass("java.util.List"));
        assertTrue(only.allowsClass("java.util.concurrent.Future"));
        assertTrue("a nested class is part of the class its prefix named",
                only.allowsClass("java.util.Map$Entry"));
        assertFalse("the boundary is a dot, not a character count",
                only.allowsClass("java.utility.Thing"));
        assertFalse(only.allowsClass("java.lang.System"));

        assertTrue("an empty allowlist is a posture, not a mistake",
                !ScriptPolicy.of(List.of()).allowsClass("java.util.List"));
        assertTrue("a null list is not a restriction", ScriptPolicy.of(null).allowsEverything());
        assertTrue(ScriptPolicy.allowAll().allowsEverything());
        assertFalse(ScriptPolicy.denying(ScriptPolicy.UNSAFE).allowsEverything());
    }

    @Test
    public void arraysAndPrimitivesAreTheirElementType() {
        ScriptPolicy policy = ScriptPolicy.of(List.of("java.util"));
        assertTrue(policy.allowsClass("java.util.List[]"));
        assertTrue("the shutter's spelling, which is the one that sees an array",
                policy.allowsClass("[Ljava.util.List;"));
        assertFalse(policy.allowsClass("java.lang.System[]"));
        assertTrue(policy.allowsClass("int"));
        assertTrue(policy.allowsClass("[I"));
    }

    @Test
    public void aPathStaysWalkableToWhatIsAtTheEndOfIt() {
        ScriptPolicy policy = ScriptPolicy.of(List.of("java.util"));
        assertTrue("a root has to be offerable for what is under it to be reachable",
                policy.allowsPackage("java"));
        assertTrue(policy.allowsPackage("java.util.concurrent"));
        assertFalse(policy.allowsPackage("javax"));
    }

    /** A package is not its worst member: refusing one thing inside it does not close it. */
    @Test
    public void aDeniedMemberDoesNotClosePackage() {
        ScriptPolicy policy = ScriptPolicy.builder()
                .allow("java.lang")
                .deny("java.lang.System#exit")
                .build();

        assertTrue(policy.allowsPackage("java.lang"));
        assertTrue(policy.allowsClass("java.lang.System"));
    }
}
