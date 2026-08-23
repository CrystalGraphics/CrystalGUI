package com.crystalgui.language.js.rhino.exec;

import com.crystalgui.language.js.rhino.JsImports;
import com.crystalgui.language.js.rhino.JsLoaders;
import com.crystalgui.text.lang.ProjectSourcesRegistry;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * One script importing another — M15 S6, the runtime half.
 *
 * <h3>Why this is not Rhino's CommonJS, which is right there and was the plan</h3>
 *
 * <p>Rhino ships {@code Require}, {@code RequireBuilder} and the {@code ModuleSourceProvider} SPI in both
 * bands, byte-identical, and {@code ModuleSource} takes a {@code Reader} — so serving a script from its
 * live buffer would have been a natural fit. §24.3 chose it for exactly that reason.</p>
 *
 * <p>It does not fit <b>our</b> import statement. {@code require("x")} is an expression a script writes
 * and whose value it binds itself; {@code import a.b.C;} is a statement that is <em>blanked</em>, and
 * whose binding therefore has to be injected into the scope by us. Rhino builds each module's scope
 * inside {@code Require} and offers no per-module hook to inject into — so a module that itself imported
 * anything could not be served at all. That is not an edge case: a two-file script where the second file
 * also uses a Java type is the ordinary shape.</p>
 *
 * <p>What Rhino's implementation would have contributed beyond that is a per-module scope, an
 * {@code exports} object, a cycle guard and a cache. Those are the four small things below, and having
 * them here means they answer to this codebase's rules rather than to CommonJS's — notably that a module
 * is read through {@link ProjectSourcesRegistry}, so an <b>unsaved buffer</b> is what runs.</p>
 *
 * <h3>Per run, never longer</h3>
 *
 * <p>The cache lives as long as one execution. Rhino's own providers cache across runs, which is the one
 * behaviour this must not have: the whole point of reading through {@code ProjectSources} is that an edit
 * takes effect without a save, and a module held from a previous run would defeat that in the same way
 * {@code ScriptCacheKey} did before M15 S5 gave it a dependency component.</p>
 */
final class JsModules {

    /**
     * Modules already loaded in this run, by qualified name.
     *
     * <p>An entry is written <b>before</b> the module is evaluated, which is what makes a cycle
     * terminate: A importing B importing A hands the second import A's exports object as it stands at
     * that moment rather than recursing forever. Node does the same and documents the same caveat —
     * a module that <em>replaces</em> {@code module.exports} outright leaves a cyclic importer holding
     * the original object. There is no fix for that short of refusing cycles.</p>
     */
    private final Map<String, Scriptable> loaded = new HashMap<>();

    /** The run's own scope, which every module scope inherits the globals from. */
    private final Scriptable topScope;

    /** The sandbox, or null when everything is allowed. @see com.crystalgui.language.run.ScriptPolicy */
    private final Predicate<String> allowsClass;

    JsModules(Scriptable topScope, Predicate<String> allowsClass) {
        this.topScope = topScope;
        this.allowsClass = allowsClass;
    }

    /**
     * Binds every name a script imports into {@code scope} — a project script first, then a Java type.
     *
     * <h3>The order is the same one the whole stack uses</h3>
     *
     * <p>A workspace file outranks a jar publishing the same name, exactly as {@code ScriptNameEnvironment}
     * and {@code AttachedSources} decide it for Java. The alternative — classpath first — means adding a
     * dependency can silently take over a name your own project declares.</p>
     *
     * <p><b>The policy is asked once, before either tier.</b> An import is a reach for a name and is
     * refused as the explicit spelling would be; asking per tier would let one of them answer for a name
     * the other was refused. A refused or missing import is skipped rather than thrown, which leaves the
     * script to fail on the name itself if it actually uses it — the behaviour this had before modules
     * existed, and the reason an unused bad import is not fatal.</p>
     */
    void bindInto(Context cx, Scriptable scope, Map<String, String> imported) {
        if (imported == null || imported.isEmpty()) return;
        for (Map.Entry<String, String> each : imported.entrySet()) {
            String qualifiedName = each.getValue();
            if (allowsClass != null && !allowsClass.test(qualifiedName)) continue;

            Scriptable module = exportsOf(cx, qualifiedName);
            if (module != null) {
                ScriptableObject.putProperty(scope, each.getKey(), module);
                continue;
            }
            Class<?> found = JsLoaders.load(qualifiedName);
            if (found == null) continue;
            ScriptableObject.putProperty(scope, each.getKey(),
                    RhinoExecutor.wrapForScript(cx, scope, found));
        }
    }

    /**
     * What the project script {@code qualifiedName} exports, or null when the workspace has no such file.
     *
     * <p>Null rather than an exception, because "the workspace does not declare this" is how the caller
     * knows to try the classpath. A module that exists and <em>fails</em> is a different thing and does
     * throw: its own error is the author's to see, and swallowing it would leave them with a binding that
     * is silently undefined.</p>
     */
    private Scriptable exportsOf(Context cx, String qualifiedName) {
        Scriptable already = loaded.get(qualifiedName);
        if (already != null) return already;

        // THROUGH THE REGISTRY, so an open editor's buffer beats the file on disk -- no save required,
        // which is the same tier M15 S4 resolves against and S5 fingerprints.
        String source = ProjectSourcesRegistry.view().sourceOf(qualifiedName);
        if (source == null) return null;

        // PROTOTYPE, NOT PARENT. Making the run's scope this scope's prototype is what leaves the
        // standard library and the host's globals visible while keeping every `var` the module declares
        // on the module's own object -- so two modules cannot see or overwrite each other's top-level
        // names. A parent-scope chain would share them.
        Scriptable moduleScope = cx.newObject(topScope);
        moduleScope.setPrototype(topScope);
        moduleScope.setParentScope(null);

        Scriptable exports = cx.newObject(moduleScope);
        Scriptable module = cx.newObject(moduleScope);
        ScriptableObject.putProperty(module, "exports", exports);
        ScriptableObject.putProperty(module, "id", qualifiedName);
        ScriptableObject.putProperty(moduleScope, "exports", exports);
        ScriptableObject.putProperty(moduleScope, "module", module);

        // BEFORE EVALUATING, so a cycle resolves to what exists so far instead of recursing. @see #loaded
        loaded.put(qualifiedName, exports);

        JsImports.Scanned scanned = JsImports.scan(source);
        // A MODULE'S OWN IMPORTS, recursively -- which is the whole reason this is not Rhino's Require.
        bindInto(cx, moduleScope, scanned.imported());

        // THE BLANKED TEXT, and line 1 really is line 1: blanking replaces a statement with spaces of the
        // same length and never touches a newline, so a stack frame from inside a module names the row
        // its author wrote. The name carries `.js` because that is what a frame reads best.
        cx.evaluateString(moduleScope, scanned.source(), qualifiedName + ".js", 1, null);

        // REREAD FROM `module`, because a module may ASSIGN to `module.exports` rather than adding to it
        // -- `module.exports = function () {}` is the commonest single-export shape there is, and taking
        // the original object back would hand the importer an empty one.
        Object finished = ScriptableObject.getProperty(module, "exports");
        Scriptable result = finished instanceof Scriptable ? (Scriptable) finished : exports;
        loaded.put(qualifiedName, result);
        return result;
    }
}
