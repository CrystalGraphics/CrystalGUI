package com.crystalgui.mc.client;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.language.platform.ScriptPlatform;
import com.crystalgui.language.platform.ScriptPlatforms;
import com.crystalgui.language.run.ScriptRuntime;
import com.crystalgui.language.run.view.ScriptWorkbench;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.launchwrapper.LaunchClassLoader;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Drives the client unattended: open the editor, screenshot it, quit.
 *
 * <p><b>Why this exists.</b> Every render defect so far has been diagnosed by a human launching the
 * client, walking into a world, pressing a key and describing what they saw — which is slow, and worse,
 * it puts a person in the loop for something that is really just "render N frames and look at the
 * pixels". The GL debug harness has had exactly this since the beginning
 * ({@code ArtifactService.requestCapture}), and it is the reason harness bugs get fixed in minutes.
 * This is the same affordance for Minecraft.</p>
 *
 * <h3>No world is loaded, deliberately</h3>
 *
 * <p>{@link CgUiScreen} is an ordinary {@code GuiScreen}: it opens over the main menu just as well as
 * over a world, and nothing it paints depends on there being a level. Skipping world creation removes
 * the slowest and least reliable part of the loop (world gen, an integrated server, chunk loading) and
 * the wait shrinks from a minute to a few seconds. If a defect ever turns out to need a world, that is
 * a specific extra step rather than the default.</p>
 *
 * <p>Off unless {@code -Dcrystalgui.autotest=true}. Enable with {@code ./gradlew :mc1710:runClient
 * -PcgAutoTest}, which also sets the output path.</p>
 */
public final class CgUiAutoTest {

    /** @see CgUiAutoTest */
    public static final boolean ENABLED = Boolean.getBoolean("crystalgui.autotest");

    /** Where the capture is written. */
    private static final String OUTPUT =
            System.getProperty("crystalgui.autotest.out", "crystalgui-autotest.png");

    /**
     * Ticks to wait at the main menu before opening the editor.
     *
     * <p>Not zero: the first client tick fires while resources are still being reloaded, and opening
     * then means measuring a half-initialised game rather than the editor.</p>
     */
    private static final int OPEN_AFTER_TICKS = 40;

    /**
     * Frames to paint before capturing.
     *
     * <p>Layout settles over several passes — {@code UIText} re-measures and pushes its height back as
     * an IMPORTANT candidate until it stops changing — so a capture on frame 1 shows a tree mid-settle
     * and reads as a layout bug that is really just an early screenshot.</p>
     */
    static final int CAPTURE_ON_FRAME =
            Integer.getInteger("crystalgui.autotest.frame", 10);

    /**
     * A SECOND capture, much later, written beside the first with a {@code -late} suffix.
     *
     * <p>The first capture proves the editor renders; it cannot prove it KEEPS rendering. A defect that
     * appears after a few hundred frames — a resource freed, a cache invalidated, a pooled buffer
     * recycled — looks identical to "it never worked" to somebody watching, and identical to "it works"
     * to a screenshot taken on frame 10. Two captures separated by real time distinguish them.</p>
     */
    static final int LATE_CAPTURE_ON_FRAME =
            Integer.getInteger("crystalgui.autotest.lateFrame", 0);

    /**
     * Save folder to load before opening the editor, or null to stay on the main menu.
     * {@code -PcgAutoTest=<folder>} — e.g. {@code -PcgAutoTest="New World"}.
     */
    private static final String WORLD = emptyToNull(System.getProperty("crystalgui.autotest.world"));

    /** Frames in the world before opening, so the render pipeline has really run. */
    private static final int IN_WORLD_SETTLE_TICKS = 40;

    /**
     * A script to compile and run once the editor is up, or null to run nothing.
     *
     * <p>{@code -PcgScript=Probe.java} — the EXTENSION picks the language, which is the whole point: the
     * same probe run as {@code .java} and as {@code .js} is the only honest comparison when one works and
     * the other does not.</p>
     */
    private static final String SCRIPT = emptyToNull(System.getProperty("crystalgui.autotest.script"));

    /**
     * What that script says. One line, because the failure being chased is not in the script.
     *
     * <p>{@code System.out.println} is deliberate: it is the first thing anybody writes, it exercises the
     * output capture, and it is what the report that prompted this named.</p>
     */
    private static final String SCRIPT_SOURCE = System.getProperty(
            "crystalgui.autotest.scriptSource", "System.out.println(\"moo\");");

    /**
     * A class to compare LIVE bytes against DISK bytes, or null.
     *
     * <p>{@code -PcgBytes=net/minecraft/client/Minecraft}. The whole claim of §15.5 A is that a
     * file-based classpath cannot see what a transformer produced, and this is what makes that visible
     * rather than asserted — the difference between the two byte sources IS the capability.</p>
     */
    private static final String BYTES_PROBE = emptyToNull(System.getProperty("crystalgui.autotest.bytes"));

    /** Which painted frame runs it — before {@link #CAPTURE_ON_FRAME}, so a capture still happens. */
    static final int RUN_SCRIPT_ON_FRAME =
            SCRIPT == null ? -1 : Integer.getInteger("crystalgui.autotest.scriptFrame", 5);

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static int inWorldTicks;
    private static boolean loadingWorld;
    private static int ticks;
    private static boolean opened;
    private static boolean captured;
    private static boolean scriptRun;
    private static boolean bytesProbed;

    private CgUiAutoTest() {
    }

    public static void register() {
        if (!ENABLED) return;
        FMLCommonHandler.instance().bus().register(new CgUiAutoTest.Handler());
        CrystalGuiCore.LOGGER.info("CGUI AUTOTEST armed — will open the editor and capture to {}", OUTPUT);
    }

    /** Instance methods, because {@code @SubscribeEvent} is not honoured on statics. */
    public static final class Handler {

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END || opened) return;
            Minecraft mc = Minecraft.getMinecraft();

            if (WORLD != null) {
                // IN-WORLD CAPTURE. Not the same test as the main menu one: in a world CrystalGraphics'
                // CgRenderHook runs executeOpaquePass/executeTransparentPass every frame BEFORE the GUI,
                // so the UI paints after a full render pipeline pass. At the main menu that hook never
                // fires. If the editor draws in one and not the other, that difference is the bug.
                if (mc.theWorld == null) {
                    if (!(mc.currentScreen instanceof GuiMainMenu)) return;
                    if (++ticks < OPEN_AFTER_TICKS) return;
                    if (!loadingWorld) {
                        loadingWorld = true;
                        CrystalGuiCore.LOGGER.info("CGUI AUTOTEST loading world '{}'", WORLD);
                        mc.launchIntegratedServer(WORLD, WORLD, null);
                    }
                    return;
                }
                // In the world now — let it settle so the pipeline has really run some frames.
                if (++inWorldTicks < IN_WORLD_SETTLE_TICKS) return;
                opened = true;
                CrystalGuiCore.LOGGER.info("CGUI AUTOTEST opening the editor (in world)");
                CgUiScreen.open();
                return;
            }

            // Wait for the main menu specifically. "Any screen" would fire on the Mojang splash, before
            // a GL context is in the state a GuiScreen normally paints under.
            if (!(mc.currentScreen instanceof GuiMainMenu)) return;
            if (++ticks < OPEN_AFTER_TICKS) return;
            opened = true;
            CrystalGuiCore.LOGGER.info("CGUI AUTOTEST opening the editor");
            CgUiScreen.open();
        }
    }

    /**
     * Compiles and runs one script, on the client thread, logging every step.
     *
     * <h3>Why this bypasses the Run command</h3>
     *
     * <p>It is a bisect, not a substitute. Going through the command would exercise the keymap, the
     * action's enablement, the panel and the console as well as the runtime — so a failure anywhere in
     * that chain looks the same. This calls {@code ScriptRuntimes.forFile} + {@code compileScript} +
     * {@code runAsync} and nothing else, so if the game still dies the language stack owns it and if it
     * does not, the shell does.</p>
     *
     * <p><b>On the client thread deliberately.</b> That is where the Run command's compile happens, and a
     * probe on a worker thread would prove nothing about a failure that reaches Minecraft's game loop —
     * {@code Minecraft.run} catches {@code MinecraftError} silently and then runs
     * {@code shutdownMinecraftApplet}, which is a clean exit 0 with no crash report and nothing in the log
     * to search for. The compile is the half that runs here; {@code runAsync} takes a daemon thread of its
     * own, so a fault after that line is the script's and not the game's.</p>
     *
     * <p>Every step is logged before it is attempted rather than after, because the failure being chased
     * leaves nothing behind — the last line printed is the answer.</p>
     */
    static void runScriptOnce(ScriptWorkbench scripting) {
        if (!ENABLED || SCRIPT == null || scriptRun) return;
        scriptRun = true;
        if (scripting == null) {
            CrystalGuiCore.LOGGER.error("CGUI AUTOTEST script: no ScriptWorkbench — no engine band opened");
            return;
        }
        try {
            CrystalGuiCore.LOGGER.info("CGUI AUTOTEST script: resolving a runtime for {}", SCRIPT);
            ScriptRuntime runtime = scripting.runtimes().forFile(SCRIPT);
            if (runtime == null) {
                CrystalGuiCore.LOGGER.error("CGUI AUTOTEST script: no runtime for {}", SCRIPT);
                return;
            }
            CrystalGuiCore.LOGGER.info("CGUI AUTOTEST script: runtime is {} for language {}",
                    runtime.getClass().getName(), runtime.language());

            CrystalGuiCore.LOGGER.info("CGUI AUTOTEST script: compiling [{}]", SCRIPT_SOURCE);
            ScriptRuntime.Compiled compiled =
                    runtime.compileScript(SCRIPT, SCRIPT_SOURCE, Collections.emptyMap());
            CrystalGuiCore.LOGGER.info("CGUI AUTOTEST script: compiled, successful={}",
                    compiled == null ? "null" : Boolean.valueOf(compiled.successful()));
            if (compiled == null || !compiled.successful()) {
                // WITH THE MESSAGES. "compile failed" names nothing a reader can act on, and this probe
                // exists precisely for the runs nobody is watching -- a failure whose reason is not in
                // the log costs another whole launch to find out.
                CrystalGuiCore.LOGGER.error("CGUI AUTOTEST script: compile failed, not running: {}",
                        compiled == null ? "(no result)" : compiled.messages());
                return;
            }

            CrystalGuiCore.LOGGER.info("CGUI AUTOTEST script: runAsync");
            runtime.runAsync(compiled, Collections.<String, Object>emptyMap(),
                    (ref, thrown) -> CrystalGuiCore.LOGGER.error("CGUI AUTOTEST script: threw", thrown));
            CrystalGuiCore.LOGGER.info("CGUI AUTOTEST script: runAsync returned — the game survived it");
        } catch (Throwable failed) {
            // THROWABLE, and it is the point. EcjCompilation catches RuntimeException and lets an Error
            // through, and JDT is documented here as asserting on its own invariants -- so an Error is
            // the likely shape and the ordinary catch would miss exactly the case being chased.
            //
            // AS A STRING, NEVER AS A THROWABLE. Handing log4j 2.0-beta9 a Throwable makes ThrowableProxy
            // walk the trace and Class.forName every frame's class on the APP loader to annotate it with
            // a jar name -- and a frame in a child-side class cannot be DEFINED there, because its
            // supertype is an engine type that lives only in the band loader. The NoClassDefFoundError
            // that produces escapes the logging call itself, so the act of reporting the failure destroys
            // the report and takes the client with it. @see #describe
            CrystalGuiCore.LOGGER.error("CGUI AUTOTEST script: FAILED\n{}", describe(failed));
        }
    }

    /**
     * Reports every member the LIVE runtime declares and the class FILE does not.
     *
     * <h3>What this is proving</h3>
     *
     * <p>§26.4 exists for one claim: a member can exist only because a transformer produced it, and no
     * list of file paths can ever resolve it. That is easy to assert and hard to believe, so this reads
     * both sources in the running client and prints the difference.</p>
     *
     * <p>It needs no mixin of its own. CrystalGraphics already mixes into {@code Minecraft},
     * {@code EntityRenderer} and {@code RenderGlobal} in this very client, for reasons that have nothing
     * to do with scripting — which makes it a better witness than one written to pass: the members it
     * finds were put there by somebody else, for their own purposes, before this feature existed.</p>
     *
     * <p>Both sides are read through the SAME parse, so a difference cannot be an artefact of reading
     * them differently. The live side is {@link com.crystalgui.mc.script.LaunchWrapperBytes} — exactly
     * what the compiler's name environment asks — and the disk side is the raw pre-transform bytes
     * LaunchWrapper itself hands out.</p>
     */
    static void probeLiveBytesOnce() {
        if (!ENABLED || BYTES_PROBE == null || bytesProbed) return;
        bytesProbed = true;
        try {
            ScriptPlatform platform = ScriptPlatforms.current();
            if (platform == ScriptPlatform.NONE) {
                CrystalGuiCore.LOGGER.error("CGUI AUTOTEST bytes: no platform registered");
                return;
            }
            byte[] live = platform.liveBytes().bytesOf(BYTES_PROBE);
            byte[] raw = rawBytesOf(BYTES_PROBE);
            CrystalGuiCore.LOGGER.info("CGUI AUTOTEST bytes: {} live={} raw={}",
                    BYTES_PROBE, live == null ? -1 : live.length, raw == null ? -1 : raw.length);
            if (live == null || raw == null) return;

            if (live.length == raw.length) {
                CrystalGuiCore.LOGGER.warn("CGUI AUTOTEST bytes: live and raw are the same size — "
                        + "no transformer changed this class, so it proves nothing");
            }
            Set<String> onlyLive = new LinkedHashSet<>(stringsIn(live));
            onlyLive.removeAll(stringsIn(raw));
            CrystalGuiCore.LOGGER.info("CGUI AUTOTEST bytes: {} constants exist in the LIVE class "
                    + "and in NO file on disk", onlyLive.size());
            int shown = 0;
            for (String constant : onlyLive) {
                if (shown++ >= 40) break;
                CrystalGuiCore.LOGGER.info("CGUI AUTOTEST bytes:     {}", constant);
            }
        } catch (Throwable failed) {
            CrystalGuiCore.LOGGER.error("CGUI AUTOTEST bytes: FAILED\n{}", describe(failed));
        }
    }

    /** Pre-transform bytes — what a file-based classpath would see. */
    private static byte[] rawBytesOf(String internalName) {
        ClassLoader loader = CgUiAutoTest.class.getClassLoader();
        if (!(loader instanceof LaunchClassLoader)) return null;
        try {
            return ((LaunchClassLoader) loader).getClassBytes(internalName.replace('/', '.'));
        } catch (IOException unavailable) {
            return null;
        }
    }

    /**
     * Printable strings in a class file — its constant pool, without parsing one.
     *
     * <h3>Why not ASM</h3>
     *
     * <p>This module compiles against <b>LaunchWrapper's</b> ASM 5.0.3, where {@code Opcodes.ASM9} does
     * not exist and {@code ClassRemapper} is still {@code RemappingClassAdapter} — the same 5.0.3 that
     * forced the mod's own ASM to be relocated. Writing a class visitor here would compile against one
     * ASM and, after relocation, run against another; that is a trap, and this probe does not need a
     * parser to make its point.</p>
     *
     * <p>Every method name, field name and descriptor is a UTF-8 constant, so a scan for printable runs
     * finds all of them plus some noise. Noise is harmless: it appears on <b>both</b> sides and cancels
     * in the difference. What survives is what one class file has and the other does not.</p>
     */
    private static Set<String> stringsIn(byte[] classFile) {
        Set<String> found = new LinkedHashSet<>();
        StringBuilder run = new StringBuilder();
        for (byte raw : classFile) {
            int character = raw & 0xFF;
            if (character >= 0x21 && character <= 0x7E) {
                run.append((char) character);
                continue;
            }
            if (run.length() >= 6) found.add(run.toString());
            run.setLength(0);
        }
        if (run.length() >= 6) found.add(run.toString());
        return found;
    }

    /**
     * A throwable as plain text, with its causes — safe to hand a logger.
     *
     * <p>Built here rather than with {@code Throwables.getStackTraceAsString} or a {@code PrintWriter}
     * because the point is that <b>no {@code Throwable} object reaches log4j</b>. Frame classes are named
     * as the strings they already are; nothing is loaded to describe them.</p>
     */
    private static String describe(Throwable failed) {
        StringBuilder text = new StringBuilder();
        for (Throwable at = failed; at != null; at = at.getCause()) {
            text.append(at == failed ? "" : "Caused by: ")
                .append(at.getClass().getName()).append(": ").append(at.getMessage()).append('\n');
            StackTraceElement[] frames = at.getStackTrace();
            for (int i = 0; i < frames.length && i < 18; i++) {
                text.append("\tat ").append(frames[i]).append('\n');
            }
            if (at.getCause() == at) break;
        }
        return text.toString();
    }

    /**
     * Reads the bound framebuffer back to a PNG, then quits.
     *
     * <p>Called from {@link CgUiScreen#drawScreen} while Minecraft's own framebuffer is bound, so this
     * captures <b>what Minecraft is about to present</b> rather than a target of our own — the same
     * pixels a person would photograph, which is what makes it a valid substitute for one.</p>
     */
    static void captureAndQuit(Minecraft mc, int width, int height) {
        if (!ENABLED || captured) return;
        // When a late capture is asked for, the first one must NOT quit -- there would be nothing left
        // running to take the second with.
        boolean quitAfter = LATE_CAPTURE_ON_FRAME <= 0;
        capture(mc, width, height, quitAfter ? OUTPUT : OUTPUT.replace(".png", "-early.png"));
        if (quitAfter) { captured = true; mc.shutdown(); }
    }

    /** The late capture, and the one that ends the run. */
    static void captureLateAndQuit(Minecraft mc, int width, int height) {
        if (!ENABLED || captured || LATE_CAPTURE_ON_FRAME <= 0) return;
        captured = true;
        capture(mc, width, height, OUTPUT.replace(".png", "-late.png"));
        mc.shutdown();
    }

    private static void capture(Minecraft mc, int width, int height, String path) {
        try {
            ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
            GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);

            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int i = (x + width * y) * 4;
                    int r = pixels.get(i) & 0xFF;
                    int g = pixels.get(i + 1) & 0xFF;
                    int b = pixels.get(i + 2) & 0xFF;
                    // GL's origin is bottom-left and an image's is top-left, so rows invert.
                    image.setRGB(x, height - 1 - y, (r << 16) | (g << 8) | b);
                }
            }
            File out = new File(path).getAbsoluteFile();
            ImageIO.write(image, "PNG", out);
            CrystalGuiCore.LOGGER.info("CGUI AUTOTEST wrote {}x{} capture to {}", width, height, out);
        } catch (Throwable t) {
            CrystalGuiCore.LOGGER.warn("CGUI AUTOTEST capture failed", t);
        }
    }
}
