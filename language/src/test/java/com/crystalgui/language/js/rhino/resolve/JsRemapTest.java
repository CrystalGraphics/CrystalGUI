package com.crystalgui.language.js.rhino.resolve;

import com.crystalgui.language.engine.EngineHost;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.engine.bridge.Analysis;
import com.crystalgui.language.java.JavaLanguage;
import com.crystalgui.language.js.host.JsHost;
import com.crystalgui.language.js.rhino.resolve.JsTypeRef;
import com.crystalgui.text.syntax.SyntaxToken;
import com.crystalgui.language.map.MappingSet;
import com.crystalgui.text.lang.SymbolInfo;

import org.junit.After;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import com.crystalgui.language.js.JsLanguage;

/**
 * <b>M10.11 — a script written in readable names, running against a class that declares obfuscated ones.</b>
 *
 * <p>The row's third exit criterion. Without this the JavaScript engine is a developer's toy: on an
 * obfuscated Minecraft the method is {@code func_147439_a}, and a script written against that breaks on
 * every version bump and is unreadable in between.</p>
 *
 * <p>The fixture is the shape {@code RemapRoundTripTest} uses on the Java side — a class declaring the
 * runtime name, a {@code MappingSet} naming the readable one, and a caller written in readable names —
 * which is what makes the two languages provably agree about one mapping.</p>
 */
public class JsRemapTest {

    /**
     * The "obfuscated" class a script reaches.
     *
     * <p>Named as Minecraft's 1.7.10 mappings name things, because the point of the test is the shape of a
     * real deployment rather than a rename in the abstract.</p>
     */
    public static class ObfuscatedWorld {

        public static final List<String> CALLS = new ArrayList<>();

        /** {@code getBlock} under the mapping. */
        public String func_147439_a(int at) {
            CALLS.add("block@" + at);
            return "stone";
        }

        /** {@code isRemote} under the mapping. */
        public final boolean field_72995_K = true;

        /** Unmapped, so the same object proves a translation is not applied to everything. */
        public String plainName() {
            CALLS.add("plain");
            return "plain";
        }
    }

    private static final String INTERNAL = ObfuscatedWorld.class.getName().replace('.', '/');

    /** {@code func_147439_a} → {@code getBlock}, {@code field_72995_K} → {@code isRemote}. */
    private static MappingSet mappings() {
        return MappingSet.builder()
                .method(INTERNAL, "func_147439_a", "getBlock")
                .field(INTERNAL, "field_72995_K", "isRemote")
                .build();
    }

    @BeforeClass
    public static void openTheEngines() {
        Assume.assumeTrue("no staged engine directory; run :language:stageEngines",
                EngineHost.defaultSource() != EngineSource.NONE);
        JavaLanguage.register(null, EngineHost.defaultSource());
        Assume.assumeTrue("the staged directory has no Rhino for this band",
                JsLanguage.register(null, EngineHost.defaultSource()));
    }

    @After
    public void forgetTheMappings() {
        // PROCESS-WIDE, like the policy, so a test that left one installed would rename members in every
        // later test in the JVM -- and that failure reads as resolution breaking rather than as a leak.
        // BOTH POSTURES AT ONCE, through the one call that restores them: restoring half is how a later
        // class comes to see an allowlist nothing in it installed.
        JsLanguage.resetPosturesForTesting();
        ObfuscatedWorld.CALLS.clear();
    }

    private static Object run(String source, Map<String, Object> bindings) throws Throwable {
        JsHost host = new JsHost(JsLanguage.executor());
        try {
            return host.run(host.compileScript("Probe.js", source, Map.of()), bindings);
        } finally {
            host.close();
        }
    }

    private static Map<String, Object> world() {
        Map<String, Object> bindings = new LinkedHashMap<>();
        bindings.put("world", new ObfuscatedWorld());
        return bindings;
    }

    // ── Out: the script writes the readable name and the call lands ──────────────────────────────

    /** <b>The criterion.</b> A readable-name call reaches a member the class declares under another name. */
    @Test
    public void aReadableNameCallRunsAgainstARenamedMethod() throws Throwable {
        JsLanguage.useMemberNames(mappings());
        Object answer = run("world.getBlock(3);\n", world());
        assertEquals("stone", answer);
        assertEquals("the mapped call never reached the class", List.of("block@3"), ObfuscatedWorld.CALLS);
    }

    @Test
    public void aReadableFieldNameReadsTheRenamedField() throws Throwable {
        JsLanguage.useMemberNames(mappings());
        assertEquals(Boolean.TRUE, run("world.isRemote;\n", world()));
    }

    /**
     * The runtime name still works, and that is deliberate.
     *
     * <p>The declared name is asked for <em>first</em>: a class that genuinely has a member by the name
     * written — an unobfuscated build, or a mapping that is out of date in the harmless direction — must not
     * be shadowed by a translation. It is also the fast path for every unmapped lookup.</p>
     */
    @Test
    public void theDeclaredNameKeepsWorkingUnderAMapping() throws Throwable {
        JsLanguage.useMemberNames(mappings());
        assertEquals("stone", run("world.func_147439_a(1);\n", world()));
    }

    @Test
    public void anUnmappedMemberOfAMappedClassIsUntouched() throws Throwable {
        // A translation applies to the names a mapping names, and to nothing else.
        JsLanguage.useMemberNames(mappings());
        assertEquals("plain", run("world.plainName();\n", world()));
    }

    @Test
    public void withNoMappingTheReadableNameIsNotFound() throws Throwable {
        // The control: without this the test above could pass against a class that had `getBlock` all along.
        JsLanguage.useMemberNames(MappingSet.IDENTITY);
        try {
            run("world.getBlock(3);\n", world());
            fail("an unmapped readable name resolved to something");
        } catch (Throwable expected) {
            assertTrue(String.valueOf(expected.getMessage()),
                    String.valueOf(expected.getMessage()).contains("getBlock"));
        }
        assertEquals(List.of(), ObfuscatedWorld.CALLS);
    }

    /**
     * <b>A script that spells a member the way the RUNTIME does is still understood.</b>
     *
     * <h3>It ran perfectly and the editor could say nothing about it</h3>
     *
     * <p>Members are renamed on the way out of {@code InteropResolver}, so the list offers
     * {@code getBlock} and a script written against {@code func_147439_a} matched nothing in it. Nothing
     * failed: the runtime has that member under that name, so the call needed no translation and simply
     * worked. What was lost was everything the editor knows — no signature, no javadoc, no semantic
     * colour, and a documentation popup containing a bare word.</p>
     *
     * <p><b>Not inherited from the Java side, and that was measured.</b> Teaching the compile view both
     * spellings does not reach here: everything the Java engine reports comes back through
     * {@code asReadable} and collapses onto the readable name, so both spellings arrive as
     * {@code getBlock} and the typed identifier matches neither. This test was deleted once on the theory
     * that the Java fix covered it, and reverting the change failed it immediately.</p>
     *
     * <p>Both halves are asserted because they are one question asked twice: {@code memberCaptureAt} and
     * the popup both go through {@code resolveMember}, so a fix to one that missed the other would leave
     * the two disagreeing about whether the member exists.</p>
     */
    @Test
    public void aRuntimeSpelledMemberResolvesToItsReadableSelf() {
        JsLanguage.useMemberNames(mappings());
        String source = "var w = new " + ObfuscatedWorld.class.getName() + "();' + NL + '"
                + "w.func_147439_a(1);' + NL + '";

        SymbolInfo found = JsLanguage.analyzer().analyze("Probe.js", source, 1L)
                .resolveAt(source.indexOf(".func_147439_a") + 1);
        assertNotNull("a runtime-spelled member resolved to nothing at all", found);
        assertEquals("the popup must show the member as the mapping names it",
                "getBlock", found.name());

        assertTrue("a runtime-spelled member was left uncoloured, got " + capturesOver(source),
                capturesOver(source).stream().anyMatch(name -> name.startsWith("function")));
    }

    /**
     * The readable spelling keeps working — the counter-assertion that matters here.
     *
     * <p>A match written as "try the runtime name" rather than "try either" would answer for the
     * obfuscated script and silently stop answering for every ordinary one.</p>
     */
    @Test
    public void theReadableSpellingStillResolves() {
        JsLanguage.useMemberNames(mappings());
        String source = "var w = new " + ObfuscatedWorld.class.getName() + "();' + NL + '"
                + "w.getBlock(1);' + NL + '";

        SymbolInfo found = JsLanguage.analyzer().analyze("Probe.js", source, 1L)
                .resolveAt(source.indexOf(".getBlock") + 1);
        assertNotNull("the ordinary spelling stopped resolving", found);
        assertEquals("getBlock", found.name());
    }

    /** Every capture over the {@code func_147439_a} in {@code source}. */
    private static List<String> capturesOver(String source) {
        int at = source.indexOf("func_147439_a");
        List<String> found = new ArrayList<>();
        for (SyntaxToken token : JsLanguage.analyzer().analyze("Probe.js", source, 1L).semanticTokens()) {
            if (token.start() == at) found.add(token.name());
        }
        return found;
    }

    /** An unmapped class is unaffected, which is what keeps the cost off every JDK call. */
    @Test
    public void anUnmappedClassIsUnaffected() throws Throwable {
        JsLanguage.useMemberNames(mappings());
        assertEquals(1, ((Number) run(
                "var a = new java.util.ArrayList(); a.add('x'); a.size();\n", Map.of())).intValue());
    }

    /**
     * A mapping names the type that DECLARES the member, and a script calls it on what it is holding.
     *
     * <p>So the lookup walks the hierarchy. Without that, a mapped method declared on a supertype is
     * invisible the moment a script holds a subclass — which on a real deployment is nearly always.</p>
     */
    @Test
    public void aMappedMemberDeclaredOnASupertypeIsStillFound() throws Throwable {
        JsLanguage.useMemberNames(mappings());
        Map<String, Object> bindings = new LinkedHashMap<>();
        bindings.put("world", new Subclassed());
        assertEquals("stone", run("world.getBlock(9);\n", bindings));
    }

    /**
     * A subclass that declares nothing of its own — the ordinary case on a real deployment.
     *
     * <p><b>Inheritance, not composition</b>, and the first draft got that wrong: Rhino wraps the object's
     * own class, so the walk has to climb to the type the mapping names. A delegating class declares the
     * runtime name itself and so passes without exercising the walk at all — the test would have been green
     * against a translation that could not see a supertype.</p>
     */
    public static final class Subclassed extends ObfuscatedWorld {
    }

    // ── In: the member list shows the readable name ──────────────────────────────────────────────

    /**
     * The other direction, and it is not optional.
     *
     * <p>A member list read off the class is full of runtime names. Showing those would teach an author to
     * write them — at which point the executor's translation has nothing to translate — so a completion list
     * offering {@code func_147439_a} beside a runtime that accepts {@code getBlock} is an editor working
     * against its user.</p>
     */
    @Test
    public void aMemberListShowsTheReadableName() {
        Assume.assumeTrue(JavaLanguage.isAvailable());
        JsLanguage.useMemberNames(mappings());
        List<String> names = memberNamesOf(ObfuscatedWorld.class.getName());
        assertTrue("the readable name is not offered: " + names, names.contains("getBlock"));
        assertFalse("the runtime name is still being offered", names.contains("func_147439_a"));
        assertTrue("an unmapped member of a mapped class disappeared", names.contains("plainName"));
    }

    @Test
    public void withNoMappingTheMemberListShowsTheDeclaredName() {
        Assume.assumeTrue(JavaLanguage.isAvailable());
        JsLanguage.useMemberNames(MappingSet.IDENTITY);
        List<String> names = memberNamesOf(ObfuscatedWorld.class.getName());
        assertTrue("the control failed — the declared name is not listed either",
                names.contains("func_147439_a"));
        assertFalse(names.contains("getBlock"));
    }

    /** The members of a Java class as the editor would list them. */
    private static List<String> memberNamesOf(String binaryName) {
        String source = "var w = Java.type('" + binaryName + "');\n";
        Analysis analysis = JsLanguage.analyzer().analyze("Probe.js", source, 1L);
        SymbolInfo type = analysis.resolveAt(source.indexOf("w ="));
        assertNotNull("the fixture class did not resolve", type);
        assertNotNull(type.type());
        List<String> names = new ArrayList<>();
        // BOTH SIDES, because the fixture's mapped method is an instance member and its mapped field is
        // too, while `Java.type` asks for the statics -- so the list is taken from the instance side.
        for (SymbolInfo member : analysis.membersOf(
                JsTypeRefForTesting.instanceOf(binaryName), 0)) {
            names.add(member.name());
        }
        return names;
    }

    /** A package-private door, so the test can ask for the instance side of a type by name. */
    static final class JsTypeRefForTesting {
        static com.crystalgui.text.lang.TypeRef instanceOf(String binaryName) {
            return JsTypeRef.javaInstance(binaryName);
        }
    }
}
