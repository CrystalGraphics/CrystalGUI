package com.crystalgui.language.platform;

import com.crystalgui.language.engine.EngineBand;
import com.crystalgui.language.engine.EngineHost;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.engine.bridge.ScriptCompiler;
import com.crystalgui.language.engine.bridge.TypeBytes;
import com.crystalgui.language.map.ReadableView;

import org.junit.Assume;
import org.junit.Test;

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

/**
 * <b>What may cross the engine loader, and what silently may not.</b>
 *
 * <p>{@code EngineClassLoader} is child-first for everything outside {@code java.*}, the bridge package
 * and {@code com.crystalgui.text.*}. So a compiler-side class that names a host class does not fail —
 * it gets the band's own copy, complete with its own statics. For anything holding a registry that is
 * total and invisible: {@code ScriptPlatforms.register()} runs on the host, the compiler reads a
 * different static, finds nothing, and resolves from files as though no platform were installed.</p>
 *
 * <p>The first live-name-environment implementation did exactly that and looked entirely correct —
 * scripts compiled, scripts ran, and the whole §15.5 A design was inert. Nothing failed, because
 * resolving from files is a plausible answer. <b>These are the assertions that would have caught it</b>,
 * and they are worth having as a pair: one shows why the naive route cannot work, the other shows that
 * the route actually taken does.</p>
 */
public class BridgeCrossingTest {

    private static EngineHost openBand() throws Exception {
        EngineBand band = EngineBand.detect();
        String paths = System.getProperty("cgui.test.engineBand" + band.minimumFeatureVersion());
        EngineSource source = EngineSource.ofPathList(paths);
        Assume.assumeTrue("no jars supplied for band " + band + "; skipping",
                !source.jarsFor(band).isEmpty());
        return EngineHost.open(band, source);
    }

    /**
     * A host class is a DIFFERENT class inside the band — which is why a static registry cannot cross.
     *
     * <p>Asserting the failure mode rather than trusting the rule. {@code ScriptPlatforms} and
     * {@code ReadableView} are the two the compiler most obviously wants and most obviously must not
     * take: the first keeps the registry, the second holds the mapping and the ASM remapper.</p>
     */
    @Test
    public void aHostClassIsRedefinedInsideTheBand() throws Exception {
        EngineHost host = openBand();
        try {
            ClassLoader band = host.adapter("com.crystalgui.language.java.ecj.EcjScriptCompiler",
                    ScriptCompiler.class).getClass().getClassLoader();

            assertNotSame("ScriptPlatforms crossed intact — then the registry would be shared and this "
                            + "whole test is moot; if that is now true, PARENT_FIRST changed",
                    ScriptPlatforms.class,
                    Class.forName(ScriptPlatforms.class.getName(), false, band));
            assertNotSame("ReadableView crossed intact",
                    ReadableView.class, Class.forName(ReadableView.class.getName(), false, band));
        } finally {
            host.close();
        }
    }

    /**
     * A bridge type is the SAME class on both sides — which is what makes {@code TypeBytes} usable.
     *
     * <p>The positive half, and the one that has to keep holding: the whole live-bytes design rests on
     * the host being able to hand the compiler an implementation it can call. If this ever fails the
     * symptom is a {@code ClassCastException} naming one type twice, which at least is loud — unlike the
     * bug this pair exists for.</p>
     */
    @Test
    public void aBridgeTypeIsOneClassOnBothSides() throws Exception {
        EngineHost host = openBand();
        try {
            ClassLoader band = host.adapter("com.crystalgui.language.java.ecj.EcjScriptCompiler",
                    ScriptCompiler.class).getClass().getClassLoader();

            assertSame("TypeBytes is not parent-first — the host cannot hand one over",
                    TypeBytes.class, Class.forName(TypeBytes.class.getName(), false, band));
            assertSame("ScriptCompiler is not parent-first — the adapter could not be held as it",
                    ScriptCompiler.class, Class.forName(ScriptCompiler.class.getName(), false, band));
        } finally {
            host.close();
        }
    }

    /**
     * The compiler accepts what the host composed, and accepts it as the shared type.
     *
     * <p>{@code resolveAgainst} returns {@code this}, so this also pins that the adapter really
     * implements the seam rather than inheriting the interface's no-op default — which would compile,
     * install nothing, and leave the design inert in exactly the way the class comment describes.</p>
     */
    @Test
    public void theHostCanInstallTypeBytesOnTheCompiler() throws Exception {
        EngineHost host = openBand();
        try {
            ScriptCompiler compiler = host.adapter(
                    "com.crystalgui.language.java.ecj.EcjScriptCompiler", ScriptCompiler.class);
            assertSame("resolveAgainst must return the compiler it configured",
                    compiler, compiler.resolveAgainst(TypeBytes.NONE));
        } finally {
            host.close();
        }
    }
}
