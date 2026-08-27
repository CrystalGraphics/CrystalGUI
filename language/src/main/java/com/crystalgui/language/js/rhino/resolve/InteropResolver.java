package com.crystalgui.language.js.rhino.resolve;

import com.crystalgui.language.engine.bridge.Analysis;
import com.crystalgui.language.engine.bridge.MemberNameMapper;
import com.crystalgui.language.engine.bridge.SourceAnalyzer;
import com.crystalgui.language.js.rhino.JsLoaders;
import com.crystalgui.text.lang.ProjectSources;
import com.crystalgui.text.lang.ProjectSourcesRegistry;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
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
public final class InteropResolver {

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

    /**
     * The member half of the policy — (declaring class, member name).
     *
     * <p>Separate from {@link #allowsClass} because the two answers differ on purpose: a class one of
     * whose members is permitted stays <b>reachable</b>, or that member could never be called on it — so
     * the class predicate says yes while this one refuses everything else on it.</p>
     */
    @Nullable private volatile BiPredicate<String, String> allowsMember;

    /** How member names are shown — runtime → readable. @see MemberNameMapper */
    @Nullable private volatile MemberNameMapper memberNames;

    public void useMemberNames(@Nullable MemberNameMapper mapper) {
        this.memberNames = mapper == MemberNameMapper.IDENTITY ? null : mapper;
        synchronized (this) {
            members.clear();
            // AND THE LISTS, WHICH CARRY THE NAMES. The analyses do not: what a class declares is a fact
            // about the class, and the mapping only decides what those declarations are CALLED.
            memberLists.clear();
        }
    }

    /**
     * The member under the name an author should write.
     *
     * <p>Renamed on the way <b>out</b>, like the policy filter and for the same reason: the Java engine's
     * answer about a class does not depend on the mapping, so the cached analysis stays reusable and one
     * place decides what a name looks like.</p>
     */
    private SymbolInfo asReadable(String binaryName, SymbolInfo member) {
        MemberNameMapper mapper = memberNames;
        if (mapper == null) return member;
        // THE DECLARING CLASS, not the receiver: a mapping names the type that declares the member, and
        // `container()` is what the Java engine reported that to be.
        String owner = member.container() == null ? binaryName : member.container();
        String internal = owner.replace('.', '/');
        if (!mapper.mapsAnythingIn(internal)) return member;
        String readable = mapper.readableName(internal, member.name());
        return readable == null || readable.equals(member.name()) ? member : member.withName(readable);
    }

    /**
     * Whether {@code typed} names this member — under the name it is SHOWN by, or the runtime one it was
     * renamed from.
     *
     * <h3>An obfuscated script deserves the same answer as a readable one</h3>
     *
     * <p>{@link #asReadable} renames every member on the way out, so {@code membersOf} offers
     * {@code getServer} and a script spelling {@code func_71276_C} matched nothing in it. It still RAN —
     * the runtime has that member under that name, so nothing needed translating — and the editor simply
     * had no idea what it was: no signature, no javadoc, no semantic colour, a documentation popup with a
     * bare word in it.</p>
     *
     * <p><b>This is not inherited from the Java-side work, which was measured rather than assumed.</b>
     * The compile view teaching ECJ both spellings does not help here, because everything the Java engine
     * reports comes back through {@code asReadable} and collapses onto the readable name — so both
     * spellings arrive as {@code getServer} and the typed identifier matches neither. And
     * {@code ReadableSymbols} can only rename what already resolved. The matching has to be done by the
     * engine that did the renaming.</p>
     *
     * <p>Asked as "is this member called {@code typed}" rather than by translating the typed name first,
     * and the direction matters: a mapping names the type that DECLARES a member, and at the call site
     * all that is known is the receiver. {@code asReadable} already has the declaring class — the Java
     * engine reported it as {@code container()} — so going back through the same owner is exact, where
     * guessing an owner from the receiver would miss every inherited member.</p>
     *
     * <p>The readable name is tried first and costs one string comparison, so a readable script pays
     * nothing for this.</p>
     */
    public boolean isCalled(String binaryName, SymbolInfo member, String typed) {
        if (member == null || typed == null) return false;
        if (typed.equals(member.name())) return true;
        MemberNameMapper mapper = memberNames;
        if (mapper == null) return false;
        String owner = member.container() == null ? binaryName : member.container();
        String internal = owner.replace('.', '/');
        if (!mapper.mapsAnythingIn(internal)) return false;
        return typed.equals(mapper.runtimeName(internal, member.name()));
    }

    public void restrictMembersTo(@Nullable BiPredicate<String, String> policy) {
        this.allowsMember = policy;
        // The member caches go, for the reason the class half gives below: a cached list describes the
        // posture that was in force when it was built.
        synchronized (this) {
            members.clear();
            memberLists.clear();
        }
    }

    public void restrictTo(@Nullable Predicate<String> policy) {
        this.allowsClass = policy;
        // THE MEMBER CACHES GO, the analysis cache stays. A member list is what the policy filters, so a
        // cached one describes the old posture; an Analysis describes the class and is policy-free.
        synchronized (this) {
            members.clear();
            memberLists.clear();
        }
    }

    /** Whether one member may be described — see {@link #allowsMember}. */
    boolean permitsMember(@Nullable String container, @Nullable String name) {
        BiPredicate<String, String> policy = allowsMember;
        if (policy == null || container == null || container.isEmpty() || name == null) return true;
        return policy.test(container, name);
    }

    /** Package-private so {@code RhinoResolution} can filter the INFERENCE tier on the same policy. */
    boolean permits(@Nullable String binaryName) {
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

    public InteropResolver(@Nullable SourceAnalyzer java, @Nullable List<String> classpath, int releaseLevel) {
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
        forgetIfProjectSourceChanged(binaryName);
        if (binaryName == null || binaryName.isEmpty()) return List.of();
        // A REFUSED CLASS HAS NO MEMBERS, which is what makes the completion list and the run agree: the
        // shutter refuses the call, and this refuses to have suggested it.
        if (!permits(binaryName)) return List.of();
        List<SymbolInfo> all = memberListOf(binaryName);
        if (all.isEmpty()) return all;
        List<SymbolInfo> filtered = new ArrayList<>(all.size());
        for (SymbolInfo member : all) {
            if (member.is(SymbolModifier.STATIC) != staticSide) continue;
            // AND A MEMBER WHOSE DECLARING CLASS IS REFUSED goes too, however reachable the receiver is:
            // `toString()` inherited from a refused type is still a call into it.
            if (!permits(member.container())) continue;
            // AND THEN THE MEMBER ITSELF. Asked with the DECLARING class rather than the receiver's, so
            // `deny java.lang.System#exit` refuses it however it was reached.
            if (!permitsMember(member.container(), member.name())) continue;
            filtered.add(asReadable(binaryName, member));
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
        forgetIfProjectSourceChanged(binaryName);
        if (java == null || binaryName == null || member == null || member.name().isEmpty()) return null;
        if (!permits(binaryName)) return null;
        // KEYED BY THE PARAMETER TYPES, not by how many there are. `Math.max(int, int)` and
        // `max(double, double)` are two members with one arity, so an arity key made the second hover show
        // the first one's quoted declaration -- a signature for a member the user is not looking at, which
        // is the one thing worse than no signature.
        String key = binaryName + (staticSide ? "#" : ".") + member.name() + signatureKeyOf(member);
        SymbolInfo cached = members.get(key);
        if (cached != null) return cached == ABSENT ? null : cached;

        SymbolInfo described = probeMember(binaryName, member, staticSide);
        members.put(key, described == null ? ABSENT : described);
        return described;
    }

    /** {@code (int,int)} — what tells two overloads of one name apart. */
    private static String signatureKeyOf(SymbolInfo member) {
        StringBuilder key = new StringBuilder("(");
        for (TypeRef parameter : member.parameters()) {
            if (key.length() > 1) key.append(',');
            key.append(parameter.qualifiedName());
        }
        return key.append(')').toString();
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

    /**
     * The whole member list for a class, <b>derived once and kept</b> however small the analysis cache is.
     *
     * <p>The analyses are what cost memory — each holds a resolved AST — and the derived list is a handful
     * of plain records. Holding only the analyses meant a file whose JSDoc names more than {@link
     * #MAX_CACHED} Java classes thrashed the LRU on every keystroke: {@code symbolsInScope} asks about
     * every declaration, so each pass re-ran an ECJ analysis per class, in order, evicting the one the next
     * declaration was about to need.</p>
     *
     * <p>Dropped wholesale when the policy or the mapping changes, like the member cache — those two decide
     * what a list <em>says</em>, where an analysis is a fact about the class and is unaffected.</p>
     */
    private List<SymbolInfo> memberListOf(String binaryName) {
        List<SymbolInfo> cached = memberLists.get(binaryName);
        if (cached != null) return cached;
        Probe probe = probeFor(binaryName);
        List<SymbolInfo> found = probe == null ? reflectMembers(binaryName) : probe.members();
        List<SymbolInfo> stored = found == null ? List.of() : List.copyOf(found);
        memberLists.put(binaryName, stored);
        return stored;
    }

    /** Derived facts, unbounded — see {@link #memberListOf}. Cleared with the member cache. */
    private final Map<String, List<SymbolInfo>> memberLists = new HashMap<>();

    /**
     * <b>Everything cached about a class is forgotten when the WORKSPACE FILE behind it changes.</b>
     *
     * <h3>Why every cache here could be keyed on the name alone, and no longer can</h3>
     *
     * <p>All three caches assume a class is a fact for the life of the process, and for a jar that is
     * true — which is why {@link #probeFor} caches a probe <em>even when the type did not resolve</em>,
     * and why {@link #memberLists} is unbounded and dropped only when the policy or the mapping changes.
     * Both are right about the classpath and neither is right about a {@code .java} file the author has
     * open in the next tab.</p>
     *
     * <p>Two failures, and the first is the ugly one. The index crawls in the background and
     * {@code sourceOf} SCHEDULES a read rather than waiting, so the first ask for a project type routinely
     * lands before there is an answer — and that miss was then permanent: an empty member list cached
     * under the class name, no hover, no Ctrl+B, and nothing anywhere that would ever retry. The second is
     * the ordinary one: a method added to {@code Main.java} never appeared behind {@code Main.} in the
     * {@code .js} file beside it, breaking the same no-save promise M15 S5 makes for running.</p>
     *
     * <p>Costs one index lookup for a classpath name, which is every name in most files: {@code pathOf}
     * answers from the crawled map and, unlike {@code sourceOf}, a miss there does not schedule a read.</p>
     */
    private void forgetIfProjectSourceChanged(String binaryName) {
        if (binaryName == null || binaryName.isEmpty()) return;
        Integer now = projectStampOf(binaryName);
        if (!projectStamps.containsKey(binaryName)) {
            projectStamps.put(binaryName, now);
            return;
        }
        if (Objects.equals(projectStamps.get(binaryName), now)) return;
        projectStamps.put(binaryName, now);
        Probe probe = cache.remove(binaryName);
        if (probe != null) probe.close();
        memberLists.remove(binaryName);
        // THE MEMBER DESCRIPTIONS TOO, which are keyed by class-plus-member. Leaving them would quote a
        // signature from the file as it used to be, under a member list read from the file as it is.
        members.keySet().removeIf(key -> key.startsWith(binaryName + "#")
                || key.startsWith(binaryName + "."));
    }

    /**
     * A cheap stand-in for "the workspace's copy of this class", or null when it declares none.
     *
     * <p>A hash rather than the text: this runs on the resolution path, and holding a reference to every
     * probed file's source would make an editor-shaped cache out of what is meant to be a validity stamp.
     * A collision means one missed invalidation, which is exactly the behaviour that existed before this
     * method did. {@code .java} only — one index holds both languages and a {@code .js} file is not
     * something the Java engine was ever going to resolve.</p>
     */
    @Nullable
    private static Integer projectStampOf(String binaryName) {
        ProjectSources project = ProjectSourcesRegistry.view();
        String path = project.pathOf(binaryName);
        if (path == null || !path.endsWith(".java")) return null;
        String source = project.sourceOf(binaryName);
        return source == null ? null : source.hashCode();
    }

    /** What each cached class's workspace file looked like when it was cached. @see #forgetIfProjectSourceChanged */
    private final Map<String, Integer> projectStamps = new HashMap<>();

    /** What the class itself is, for a hover over {@code java.util.ArrayList}. */
    @Nullable
    synchronized SymbolInfo describe(String binaryName, boolean staticSide) {
        if (binaryName == null || binaryName.isEmpty()) return null;
        forgetIfProjectSourceChanged(binaryName);
        if (!permits(binaryName)) return null;
        int lastDot = binaryName.lastIndexOf('.');
        String simple = lastDot < 0 ? binaryName : binaryName.substring(lastDot + 1);
        String container = lastDot < 0 ? null : binaryName.substring(0, lastDot);
        Probe probe = probeFor(binaryName);
        TypeRef type = probe != null ? probe.type()
                : (exists(binaryName) ? JsTypeRef.javaInstance(binaryName) : null);
        if (type == null) return null;

        // THE JAVA ENGINE'S OWN DESCRIPTION OF THE TYPE, where there is one. This used to hand-build the
        // symbol with a hard-coded CLASS and no signature, and both showed: `java.util.List` reported
        // itself a class, so the owner band drew a class glyph beside an interface; and with no signature
        // the popup fell through to its ASSEMBLED line, which paints from its own three bands rather than
        // from the editor's scheme. So a Java type hovered from JavaScript read `class ArrayList` in the
        // popup's yellow while the identical hover in a .java file read
        // `public interface List<E> extends SequencedCollection<E>` in the code colours. Same widget,
        // same session, two answers.
        SymbolInfo declared = probe == null ? null : probe.declaration();
        SymbolInfo described = new SymbolInfo(simple,
                declared == null ? SymbolKind.CLASS : declared.kind(),
                staticSide ? JsTypeRef.javaClass(binaryName) : type,
                container, declared == null ? null : declared.documentation(),
                declared == null ? Set.of() : declared.modifiers(),
                // AND WHERE IT IS DECLARED. The probe resolves against the real classpath, so its answer
                // carries a site the moment the Java engine can produce one -- and passing null here
                // threw it away, which made Ctrl+Click on a Java TYPE in a .js file do nothing while the
                // same click on one of its MEMBERS worked, because the member path hands the probe's
                // answer back whole. Third field this hand-built symbol has quietly dropped: the kind
                // and the signature were the first two, and each read as a different feature failing.
                declared == null ? null : declared.declaration());
        return declared == null ? described : described.withSignature(declared.signature());
    }

    /**
     * Whether the class exists at all — what decides a package chain is a type and not a typo.
     *
     * <p><b>Remembered separately from the analyses.</b> Every JSDoc type name and every package chain in
     * the file asks this, once per keystroke through {@code symbolsInScope}; going through the LRU meant a
     * yes/no answer could evict the member list somebody was about to read.</p>
     */
    synchronized boolean exists(String binaryName) {
        forgetIfProjectSourceChanged(binaryName);
        if (binaryName == null || binaryName.isEmpty()) return false;
        if (!permits(binaryName)) return false;
        Boolean known = existence.get(binaryName);
        if (known != null) return known;
        boolean found = java != null ? probeFor(binaryName) != null : JsLoaders.load(binaryName) != null;
        existence.put(binaryName, found);
        return found;
    }

    /** Whether a name is a class, unbounded — a boolean per name asked about. @see #exists */
    private final Map<String, Boolean> existence = new HashMap<>();

    public synchronized void close() {
        for (Probe probe : cache.values()) probe.close();
        cache.clear();
        projectStamps.clear();
        members.clear();
        memberLists.clear();
        existence.clear();
    }

    // ── The probe unit ──────────────────────────────────────────────────────────────────────────

    @Nullable
    private Probe probeFor(String binaryName) {
        if (java == null) return null;
        Probe cached = cache.get(binaryName);
        if (cached != null) return cached.isUsable() ? cached : null;

        String prefix = "class " + PROBE_CLASS + " { ";
        String source = prefix + binaryName + " " + PROBE_FIELD + "; }";
        int offset = source.indexOf(PROBE_FIELD);
        // AND WHERE THE TYPE ITSELF IS WRITTEN. Resolving the FIELD answers what it is; resolving the
        // type NAME answers what the type is -- its kind, its modifiers and the declaration the Java
        // engine would quote for it. The unit already exists and is already analysed, so this is a second
        // question to an answer we have rather than a second compile.
        int typeOffset = prefix.length() + binaryName.lastIndexOf('.') + 1;
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
        Probe probe = new Probe(analysis, type, offset, typeOffset);
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
        private final int typeOffset;
        @Nullable private List<SymbolInfo> members;
        @Nullable private SymbolInfo declaration;
        private boolean declarationAsked;
        private boolean closed;

        Probe(Analysis analysis, @Nullable TypeRef type, int offset, int typeOffset) {
            this.analysis = analysis;
            this.type = type;
            this.offset = offset;
            this.typeOffset = typeOffset;
        }

        /**
         * What the Java engine says about the TYPE — kind, modifiers, and its quoted declaration.
         *
         * <p>The flag rather than a null check, because "no declaration" is a real answer for a type with
         * no source beside it and re-asking on every hover would defeat the cache the whole class is.</p>
         */
        @Nullable
        SymbolInfo declaration() {
            if (!isUsable()) return null;
            if (!declarationAsked) {
                declarationAsked = true;
                declaration = analysis.resolveAt(typeOffset);
            }
            return declaration;
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

        public void close() {
            if (closed) return;
            closed = true;
            members = List.of();
            analysis.close();
        }
    }

    // ── The reflection fallback ─────────────────────────────────────────────────────────────────

    private static List<SymbolInfo> reflectMembers(String binaryName) {
        // THROUGH THE ONE LOOKUP — @see JsLoaders. It held its own copy of the host loader, spelled
        // differently from the executor's, so the editor's "does this class exist" and the runtime's "load
        // this class" were two questions with two answers about one name.
        Class<?> type = JsLoaders.load(binaryName);
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

    /**
     * How a Java type is written down — <b>one implementation, asked from both sides</b>.
     *
     * <p>This was {@code Class.getSimpleName()} and the syntactic tier had its own, which is exactly the
     * shape that produced two conventions in one popup: a type from a MEMBER lookup displayed short and
     * a type from a chain displayed as its binary name, so {@code var list: java.util.ArrayList} sat one
     * line above {@code var text: CgMaterial}. The two would also have disagreed about a nested class —
     * {@code getSimpleName()} answers {@code Entry} where an author writes {@code Map.Entry}.</p>
     *
     * <p>So it delegates. {@link Class#getName()} is the binary name, which is what
     * {@code JsTypeRef.simpleNameOf} takes, and that method owns the decision for everything: the
     * package cut, the {@code $} of a nested class, and the JVM's array spelling.</p>
     */
    private static String simpleName(Class<?> type) {
        return JsTypeRef.simpleNameOf(type.getName());
    }
}
