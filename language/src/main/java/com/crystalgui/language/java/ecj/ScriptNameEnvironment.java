package com.crystalgui.language.java.ecj;

import com.crystalgui.language.engine.bridge.TypeBytes;

import org.eclipse.jdt.internal.compiler.env.IModule;
import org.eclipse.jdt.internal.compiler.env.IModuleAwareNameEnvironment;
import org.eclipse.jdt.internal.compiler.env.INameEnvironment;
import com.crystalgui.text.lang.ProjectSources;
import com.crystalgui.text.lang.ProjectSourcesRegistry;
import org.eclipse.jdt.core.compiler.CharOperation;
import org.eclipse.jdt.internal.compiler.env.NameEnvironmentAnswer;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * What the compiler resolves against: the <b>live runtime</b> first, the classpath behind it.
 *
 * <h3>Why a name environment rather than a directory of files</h3>
 *
 * <p>{@code ReadableView.materialise} writes remapped classes out and hands over the path, because
 * {@code setEnvironment} and {@code -classpath} both take file paths. That is correct anywhere bytes are
 * obtainable and wrong on a live Minecraft host, for two reasons that no amount of writing fixes:</p>
 *
 * <ul>
 *   <li><b>The disk view lies.</b> 1.7.10 production ships Notch-obfuscated jars whose classes are
 *       remapped <em>as they load</em>, so SRG members exist only in memory.</li>
 *   <li><b>Transformers add members no class file has.</b> A type whose bytes exist only because a mixin
 *       produced them cannot be found by looking at a jar, and that is precisely the case §15.5 A is
 *       for.</li>
 * </ul>
 *
 * <p>Answering from bytes means what the compiler resolves against is exactly what will execute.</p>
 *
 * <h3>Three tiers, in this order, and the order is the design</h3>
 *
 * <ol>
 *   <li><b>{@link TypeBytes#readable}</b> — the live runtime, remapped. What is loaded is what will
 *       execute, so where it and a file disagree it wins (§15.2).</li>
 *   <li><b>The classpath delegate</b> — ECJ's own {@code FileSystem}. The JDK, and every jar that is on
 *       the script's classpath and not loaded.</li>
 *   <li><b>{@link TypeBytes#synthesized}</b> — a reflective stub, and only where the first two have both
 *       said nothing. It is erased of whatever reflection cannot see, so it must never pre-empt a real
 *       class file.</li>
 * </ol>
 *
 * <h3>It holds a bridge type and nothing else, which is not a style choice</h3>
 *
 * <p>This class is loaded by {@code EngineClassLoader}, which is child-first for everything outside
 * {@code java.*}, the bridge package and {@code com.crystalgui.text.*}. So naming
 * {@code ScriptServices} here gets the band's <em>own</em> copy of it, with its own statics — and since
 * {@code register()} runs on the host, the compiler reads a registry nothing ever wrote to, concludes
 * there is no platform, and resolves entirely from files. <b>Everything works and nothing is live.</b>
 * That is not hypothetical: it is what the first version of this class did, undetectably, because a
 * file-based answer is a plausible answer.</p>
 *
 * <p>So the host composes {@link TypeBytes} and only {@code byte[]} crosses — the same rule
 * {@code MemberNameMapper} states for {@code MappingSet}, the console for its {@code Consumer} and the
 * sandbox for its {@code Predicate}.</p>
 *
 * <p>With {@link TypeBytes#NONE} — the harness, every test, a plain JVM — {@link #live} is false and
 * every query goes straight to the delegate, so behaviour is identical to the file-based path this
 * replaces. That is deliberate: the environment nobody runs is the environment nobody notices breaking,
 * and this one is run by the entire existing suite.</p>
 *
 * <h3>Module-aware, because on a modern band ECJ requires it</h3>
 *
 * <p>Implementing only {@code INameEnvironment} looks sufficient and is not. At compliance 9 or above
 * the compiler asks its environment module questions, and an environment that cannot answer them sends
 * it down a path that ends in {@code ProblemReporter.moduleNotFound} — which then throws
 * {@code NullPointerException} out of {@code String.valueOf(null)} while trying to name the module it
 * did not find. The failure therefore arrives as an NPE inside ECJ's own error reporting, naming
 * nothing about modules and nothing about this class.</p>
 *
 * <p>It only bites on the bands that are module-aware, so a fixture on band 8 would never see it. Every
 * module question is delegated: this class has an opinion about where a type's <em>bytes</em> come
 * from and none whatsoever about module structure, which is genuinely the classpath's to answer.</p>
 *
 * <h3>The cache is per instance, which means per compile</h3>
 *
 * <p><b>Not per process, and that is the whole point.</b> A mixin can add a member between one run and
 * the next; a cache that outlived a compile would answer from before it, and the script would compile
 * against a member the runtime has and then fail to link against one it does not — or the reverse. A
 * short-lived cache is still worth having, because ECJ asks for the same supertypes repeatedly while
 * resolving one unit.</p>
 */
final class ScriptNameEnvironment implements IModuleAwareNameEnvironment {

    private final INameEnvironment delegate;

    /** @see #cache — per instance, and an instance is per compile */
    private final Map<String, NameEnvironmentAnswer> cache = new HashMap<String, NameEnvironmentAnswer>();

    /** Names already looked up and known absent, so a miss is not re-fetched within one compile. */
    private final Map<String, Boolean> misses = new HashMap<String, Boolean>();

    /**
     * Packages a type has genuinely been resolved inside. @see #isPackage
     *
     * <p>Per instance for the same reason the caches are: a package that exists only because a
     * transformer synthesized a class into it can stop existing between one compile and the next.</p>
     */
    private final Set<String> resolvedPackages = new HashSet<String>();

    /** Names already decided package-or-type by the live tier, for this compile. @see #isPackage */
    private final Map<String, Boolean> packages = new HashMap<String, Boolean>();

    /** @see #classpathModules */
    private char[][] classpathModules;

    /** Everything the classpath cannot supply, composed on the HOST side. @see TypeBytes */
    private final TypeBytes types;

    /**
     * Whether the live tiers are consulted at all.
     *
     * <p>False for {@link TypeBytes#NONE} — the harness, every test, a plain JVM — so every query goes
     * straight to the delegate and behaviour is identical to the file-based path this replaces. That is
     * deliberate: the environment nobody runs is the environment nobody notices breaking, and this one is
     * run by the entire existing suite.</p>
     */
    private final boolean live;

    /**
     * What the workspace itself declares. Reached as a static because
     * {@code com.crystalgui.text.} is <b>parent-first</b> on the band loader.
     *
     * <p>That is not a convenience — it is the whole reason the SPI lives in that package tree. A registry
     * under {@code com.crystalgui.language.*} would be redefined inside the band, so its statics would be
     * a <em>different</em> set: {@code register()} would run on the host and this would read an empty one.
     * §15.5 A shipped precisely that, and the symptom was not a failure but a plausible answer —
     * everything resolved from files and the feature was inert for a release.</p>
     */
    private final ProjectSources project;

    /**
     * The type being compiled, which must never be answered for.
     *
     * <p>The unit under analysis is already in ECJ's {@code unitsToProcess}. Handing the same name back
     * from the environment as a second source unit is how a file comes to be declared twice, and the
     * error lands on the author's own class rather than on anything they did.</p>
     */
    private final String self;

    ScriptNameEnvironment(INameEnvironment delegate, TypeBytes types,
                          ProjectSources project, String self) {
        this(delegate, types, project, self, false);
    }

    /**
     * @param mayWaitForSources whether a project file nobody has open may be WAITED for. True for a
     *                          compile that is about to run something, false inside an analysis — see
     *                          {@link ProjectSources#awaitSourceOf}
     */
    ScriptNameEnvironment(INameEnvironment delegate, TypeBytes types,
                          ProjectSources project, String self, boolean mayWaitForSources) {
        this.delegate = delegate;
        this.types = types == null ? TypeBytes.NONE : types;
        this.live = this.types != TypeBytes.NONE;
        this.project = project == null ? ProjectSources.NONE : project;
        this.self = self;
        this.mayWaitForSources = mayWaitForSources;
    }

    /** @see ProjectSources#awaitSourceOf */
    private final boolean mayWaitForSources;

    @Override
    public NameEnvironmentAnswer findType(char[][] compoundTypeName) {
        return find(internalName(compoundTypeName, null));
    }

    @Override
    public NameEnvironmentAnswer findType(char[] typeName, char[][] packageName) {
        return find(internalName(packageName, typeName));
    }

    private NameEnvironmentAnswer find(String internalName) {
        // THE PROJECT FIRST, and OUTSIDE the `live` gate.
        //
        // Outside, because `live` means "there are runtime bytes to overlay" -- the harness, every test
        // and a plain JVM have none, and those are exactly the hosts whose project files must resolve.
        // Putting this behind the gate would make cross-file resolution a Minecraft-only feature.
        //
        // First, because a workspace's own source outranks a jar that happens to publish the same name.
        // That is what every IDE does, and the alternative is that adding a dependency silently changes
        // which file your own code compiles against.
        NameEnvironmentAnswer fromProject = fromProject(internalName);
        if (fromProject != null) return fromProject;

        if (!live) return delegate.findType(split(internalName));

        NameEnvironmentAnswer cached = cache.get(internalName);
        if (cached != null) return cached;
        if (!misses.containsKey(internalName)) {
            // FOR COMPILING, so a member spelled the way the runtime spells it resolves too. This is the
            // one consumer that wants both names; the decompiler deliberately keeps the plain view.
            byte[] bytes = types.forCompiling(internalName);
            if (bytes != null) {
                NameEnvironmentAnswer answer = answerFor(internalName, bytes);
                if (answer != null) {
                    remember(internalName, answer);
                    return answer;
                }
            }
            misses.put(internalName, Boolean.TRUE);
        }

        NameEnvironmentAnswer fromFiles = delegate.findType(split(internalName));
        if (fromFiles != null) {
            // NOT CACHED. The delegate has its own cache and is the authority on its own answers;
            // holding a second copy here would only add a way for the two to disagree.
            rememberPackageOf(internalName);
            return fromFiles;
        }
        return synthesized(internalName);
    }

    /**
     * The project's own source for {@code internalName}, as a compilation unit ECJ can resolve against.
     *
     * <p>Null for a name the project does not declare, for the unit being compiled, and for a file whose
     * text has not been read yet — {@link ProjectSources#sourceOf} cannot block, so "not yet" and "no"
     * arrive the same way. The analysis simply resolves without it and the next one, after the read
     * lands, resolves with it.</p>
     *
     * <p><b>Not cached here.</b> The text can change on any keystroke in another editor, and this
     * environment outlives the call that built it — a cached source unit would pin the version of a file
     * as it was when some earlier analysis happened to ask.</p>
     */
    private NameEnvironmentAnswer fromProject(String internalName) {
        if (internalName == null || internalName.equals(self)) return null;
        String qualified = internalName.replace('/', '.');

        // ONLY A `.java` FILE. `SourceRoots` names any file under a declared root whatever its extension,
        // and both `src/main/java` and `src/main/js` are declared -- so one index holds
        // `com.example.Main` and `util.Greeter` side by side with nothing in the NAME to say which
        // language wrote it. Handing a script to ECJ produces a page of syntax errors about the wrong
        // file instead of the one true thing: there is no such type. A provider that cannot say where a
        // name lives is trusted, which keeps every in-memory stand-in behaving as it did.
        String path = project.pathOf(qualified);
        if (path != null && !path.endsWith(".java")) return null;

        // WAITED FOR ONLY WHEN SOMETHING IS ABOUT TO RUN. `sourceOf` answers null for a file nobody has
        // open and schedules a read, which is right on a keystroke and wrong for a run: there, "not yet"
        // is not a deferral but a failure, and running a second time makes it work. @see #mayWaitForSources
        String source = mayWaitForSources
                ? project.awaitSourceOf(qualified) : project.sourceOf(qualified);
        if (source == null) return null;
        // RECORDED, because a compiled script is CACHED and this is the only place that knows what went
        // into it. The key describes the file the author ran; a sibling it pulled in is invisible to it,
        // so editing that sibling would leave the cache serving bytes compiled against the old one --
        // the file would be "saved" as far as the compiler was concerned and the run would not change.
        // @see ScriptCompiler.Result#projectSources
        consumedProjectSources.add(qualified);
        return new NameEnvironmentAnswer(new ProjectUnit(qualified, source), null);
    }

    /**
     * Every project type this compile resolved from the workspace rather than the classpath.
     *
     * <p>A {@code Set} of NAMES and not of texts: the host hashes the current source itself, both when
     * it stores an entry and when it looks one up, so the two are always compared the same way. Handing
     * the text across would also mean retaining a copy of every file a script touched.</p>
     */
    Set<String> consumedProjectSources() {
        return consumedProjectSources;
    }

    private final Set<String> consumedProjectSources = new LinkedHashSet<>();

    /** One project file, as the compiler's idea of a compilation unit. */
    private static final class ProjectUnit
            implements org.eclipse.jdt.internal.compiler.env.ICompilationUnit {

        private final char[] contents;
        private final char[] mainTypeName;
        private final char[][] packageName;
        private final char[] fileName;

        ProjectUnit(String qualifiedName, String source) {
            this.contents = source.toCharArray();
            int lastDot = qualifiedName.lastIndexOf('.');
            String simple = lastDot < 0 ? qualifiedName : qualifiedName.substring(lastDot + 1);
            this.mainTypeName = simple.toCharArray();
            this.packageName = lastDot < 0
                    ? CharOperation.NO_CHAR_CHAR
                    : CharOperation.splitOn('.', qualifiedName.substring(0, lastDot).toCharArray());
            // THE PATH IT WOULD HAVE, which ECJ compares against the declared package. A name alone would
            // make every project file report "the declared package does not match the expected package".
            this.fileName = (qualifiedName.replace('.', '/') + ".java").toCharArray();
        }

        @Override
        public char[] getContents() {
            return contents;
        }

        @Override
        public char[] getMainTypeName() {
            return mainTypeName;
        }

        @Override
        public char[][] getPackageName() {
            return packageName;
        }

        @Override
        public char[] getFileName() {
            return fileName;
        }
    }

    /**
     * The last resort: a stub built by reflection, for a type that exists to the JVM and has no bytes.
     *
     * <p><b>Third and not second.</b> A stub is a weaker answer than either real source — it is erased of
     * everything reflection cannot see, and it describes the class as <em>loaded</em> rather than as the
     * compiler would read it. So it only ever answers where both the live loader and the classpath have
     * already said nothing, which is precisely §15.5 A's case: a type the loader will hand over a
     * {@link Class} for but no bytes. A class generated at runtime and a class a previous script defined
     * are the two that occur, and both would otherwise fail to compile against something the author can
     * demonstrably call.</p>
     *
     * <p>Loaded with {@code initialize = false}: resolving a name must never run a static initializer.
     * On a Minecraft host that would execute arbitrary class setup during a keystroke.</p>
     */
    private NameEnvironmentAnswer synthesized(String internalName) {
        byte[] stub = types.synthesized(internalName);
        if (stub == null) return null;
        NameEnvironmentAnswer answer = answerFor(internalName, stub);
        if (answer != null) remember(internalName, answer);
        return answer;
    }

    private void remember(String internalName, NameEnvironmentAnswer answer) {
        cache.put(internalName, answer);
        rememberPackageOf(internalName);
    }

    /**
     * Records that a package exists, because a type inside it resolved.
     *
     * <p>Every ancestor, not just the immediate one: {@code isPackage} is asked about each segment of a
     * qualified name in turn, so recording only {@code net/minecraft/world} would leave
     * {@code net/minecraft} unanswered and the walk would stop before reaching it.</p>
     */
    private void rememberPackageOf(String internalName) {
        for (int slash = internalName.indexOf('/'); slash > 0; slash = internalName.indexOf('/', slash + 1)) {
            resolvedPackages.add(internalName.substring(0, slash));
        }
    }

    /**
     * A {@code NameEnvironmentAnswer} over raw bytes.
     *
     * <p><b>Not fully initialised</b>, and that is deliberate rather than a default left alone. The eager
     * form was tried while chasing an empty member list and rejects a class file that a lazy read
     * tolerates — a synthesized stub carries method entries with no {@code Code} attribute, which is
     * malformed by the letter of the format and exactly what {@code TypeBytes.synthesized} produces. It
     * made a live-only type stop resolving at all, and it was never the fix: the empty member list was a
     * name environment closed before its bindings were read. @see EcjSourceAnalyzer#live</p>
     */
    private static NameEnvironmentAnswer answerFor(String internalName, byte[] bytes) {
        try {
            return new NameEnvironmentAnswer(
                    org.eclipse.jdt.internal.compiler.classfmt.ClassFileReader
                            .read(new java.io.ByteArrayInputStream(bytes), internalName + ".class"),
                    null);
        } catch (Exception unreadable) {
            // Bytes that are not a class file the compiler can read are the same as no bytes -- the
            // classpath gets its turn rather than the compile failing on our account.
            return null;
        }
    }

    /**
     * Whether a name is a package.
     *
     * <h3>Why the delegate alone cannot answer this on a live runtime</h3>
     *
     * <p>A package is not a class file. A {@code ByteSource} answers "give me these bytes" and a
     * classloader cannot enumerate what lives under a prefix, so there is nothing to ask it directly.
     * The classpath delegate answers correctly wherever the files are laid out in packages — which is
     * everywhere except the case this whole layer exists for.</p>
     *
     * <p><b>On an obfuscated 1.7.10 client there is no {@code net/minecraft/init} directory anywhere.</b>
     * The jar holds {@code ave.class} and friends; {@code net.minecraft.init.Blocks} exists only after
     * the deobfuscating transformer has run. So the delegate says "not a package", and ECJ — which asks
     * about each segment of a qualified name <em>before</em> it looks the type up — stops with
     * {@code net.minecraft.init cannot be resolved}. Every Minecraft type in every script, on the one
     * environment that is production. Found by running the reobfuscated client, and invisible in dev
     * where the packages really are directories.</p>
     *
     * <h3>So the live tier answers it the only way it can: a name that is not a TYPE is a package</h3>
     *
     * <p>That is what any classloader-backed name environment does, and the inversion is sound in the
     * direction that matters: the source can say definitively that something <em>is</em> a type, so
     * everything else is either a package or nothing, and ECJ finds out which the moment it asks for the
     * type itself.</p>
     *
     * <p>It does mean a misspelled qualified name is reported as an unresolvable <em>type</em> rather
     * than an unknown package, which is the better message anyway. It is gated on {@link #live}, so a
     * harness, a test and a plain JVM keep the delegate's answer exactly as before.</p>
     */
    @Override
    public boolean isPackage(char[][] parentPackageName, char[] packageName) {
        if (delegate.isPackage(parentPackageName, packageName)) return true;

        String name = internalName(parentPackageName, packageName);
        // THE PROJECT'S PACKAGES, through the SHARED predicate rather than inline. @see #declaredByProject
        if (declaredByProject(name)) return true;

        if (!live) return false;
        if (resolvedPackages.contains(name)) return true;
        return isPackageName(name);
    }

    /**
     * The inversion itself, <b>in one place because two callers share its cache</b>.
     *
     * <p>{@link #isPackage} and {@link #getModulesDeclaringPackage} ask the same question and memoise the
     * answer in the same map, so a second copy of the predicate is not duplication — it is a way for the
     * two to disagree, decided by which ECJ happens to ask first. That is exactly what happened: this one
     * was corrected and the other was not, so the corrected method went on returning the wrong answer out
     * of the cache the stale one had filled, and the fix appeared to do nothing at all.</p>
     */
    /**
     * Whether the workspace declares anything at or under {@code name}, in internal form.
     *
     * <p>One predicate for both askers, for the reason {@link #isPackageName} states at length: they
     * memoise into the same place and a second copy is a way for the two to disagree, decided by which
     * ECJ happens to ask first. Deliberately <b>outside</b> the {@code live} gate — `live` means there are
     * runtime bytes to overlay, and the hosts with none are exactly the ones whose project files must
     * resolve.</p>
     */
    private boolean declaredByProject(String internalName) {
        return internalName != null && !internalName.isEmpty()
                && project.declaresPackage(internalName.replace('/', '.'));
    }

    private boolean isPackageName(String name) {
        Boolean known = packages.get(name);
        if (known != null) return known;
        // A TYPE of this exact name settles it; anything else is treated as a package. Cached, because
        // ECJ asks about the same prefixes for every qualified name in a unit -- `net`, `net/minecraft`
        // and `net/minecraft/init` once per Minecraft type the script mentions.
        //
        // ASKED OF THE LIVE SOURCE ALONE, deliberately. Consulting the delegate too looks like it would
        // make this more accurate -- it is the classpath, after all, and it knows what a type is. It is
        // not: `findType` on a name that is a package PREFIX rather than a type is a miss ECJ records,
        // and asking it here for every segment of every qualified name resolved `demo` to nothing where
        // the live route had it. The inversion's job is to answer for names the classpath has never
        // heard of, which is the whole reason it exists on an obfuscated host.
        boolean isPackage = types.readable(name) == null;
        packages.put(name, isPackage);
        return isPackage;
    }

    @Override
    public void cleanup() {
        cache.clear();
        misses.clear();
        packages.clear();
        resolvedPackages.clear();
        delegate.cleanup();
    }

    // ── Module questions: delegated wholesale ────────────────────────────────────────────────────
    //
    // Answered by the classpath, which is the thing that actually knows. The live loader supplies BYTES
    // for a type; it has no view of module structure, and inventing one here would be a second opinion
    // about something this class has no information on.
    //
    // Guarded on the delegate really being module-aware: FileSystem is, but the fallback path and any
    // future delegate need not be, and calling through blindly would trade a compile failure for a
    // ClassCastException.

    @Override
    public NameEnvironmentAnswer findType(char[][] compoundName, char[] moduleName) {
        NameEnvironmentAnswer live = findType(compoundName);
        if (live != null) return live;
        return modules() == null ? null : modules().findType(compoundName, moduleName);
    }

    @Override
    public NameEnvironmentAnswer findType(char[] typeName, char[][] packageName, char[] moduleName) {
        NameEnvironmentAnswer live = findType(typeName, packageName);
        if (live != null) return live;
        return modules() == null ? null : modules().findType(typeName, packageName, moduleName);
    }

    /**
     * Which modules declare a package — <b>the module-aware spelling of {@link #isPackage}</b>.
     *
     * <h3>This is the one ECJ actually calls, and overriding only {@code isPackage} looks right</h3>
     *
     * <p>{@code isPackage} is a <em>default</em> method on {@code IModuleAwareNameEnvironment},
     * implemented in terms of this one — so at compliance 9 or above ECJ asks here and the override
     * never runs. The failure is silent and total: {@code demo.live.OnlyInMemory} is looked up as a type,
     * comes back null, and resolution stops with {@code demo cannot be resolved to a type} without
     * {@code isPackage} being consulted once.</p>
     *
     * <p>It also splits by band, which is what makes it nasty to find: band 8 compiles at compliance 8
     * and uses {@code isPackage}, so an obfuscated 1.7.10 client worked while the test suite — which runs
     * on band 17 — did not, for the same source and the same environment.</p>
     *
     * <h3>The module answer is BORROWED rather than invented</h3>
     *
     * <p>A live package belongs wherever the classpath's own types belong, and naming that module means
     * naming ECJ's unnamed-module constant — an internal detail that has moved before. So the delegate is
     * asked once about a package it certainly has, and its answer is reused verbatim. That cannot drift
     * from what the rest of the environment reports, because it <em>is</em> what the rest of the
     * environment reports.</p>
     */
    @Override
    public char[][] getModulesDeclaringPackage(char[][] packageName, char[] moduleName) {
        char[][] fromFiles = modules() == null
                ? null : modules().getModulesDeclaringPackage(packageName, moduleName);
        if (fromFiles != null) return fromFiles;
        // BEFORE the `live` early-return, or a project package is invisible on every host without a
        // platform -- which is every host that has a workspace open.
        if (declaredByProject(internalName(packageName, null))) return projectModule();
        if (!live) return null;

        String name = internalName(packageName, null);
        if (name.isEmpty()) return null;
        // THE PROJECT, ASKED HERE TOO -- and this is the exact trap the note on `isPackageName` records.
        // The first version of M15 S4 taught `isPackage` about project packages and left this one alone,
        // so an import of a project type failed with "The import com.example cannot be resolved" while
        // the identical question asked the other way answered true.
        if (declaredByProject(name)) return projectModule();
        if (resolvedPackages.contains(name)) return classpathModules(moduleName);
        // THE SAME PREDICATE, not a second copy of it. @see #isPackageName
        return isPackageName(name) ? classpathModules(moduleName) : null;
    }

    /**
     * Whatever the delegate says about a package it definitely has — {@code java/lang}.
     *
     * <p>Resolved once and cached, including a null answer: an environment that will not name a module
     * for {@code java.lang} is not going to name one for ours either, and asking again per package would
     * be a delegate call on the hot path of every qualified name.</p>
     */
    /**
     * The module a WORKSPACE package belongs to — the unnamed one, always.
     *
     * <h3>Why this is not {@link #classpathModules}, which is what it used to be</h3>
     *
     * <p>The live tier borrows {@code java/lang}'s module because a live package really does belong
     * wherever the classpath's own types belong — those bytes came from the classpath. A project source
     * file did not. It is compiled here, in the unnamed module, alongside the unit under analysis.</p>
     *
     * <p>Saying otherwise splits a package chain across two modules, and ECJ discards the result rather
     * than complaining. On a JRT classpath {@code java/lang} answers {@code java.base}, so
     * {@code com.example} — the compiled unit's OWN package, in the unnamed module — would acquire a
     * subpackage {@code com.example.util} attributed to {@code java.base}. The import is then reported
     * as <em>"The import com.example.util cannot be resolved"</em> even though this environment answered
     * that the package exists, and the type is never asked for at all.</p>
     *
     * <p><b>It only shows when the imported package sits under the importing file's own.</b> An importer
     * in an unrelated package has every segment of the chain invented by this environment, so all of them
     * get the same wrong module and agree with each other — which is why a cross-package import test
     * passed for a release while {@code com.example.Main} importing {@code com.example.util} did not.</p>
     */
    private static char[][] projectModule() {
        // The unnamed module, spelled as ECJ spells it: a single zero-length name.
        return new char[][]{new char[0]};
    }

    private char[][] classpathModules(char[] moduleName) {
        if (classpathModules == null) {
            // THE CALLER'S OWN moduleName, never null. FileSystem.getModulesDeclaringPackage does
            // String.valueOf(moduleName) with no guard, so asking with null answers with an NPE from
            // inside ECJ rather than with a module list -- and it arrives as a failed conversion, which
            // reads as the type not existing.
            char[][] borrowed = modules() == null ? null
                    : modules().getModulesDeclaringPackage(
                    new char[][]{"java".toCharArray(), "lang".toCharArray()}, moduleName);
            // An empty module name is the unnamed module, which is what a plain classpath is. Used only
            // where the delegate declines to answer at all.
            classpathModules = borrowed == null ? new char[][]{new char[0]} : borrowed;
        }
        return classpathModules;
    }

    @Override
    public boolean hasCompilationUnit(char[][] packageName, char[] moduleName, boolean checkCUs) {
        return modules() != null && modules().hasCompilationUnit(packageName, moduleName, checkCUs);
    }

    @Override
    public IModule getModule(char[] moduleName) {
        return modules() == null ? null : modules().getModule(moduleName);
    }

    @Override
    public char[][] getAllAutomaticModules() {
        return modules() == null ? new char[0][] : modules().getAllAutomaticModules();
    }

    @Override
    public char[][] listPackages(char[] moduleName) {
        return modules() == null ? new char[0][] : modules().listPackages(moduleName);
    }

    private IModuleAwareNameEnvironment modules() {
        return delegate instanceof IModuleAwareNameEnvironment
                ? (IModuleAwareNameEnvironment) delegate : null;
    }

    /**
     * {@code a/b/C} from either shape ECJ asks in.
     *
     * <p>Two callers with different conventions: {@code findType(char[][])} passes the whole compound
     * name with the type as its last segment, and {@code findType(char[], char[][])} passes the package
     * and the type apart. Joining them here is why {@link #find} has one form to key its caches on.</p>
     */
    private static String internalName(char[][] packageName, char[] typeName) {
        // NULL IS THE DEFAULT PACKAGE, and ECJ really does pass it: `isPackage(null, "demo")` is how it
        // asks about a top-level package name. Treating it as an empty array rather than dereferencing
        // it matters more than it looks -- an NPE thrown out of a name environment does not surface as a
        // crash, it surfaces as `demo cannot be resolved to a type`, which reads as the type genuinely
        // being absent and sends you looking at the byte source.
        char[][] segmentsIn = packageName == null ? new char[0][] : packageName;
        if (typeName == null && segmentsIn.length == 0) return "";
        StringBuilder name = new StringBuilder();
        int segments = typeName == null ? segmentsIn.length - 1 : segmentsIn.length;
        for (int i = 0; i < segments; i++) {
            if (name.length() > 0) name.append('/');
            name.append(segmentsIn[i]);
        }
        if (name.length() > 0) name.append('/');
        name.append(typeName == null ? segmentsIn[segmentsIn.length - 1] : typeName);
        return name.toString();
    }

    private static char[][] split(String internalName) {
        String[] parts = internalName.split("/");
        char[][] out = new char[parts.length][];
        for (int i = 0; i < parts.length; i++) out[i] = parts[i].toCharArray();
        return out;
    }
}
