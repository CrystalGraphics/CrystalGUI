package com.crystalgui.language.js;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The names a script may use without declaring one — asked of the engine, once.
 *
 * <h3>Why this is not a list in a file</h3>
 *
 * <p>Which globals exist differs per band, and the difference is real: 1.9.1 has {@code Proxy} and
 * {@code Reflect}; 1.7.15.1 has neither. A hand-written list would therefore be wrong on one of the two
 * shipped engines — and wrong in the direction that hurts, marking a name <em>unresolved</em> that the
 * running engine resolves perfectly well. The capability probe measured exactly this difference; the
 * conclusion it points to is that nothing should be typing the answer at all.</p>
 *
 * <p>It also cannot go stale. A band bump that adds a global adds it here on the next launch, with no
 * commit and nothing to remember.</p>
 *
 * <h3>Read once, and the scope is thrown away</h3>
 *
 * <p>A standard-object scope is not cheap to build and the answer cannot change while a loader lives,
 * so this is computed on first use and held. The scope itself is dropped immediately — only the names
 * are kept, which is all anything here asks and which keeps a live Rhino object out of a static field
 * where the UI thread would eventually touch it.</p>
 */
final class RhinoGlobals {

    private RhinoGlobals() {
    }

    private static volatile Set<String> names;

    /** Every global a fresh standard scope defines, plus Rhino's own Java bridge roots. */
    /** Whether the engine has this name without anybody declaring it. */
    static boolean isBuiltin(String name) {
        return name != null && !name.isEmpty() && names().contains(name);
    }

    static Set<String> names() {
        Set<String> cached = names;
        if (cached != null) return cached;
        synchronized (RhinoGlobals.class) {
            if (names == null) names = read();
            return names;
        }
    }

    private static Set<String> read() {
        return RhinoThread.with(() -> {
            // ALWAYS THE BASELINE, whatever the scope reports. Two reasons, and the second is the one
            // that cost a round: an engine that cannot build a scope at all must not paint the whole
            // file red, AND `getPropertyIds` under-reports a scope that is perfectly fine, because
            // Rhino initialises standard objects LAZILY -- the same trap the capability probe hit when
            // `typeof RegExp` answered "undefined" for a global that plainly exists. A union can only
            // be too generous, and being too generous here costs a missing mark on a typo while being
            // too strict marks working code as broken.
            Set<String> found = new LinkedHashSet<>(FALLBACK);
            Context cx = Context.enter();
            try {
                cx.setLanguageVersion(Context.VERSION_ES6);
                cx.setOptimizationLevel(-1);
                Scriptable scope = cx.initStandardObjects();
                for (Object id : ScriptableObject.getPropertyIds(scope)) {
                    if (id instanceof String) found.add((String) id);
                }
                // AND THE LAZY ONES, FORCED. `typeof X` does not initialise a lazy slot, but referencing
                // X does -- so each candidate is asked in a way that throws a ReferenceError when it is
                // genuinely absent and quietly initialises it when it is not. Which of these exist
                // differs per band (1.9.1 has Proxy and Reflect; 1.7.15.1 has neither), which is the
                // whole reason this is measured rather than listed.
                for (String candidate : MODERN) {
                    if (found.contains(candidate)) continue;
                    if (exists(cx, scope, candidate)) found.add(candidate);
                }
            } catch (RuntimeException unavailable) {
                // The baseline above is already in `found`, so there is nothing to recover.
            } finally {
                Context.exit();
            }
            // ALWAYS PRESENT, whatever the scope said. The bare package roots are how Rhino reaches Java,
            // and the host globals are installed by the executor rather than by initStandardObjects, so a
            // scope built here has never heard of either -- and marking `java.util.List` unresolved in a
            // file whose whole purpose is Java interop would be the most visible possible error.
            //
            // FROM THE CONSTANTS BELOW, not typed again here. The two lists were written out in three
            // files between them and had already drifted: `prompt` is installed and was not listed, and
            // `edu`/`net` are package roots the inference tier reads and this did not -- so `prompt('x')`
            // and `net.minecraft.…`, which is the first line a mod author writes, were both drawn as
            // unresolved names and offered a rename.
            found.addAll(PACKAGE_ROOTS);
            found.addAll(HOST_GLOBALS);
            return Collections.unmodifiableSet(found);
        });
    }

    /** Whether {@code name} resolves in {@code scope} — by referencing it, which forces a lazy slot. */
    private static boolean exists(Context cx, Scriptable scope, String name) {
        try {
            Object answer = cx.evaluateString(scope,
                    "(function () { try { " + name + "; return true; } catch (e) { return false; } })()",
                    "globals.js", 1, null);
            return Boolean.TRUE.equals(answer);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /**
     * The names a package chain may start at — <b>one definition</b>, read by everything that cares.
     *
     * <p>Rhino's standard scope installs these as {@code NativeJavaTopPackage}s, so they are the names a
     * script can actually reach a class through, not a guess. {@code Packages} is the explicit escape for
     * everything else ({@code Packages.mymod.Thing}), which is why the list does not need to grow.</p>
     *
     * <p>It existed in three places — here, {@code RhinoInference}, and a host-side copy in the completion
     * provider — and no two of them agreed. A fact about the engine belongs on the side of the bridge that
     * has the engine; the host side reads it through {@link
     * com.crystalgui.language.engine.bridge.JsSourceAnalyzer#globals()}.</p>
     */
    static final Set<String> PACKAGE_ROOTS =
            Set.of("java", "javax", "org", "com", "edu", "net", "Packages");

    /**
     * What {@code RhinoExecutor.installGlobals} puts in every scope — the same list, from the same place.
     *
     * <p>A name installed at run time and unknown to the analyser is drawn as a mistake in an editor whose
     * own runtime provides it, which is the worst kind of wrong: {@code prompt} was installed for a
     * release and marked unresolved the whole time.</p>
     */
    static final Set<String> HOST_GLOBALS =
            Set.of("console", "print", "readLine", "prompt", "Java");

    /**
     * Globals that exist on some bands and not others — asked one at a time.
     *
     * <p>Short by design. This is not a list of everything JavaScript has: it is the handful whose
     * presence <em>differs between the two shipped Rhinos</em> or that lazy initialisation hides from
     * {@code getPropertyIds}, which is the only reason a name needs asking about individually.</p>
     */
    private static final String[] MODERN = {
            "Symbol", "Map", "Set", "WeakMap", "WeakSet", "Promise", "Proxy", "Reflect", "BigInt",
            "ArrayBuffer", "DataView", "Int8Array", "Uint8Array", "Uint8ClampedArray", "Int16Array",
            "Uint16Array", "Int32Array", "Uint32Array", "Float32Array", "Float64Array",
            "globalThis", "Iterator", "Generator", "WeakRef", "FinalizationRegistry",
    };

    /**
     * The baseline every JavaScript engine has.
     *
     * <p>Deliberately the ES5 core rather than everything: this is the set that has existed in every
     * engine ever shipped, so nothing in it can be a false positive on any band. Anything newer goes
     * through {@link #MODERN}, where it is measured rather than assumed.</p>
     */
    private static final Set<String> FALLBACK = Set.of(
            "Object", "Function", "Array", "String", "Boolean", "Number", "Date", "RegExp",
            "Error", "EvalError", "RangeError", "ReferenceError", "SyntaxError", "TypeError",
            "URIError", "Math", "JSON", "NaN", "Infinity", "undefined",
            "eval", "parseInt", "parseFloat", "isNaN", "isFinite",
            "encodeURI", "decodeURI", "encodeURIComponent", "decodeURIComponent");
}
