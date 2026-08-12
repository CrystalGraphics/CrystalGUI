package com.crystalgui.language.map;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

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
 */
public final class MappingSet {

    /** Runtime already speaks the readable namespace. Every translation is the identity. */
    public static final MappingSet IDENTITY = new MappingSet(
            Collections.<String, String>emptyMap(),
            Collections.<String, String>emptyMap(),
            Collections.<String, String>emptyMap());

    /** internal class name (runtime) → internal class name (readable) */
    private final Map<String, String> classes;
    /** "runtimeOwner.runtimeName" → readable member name */
    private final Map<String, String> methods;
    private final Map<String, String> fields;

    /** The reverse of each, built once so translation is a lookup in both directions. */
    private final Map<String, String> classesReversed;
    private final Map<String, String> methodsReversed;
    private final Map<String, String> fieldsReversed;

    private MappingSet(Map<String, String> classes, Map<String, String> methods,
                       Map<String, String> fields) {
        this.classes = classes;
        this.methods = methods;
        this.fields = fields;
        this.classesReversed = reverse(classes);
        this.methodsReversed = reverseMembers(methods, classes);
        this.fieldsReversed = reverseMembers(fields, classes);
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isIdentity() {
        return classes.isEmpty() && methods.isEmpty() && fields.isEmpty();
    }

    // ── runtime → readable (the "in" direction) ─────────────────────────────────────────────────

    /** @param internalName e.g. {@code net/minecraft/world/World} */
    public String readableClass(String internalName) {
        String mapped = classes.get(internalName);
        return mapped == null ? internalName : mapped;
    }

    public String readableMethod(String runtimeOwner, String runtimeName) {
        String mapped = methods.get(key(runtimeOwner, runtimeName));
        return mapped == null ? runtimeName : mapped;
    }

    public String readableField(String runtimeOwner, String runtimeName) {
        String mapped = fields.get(key(runtimeOwner, runtimeName));
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
        return mapped == null ? readableName : mapped;
    }

    public String runtimeField(String readableOwner, String readableName) {
        String mapped = fieldsReversed.get(key(readableOwner, readableName));
        return mapped == null ? readableName : mapped;
    }

    /** Whether any member of this readable type is mapped — the fast path for an unmapped class. */
    public boolean mapsAnyMemberOf(String readableOwner) {
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

        public MappingSet build() {
            return new MappingSet(new LinkedHashMap<>(classes), new LinkedHashMap<>(methods),
                    new LinkedHashMap<>(fields));
        }
    }
}
