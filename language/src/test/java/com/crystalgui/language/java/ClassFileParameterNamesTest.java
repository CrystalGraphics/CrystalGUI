package com.crystalgui.language.java;

import com.crystalgui.language.java.classpath.ClassFileParameterNames;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * <b>M13 §25.1 — parameter names off the class file, with nothing shipped.</b>
 *
 * <p>The milestone's premise, asserted rather than trusted: names <em>survive compilation</em>, so the
 * headline benefit of source attachment is reachable on a plain JRE. Every expectation here was read out
 * of the running JDK with a probe before it was written down.</p>
 *
 * <p>No engine band and no classpath: this reads through the running loader, which is the route that
 * covers the JDK's own runtime image. That is the case that matters most and the one a classpath list
 * cannot reach.</p>
 */
public class ClassFileParameterNamesTest {

    private static final ClassFileParameterNames READER =
            ClassFileParameterNames.forClasspath(List.of());

    /** The headline case. {@code ArrayList.add} carries {@code e} and always has. */
    @Test
    public void aConcreteJdkMethodNamesItsParameter() {
        assertEquals(List.of("e"), READER.namesOf("java.util.ArrayList", "add",
                List.of("java.lang.Object")));
    }

    /**
     * <b>Trap one: slot 0 is {@code this} for an instance method and the FIRST PARAMETER for a static
     * one.</b>
     *
     * <p>{@code String.format} is the static case, and reading it as though slot 0 were {@code this}
     * shifts every name by one — producing {@code format(String args, Object[] ???)}, which is plausible,
     * wrong, and reads as authoritative.</p>
     */
    @Test
    public void aStaticMethodStartsAtSlotZero() {
        assertEquals(List.of("format", "args"), READER.namesOf("java.lang.String", "format",
                List.of("java.lang.String", "java.lang.Object[]")));
    }

    /**
     * <b>Trap two: the table holds every local, not just the parameters.</b>
     *
     * <p>{@code ArrayList.add(int, Object)} reports {@code [this, index, element, s, elementData]} —
     * taking the table wholesale would name five parameters for a method that has two.</p>
     */
    @Test
    public void trailingLocalsAreNotParameters() {
        assertEquals(List.of("index", "element"), READER.namesOf("java.util.ArrayList", "add",
                List.of("int", "java.lang.Object")));
    }

    /**
     * <b>Trap three: {@code long} and {@code double} occupy two slots each.</b>
     *
     * <p>So the parameter after a {@code long} is at slot 3 rather than 2, and a reader that advanced by
     * one would take whatever local happened to sit in the skipped slot.
     */
    @Test
    public void aWideParameterAdvancesTwoSlots() {
        assertEquals("a name after a long came out of the wrong slot",
                List.of("datum"), READER.namesOf(Fixture.class.getName(), "afterALong",
                        List.of("long", "java.lang.String")).subList(1, 2));
    }

    /**
     * <b>The shape of the gap: an interface method has no {@code Code}, so no local-variable table.</b>
     *
     * <p>Verified rather than assumed — {@code java.util.List} carries neither attribute. It matters more
     * than it sounds, because idiomatic Java declares variables as the interface, so {@code List.add} and
     * {@code Map.put} are exactly the hovers a reader performs most. {@code -parameters} is the only
     * mechanism that closes it, and only for code we compile.</p>
     */
    @Test
    public void anInterfaceMethodHasNoNamesWithoutTheParametersFlag() {
        assertNull(READER.namesOf("java.util.List", "add", List.of("java.lang.Object")));
    }

    /**
     * <b>...and {@code -parameters} is what closes it, for the code we compile.</b>
     *
     * <p>The other half of §25.1, and the reason it is a build flag rather than a reader change:
     * {@code MethodParameters} is the one attribute that does not need a {@code Code} attribute, so it is
     * the only mechanism that can name an interface method's parameters at all. Asserted against
     * <b>our own</b> interface, which is the surface it exists for — this stack is mostly SPI, so
     * {@code SourceAnalyzer.analyze} and every bridge seam were exactly the hovers that had nothing.</p>
     *
     * <p>Fails without the flag on {@code :language}, which is the point of asserting it here rather than
     * trusting the build file: a flag nothing reads is a flag somebody removes.</p>
     */
    @Test
    public void ourOwnInterfaceMethodsAreNamedBecauseWeCompileWithParameters() {
        assertEquals("`-parameters` is missing from :language's compilerArgs -- an interface method has "
                        + "no Code attribute, so nothing else can name it",
                List.of("classpath"),
                READER.namesOf(Named.class.getName(), "over", List.of("java.util.List")));

        // AND CORE'S, which is the module the SPI actually lives in. `Resolver` is the seam every
        // engine implements and every hover of an engine-supplied symbol goes through, and it had
        // nothing to say about its own parameters until this flag.
        assertEquals("`-parameters` is missing from :core's compilerArgs",
                List.of("offset", "answer"),
                READER.namesOf("com.crystalgui.text.lang.Resolver", "resolveAt",
                        List.of("int", "java.util.function.Consumer")));
    }

    /** An interface of our own, so the flag is asserted on the surface it was added for. */
    interface Named {
        void over(List<String> classpath);
    }

    /**
     * <b>Ambiguity answers nothing rather than guessing.</b>
     *
     * <p>Asked with an erased type that matches no overload, two same-arity candidates cannot be told
     * apart — and a signature showing one overload's names on another's types is worse than showing no
     * names at all, because it reads as authoritative. Falling back to types-only is exactly what the
     * caller did before this existed.</p>
     */
    @Test
    public void twoOverloadsOfOneArityAreNotGuessedBetween() {
        assertNull(READER.namesOf(Fixture.class.getName(), "ambiguous",
                List.of("java.lang.Void")));
    }

    /** A class the reader cannot find is not an error — it is types-only, as before. */
    @Test
    public void anUnreachableClassIsAnswerless() {
        assertNull(READER.namesOf("no.such.Type", "whatever", List.of("int")));
    }

    /** Our own code carries names today, with no build change — Gradle passes {@code -g} by default. */
    @Test
    public void ourOwnCompiledCodeCarriesItsNames() {
        assertEquals(List.of("point"), READER.namesOf("com.crystalgui.text.Rope", "pointToOffset",
                List.of("com.crystalgui.text.TextPoint")));
    }

    /** Compiled by this module's own build, so its locals are whatever the shipped flags produce. */
    @SuppressWarnings("unused")
    static final class Fixture {
        void afterALong(long width, String datum) {
            int unrelated = 1;
            if (width > 0) unrelated++;
        }

        void ambiguous(String one) {
        }

        void ambiguous(Integer other) {
        }
    }
}
