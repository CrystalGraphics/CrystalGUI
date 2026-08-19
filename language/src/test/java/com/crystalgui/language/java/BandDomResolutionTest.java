package com.crystalgui.language.java;

import com.crystalgui.language.engine.EngineBand;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.engine.JavaEngine;
import com.crystalgui.language.java.classpath.HostClasspath;
import com.crystalgui.language.map.PlatformMappings;
import com.crystalgui.language.map.ReadableView;
import com.crystalgui.language.platform.MappingCoordinates;
import com.crystalgui.language.platform.NamespaceProbe;
import com.crystalgui.language.platform.ScriptPlatform;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.language.platform.ScriptPlatforms;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.text.lang.CompletionItem;
import com.crystalgui.text.lang.CompletionList;
import com.crystalgui.text.lang.CompletionProvider;
import com.crystalgui.text.lang.LanguageServices;
import com.crystalgui.text.lang.Versioned;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * <b>The member list on EVERY band, not just the one this JVM happens to pick.</b>
 *
 * <p>{@code EngineBand.detect()} keys on the host JVM, so a test suite run on 17 exercises band 17 and
 * nothing else — while a Minecraft 1.7.10 client is on Java 8 and therefore runs band 8, and that is the
 * only place band 8 has ever executed. {@code DomResolution} is the reason that gap matters: it reaches
 * {@code CompilationUnitResolver} by <b>reflection</b>, picking {@code convert} by arity because 3.46
 * added an overload, and reading three {@code static final int} flags off the class. Every one of those
 * is a thing two bands can spell differently — the same hazard {@code Token} constants already cost a
 * round for, in the engine's other half.</p>
 *
 * <p>An empty member list is the specific symptom to watch: a resolver that constructs but produces a
 * tree with no bindings answers plausibly and wrongly, which is a popup with no rows and no error
 * anywhere. Nothing throws, so only asking the question finds it.</p>
 *
 * <p>The bands' jars are Java 8 bytecode, so an older band loads and runs perfectly well on a newer host.
 * That is what makes this testable at all — the band is a classpath, not a JVM.</p>
 */
public class BandDomResolutionTest {

    private JavaEngine engine;

    @Before
    @After
    public void forget() {
        CgPlatform.provide(ScriptPlatforms.SERVICE, null);
        PlatformMappings.resetForTesting();
    }

    @After
    public void closeEngine() throws Exception {
        if (engine != null) engine.close();
        engine = null;
    }

    /**
     * A platform that serves no bytes of its own.
     *
     * <p>Registered anyway, and that is the entire point: it is a platform's <em>presence</em> that
     * switches the analyser onto {@code DomResolution}, not what it can supply. So this is the smallest
     * fixture that takes the client's code path while resolving everything from the ordinary classpath.</p>
     */
    private void registerBarePlatform() {
        CgPlatform.provide(ScriptPlatforms.SERVICE, new ScriptPlatform() {
            @Override
            public ReadableView.ByteSource liveBytes() {
                return name -> null;
            }

            @Override
            public Path cacheRoot() {
                return Paths.get("build", "crystalgui-test-cache").toAbsolutePath();
            }

            @Override
            public MappingCoordinates mappings() {
                return MappingCoordinates.NONE;
            }

            @Override
            public NamespaceProbe namespaceProbe() {
                return NamespaceProbe.NONE;
            }

            @Override
            public String runtimeClassName(String onDiskInternalName) {
                return onDiskInternalName;
            }
        });
    }

    /** Opens {@code band} explicitly rather than letting the host JVM choose one. */
    private void openBand(EngineBand band) throws Exception {
        String paths = System.getProperty("cgui.test.engineBand" + band.minimumFeatureVersion());
        EngineSource source = EngineSource.ofPathList(paths);
        Assume.assumeTrue("no jars staged for band " + band + "; skipping",
                !source.jarsFor(band).isEmpty());
        registerBarePlatform();
        engine = JavaEngine.open(band, source);
    }

    private List<String> completeAfter(String source, String upTo) {
        TextBuffer buffer = new TextBuffer(source);
        LanguageServices services = new JavaLanguageServices(
                buffer, engine, null, "Script", HostClasspath.detect());
        try {
            int caret = source.indexOf(upTo) + upTo.length();
            final CompletionList[] got = {CompletionList.EMPTY};
            services.completion().complete(
                    CompletionProvider.Request.character(caret, "", "."),
                    (Versioned<CompletionList> v) -> got[0] = v.orElse(CompletionList.EMPTY));
            List<String> labels = new ArrayList<>();
            for (CompletionItem item : got[0].items()) labels.add(item.label());
            return labels;
        } finally {
            services.close();
        }
    }

    /**
     * {@code System.out.} in a bare script — the first line most scripts have.
     *
     * <p><b>Skipped when the band's ceiling is below this host's class library</b>, which is a real
     * confound rather than a convenience. An older band on a newer host reads the host's {@code java.base}
     * — band 8 resolves at release 16 here, against Java 21 class files — and a class file it cannot fully
     * read yields a binding whose {@code getDeclaredMethods()} is empty while its supertypes' are not. The
     * observable result is a member list that is <em>plausible and wrong</em>: {@code System.out.} offers
     * twenty rows of {@code OutputStream} and {@code Object} with no {@code println} anywhere, and nothing
     * throws. Worth knowing, and not this band's fault — a 1.7.10 client on Java 8 runs band 8 against
     * Java 8 class files, where the pairing is the one it was pinned for.</p>
     *
     * <p>So the skip is on the mismatch, not on the band: run this suite on a Java 8 host and band 8 is
     * genuinely checked. Which is the point — {@code EngineBand.detect()} keys on the host JVM, so a suite
     * run on 17 exercises band 17 and nothing else, and the client is the only place the others execute.</p>
     */
    private void assertMembersOn(EngineBand band) throws Exception {
        openBand(band);
        int host = hostFeatureVersion();
        Assume.assumeTrue("band " + band + " resolves at release " + engine.releaseLevel()
                        + " and this host's class library is " + host
                        + " -- an older band cannot read newer class files, which is a mismatch no "
                        + "deployment has",
                engine.releaseLevel() >= host);

        List<String> labels = completeAfter("System.out." + System.lineSeparator(), "System.out.");
        assertFalse(band + " offered no members at all", labels.isEmpty());
        assertTrue(band + " is missing println -- a receiver whose own declared members are absent is what "
                        + "a binding with no methods looks like, and it reads as a wrong list rather than "
                        + "a broken one: " + labels.size() + " rows: " + labels,
                labels.toString().contains("println"));
    }

    /** This JVM's feature version, which is what version its class files are. */
    private static int hostFeatureVersion() {
        String property = System.getProperty("java.specification.version", "8");
        int dot = property.indexOf('.');
        // "1.8" on 8, a bare number from 9 onward.
        String feature = property.startsWith("1.") ? property.substring(dot + 1) : property;
        try {
            return Integer.parseInt(feature.trim());
        } catch (NumberFormatException unreadable) {
            return 8;
        }
    }

    /** Band 8 — <b>what a 1.7.10 client runs</b>, and what nothing else ever exercises. */
    @Test
    public void band8OffersMembers() throws Exception {
        assertMembersOn(EngineBand.JAVA_8);
    }

    @Test
    public void band11OffersMembers() throws Exception {
        assertMembersOn(EngineBand.JAVA_11);
    }

    @Test
    public void band17OffersMembers() throws Exception {
        assertMembersOn(EngineBand.JAVA_17);
    }
}
