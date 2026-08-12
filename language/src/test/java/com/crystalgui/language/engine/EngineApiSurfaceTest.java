package com.crystalgui.language.engine;

import org.junit.Assume;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <b>Every band carries the API the single adapter will be written against.</b>
 *
 * <p>This is {@code plan_syntax.md} §23 rows 3 and 8, answered as a test rather than as a one-off
 * check. §6.3's whole design — "one adapter per engine, compiled against the oldest band's API" — rests
 * on an unverified claim that the JDT DOM and {@code org.mozilla.javascript} are source-stable across
 * the range we ship. They are, and now something says so on every build.</p>
 *
 * <h3>What this does and does not prove</h3>
 *
 * <p><b>Does:</b> the types and methods exist, with the right shapes, in each band's actual jars, loaded
 * through the real {@link EngineClassLoader}. A pin bumped to a version that dropped or renamed
 * something fails here.</p>
 *
 * <p><b>Does not:</b> that each band <em>runs</em> on its own JVM. This test JVM is one version, and a
 * Java 8 host is what band 8 exists for. Two other things cover that from the outside — the build's
 * {@code checkEngineBands} holds every jar to its band's class-file ceiling, which is the mechanical
 * half, and M6's toolchain matrix compiles the adapter against each band, which is the behavioural
 * half. Saying so here because "the API test passes" reads like more than it is.</p>
 *
 * <p>Skips cleanly when the band jars were not handed over, so a checkout with no network still runs
 * every other test in this module.</p>
 */
public class EngineApiSurfaceTest {

    private static EngineClassLoader loaderFor(EngineBand band) throws IOException {
        String paths = System.getProperty("cgui.test.engineBand" + band.minimumFeatureVersion());
        EngineSource source = EngineSource.ofPathList(paths);
        Assume.assumeTrue("no jars supplied for band " + band + "; skipping",
                !source.jarsFor(band).isEmpty());
        return EngineClassLoader.over(band, source, EngineApiSurfaceTest.class.getClassLoader());
    }

    private static Class<?> require(EngineClassLoader loader, String className) {
        try {
            return Class.forName(className, false, loader);
        } catch (ClassNotFoundException absent) {
            fail(loader.band() + " has no " + className + " — the adapter could not be written against "
                    + "the oldest band and loaded on this one");
            return null;
        }
    }

    private static void requireMethod(Class<?> owner, String name, Class<?>... parameters) {
        try {
            Method method = owner.getMethod(name, parameters);
            assertNotNull(method);
        } catch (NoSuchMethodException absent) {
            StringBuilder signature = new StringBuilder(owner.getName()).append('.').append(name).append('(');
            for (int i = 0; i < parameters.length; i++) {
                if (i > 0) signature.append(", ");
                signature.append(parameters[i].getSimpleName());
            }
            fail("missing " + signature.append(')'));
        }
    }

    // ── JDT: the DOM and bindings half, which is what we need and the batch compiler is not ──────

    @Test
    public void everyBandCarriesTheJdtDomApiTheAdapterUses() throws IOException {
        for (EngineBand band : EngineBand.values()) {
            EngineClassLoader loader = loaderFor(band);
            try {
                Class<?> parser = require(loader, "org.eclipse.jdt.core.dom.ASTParser");
                requireMethod(parser, "newParser", int.class);
                requireMethod(parser, "setSource", char[].class);
                requireMethod(parser, "setUnitName", String.class);
                requireMethod(parser, "setResolveBindings", boolean.class);
                // THE BROKEN-CODE STORY (§15.1, §23 row 4). A script under the caret is nearly always
                // incomplete, so an adapter that only answers for well-formed input answers exactly when
                // it is not needed. These two are what make partial answers possible at all.
                requireMethod(parser, "setBindingsRecovery", boolean.class);
                requireMethod(parser, "setStatementsRecovery", boolean.class);
                // The classpath overlay -- §15.2's live loader plus our own entries.
                requireMethod(parser, "setEnvironment",
                        String[].class, String[].class, String[].class, boolean.class);
                requireMethod(parser, "setCompilerOptions", java.util.Map.class);

                Class<?> typeBinding = require(loader, "org.eclipse.jdt.core.dom.ITypeBinding");
                requireMethod(typeBinding, "getQualifiedName");
                requireMethod(typeBinding, "getTypeArguments");
                requireMethod(typeBinding, "getDeclaredMethods");
                requireMethod(typeBinding, "getDeclaredFields");
                requireMethod(typeBinding, "isAssignmentCompatible", typeBinding);

                require(loader, "org.eclipse.jdt.core.dom.IMethodBinding");
                require(loader, "org.eclipse.jdt.core.dom.IVariableBinding");
                require(loader, "org.eclipse.jdt.core.dom.CompilationUnit");
                require(loader, "org.eclipse.jdt.core.compiler.IProblem");
            } finally {
                loader.close();
            }
        }
    }

    /**
     * The JLS level is discovered, never named — §6.3's mechanism, checked on every band.
     *
     * <p>An adapter naming {@code AST.JLS21} fails to compile against band 8; one naming {@code JLS8}
     * compiles everywhere and silently caps band 17 at Java 8 syntax, which is worse because it works.</p>
     */
    @Test
    public void eachBandOffersAJlsLevelAndTheNewerBandsOfferMore() throws IOException {
        List<String> report = new ArrayList<>();
        int previous = 0;
        for (EngineBand band : EngineBand.values()) {
            EngineClassLoader loader = loaderFor(band);
            try {
                int level = JlsLevel.highestAvailable(loader);
                report.add(band + " -> JLS" + level);
                assertTrue(band + " reports JLS" + level + ", below the JLS" + JlsLevel.MINIMUM
                        + " floor", level >= JlsLevel.MINIMUM);
                assertTrue("a newer band must not offer a lower level than an older one: " + report,
                        level >= previous);
                previous = level;
            } finally {
                loader.close();
            }
        }
        System.out.println("discovered JLS levels: " + report);
    }

    // ── Rhino: §23 row 8, the 1.7.15.1 <-> 1.9.1 intersection ────────────────────────────────────

    @Test
    public void everyBandCarriesTheRhinoApiTheAdapterUses() throws IOException {
        for (EngineBand band : EngineBand.values()) {
            EngineClassLoader loader = loaderFor(band);
            try {
                Class<?> context = require(loader, "org.mozilla.javascript.Context");
                Class<?> scriptable = require(loader, "org.mozilla.javascript.Scriptable");
                Class<?> classShutter = require(loader, "org.mozilla.javascript.ClassShutter");

                requireMethod(context, "enter");
                requireMethod(context, "exit");
                requireMethod(context, "initStandardObjects");
                requireMethod(context, "evaluateString",
                        scriptable, String.class, String.class, int.class, Object.class);
                requireMethod(context, "setLanguageVersion", int.class);
                requireMethod(context, "setOptimizationLevel", int.class);
                // THE SANDBOX'S REAL ENFORCEMENT POINT (§19.2). Rhino is the one engine where a refusal
                // is call-time rather than advisory, and this is the method that does it.
                requireMethod(context, "setClassShutter", classShutter);
                requireMethod(classShutter, "visibleToScripts", String.class);

                Class<?> factory = require(loader, "org.mozilla.javascript.ContextFactory");
                requireMethod(factory, "getGlobal");
                requireMethod(factory, "enterContext");

                require(loader, "org.mozilla.javascript.EvaluatorException");
                require(loader, "org.mozilla.javascript.ErrorReporter");
                require(loader, "org.mozilla.javascript.ScriptableObject");
            } finally {
                loader.close();
            }
        }
    }

    /**
     * Rhino's ES level constants, which the plan says differ by band and which the adapter must not name.
     *
     * <p>Same argument as {@link JlsLevel}: {@code Context.VERSION_ES6} exists in every band we ship, so
     * the adapter can name that one — but this asserts it rather than assuming it, because the day it is
     * not true the failure is a {@code NoSuchFieldError} at first evaluation on one platform only.</p>
     */
    @Test
    public void everyBandCarriesTheEs6VersionConstant() throws IOException {
        for (EngineBand band : EngineBand.values()) {
            EngineClassLoader loader = loaderFor(band);
            try {
                Class<?> context = require(loader, "org.mozilla.javascript.Context");
                int es6 = context.getField("VERSION_ES6").getInt(null);
                assertEquals(band + " spells VERSION_ES6 differently", 200, es6);
            } catch (ReflectiveOperationException absent) {
                fail(band + " has no Context.VERSION_ES6: " + absent);
            } finally {
                loader.close();
            }
        }
    }
}
