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

    // ── composing two artifacts ─────────────────────────────────────────────────────────────────

    /**
     * The same mapping read the other way: runtime becomes readable and readable becomes runtime.
     *
     * <p>Every published mapping artifact maps <em>away</em> from the obfuscated namespace — Mojang's
     * {@code client.txt} is official→obf, MCPConfig's {@code joined.tsrg} is obf→srg, Fabric's
     * {@code intermediary} is obf→intermediary — while a runtime speaks one of the far ends. Inverting
     * one of them is what brings the two onto a common footing, and {@link #then} joins them:</p>
     *
     * <pre>{@code
     * MappingSet obfToSrg      = ...;   // MCPConfig joined.tsrg
     * MappingSet obfToOfficial = ...;   // Mojang client.txt
     * MappingSet srgToOfficial = obfToSrg.invert().then(obfToOfficial);
     * }</pre>
     *
     * <p>Free — the reverse tables are built at construction, so this hands them over rather than
     * computing anything.</p>
     *
     * <p><b>Not always a round trip.</b> An unqualified name that several runtime names claim is
     * dropped rather than guessed, so inverting twice can lose entries a guess would have kept. That is
     * {@link #reverseUnambiguous}'s rule and the reason it exists.</p>
     */
    public MappingSet invert() {
        return new MappingSet(classesReversed, methodsReversed, fieldsReversed,
                globalMethodsReversed, globalFieldsReversed);
    }

    /**
     * The same mapping, plus an <b>unqualified</b> tier derived from the owner-keyed one.
     *
     * <p>Needed by everything that has a name and no owner, which in practice means a scan of TEXT:
     * {@code plr.m_8055_} in a source file is a name and a dot, and the file does not say what
     * {@code plr} is. {@link ReadableSource} and the Remap command read that tier and nothing else, so a
     * mapping without it is invisible to them however complete it is.</p>
     *
     * <pre>{@code
     * MappingSet forge = obfToSrg.invert().then(obfToOfficial).withUnqualifiedMembers();
     * forge.readableMethodAnywhere("m_8055_");   // getBlockState
     * }</pre>
     *
     * <h3>Uniqueness is MEASURED, not assumed</h3>
     *
     * <p>The tier is only sound for a format whose runtime names are globally unique. SRG and
     * intermediary both are, by construction — but a mapping that trusted that and was wrong would
     * rename the wrong member silently, which is the failure this whole class is arranged against. So a
     * name claimed by two owners with <em>different</em> readable names is left out, exactly as an
     * ambiguous reverse entry is. Two owners agreeing is not a conflict and is kept.</p>
     *
     * <p>Nothing derives this for MCP: its CSVs have no owner to key on and register here directly.</p>
     */
    /**
     * The members, re-keyed under their READABLE owners, with the class renames dropped.
     *
     * <p>For a runtime that already speaks readable CLASS names and renames only members — which is
     * Forge from 1.17 onward: {@code net.minecraft.client.Minecraft} spelled in full, with
     * {@code m_91087_} on it.</p>
     *
     * <pre>{@code
     * MappingSet forge = obfToSrg.invert().then(obfToOfficial).withoutClassRenames();
     * forge.readableMethod("net/minecraft/client/Minecraft", "m_91087_");  // getInstance
     * forge.runtimeClass("net/minecraft/client/Minecraft");                // itself, unchanged
     * }</pre>
     *
     * <h3>Why this is not the same as the join already producing it</h3>
     *
     * <p>MCPConfig's srg namespace has a class vocabulary of its OWN —
     * {@code enn -> net/minecraft/src/C_3391_} — and Forge runs none of it. Composed unaltered, the
     * join keys every member under {@code C_3391_} and answers {@code runtimeClass(Minecraft)} with
     * {@code C_3391_}; a script then links against a class no runtime has, and fails with
     * {@code NoClassDefFoundError: net/minecraft/src/C_3391_} pointing at the line that named
     * {@code Minecraft}. So the owners are translated into the readable namespace and the class table
     * is discarded, leaving classes to answer as themselves.</p>
     *
     * <p><b>Not for every host.</b> Fabric renames classes for real — {@code net/minecraft/class_1937}
     * IS what its runtime has — so calling this there would throw the mapping's class half away and
     * break exactly what it is meant to fix.</p>
     */
    public MappingSet withoutClassRenames() {
        Builder out = builder();
        rekeyUnderReadableOwner(methods, out, true);
        rekeyUnderReadableOwner(fields, out, false);
        globalMethods.forEach(out::method);
        globalFields.forEach(out::field);
        return out.build();
    }

    /** One owner-keyed table, its owners moved into the readable namespace. @see #withoutClassRenames */
    private void rekeyUnderReadableOwner(Map<String, String> table, Builder out, boolean method) {
        for (Map.Entry<String, String> entry : table.entrySet()) {
            int dot = entry.getKey().lastIndexOf('.');
            String owner = readableClass(entry.getKey().substring(0, dot));
            String name = entry.getKey().substring(dot + 1);
            if (method) {
                out.method(owner, name, entry.getValue());
            } else {
                out.field(owner, name, entry.getValue());
            }
        }
    }

    public MappingSet withUnqualifiedMembers() {
        return new MappingSet(classes, methods, fields,
                mergedWithUnambiguous(globalMethods, methods),
                mergedWithUnambiguous(globalFields, fields));
    }

    /** {@code existing}, plus every owner-keyed name that exactly one readable name is claimed by. */
    private static Map<String, String> mergedWithUnambiguous(Map<String, String> existing,
                                                             Map<String, String> ownerKeyed) {
        Map<String, String> byName = new LinkedHashMap<>(existing);
        Set<String> collided = new HashSet<>();
        for (Map.Entry<String, String> entry : ownerKeyed.entrySet()) {
            String name = entry.getKey().substring(entry.getKey().lastIndexOf('.') + 1);
            if (collided.contains(name)) continue;
            String readable = entry.getValue();
            String seen = byName.put(name, readable);
            if (seen != null && !seen.equals(readable)) {
                byName.remove(name);
                collided.add(name);
            }
        }
        return byName;
    }

    /**
     * This mapping followed by {@code next} — {@code P→Q} then {@code Q→R} gives {@code P→R}.
     *
     * <p>Members are joined through their OWNER as well as their name: a method's owner is carried into
     * {@code next}'s namespace with {@link #readableClass} before it is looked up there. Joining on the
     * bare name would ask an owner-keyed second stage a question it cannot answer.</p>
     *
     * <p>A name {@code next} does not carry passes through, exactly as a lookup on it would answer, so a
     * partial second stage narrows the result rather than emptying it.</p>
     *
     * <p><b>The mixed namespaces come out right, which is the whole reason this composes rather than
     * concatenates.</b> Forge 1.20.1 runs official class names with SRG members, and MCPConfig's srg
     * namespace is that same mix — so {@code obfToSrg.invert().then(obfToOfficial)} yields identity
     * classes and {@code Level.m_8055_ → getBlockState}, which is exactly what that runtime needs.</p>
     *
     * <p><b>This is a JOIN, not a monoid composition, and {@code then(IDENTITY)} is therefore
     * {@link #IDENTITY} rather than {@code this}.</b> A second stage that knows nothing leaves every
     * entry pointing at the intermediate namespace, and here that is the obfuscated one — a mapping into
     * it is not a readable mapping, so the honest answer is to have none. @see #carries</p>
     */
    public MappingSet then(MappingSet next) {
        Builder out = builder();
        for (Map.Entry<String, String> entry : classes.entrySet()) {
            String joined = next.readableClass(entry.getValue());
            if (carries(joined, entry.getValue())) out.type(entry.getKey(), joined);
        }
        joinMembers(methods, next, out, true);
        joinMembers(fields, next, out, false);
        for (Map.Entry<String, String> entry : globalMethods.entrySet()) {
            String joined = next.readableMethodAnywhere(entry.getValue());
            if (carries(joined, entry.getValue())) out.method(entry.getKey(), joined);
        }
        for (Map.Entry<String, String> entry : globalFields.entrySet()) {
            String joined = next.readableFieldAnywhere(entry.getValue());
            if (carries(joined, entry.getValue())) out.field(entry.getKey(), joined);
        }
        return out.build();
    }

    /**
     * Whether {@code next} actually knew this name, rather than handing back what it was asked.
     *
     * <p><b>An entry it did not know must be DROPPED, not kept.</b> Keeping it stores the intermediate
     * name as though it were readable — and the intermediate namespace here is the obfuscated one, so a
     * member the second stage is missing would be shown to a script author as {@code zz}. That is a
     * plausible-looking answer, which is the worst kind: unmapped passes the runtime name through and
     * looks unmapped.</p>
     *
     * <p>A name that genuinely maps to itself is dropped too, and harmlessly: a lookup that finds
     * nothing returns its argument, which is the same answer the stored entry would have given.</p>
     */
    private static boolean carries(String joined, String intermediate) {
        return !joined.equals(intermediate);
    }

    /** One owner-keyed table through {@code next}, owners translated first. @see #then */
    private void joinMembers(Map<String, String> table, MappingSet next, Builder out, boolean method) {
        for (Map.Entry<String, String> entry : table.entrySet()) {
            int dot = entry.getKey().lastIndexOf('.');
            String owner = entry.getKey().substring(0, dot);
            String name = entry.getKey().substring(dot + 1);
            String inNext = readableClass(owner);
            String joined = method
                    ? next.readableMethod(inNext, entry.getValue())
                    : next.readableField(inNext, entry.getValue());
            if (!carries(joined, entry.getValue())) continue;
            if (method) {
                out.method(owner, name, joined);
            } else {
                out.field(owner, name, joined);
            }
        }
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

    /**
     * The owner-keyed answer only — no unqualified fallback. @see #runtimeMethodOfOwner
     *
     * <p>The two tiers carry different authority in this direction too, and the difference shows the
     * moment a caller has a receiver rather than a declaring class. A JavaScript chain ending in
     * {@code list.get(0)} infers {@code java.lang.Object}, and the unqualified tier renamed
     * {@code field_71075_bZ} on it anyway — so a hover reported a member called {@code capabilities}
     * belonging to {@code Object}, which declares nothing of the sort. The name was right and the type it
     * was attributed to was invented.</p>
     */
    public String readableMethodOfOwner(String runtimeOwner, String runtimeName) {
        String mapped = methods.get(key(runtimeOwner, runtimeName));
        return mapped == null ? runtimeName : mapped;
    }

    /** @see #readableMethodOfOwner */
    public String readableFieldOfOwner(String runtimeOwner, String runtimeName) {
        String mapped = fields.get(key(runtimeOwner, runtimeName));
        return mapped == null ? runtimeName : mapped;
    }

    /**
     * The <b>unqualified</b> answer only — no owner-keyed tier, and no owner to key on.
     *
     * <p>For a caller that has a name and nothing else, which in practice means a scan of TEXT:
     * {@code plr.field_71075_bZ} in a source file is a name and a dot, and the file does not say what
     * {@code plr} is. This tier is the one the format guarantees is answerable that way — an SRG name is
     * globally unique by construction, which is the whole reason {@code methods.csv} has no owner column.
     * The owner-keyed tier is deliberately skipped rather than tried first, because in a mapping that
     * does carry owners {@code a} is a method on hundreds of classes with a different readable name on
     * each, and reading that table without an owner is a coin toss dressed as a lookup.</p>
     *
     * @see ReadableSource
     */
    public String readableMethodAnywhere(String runtimeName) {
        String mapped = globalMethods.get(runtimeName);
        return mapped == null ? runtimeName : mapped;
    }

    /** @see #readableMethodAnywhere */
    public String readableFieldAnywhere(String runtimeName) {
        String mapped = globalFields.get(runtimeName);
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

    // ── The two tiers, apart ────────────────────────────────────────────────────────────────────
    //
    // A caller that REWRITES BYTECODE has to tell them apart, because they carry different authority.
    // An owner-keyed entry names the type it applies to and can be trusted outright. An unqualified one
    // applies to no owner in particular, so applying it blindly renames members of classes that have
    // nothing to do with the mapping.
    //
    // That is not theoretical. `run` and `add` are ordinary readable names, and MCP maps SRG methods to
    // both -- so an unverified rename turned the SCRIPT'S OWN `run()` into a func_* name and left
    // ScriptHost reporting "Probe has neither a no-argument run() nor a static main(String[])" about a
    // class it had just compiled. The same rename would hit `list.add(...)` on the way past.

    /** The owner-keyed answer only, or {@code readableName}. Trustworthy without further checking. */
    public String runtimeMethodOfOwner(String readableOwner, String readableName) {
        String mapped = methodsReversed.get(key(readableOwner, readableName));
        return mapped == null ? readableName : mapped;
    }

    /** @see #runtimeMethodOfOwner */
    public String runtimeFieldOfOwner(String readableOwner, String readableName) {
        String mapped = fieldsReversed.get(key(readableOwner, readableName));
        return mapped == null ? readableName : mapped;
    }

    /**
     * The unqualified answer only, or {@code readableName}.
     *
     * <p><b>A caller rewriting bytecode must verify this against the owner</b> — see the note above.
     * Reading it to DISPLAY a name is safe, because a wrong readable name is a cosmetic error and a
     * wrong runtime name is a {@code NoSuchMethodError}.</p>
     */
    public String runtimeMethodAnywhere(String readableName) {
        String mapped = globalMethodsReversed.get(readableName);
        return mapped == null ? readableName : mapped;
    }

    /** @see #runtimeMethodAnywhere */
    public String runtimeFieldAnywhere(String readableName) {
        String mapped = globalFieldsReversed.get(readableName);
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
