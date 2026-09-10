package com.crystalgui.mc.client;

import com.crystalgui.core.CrystalGuiCore;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;


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

    /**
     * The save to load: {@code *} means "whichever one is there", any other value is a folder name.
     *
     * <p><b>Because a folder name cannot always be passed.</b> An installed client takes these through
     * PrismLauncher's {@code JvmArgs}, which splits on spaces and strips quotes -- so
     * {@code -Dcrystalgui.autotest.world=New World} arrives as two arguments and the JVM dies with
     * {@code Could not find or load main class World...}. Every world made through the vanilla UI is
     * called "New World". The sentinel sidesteps the whole quoting problem, and "the first save" is
     * what somebody driving this unattended means anyway.</p>
     */
    private static String resolveWorld(Minecraft mc) {
        if (!"*".equals(WORLD)) return WORLD;
        File[] saves = new File(mc.mcDataDir, "saves").listFiles(File::isDirectory);
        if (saves != null) {
            for (File save : saves) {
                if (new File(save, "level.dat").isFile()) return save.getName();
            }
        }
        // Nothing to load, so name the one this would create. @see the WorldSettings below
        return "cgui-autotest";
    }

    /** Frames in the world before opening, so the render pipeline has really run. */
    private static final int IN_WORLD_SETTLE_TICKS = 40;


    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static int inWorldTicks;
    private static boolean loadingWorld;
    private static int ticks;
    private static boolean opened;
    private static boolean captured;

    /**
     * Extra work another jar wants run on a painted frame, keyed by frame number.
     *
     * <p><b>The seam the language mod's own probes arrive through</b>, and the reason this class names
     * nothing in {@code language/}: since J8 the scripting half ships in a second jar, so its steps
     * cannot be called from here by name. A step registered for a frame that has already passed simply
     * never runs — registration happens at mod init, long before the first paint.</p>
     */
    private static final Map<Integer, List<Runnable>> FRAME_STEPS = new TreeMap<>();

    /**
     * Runs {@code step} on the {@code frame}-th painted frame of the editor.
     *
     * <pre>{@code
     * CgUiAutoTest.onFrame(5, MyProbe::runOnce);   // from mod init, before anything paints
     * }</pre>
     *
     * <p>Frames are counted by {@link CgUiScreen}, from 1. Several steps may share a frame and run in
     * registration order; a step that throws is logged and the rest still run.</p>
     */
    public static void onFrame(int frame, Runnable step) {
        FRAME_STEPS.computeIfAbsent(frame, unused -> new ArrayList<>()).add(step);
    }

    /** Called once per painted frame by {@link CgUiScreen#drawScreen}. */
    static void runFrameSteps(int framesPainted) {
        List<Runnable> steps = FRAME_STEPS.get(framesPainted);
        if (steps == null) return;
        for (Runnable step : steps) {
            try {
                step.run();
            } catch (RuntimeException failed) {
                CrystalGuiCore.LOGGER.error("CGUI AUTOTEST frame {} step failed", framesPainted, failed);
            }
        }
    }

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
                        // CREATED IF IT IS NOT THERE, which is what makes this run self-sufficient.
                        // launchIntegratedServer's third argument is the settings to CREATE with, and
                        // null means "load what is already on disk" -- so on a fresh checkout, where
                        // there are no saves at all, it NPE'd inside Minecraft before the editor was
                        // ever asked for. An unattended capture that needs somebody to have made a world
                        // by hand first is not unattended.
                        //
                        // Superflat creative: the fastest world to generate, and the one whose content
                        // has the least to do with what is being photographed.
                        // level.dat, not the DIRECTORY: a failed launch leaves the folder behind
                        // with a session.lock in it, and loading that answers null and NPEs inside
                        // Minecraft -- so the second run of a broken first run fails differently
                        // from the first, which is the worst kind of flake to read.
                        String world = resolveWorld(mc);
                        boolean exists = new File(mc.mcDataDir, "saves/" + world + "/level.dat").isFile();
                        CrystalGuiCore.LOGGER.info("CGUI AUTOTEST {} world '{}'",
                                exists ? "loading" : "creating", world);
                        mc.launchIntegratedServer(world, world, exists ? null
                                : new WorldSettings(0L, WorldSettings.GameType.CREATIVE,
                                        false, false, WorldType.FLAT));
                    }
                    return;
                }
                // In the world now — let it settle so the pipeline has really run some frames.
                if (++inWorldTicks < IN_WORLD_SETTLE_TICKS) return;
                opened = true;
                CrystalGuiCore.LOGGER.info("CGUI AUTOTEST opening the editor (in world)");
                CgUiScreen.openEditor();
                return;
            }

            // Wait for the main menu specifically. "Any screen" would fire on the Mojang splash, before
            // a GL context is in the state a GuiScreen normally paints under.
            if (!(mc.currentScreen instanceof GuiMainMenu)) return;
            if (++ticks < OPEN_AFTER_TICKS) return;
            opened = true;
            CrystalGuiCore.LOGGER.info("CGUI AUTOTEST opening the editor");
            CgUiScreen.openEditor();
        }
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
