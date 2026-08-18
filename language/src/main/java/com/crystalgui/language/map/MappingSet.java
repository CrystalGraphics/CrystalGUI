package com.crystalgui.language.map;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The readable ↔ runtime name mapping — one authoring namespace, translated at the boundary.
 *
 * <h3>Why this exists at all</h3>
 *
 * <p>On a Minecraft host, runtime member names differ per environment: 1.7.10 production has
 * {@code field_70170_p}, 1.7.10 dev has {@code theWorld}, Forge 1.20.1 production has {@code f_123_},
 * NeoForge has Mojmap. A script written against any one of those breaks on the others. So scripts are
 * authored in <b>one</b> namespace — the readable one — and this translates, in both directions:</p>
 *
 * <ul>
 *   <li><b>in</b>: the compiler is shown types whose members carry readable names, so bindings,
 *       diagnostics, completion and hover all live in readable names with no further work anywhere;</li>
 *   <li><b>out</b>: compiled script bytecode is remapped readable→runtime before it is defined — the
 *       only place the runtime namespace appears at all.</li>
 * </ul>
 *
 * <h3>{@link #IDENTITY} is the common case, not a fallback</h3>
 *
 * <p>A dev environment, NeoForge's Mojmap-at-runtime, the harness, a plain JVM: in all of them runtime
 * already speaks the readable namespace and the boundary disappears. The mapping being data means those
 * hosts pay nothing and take the same code path, rather than having a second path nobody exercises.</p>
 *
 * <h3>Names are keyed by owner, and that is not optional</h3>
 *
 * <p>A member name is only unique within its declaring type — two classes routinely have a
 * {@code getValue}, mapped to different runtime names. Keying on the bare name works on a fixture and
 * corrupts a real mapping set, and it corrupts it <em>silently</em>: the wrong member is renamed and
 * the result still verifies.</p>
 *
 * <h3>… except where the format guarantees the name is already unique</h3>
 *
 * <p>MCP's SRG names are globally unique <b>by construction</b>: {@code func_147439_a} names exactly one
 * method in the whole game, which is why {@code methods.csv} is four columns with no owner among them.
 * For such a format there is no owner to key on, and inventing one would mean deriving from
 * {@code packaged.srg} something the data already asserts.</p>
 *
 * <p>So an entry may be registered <em>unqualified</em>, and a lookup tries the owner-keyed table first
 * and falls back to it. That order is what keeps the paragraph above true — a format that does carry
 * owners still wins for its own types, and the unqualified tier only answers where nothing more specific
 * does.</p>
 *
 * <h3>… and the guarantee holds in ONE DIRECTION ONLY</h3>
 *
 * <p><b>Measured on {@code mcp_stable/12}, not assumed.</b> SRG → readable is a function: every
 * {@code func_*} names one method. Readable → SRG is <em>not</em>, because unrelated classes are allowed
 * the same readable name — {@code getBlock} is four distinct SRG methods, and across the real files:</p>
 *
 * <pre>
 * methods  4,819 rows  4,311 distinct readable names  357 ambiguous, covering   865 rows (18%)
 * fields   4,791 rows  4,058 distinct readable names  329 ambiguous, covering 1,062 rows (22%)
 * </pre>
 *
 * <p>So roughly one member in five cannot be reversed from the name alone — and the reverse is the
 * direction that makes a script <em>link</em>. A map that simply kept the last entry would answer with
 * one of the four, and the script would fail at run time with a {@code NoSuchMethodError} naming an SRG
 * name its author never wrote.</p>
 *
 * <p>An ambiguous name therefore answers <b>unmapped</b>, and {@link #isAmbiguousReadableMethod} says so
 * out loud. Unmapped is also wrong, but it is wrong in the direction that can be detected and fixed by
 * asking the owner — which is exactly what {@code InheritanceAwareRemapper} exists to do, and why the
 * short-circuit above is only ever an optimisation for the unambiguous case.</p>
 */
public final class MappingSet {

    /** Runtime already speaks the readable namespace. Every translation is the identity. */
    public static final MappingSet IDENTITY = new MappingSet(
            Collections.<String, String>emptyMap(),
            Collections.<String, String>emptyMap(),
            Collections.<String, String>emptyMap(),
            Collections.<String, String>emptyMap(),
            Collections.<String, String>emptyMap());

    /** internal class name (runtime) → internal class name (readable) */
    private final Map<String, String> classes;
    /** "runtimeOwner.runtimeName" → readable member name */
    private final Map<String, String> methods;
    private final Map<String, String> fields;

    /** runtime member name → readable, for formats whose names are globally unique. @see MappingSet */
    private final Map<String, String> globalMethods;
    private final Map<String, String> globalFields;

    /** The reverse of each, built once so translation is a lookup in both directions. */
    private final Map<String, String> classesReversed;
    private final Map<String, String> methodsReversed;
    private final Map<String, String> fieldsReversed;
    private final Map<String, String> globalMethodsReversed;
    private final Map<String, String> globalFieldsReversed;

    /** Readable names that more than one runtime name maps to. @see MappingSet */
    private final Set<String> ambiguousMethods;
    private final Set<String> ambiguousFields;

    private MappingSet(Map<String, String> classes, Map<String, String> methods,
                       Map<String, String> fields, Map<String, String> globalMethods,
                       Map<String, String> globalFields) {
        this.classes = classes;
        this.methods = methods;
        this.fields = fields;
        this.globalMethods = globalMethods;
        this.globalFields = globalFields;
        this.classesReversed = reverse(classes);
        this.methodsReversed = reverseMembers(methods, classes);
        this.fieldsReversed = reverseMembers(fields, classes);
        this.globalMethodsReversed = reverseUnambiguous(globalMethods);
        this.globalFieldsReversed = reverseUnambiguous(globalFields);
        this.ambiguousMethods = ambiguousIn(globalMethods);
        this.ambiguousFields = ambiguousIn(globalFields);
    }

    /**
     * The reverse of an unqualified table, with every colliding name LEFT OUT.
     *
     * <p>Omission is the whole point. {@link #reverse} keeps whichever entry it saw last, which for this
     * data means picking one of four {@code getBlock}s arbitrarily — and the result is a remap that
     * compiles, verifies and dies at the call. A name that is not in the map answers as unmapped, which
     * a caller can notice.</p>
     */
    private static Map<String, String> reverseUnambiguous(Map<String, String> forward) {
        Map<String, String> back = new HashMap<>(forward.size());
        Set<String> collided = new HashSet<>();
        for (Map.Entry<String, String> entry : forward.entrySet()) {
            String readable = entry.getValue();
            if (collided.contains(readable)) continue;
            if (back.put(readable, entry.getKey()) != null) {
                back.remove(readable);
                collided.add(readable);
            }
        }
        return back;
    }

    /** Readable names that more than one runtime name claims. */
    private static Set<String> ambiguousIn(Map<String, String> forward) {
        Set<String> seen = new HashSet<>(forward.size());
        Set<String> collided = new HashSet<>();
        for (String readable : forward.values()) {
            if (!seen.add(readable)) collided.add(readable);
        }
        return collided;
    }

    /**
     * Whether this readable method name is claimed by more than one runtime name.
     *
     * <p>Asked by a remapper before it trusts an unqualified answer: true means the owner has to be
     * resolved, false means the short-circuit is safe. There is no third state — a name that is neither
     * mapped nor ambiguous is simply not ours, and passes through unchanged.</p>
     */
    public boolean isAmbiguousReadableMethod(String readableName) {
        return ambiguousMethods.contains(readableName);
    }

    /** @see #isAmbiguousReadableMethod */
    public boolean isAmbiguousReadableField(String readableName) {
        return ambiguousFields.contains(readableName);
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isIdentity() {
        return classes.isEmpty() && methods.isEmpty() && fields.isEmpty()
                && globalMethods.isEmpty() && globalFields.isEmpty();
    }

    // ── runtime → readable (the "in" direction) ─────────────────────────────────────────────────

    /** @param internalName e.g. {@code net/minecraft/world/World} */
    public String readableClass(String internalName) {
        String mapped = classes.get(internalName);
        return mapped == null ? internalName : mapped;
    }

    public String readableMethod(String runtimeOwner, String runtimeName) {
        String mapped = methods.get(key(runtimeOwner, runtimeName));
        if (mapped == null) mapped = globalMethods.get(runtimeName);
        return mapped == null ? runtimeName : mapped;
    }

    public String readableField(String runtimeOwner, String runtimeName) {
        String mapped = fields.get(key(runtimeOwner, runtimeName));
        if (mapped == null) mapped = globalFields.get(runtimeName);
        return mapped == null ? runtimeName : mapped;
    }

    // ── readable → runtime (the "out" direction) ────────────────────────────────────────────────

    public String runtimeClass(String internalName) {
        String mapped = classesReversed.get(internalName);
        return mapped == null ? internalName : mapped;
    }

    /**
     * @param readableOwner the <b>readable</b> internal name of the type that DECLARES the member —
     *                      not the type it is called on. Resolving that is
     *                      {@code InheritanceAwareRemapper}'s job and is the whole difficulty
     */
    public String runtimeMethod(String readableOwner, String readableName) {
        String mapped = methodsReversed.get(key(readableOwner, readableName));
        if (mapped == null) mapped = globalMethodsReversed.get(readableName);
        return mapped == null ? readableName : mapped;
    }

    public String runtimeField(String readableOwner, String readableName) {
        String mapped = fieldsReversed.get(key(readableOwner, readableName));
        if (mapped == null) mapped = globalFieldsReversed.get(readableName);
        return mapped == null ? readableName : mapped;
    }

    /** Whether any member of this readable type is mapped — the fast path for an unmapped class. */
    public boolean mapsAnyMemberOf(String readableOwner) {
        // A GLOBAL ENTRY MAPS EVERY OWNER, so this fast path cannot skip anything while one exists. That
        // reads as giving up the optimisation and does not: the search below is for keys qualified by
        // owner, and a set built from an unqualified format has none of those to search.
        if (!globalMethodsReversed.isEmpty() || !globalFieldsReversed.isEmpty()) return true;
        String prefix = readableOwner + ".";
        for (String key : methodsReversed.keySet()) {
            if (key.startsWith(prefix)) return true;
        }
        for (String key : fieldsReversed.keySet()) {
            if (key.startsWith(prefix)) return true;
        }
        return false;
    }

    private static String key(String owner, String name) {
        return owner + "." + name;
    }

    private static Map<String, String> reverse(Map<String, String> forward) {
        Map<String, String> back = new HashMap<>(forward.size());
        for (Map.Entry<String, String> entry : forward.entrySet()) {
            back.put(entry.getValue(), entry.getKey());
        }
        return back;
    }

    /** Member keys are owner-qualified, so reversing has to re-qualify with the READABLE owner. */
    private static Map<String, String> reverseMembers(Map<String, String> forward,
                                                      Map<String, String> classes) {
        Map<String, String> back = new HashMap<>(forward.size());
        for (Map.Entry<String, String> entry : forward.entrySet()) {
            int dot = entry.getKey().lastIndexOf('.');
            String runtimeOwner = entry.getKey().substring(0, dot);
            String runtimeName = entry.getKey().substring(dot + 1);
            String readableOwner = classes.containsKey(runtimeOwner)
                    ? classes.get(runtimeOwner) : runtimeOwner;
            back.put(readableOwner + "." + entry.getValue(), runtimeName);
        }
        return back;
    }

    public static final class Builder {
        private final Map<String, String> classes = new LinkedHashMap<>();
        private final Map<String, String> methods = new LinkedHashMap<>();
        private final Map<String, String> fields = new LinkedHashMap<>();
        private final Map<String, String> globalMethods = new LinkedHashMap<>();
        private final Map<String, String> globalFields = new LinkedHashMap<>();

        private Builder() {
        }

        /** @param runtimeInternalName slashes, not dots — {@code a/b/C} */
        public Builder type(String runtimeInternalName, String readableInternalName) {
            classes.put(runtimeInternalName, readableInternalName);
            return this;
        }

        public Builder method(String runtimeOwner, String runtimeName, String readableName) {
            methods.put(runtimeOwner + "." + runtimeName, readableName);
            return this;
        }

        public Builder field(String runtimeOwner, String runtimeName, String readableName) {
            fields.put(runtimeOwner + "." + runtimeName, readableName);
            return this;
        }

        /**
         * A method whose runtime name is unique across the entire runtime — no owner to key on.
         *
         * <p>Only legal for a format that guarantees it. MCP's SRG names do, by construction; a format
         * whose names are unique only within a type must use the three-argument form, and reaching for
         * this one instead renames the wrong member silently. @see MappingSet</p>
         */
        public Builder method(String runtimeName, String readableName) {
            globalMethods.put(runtimeName, readableName);
            return this;
        }

        /** @see #method(String, String) */
        public Builder field(String runtimeName, String readableName) {
            globalFields.put(runtimeName, readableName);
            return this;
        }

        public MappingSet build() {
            return new MappingSet(new LinkedHashMap<>(classes), new LinkedHashMap<>(methods),
                    new LinkedHashMap<>(fields), new LinkedHashMap<>(globalMethods),
                    new LinkedHashMap<>(globalFields));
        }
    }
}
