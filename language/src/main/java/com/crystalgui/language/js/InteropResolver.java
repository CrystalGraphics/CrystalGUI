package com.crystalgui.language.js;

import com.crystalgui.language.engine.bridge.Analysis;
import com.crystalgui.language.engine.bridge.JsExecutor;
import com.crystalgui.language.engine.bridge.SourceAnalyzer;
import com.crystalgui.text.lang.SymbolInfo;
import com.crystalgui.text.lang.SymbolKind;
import com.crystalgui.text.lang.SymbolModifier;
import com.crystalgui.text.lang.TypeRef;

import javax.annotation.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A Java class reached from JavaScript, answered by the <b>Java</b> engine.
 *
 * <h3>One synthetic unit, and everything else for free</h3>
 *
 * <p>Asked what {@code new java.util.ArrayList()} offers, this analyses {@code class $Probe {
 * java.util.ArrayList $x; }} through the Java engine's own {@code SourceAnalyzer}, resolves {@code $x},
 * and hands back that analysis's member list verbatim. So the members, their signatures, their generic
 * substitution, their accessibility, their {@code @Deprecated} marks and the binding keys
 * {@code AttachedSources} needs to quote them out of {@code src.zip} are all <em>the same answers a
 * {@code .java} file would have got</em> — which is the whole point, and is not something reflection can
 * reproduce.</p>
 *
 * <p>The one place this is less precise than Java is generics: JavaScript has no diamond, so
 * {@code new java.util.ArrayList()} is a raw {@code ArrayList} and {@code get(0)} answers {@code Object}
 * rather than {@code String}. That is inherent in the language rather than a gap here — the script has no
 * more information than the probe does.</p>
 *
 * <h3>The cache is small on purpose</h3>
 *
 * <p>Each entry holds a resolved {@code Analysis}, which holds an AST. A dozen is far more than a script
 * touches in a sitting and is bounded memory; the eviction closes what it drops, which is what stops this
 * being a leak with a nicer name.</p>
 *
 * <h3>Reflection when there is no Java engine</h3>
 *
 * <p>A build that ships Rhino without ECJ still runs scripts, and a script that calls Java still works —
 * so refusing to answer would make the editor useless for exactly the case it degrades through. The
 * fallback walks {@code getMethods()}/{@code getFields()} on the host loader, which is <em>what Rhino
 * itself does at call time</em>: the list is what the script can really call, with erased types and no
 * documentation.</p>
 */
final class InteropResolver {

    /** Analyses held at once. Each is an AST; a script touches a handful of Java classes in a sitting. */
    private static final int MAX_CACHED = 12;

    /** The name of the synthetic unit. A {@code $} start cannot collide with a real class of the user's. */
    private static final String PROBE_CLASS = "$Probe";

    /** The field whose type is the class being asked about. */
    private static final String PROBE_FIELD = "$x";

    @Nullable private final SourceAnalyzer java;
    private final List<String> classpath;
    private final int releaseLevel;

    /**
     * What a script may reach, or null for everything.
     *
     * <p>Consulted on the way <em>out</em> rather than at the probe: the Java engine's answer is the same
     * whatever the policy is, so filtering here keeps the cached analysis reusable if the policy ever
     * changes, and keeps the refusal in one place instead of woven through the probe.</p>
     */
    @Nullable private volatile Predicate<String> allowsClass;

    void restrictTo(@Nullable Predicate<String> policy) {
        this.allowsClass = policy;
        // THE MEMBER CACHE GOES, the analysis cache stays. A member list is what the policy filters, so a
        // cached one describes the old posture; an Analysis describes the class and is policy-free.
        synchronized (this) {
            members.clear();
        }
    }

    private boolean permits(@Nullable String binaryName) {
        Predicate<String> policy = allowsClass;
        return policy == null || binaryName == null || binaryName.isEmpty() || policy.test(binaryName);
    }

    /** Access-ordered, so eviction drops the least recently asked-about class. */
    private final LinkedHashMap<String, Probe> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Probe> eldest) {
            if (size() <= MAX_CACHED) return false;
            // CLOSED ON THE WAY OUT. An Analysis holds a resolved AST; dropping the reference without
            // closing it is the shape that makes a bounded cache leak anyway.
            eldest.getValue().close();
            return true;
        }
    };

    InteropResolver(@Nullable SourceAnalyzer java, @Nullable List<String> classpath, int releaseLevel) {
        this.java = java;
        this.classpath = classpath == null ? List.of() : List.copyOf(classpath);
        this.releaseLevel = releaseLevel <= 0 ? 8 : releaseLevel;
    }

    /** Whether the Java engine is behind this — false when the answers come from reflection. */
    boolean hasJavaEngine() {
        return java != null;
    }

    /**
     * The members of a Java class, as the Java engine reports them.
     *
     * @param staticSide the class object's statics ({@code Java.type("a.b.C").}) rather than an
     *                   instance's members ({@code new a.b.C().})
     */
    synchronized List<SymbolInfo> membersOf(String binaryName, boolean staticSide) {
        if (binaryName == null || binaryName.isEmpty()) return List.of();
        // A REFUSED CLASS HAS NO MEMBERS, which is what makes the completion list and the run agree: the
        // shutter refuses the call, and this refuses to have suggested it.
        if (!permits(binaryName)) return List.of();
        Probe probe = probeFor(binaryName);
        List<SymbolInfo> all = probe == null ? reflectMembers(binaryName) : probe.members();
        if (all.isEmpty()) return all;
        List<SymbolInfo> filtered = new ArrayList<>(all.size());
        for (SymbolInfo member : all) {
            if (member.is(SymbolModifier.STATIC) != staticSide) continue;
            // AND A MEMBER WHOSE DECLARING CLASS IS REFUSED goes too, however reachable the receiver is:
            // `toString()` inherited from a refused type is still a call into it.
            if (!permits(member.container())) continue;
            filtered.add(member);
        }
        // A CLASS WITH NO STATICS STILL HAS SOME -- `class` itself, and anything inherited from Object is
        // instance-side -- so an empty static list is a real answer and not a reason to fall back to the
        // instance one. Falling back would list `toString()` under `Java.type("…").`, which cannot be
        // called there.
        return filtered;
    }

    /**
     * One member, described by the <b>Java</b> engine — signature quoted from source when there is any.
     *
     * <h3>Why this needs a second, member-shaped probe</h3>
     *
     * <p>{@code membersOf} deliberately leaves the signature off: it answers with hundreds for a completion
     * list, which draws a label and a detail column and would never read one. Quoting a declaration out of
     * {@code src.zip} needs the member's <em>binding key</em>, and a {@code SymbolInfo} carries no binding —
     * that is the whole point of the bridge. So the only way to get the Java engine's own answer about one
     * member is to hand it a unit in which that member is <em>named</em>, and ask it to resolve there.</p>
     *
     * <p>The unit declares a parameter of each of the member's own declared types and passes them at the
     * call, which makes overload resolution <b>exact</b> rather than a guess:</p>
     *
     * <pre>
     *   class $Probe {
     *       java.util.ArrayList $x;
     *       void $m(java.lang.Object $p0) { $x.add($p0); }
     *   }
     * </pre>
     *
     * <p>Parameters rather than casts, because a cast of {@code null} is ambiguous for a primitive and a
     * cast to a type variable does not parse. Asked only on a <b>hover</b> — one deliberate gesture on one
     * member — and cached, so the cost is a parse the user waited for.</p>
     *
     * <p>Guarded end to end: if the probe does not compile, or resolves to a member of another name, or the
     * class has no source beside it, the answer is null and the caller assembles what it already knows. A
     * signature is a nicety; being wrong about one is not.</p>
     */
    @Nullable
    synchronized SymbolInfo describeMember(String binaryName, SymbolInfo member, boolean staticSide) {
        if (java == null || binaryName == null || member == null || member.name().isEmpty()) return null;
        if (!permits(binaryName)) return null;
        String key = binaryName + (staticSide ? "#" : ".") + member.name() + "/" + member.parameters().size();
        SymbolInfo cached = members.get(key);
        if (cached != null) return cached == ABSENT ? null : cached;

        SymbolInfo described = probeMember(binaryName, member, staticSide);
        members.put(key, described == null ? ABSENT : described);
        return described;
    }

    /** A sentinel, so a member with no quotable declaration is not re-probed on every hover. */
    private static final SymbolInfo ABSENT = SymbolInfo.of("", SymbolKind.UNKNOWN);

    /** Bounded for the reason the analysis cache is: a hover is cheap to repeat and memory is not free. */
    private final LinkedHashMap<String, SymbolInfo> members = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, SymbolInfo> eldest) {
            return size() > MAX_CACHED_MEMBERS;
        }
    };

    private static final int MAX_CACHED_MEMBERS = 64;

    @Nullable
    private SymbolInfo probeMember(String binaryName, SymbolInfo member, boolean staticSide) {
        StringBuilder unit = new StringBuilder("class ").append(PROBE_CLASS).append(" {\n");
        if (!staticSide) unit.append("    ").append(binaryName).append(' ').append(PROBE_FIELD).append(";\n");
        unit.append("    void $m(");
        List<TypeRef> parameters = member.parameters();
        for (int i = 0; i < parameters.size(); i++) {
            String type = parameters.get(i).qualifiedName();
            // A TYPE VARIABLE IS NOT A TYPE NAME. A raw receiver erases them, but a member reported from a
            // parameterised binding can still name one -- and `void $m(E $p0)` does not compile, so the
            // whole probe would resolve to nothing rather than to the wrong thing.
            if (type == null || type.isEmpty() || type.indexOf('.') < 0 && !isPrimitive(type)) return null;
            if (i > 0) unit.append(", ");
            unit.append(type).append(" $p").append(i);
        }
        unit.append(") {\n        ");
        String receiver = staticSide ? binaryName : PROBE_FIELD;
        int callAt = unit.length() + receiver.length() + 1;
        unit.append(receiver).append('.').append(member.name());
        if (member.isInvocable()) {
            unit.append('(');
            for (int i = 0; i < parameters.size(); i++) {
                if (i > 0) unit.append(", ");
                unit.append("$p").append(i);
            }
            unit.append(')');
        }
        unit.append(";\n    }\n}\n");

        Analysis analysis;
        try {
            analysis = java.analyze(PROBE_CLASS, unit.toString(), classpath, releaseLevel, -1L);
        } catch (RuntimeException unavailable) {
            return null;
        }
        if (analysis == null) return null;
        try {
            SymbolInfo resolved = analysis.resolveAt(callAt);
            // THE NAME HAS TO MATCH. A probe the compiler recovered differently can resolve to something
            // else entirely, and a signature quoted for the wrong member is worse than none at all.
            return resolved != null && member.name().equals(resolved.name()) ? resolved : null;
        } finally {
            analysis.close();
        }
    }

    private static boolean isPrimitive(String type) {
        switch (type) {
            case "boolean": case "byte": case "char": case "short":
            case "int": case "long": case "float": case "double": case "void":
                return true;
            default:
                return false;
        }
    }

    /** What the class itself is, for a hover over {@code java.util.ArrayList}. */
    @Nullable
    synchronized SymbolInfo describe(String binaryName, boolean staticSide) {
        if (binaryName == null || binaryName.isEmpty()) return null;
        if (!permits(binaryName)) return null;
        int lastDot = binaryName.lastIndexOf('.');
        String simple = lastDot < 0 ? binaryName : binaryName.substring(lastDot + 1);
        String container = lastDot < 0 ? null : binaryName.substring(0, lastDot);
        Probe probe = probeFor(binaryName);
        TypeRef type = probe != null ? probe.type()
                : (exists(binaryName) ? JsTypeRef.javaInstance(binaryName) : null);
        if (type == null) return null;
        return new SymbolInfo(simple, SymbolKind.CLASS,
                staticSide ? JsTypeRef.javaClass(binaryName) : type,
                container, null, Set.of(), null);
    }

    /** The {@code TypeRef} the Java engine uses for this class — the only kind its {@code membersOf} takes. */
    @Nullable
    synchronized TypeRef javaTypeOf(String binaryName) {
        Probe probe = probeFor(binaryName);
        return probe == null ? null : probe.type();
    }

    /** Whether the class exists at all — what decides a package chain is a type and not a typo. */
    synchronized boolean exists(String binaryName) {
        if (binaryName == null || binaryName.isEmpty()) return false;
        if (!permits(binaryName)) return false;
        if (java != null) return probeFor(binaryName) != null;
        return loadClass(binaryName) != null;
    }

    synchronized void close() {
        for (Probe probe : cache.values()) probe.close();
        cache.clear();
        members.clear();
    }

    // ── The probe unit ──────────────────────────────────────────────────────────────────────────

    @Nullable
    private Probe probeFor(String binaryName) {
        if (java == null) return null;
        Probe cached = cache.get(binaryName);
        if (cached != null) return cached.isUsable() ? cached : null;

        String source = "class " + PROBE_CLASS + " { " + binaryName + " " + PROBE_FIELD + "; }";
        int offset = source.indexOf(PROBE_FIELD);
        Analysis analysis;
        try {
            analysis = java.analyze(PROBE_CLASS, source, classpath, releaseLevel, -1L);
        } catch (RuntimeException unavailable) {
            // A PROBE THAT THREW IS NOT A BROKEN EDITOR. The class list falls back to reflection, which
            // is the same degradation as having no Java engine at all.
            return null;
        }
        if (analysis == null) return null;
        SymbolInfo field = analysis.resolveAt(offset);
        TypeRef type = field == null ? null : field.type();
        Probe probe = new Probe(analysis, type, offset);
        // CACHED EVEN WHEN THE TYPE DID NOT RESOLVE, so a name that is not a class is not re-analysed on
        // every keystroke -- a mistyped package would otherwise cost a compile per character.
        cache.put(binaryName, probe);
        return probe.isUsable() ? probe : null;
    }

    /** One class's resolved probe unit. */
    private static final class Probe {

        private final Analysis analysis;
        @Nullable private final TypeRef type;
        private final int offset;
        @Nullable private List<SymbolInfo> members;
        private boolean closed;

        Probe(Analysis analysis, @Nullable TypeRef type, int offset) {
            this.analysis = analysis;
            this.type = type;
            this.offset = offset;
        }

        boolean isUsable() {
            return !closed && type != null;
        }

        @Nullable
        TypeRef type() {
            return isUsable() ? type : null;
        }

        List<SymbolInfo> members() {
            if (!isUsable()) return List.of();
            if (members == null) {
                List<SymbolInfo> found = analysis.membersOf(type, offset);
                members = found == null ? List.of() : List.copyOf(found);
            }
            return members;
        }

        void close() {
            if (closed) return;
            closed = true;
            members = List.of();
            analysis.close();
        }
    }

    // ── The reflection fallback ─────────────────────────────────────────────────────────────────

    /**
     * The host's loader — the one whose classes a script actually reaches.
     *
     * <p>Read from the bridge interface, which is parent-first by construction, for the reason
     * {@code RhinoExecutor} spells out: this class is defined by the band loader, so its own loader would
     * answer with the engine's view rather than the application's.</p>
     */
    private static final ClassLoader HOST_LOADER = JsExecutor.class.getClassLoader();

    @Nullable
    private static Class<?> loadClass(String binaryName) {
        try {
            // INITIALIZE = FALSE. Resolving a name in an editor must never run a static initialiser --
            // that is somebody's code, executed because the caret moved.
            return Class.forName(binaryName, false, HOST_LOADER);
        } catch (ClassNotFoundException | LinkageError absent) {
            return null;
        }
    }

    private static List<SymbolInfo> reflectMembers(String binaryName) {
        Class<?> type = loadClass(binaryName);
        if (type == null) return List.of();
        List<SymbolInfo> members = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        try {
            for (Method method : type.getMethods()) {
                if (method.isSynthetic() || method.isBridge()) continue;
                if (!seen.add(method.getName() + "/" + method.getParameterCount())) continue;
                members.add(reflected(method));
            }
            for (Field field : type.getFields()) {
                if (field.isSynthetic() || !seen.add(field.getName())) continue;
                members.add(reflected(field));
            }
        } catch (LinkageError partial) {
            // A class whose supertype is absent lists what it could -- better than nothing at all.
        }
        return members;
    }

    private static SymbolInfo reflected(Method method) {
        List<TypeRef> parameters = new ArrayList<>(method.getParameterCount());
        for (Class<?> parameter : method.getParameterTypes()) {
            parameters.add(TypeRef.of(simpleName(parameter), parameter.getName()));
        }
        return new SymbolInfo(method.getName(), SymbolKind.METHOD,
                TypeRef.of(simpleName(method.getReturnType()), method.getReturnType().getName()),
                method.getDeclaringClass().getName(), null, modifiersOf(method.getModifiers()), null,
                parameters);
    }

    private static SymbolInfo reflected(Field field) {
        boolean constant = Modifier.isStatic(field.getModifiers())
                && Modifier.isFinal(field.getModifiers());
        return new SymbolInfo(field.getName(), constant ? SymbolKind.CONSTANT : SymbolKind.FIELD,
                TypeRef.of(simpleName(field.getType()), field.getType().getName()),
                field.getDeclaringClass().getName(), null, modifiersOf(field.getModifiers()), null);
    }

    private static Set<SymbolModifier> modifiersOf(int modifiers) {
        Set<SymbolModifier> out = new LinkedHashSet<>();
        if (Modifier.isStatic(modifiers)) out.add(SymbolModifier.STATIC);
        if (Modifier.isAbstract(modifiers)) out.add(SymbolModifier.ABSTRACT);
        if (Modifier.isFinal(modifiers)) out.add(SymbolModifier.FINAL);
        return out.isEmpty() ? Collections.emptySet() : out;
    }

    private static String simpleName(Class<?> type) {
        String name = type.getSimpleName();
        return name == null || name.isEmpty() ? type.getName() : name;
    }
}
