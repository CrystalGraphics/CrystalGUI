package com.crystalgui.language.java.ecj;

import com.crystalgui.language.engine.bridge.ScriptCompiler;
import com.crystalgui.language.engine.bridge.TypeBytes;

import java.util.List;

/**
 * The child-side {@link ScriptCompiler}: the bridge's shape over {@link EcjCompilation}.
 *
 * <h3>Which side of the loader this is on</h3>
 *
 * <p>This class names ECJ, so only the engine loader can load it. The host loads {@link ScriptCompiler}
 * — parent, bridge package — asks the child loader for this implementation by name, and holds it as the
 * interface. {@code EngineHost.adapter} is that crossing, and it asserts which loader defined the class
 * precisely because the parent <em>can</em> load this file and cannot load ECJ, so a silent fallback
 * would surface much later as a {@code NoClassDefFoundError} from inside a method that plainly imports
 * the thing it cannot find.</p>
 *
 * <h3>It used to be the batch compiler, and that is worth remembering</h3>
 *
 * <p>The first working path was {@code BatchCompiler} driven by a command line: public API, present in
 * every band, and honest for "run this script". Its own note said what it was not — <i>"a temporary
 * directory per compile is not the design, it is the cheapest thing that is genuinely correct"</i> —
 * and named the successor exactly. {@link EcjCompilation} is that successor: bytes in memory, one
 * resolver shared with the editor's analysis, and a name environment for §15.5 to hook into.</p>
 *
 * <p>What remains here is the seam and nothing else, which is the point: the compiler is now a detail
 * behind an interface the host already had.</p>
 */
public final class EcjScriptCompiler implements ScriptCompiler {

    /**
     * What the classpath cannot supply, installed by the host. @see TypeBytes
     *
     * <p>Volatile because it is written once during startup, on whichever thread opened the engine, and
     * read on every analysis and every run thereafter — which are not that thread.</p>
     */
    private volatile TypeBytes types = TypeBytes.NONE;

    @Override
    public ScriptCompiler resolveAgainst(TypeBytes types) {
        this.types = types == null ? TypeBytes.NONE : types;
        return this;
    }

    @Override
    public Result compile(String className, String source, List<String> classpath, int releaseLevel) {
        EcjCompilation.Output output =
                EcjCompilation.compile(className, source, classpath, releaseLevel, types);
        // A compile that produced no classes is a failure even without a reported error: there is
        // nothing to define, so calling it success would defer the failure to the run, where it arrives
        // as a missing class rather than as a compile problem.
        return new Result(!output.errored && !output.classes.isEmpty(), output.classes, output.messages,
                output.projectSources);
    }
}
