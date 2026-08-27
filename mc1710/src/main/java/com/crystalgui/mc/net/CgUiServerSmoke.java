package com.crystalgui.mc.net;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.net.protocol.Protocols;
import com.crystalgui.net.wire.CgNetworkChannel;
import com.crystalgui.serialization.ContentHash;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.UIDescriptionCodec;
import com.crystalgui.ui.ElementRegistry;
import com.crystalgui.ui.UIElement;

import cpw.mods.fml.common.FMLCommonHandler;
import net.minecraft.server.MinecraftServer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Boots a dedicated server, asserts the server-side stack came up, and stops it — so that the one class
 * of bug nothing else in this repo can see becomes something a build can fail on.
 *
 * <h3>Why this exists, and why no existing check replaces it</h3>
 *
 * <p>Three fatal defects shipped undetected until a dedicated server was booted for the first time:
 * CrystalGraphics' platform bundle built all nine services eagerly and died at the first one
 * ({@code NoClassDefFoundError: org/lwjgl/LWJGLException}); {@code CgPlatform.register} asked for a GL
 * backend unconditionally; and {@code PlatformService1710.onInit} called a client-only class behind a
 * guard that sat one level too high. Every one of them is a <b>runtime</b> property — <i>"a client-only
 * class is constructed on a server"</i> — so:</p>
 *
 * <ul>
 *   <li><b>1090 headless tests could not see them.</b> {@code headlessTest} asserts by <i>absence</i>:
 *       CrystalGraphics core is off that classpath, so a class that would fail on a server fails there
 *       too — but only for code the tests reach, and none of them reach a loader.</li>
 *   <li><b>The GL harness could not see them.</b> It is a client with a real GL context by design.</li>
 *   <li><b>An import guard cannot see them.</b> Every offending line was in {@code mc1710}, where
 *       {@code org.lwjgl} is a <i>legal</i> import, or in {@code CgPlatform}, which imports nothing
 *       offending and merely <i>called</i> {@code platform.gl()}. This was claimed in the plan and is
 *       wrong; a source scan cannot answer a question about class loading.</li>
 * </ul>
 *
 * <p>What did find all three, in one run, was starting the server. So that is the check.</p>
 *
 * <h3>How close a dev {@code runServer} is to a production one — measured, not assumed</h3>
 *
 * <p>This section first read <i>"a dev runServer is not a production server — RFG launches against the
 * merged artifact, so Minecraft's client classes and every LWJGL class are present"</i>. <b>Half of that
 * is wrong, and the first passing run is what said so.</b> LWJGL is <em>not</em> on RFG's server run
 * classpath: {@code CgPlatform.register} takes its {@code NoClassDefFoundError} fallback and this check
 * reports <i>"GL backend is not installed on this server — matching production"</i>. So on 1.7.10 the
 * dev server is production-shaped for exactly the failure that prompted all this, which is better than
 * was claimed. Whether ModDevGradle's Forge/NeoForge {@code runServer} is the same is <b>unverified</b>
 * — mc1201 compiles from no build we have.</p>
 *
 * <p>Minecraft's own client classes <em>are</em> present, and that is what makes
 * {@link #NEVER_LOADED_ON_A_SERVER} a real assertion rather than a tautology: on a production server the
 * class is absent and the question is moot, while here it is present and staying <b>unloaded</b> is a
 * falsifiable claim about our own code. {@code CommonProxy}'s javadoc already states that contract —
 * "a static reference from a common class is enough to fail class loading there" — and nothing checked
 * it until now.</p>
 *
 * <h3>Usage</h3>
 *
 * <pre>./gradlew :mc1710:serverSmoke</pre>
 *
 * <p>Boots, reports, and stops. Exit code 0 when every hard check passes, 1 otherwise — so it composes
 * into any pipeline that can run Gradle. {@code WARN} lines are informational and never fail the run.</p>
 *
 * <p>The task also runs the server on <b>port 25599</b> rather than 25565, and <b>requires a report file
 * to exist afterwards</b>. Both come from the first run: 25565 was in use, so the server never bound it,
 * {@code FMLServerStartedEvent} never fired, and the build reported success having executed no check at
 * all. @see #REPORT_PROPERTY</p>
 */
public final class CgUiServerSmoke {

    /** Set by {@code -PcgServerSmoke}. */
    public static final String PROPERTY = "crystalgui.server.smoke";

    /**
     * Where to write the verdict, so the build can tell "failed" from <b>"never ran"</b>.
     *
     * <p><b>This was added because the check's first run proved it was needed.</b> Port 25565 was in use,
     * the server never bound it, {@code FMLServerStartedEvent} never fired, not one assertion executed —
     * and Gradle reported <b>BUILD SUCCESSFUL</b>. A check that is green when it did not run is worse than
     * no check, because it is now also a claim.</p>
     *
     * <p>A file rather than a log scrape: the run's stdout is at the mercy of whatever log4j configuration
     * is in force, and this same class already writes its report twice for that reason. The Gradle task
     * deletes this before the run and requires it afterwards, so an absent file is a failure with a
     * message rather than a silent pass.</p>
     */
    public static final String REPORT_PROPERTY = "crystalgui.server.smoke.report";

    /**
     * Classes that must never be loaded in a dedicated server process.
     *
     * <p>Each is present on a dev server's merged classpath and absent from a real one, so a load here
     * is a defect that would be a hard {@code NoClassDefFoundError} in production.</p>
     *
     * <p><b>LWJGL is deliberately not on this list</b>, and that is a finding rather than an omission —
     * see {@link #reportGlDivergence}.</p>
     */
    private static final List<String> NEVER_LOADED_ON_A_SERVER = Arrays.asList(
            // Ours. CommonProxy exists so this one is unreachable from common code.
            "com.crystalgui.mc.client.CgUiScreen",
            "com.crystalgui.mc.client.CgUiInput",
            "com.crystalgui.mc.client.CgUiSlotScreen",
            "com.crystalgui.mc.client.Mc1710Workspace",
            "com.crystalgui.mc.ClientProxy",
            // Minecraft's. Naming one from a common path is the commonest spelling of this bug.
            "net.minecraft.client.Minecraft",
            // CrystalGraphics'. The paint context is the entry point to every GL resource CrystalGUI
            // owns, and it registers CgUiLifecycle from a static initialiser -- so if it is loaded on a
            // server, something asked a headless process to paint.
            "com.crystalgui.render.CgUiPaintContext",
            // The native-content renderer. It holds a RenderItem in a FIELD, and a field descriptor
            // resolves at class definition rather than at first use -- so merely constructing one on a
            // server is a NoClassDefFoundError in production. The elements it serves live in core and
            // ARE loaded here, which is the whole point of them; only the thing that draws must not be.
            // That separation is a one-line decision in ClientProxy, so this is the check that keeps it.
            "com.crystalgui.mc.platform.service.content.Mc1710NativeContentService");

    private CgUiServerSmoke() {
    }

    /** Whether {@code -PcgServerSmoke} was passed. */
    public static boolean enabled() {
        return Boolean.getBoolean(PROPERTY);
    }

    /**
     * Runs every check, prints the report, and stops the server.
     *
     * <p>Called from {@code FMLServerStartedEvent} — the first moment the server is genuinely up, and
     * late enough that a mod which failed to load has already taken the process down with it.</p>
     */
    public static void run() {
        List<String> lines = new ArrayList<String>();
        List<String> failures = new ArrayList<String>();

        check(lines, failures, "the process is a dedicated server",
                FMLCommonHandler.instance().getSide().isServer()
                        && MinecraftServer.getServer() != null
                        && MinecraftServer.getServer().isDedicatedServer(),
                "not a dedicated server — this check proves nothing anywhere else");

        // 1. CrystalGraphics loaded and registered. This is the one that was fatal: preInit died with
        //    NoClassDefFoundError and every dependent mod was errored alongside it, so reaching this
        //    line at all is most of the assertion.
        boolean platform;
        String platformDetail = "";
        try {
            CgPlatform.ensureCreated();
            platform = true;
        } catch (Throwable notRegistered) {
            platform = false;
            platformDetail = String.valueOf(notRegistered);
        }
        check(lines, failures, "CrystalGraphics platform bundle registered", platform, platformDetail);

        // 2. The channel. An unavailable channel is a WARN-and-return inside register() below, so
        //    checking only the lifecycle flag would report the symptom and hide the cause.
        boolean channel = false;
        try {
            channel = CgPlatform.get(CgNetworkChannel.SERVICE).isAvailable();
        } catch (Throwable noChannel) {
            channel = false;
        }
        check(lines, failures, "network channel available", channel,
                "Mc1710NetworkChannel.register() did not take, or the SERVICE slot holds the no-op default");

        // 3. The lifecycle. THE LINE THIS CHECK WAS NAMED FOR. It is a warn-and-return on failure, not
        //    a throw -- so a server with no networking at all boots perfectly happily and looks fine.
        check(lines, failures, "connection lifecycle installed", CgUiConnections.isRegistered(),
                "CgUiConnections.register() stood down; see the [cgui-net] warning above");

        // 4. Contributors. A connection only binds contributors registered BEFORE it opens, and no peer
        //    exists yet -- so this is the last moment the set can still be wrong and the first at which
        //    it certainly is not.
        Set<String> contributors = Protocols.contributors();
        check(lines, failures, "protocol contributors bound " + contributors,
                contributors.contains("workspace"),
                "expected 'workspace'; CgUiWorkspaceHost.register() runs before CgUiConnections.register()");

        // 5. A description round-trips with no GL anywhere. The serialization stack is what a server
        //    actually DOES, and it is content-addressed -- so encoding twice and hashing is a real
        //    assertion about determinism, not a smoke test of the codec.
        checkDescriptionRoundTrip(lines, failures);

        // 6. Nothing client-shaped got loaded. See the class javadoc: on a merged dev classpath this is
        //    the only one of these checks that can catch the original bug class.
        checkNothingClientSideLoaded(lines, failures);

        reportGlDivergence(lines);

        String report = render(lines, failures);
        print(report);
        writeReport(report, failures.isEmpty());
        stop(failures.isEmpty());
    }

    // ── the checks ────────────────────────────────────────────────────────────────────────────────

    private static void checkDescriptionRoundTrip(List<String> lines, List<String> failures) {
        String detail = "";
        boolean ok = false;
        try {
            ElementRegistry.bootstrapBuiltins();

            UIElement root = new UIElement();
            root.setId("smoke-root");
            root.addClass("panel");
            UIElement child = new UIElement();
            child.setId("smoke-child");
            root.addChild(child);

            // AN ITEM SLOT, ON A SERVER, which is the whole claim the slot design rests on: a slot binds
            // to a LOCATION rather than holding item data, so a dedicated server can describe an
            // inventory it has no renderer for and no way to draw. Nothing here touches
            // NativeContentService -- there is none on a server, by construction, because whatever would
            // provide one names RenderItem. If this ever throws, the elements have stopped being
            // headless and the server half is gone.
            com.crystalgui.ui.elements.slot.ItemSlot slot = new com.crystalgui.ui.elements.slot.ItemSlot();
            // Authored through the CORE formatter and asserted below against the LITERAL string. The
            // pair is the point: the formatter is the cross-version authoring path (NativeDescriptors,
            // the grammar every loader parses), and the literal is the wire format -- if the formatter
            // ever changes its spelling, this smoke fails naming a wire-format change, on a server,
            // with no renderer anywhere in sight.
            slot.setDescriptor(com.crystalgui.ui.elements.slot.NativeDescriptors.slot(12));
            root.addChild(slot);

            Object encoded = UIDescriptionCodec.CODEC.encode(PlainOps.INSTANCE, root);
            String hashA = ContentHash.of(PlainOps.INSTANCE, encoded);
            String hashB = ContentHash.of(PlainOps.INSTANCE,
                    UIDescriptionCodec.CODEC.encode(PlainOps.INSTANCE, root));

            UIElement decoded = UIDescriptionCodec.CODEC.decode(PlainOps.INSTANCE, encoded);

            boolean stable = hashA.equals(hashB);
            boolean shape = decoded != null
                    && "smoke-root".equals(decoded.getId())
                    && decoded.getChildren().size() == 2;

            // The slot's own state, checked by VALUE rather than by the tag surviving: a tag that decoded
            // to the right class while losing what it points at would be the more plausible failure and
            // the harder one to see.
            boolean slotOk = false;
            if (decoded != null && decoded.getChildren().size() == 2) {
                UIElement back = decoded.getChildren().get(1);
                slotOk = back instanceof com.crystalgui.ui.elements.slot.ItemSlot
                        && "slot:12".equals(((com.crystalgui.ui.elements.slot.ItemSlot) back).descriptor());
            }

            ok = stable && shape && slotOk;
            detail = "hash=" + hashA + (stable ? "" : " UNSTABLE across two encodes")
                    + (shape ? "" : " decoded shape wrong")
                    + (slotOk ? "" : " itemslot did not survive with its descriptor");
        } catch (Throwable failed) {
            detail = String.valueOf(failed);
        }
        check(lines, failures, "UI description round-trips headlessly", ok, detail);
    }

    private static void checkNothingClientSideLoaded(List<String> lines, List<String> failures) {
        List<String> loaded = new ArrayList<String>();
        List<String> undetermined = new ArrayList<String>();

        for (String name : NEVER_LOADED_ON_A_SERVER) {
            Boolean isLoaded = loadedByAnyLoader(name);
            if (isLoaded == null) {
                undetermined.add(name);
            } else if (isLoaded.booleanValue()) {
                loaded.add(name);
            }
        }

        if (!undetermined.isEmpty()) {
            // Said out loud rather than counted as a pass. A check that cannot run and reports green is
            // worse than no check -- the same reason EngineHost prints one line when it resolves live.
            lines.add("WARN  could not determine load state for " + undetermined
                    + " (findLoadedClass was not reachable; the assertion below covers the rest)");
        }

        check(lines, failures,
                "no client-only class loaded on the server"
                        + (undetermined.isEmpty() ? "" : " (of those determinable)"),
                loaded.isEmpty(),
                loaded.isEmpty() ? "" : "LOADED: " + loaded
                        + " — something on a common path reached a client class; in production this is a "
                        + "NoClassDefFoundError at that point, not here");
    }

    /**
     * Whether {@code name} has already been defined by this class's loader or any of its parents.
     *
     * <p>{@code ClassLoader.findLoadedClass} is protected and answers only for the loader that
     * <i>defined</i> the class, so the chain is walked. {@code null} means the question could not be
     * asked at all — reported rather than treated as a pass.</p>
     *
     * <p>Deliberately not {@code Class.forName(name, false, loader)}: that would <b>load the class</b>,
     * which is the very thing being asserted against. The check would then always pass, and would create
     * the condition it exists to detect.</p>
     */
    private static Boolean loadedByAnyLoader(String name) {
        try {
            Method find = ClassLoader.class.getDeclaredMethod("findLoadedClass", String.class);
            find.setAccessible(true);
            for (ClassLoader loader = CgUiServerSmoke.class.getClassLoader();
                 loader != null; loader = loader.getParent()) {
                if (find.invoke(loader, name) != null) return Boolean.TRUE;
            }
            return Boolean.FALSE;
        } catch (Throwable notAvailable) {
            return null;
        }
    }

    /**
     * Reports whether a GL backend was installed, which on a server it should not have been.
     *
     * <p><b>A WARN, never a failure.</b> {@code CgPlatform.register} asks for a GL backend by
     * <i>trying</i>, catching {@code NoClassDefFoundError} — which is correct, since that module may
     * import neither FML nor LWJGL and so has no other way to know its side. On a production server the
     * attempt throws and {@code CgGL} is left uninitialised.</p>
     *
     * <p>This was written expecting the opposite of what it found. The reasoning was that a dev server
     * has LWJGL on its merged classpath, so the attempt would <b>succeed</b> here, the fallback branch
     * would never be exercised, and the two environments would diverge at exactly the line that broke.
     * <b>Measured, it does not:</b> 1.7.10's dev server reports "not installed", the same as production.
     * The line stays because it is one line, it is the only thing that would say so if that ever stopped
     * being true, and the answer differs per loader — mc1201 is unmeasured and compiles from no build we
     * have. A WARN rather than a FAIL for the same reason: it reports a fact about the environment, not
     * a defect in the code.</p>
     */
    private static void reportGlDivergence(List<String> lines) {
        try {
            Class<?> cgGl = Class.forName("com.crystalgraphics.platform.gl.CgGL");
            Field backend = cgGl.getDeclaredField("backend");
            backend.setAccessible(true);
            boolean installed = backend.get(null) != null;
            lines.add("WARN  GL backend " + (installed ? "IS" : "is not") + " installed on this server"
                    + (installed
                    ? " — expected in a DEV run (merged classpath), and NOT what production does: there "
                    + "CgPlatform.register catches NoClassDefFoundError and CgGL stays null. Server-side "
                    + "code that touches CgGL therefore passes here and NPEs in production."
                    : " — matching production."));
        } catch (Throwable cannotTell) {
            lines.add("WARN  could not determine whether a GL backend is installed (" + cannotTell + ")");
        }
    }

    // ── reporting and shutdown ────────────────────────────────────────────────────────────────────

    private static void check(List<String> lines, List<String> failures,
                              String what, boolean ok, String detail) {
        lines.add((ok ? "PASS  " : "FAIL  ") + what
                + (ok || detail.isEmpty() ? "" : System.getProperty("line.separator") + "        " + detail));
        if (!ok) failures.add(what);
    }

    private static String render(List<String> lines, List<String> failures) {
        String nl = System.getProperty("line.separator");
        StringBuilder out = new StringBuilder();
        out.append("==================== CrystalGUI dedicated-server smoke ====================").append(nl);
        for (String line : lines) out.append(line).append(nl);
        out.append("--------------------------------------------------------------------------").append(nl);
        out.append(failures.isEmpty()
                ? "RESULT: pass — the server-side stack is up"
                : "RESULT: FAIL — " + failures.size() + " check(s): " + failures).append(nl);
        out.append("==========================================================================");
        return out.toString();
    }

    private static void print(String report) {
        // System.out AND the logger. The logger is what a CI log scraper reads; System.out is what
        // survives a log4j configuration that routes our category somewhere else, which on 1.7.10 is not
        // hypothetical -- and a report that can be swallowed is not a report.
        System.out.println(System.getProperty("line.separator") + report);
        System.out.flush();
        com.crystalgui.core.CrystalGuiCore.LOGGER.info(report);
    }

    /** @see #REPORT_PROPERTY */
    private static void writeReport(String report, boolean passed) {
        String path = System.getProperty(REPORT_PROPERTY, "");
        if (path.isEmpty()) return;
        Writer writer = null;
        try {
            File file = new File(path);
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            writer = new OutputStreamWriter(new FileOutputStream(file), "UTF-8");
            // The verdict FIRST, on its own line, so the build reads one line rather than parsing a
            // report -- and so a truncated write is a failure rather than a plausible pass.
            writer.write((passed ? "PASS" : "FAIL") + System.getProperty("line.separator"));
            writer.write(report);
        } catch (IOException cannotWrite) {
            // Said out loud AND made fatal by omission: with no file the build refuses, which is the
            // correct reading of "the check could not report".
            System.err.println("[cgui-smoke] could not write the report to " + path + ": " + cannotWrite);
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignored) {
                    // Nothing useful to do; the build's own check on the file is the backstop.
                }
            }
        }
    }

    /**
     * Stops the server, with the exit code carrying the verdict.
     *
     * <p>On success {@code initiateShutdown()} — a clean stop, so the world saves and {@code
     * FMLServerStoppingEvent} runs, which is itself part of what is being smoke-tested (it is what closes
     * every connection).</p>
     *
     * <p>On failure {@code halt(1)} <em>after</em> flushing, because a clean shutdown exits 0 and the
     * verdict has to reach Gradle. {@code System.exit} would run shutdown hooks that can themselves throw
     * on a half-initialised server and mask the code; {@code halt} cannot.</p>
     */
    private static void stop(boolean passed) {
        if (passed) {
            MinecraftServer server = MinecraftServer.getServer();
            if (server != null) server.initiateShutdown();
            return;
        }
        System.out.flush();
        System.err.flush();
        Runtime.getRuntime().halt(1);
    }
}
