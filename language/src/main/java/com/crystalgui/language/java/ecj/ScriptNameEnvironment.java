package com.crystalgui.language.java.ecj;

import com.crystalgui.language.engine.bridge.TypeBytes;

import org.eclipse.jdt.internal.compiler.env.IModule;
import org.eclipse.jdt.internal.compiler.env.IModuleAwareNameEnvironment;
import org.eclipse.jdt.internal.compiler.env.INameEnvironment;
import org.eclipse.jdt.internal.compiler.env.NameEnvironmentAnswer;

import java.util.HashMap;
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
 * {@code ScriptPlatforms} here gets the band's <em>own</em> copy of it, with its own statics — and since
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

    ScriptNameEnvironment(INameEnvironment delegate, TypeBytes types) {
        this.delegate = delegate;
        this.types = types == null ? TypeBytes.NONE : types;
        this.live = this.types != TypeBytes.NONE;
    }

    @Override
    public NameEnvironmentAnswer findType(char[][] compoundTypeName) {
        return find(internalName(compoundTypeName, null));
    }

    @Override
    public NameEnvironmentAnswer findType(char[] typeName, char[][] packageName) {
        return find(internalName(packageName, typeName));
    }

    private NameEnvironmentAnswer find(String internalName) {
        if (!live) return delegate.findType(split(internalName));

        NameEnvironmentAnswer cached = cache.get(internalName);
        if (cached != null) return cached;
        if (!misses.containsKey(internalName)) {
            byte[] bytes = types.readable(internalName);
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
        if (!live) return false;

        String name = internalName(parentPackageName, packageName);
        if (resolvedPackages.contains(name)) return true;

        Boolean known = packages.get(name);
        if (known != null) return known;
        // A TYPE of this exact name settles it; anything else is treated as a package. Cached, because
        // ECJ asks about the same prefixes for every qualified name in a unit -- `net`, `net/minecraft`
        // and `net/minecraft/init` once per Minecraft type the script mentions.
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

    @Override
    public char[][] getModulesDeclaringPackage(char[][] packageName, char[] moduleName) {
        return modules() == null ? null : modules().getModulesDeclaringPackage(packageName, moduleName);
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
        StringBuilder name = new StringBuilder();
        int segments = typeName == null ? packageName.length - 1 : packageName.length;
        for (int i = 0; i < segments; i++) {
            if (name.length() > 0) name.append('/');
            name.append(packageName[i]);
        }
        if (name.length() > 0) name.append('/');
        name.append(typeName == null ? packageName[packageName.length - 1] : typeName);
        return name.toString();
    }

    private static char[][] split(String internalName) {
        String[] parts = internalName.split("/");
        char[][] out = new char[parts.length][];
        for (int i = 0; i < parts.length; i++) out[i] = parts[i].toCharArray();
        return out;
    }
}
