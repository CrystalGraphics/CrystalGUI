package com.crystalgui.language.java.ecj;

import com.crystalgui.language.map.MappingSet;
import com.crystalgui.language.map.ReadableView;
import com.crystalgui.language.platform.ScriptPlatform;

import org.eclipse.jdt.internal.compiler.env.IModule;
import org.eclipse.jdt.internal.compiler.env.IModuleAwareNameEnvironment;
import org.eclipse.jdt.internal.compiler.env.INameEnvironment;
import org.eclipse.jdt.internal.compiler.env.NameEnvironmentAnswer;

import java.util.HashMap;
import java.util.Map;

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
 * <h3>The classpath is still the fallback, and off a Minecraft host it is the whole answer</h3>
 *
 * <p>With no platform registered — the harness, every test, a plain JVM — {@link #liveBytesFirst} is
 * false and every query goes straight to the delegate, so behaviour is identical to the file-based path
 * this replaces. That is deliberate: the environment nobody runs is the environment nobody notices
 * breaking, and this one is run by the entire existing suite.</p>
 *
 * <p>With a platform, live bytes win and the delegate answers for everything the runtime does not have —
 * the JDK, and any jar on the script's classpath that is not loaded.</p>
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
    private final ReadableView readable;
    private final boolean liveBytesFirst;

    /** @see #cache — per instance, and an instance is per compile */
    private final Map<String, NameEnvironmentAnswer> cache = new HashMap<String, NameEnvironmentAnswer>();

    /** Names already looked up and known absent, so a miss is not re-fetched within one compile. */
    private final Map<String, Boolean> misses = new HashMap<String, Boolean>();

    ScriptNameEnvironment(INameEnvironment delegate, ScriptPlatform platform, MappingSet mappings) {
        this.delegate = delegate;
        // NONE reads the classloader, which off a Minecraft host would answer for everything and quietly
        // take over from the classpath. Asking only when a platform is actually registered keeps the
        // existing path byte-for-byte unchanged where nothing needs it.
        this.liveBytesFirst = platform != ScriptPlatform.NONE;
        this.readable = new ReadableView(mappings, platform.liveBytes());
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
        if (!liveBytesFirst) return delegate.findType(split(internalName));

        NameEnvironmentAnswer cached = cache.get(internalName);
        if (cached != null) return cached;
        if (!misses.containsKey(internalName)) {
            byte[] bytes = readableBytes(internalName);
            if (bytes != null) {
                NameEnvironmentAnswer answer = answerFor(internalName, bytes);
                if (answer != null) {
                    cache.put(internalName, answer);
                    return answer;
                }
            }
            misses.put(internalName, Boolean.TRUE);
        }
        return delegate.findType(split(internalName));
    }

    /**
     * The type's bytes as the readable namespace sees them, or null.
     *
     * <p>Failure is an ordinary answer, not an error: a type the live loader cannot produce is one the
     * classpath may still have, and turning that into an exception would make an unremarkable miss fatal
     * to a compile.</p>
     */
    private byte[] readableBytes(String internalName) {
        try {
            return readable.readableBytesOf(internalName);
        } catch (Exception unavailable) {
            return null;
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

    @Override
    public boolean isPackage(char[][] parentPackageName, char[] packageName) {
        // ALWAYS THE DELEGATE. A package is not a class file, so live bytes cannot answer it, and
        // guessing yes would make every misspelled type look like a package rather than an error --
        // which reads as a phantom resolution failure much later, in a message about something else.
        return delegate.isPackage(parentPackageName, packageName);
    }

    @Override
    public void cleanup() {
        cache.clear();
        misses.clear();
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

    private static String internalName(char[][] packageName, char[] typeName) {
        StringBuilder name = new StringBuilder();
        int segments = typeName == null ? packageName.length : packageName.length;
        for (int i = 0; i < segments; i++) {
            if (typeName == null && i == packageName.length - 1) break;
            if (name.length() > 0) name.append('/');
            name.append(packageName[i]);
        }
        if (typeName == null) {
            if (name.length() > 0) name.append('/');
            name.append(packageName[packageName.length - 1]);
        } else {
            if (name.length() > 0) name.append('/');
            name.append(typeName);
        }
        return name.toString();
    }

    private static char[][] split(String internalName) {
        String[] parts = internalName.split("/");
        char[][] out = new char[parts.length][];
        for (int i = 0; i < parts.length; i++) out[i] = parts[i].toCharArray();
        return out;
    }
}
