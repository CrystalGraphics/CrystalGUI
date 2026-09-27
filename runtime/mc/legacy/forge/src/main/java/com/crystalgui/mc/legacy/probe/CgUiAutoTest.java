package com.crystalgui.mc.legacy.probe;

import com.crystalgui.mc.legacy.client.CgUiScreen;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.desktop.host.HostSession;
import com.crystalgui.probe.AutoTest;

import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import com.crystalgui.mc.legacy.client.ClientGame;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;


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
 * <h3>No world unless one is asked for</h3>
 *
 * <p>{@link CgUiScreen} is an ordinary {@code GuiScreen}: it opens over the main menu just as well as
 * over a world, and nothing it paints depends on there being a level. Skipping world gen, an integrated
 * server and chunk loading takes the wait from a minute to a few seconds, so that is the default.</p>
 *
 * <p>{@code -Dcrystalgui.autotest.world} asks for one — and <b>this era creates one when there is
 * none</b>, which is what lets an unattended run be genuinely unattended. Worth asking for when the
 * defect needs a server: the editor application needs one, so on the title screen the desktop comes up
 * empty. @see AutoTest#wantsWorld()</p>
 *
 * <p>Off unless {@code -Dcrystalgui.autotest=true}. Enable with {@code ./gradlew :runtime:mc:1710:runClient
 * -PcgAutoTest}, which also sets the output path.</p>
 */
public final class CgUiAutoTest {

    /** @see AutoTest#ENABLED */
    public static final boolean ENABLED = AutoTest.ENABLED;

    /**
     * PAINTED FRAMES on this era, counted by {@link CgUiScreen} from its first paint.
     *
     * <p>Frames rather than ticks because layout is what settles — {@code UIText} re-measures and pushes
     * its height back until it stops changing, and a tick clock cannot see that. 1.20.x counts ticks;
     * the sequence is shared and the clock is not. @see AutoTest.Host</p>
     */
    private static final AutoTest.Host HOST = new AutoTest.Host() {

        /**
         * The MAIN MENU specifically, or a world. "Any screen" fires on the Mojang splash, before a GL
         * context is in the state a {@code GuiScreen} normally paints under.
         */
        @Override
        public boolean readyToDrive() {
            Minecraft mc = Minecraft.getMinecraft();
            return mc.currentScreen instanceof GuiMainMenu || ClientGame.world() != null;
        }

        @Override
        public boolean inWorld() {
            return ClientGame.world() != null;
        }

        @Override
        public boolean enterWorld() {
            return CgUiAutoTest.enterWorld();
        }

        @Override
        public void openDesktop() {
            CgUiScreen.openEditor();
        }

        @Override
        public void capture(String path) {
            Minecraft mc = Minecraft.getMinecraft();
            // QUALIFIED: inside this anonymous Host, a bare `capture` is the interface method above.
            CgUiAutoTest.capture(mc.displayWidth, mc.displayHeight, path);
        }

        /**
         * <b>Expect one exception after the last capture.</b> Quitting from inside a frame leaves the
         * integrated server mid-tick and it dies with {@code Display not created}. It is written after
         * the capture and means nothing.
         */
        @Override
        public void quit() {
            Minecraft.getMinecraft().shutdown();
        }

        @Override
        public int openAfter() {
            return Integer.getInteger(AutoTest.OPEN_AFTER, 40);
        }

        @Override
        public int captureAt() {
            return Integer.getInteger(AutoTest.CAPTURE_AT, 10);
        }

        @Override
        public int lateCaptureAt() {
            return Integer.getInteger(AutoTest.LATE_CAPTURE_AT, 0);
        }
    };

    private CgUiAutoTest() {
    }

    public static void register() {
        if (!ENABLED) return;
        FMLCommonHandler.instance().bus().register(new Handler());
        CrystalGuiCore.LOGGER.info("CGUI AUTOTEST armed — will open the editor and capture to {}",
                AutoTest.earlyCapture());
    }

    /**
     * Runs {@code step} on the {@code frame}-th painted frame of the editor.
     *
     * <p>Kept as a forwarder because the language jar calls it by this name across a mod boundary.
     * New code registers with {@link AutoTest#onFrame} directly. @see AutoTest#onFrame</p>
     */
    public static void onFrame(int frame, Runnable step) {
        AutoTest.onFrame(frame, step);
    }

    /**
     * Called once per painted frame by {@link CgUiScreen#drawScreen}.
     *
     * <p>This is the settling clock on the LWJGL2 eras, so the captures hang off it rather than off the tick.</p>
     */
    public static void onPainted(int framesPainted) {
        AutoTest.runFrameSteps(framesPainted);
        AutoTest.settled(HOST, framesPainted);
    }

    /** Instance methods, because {@code @SubscribeEvent} is not honoured on statics. */
    public static final class Handler {

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            AutoTest.tick(HOST);
        }
    }

    /**
     * The save to load, or the one this host would CREATE when there is none.
     *
     * <p>Creating is the LWJGL2 eras' alone and is what makes this run self-sufficient: an unattended capture
     * that needs somebody to have made a world by hand first is not unattended. 1.20.x cannot, and stays
     * on its title screen. @see AutoTest#resolveWorld</p>
     */
    private static String resolveWorld(Minecraft mc) {
        String named = AutoTest.resolveWorld(new File(ClientGame.gameDir(), "saves"));
        return named == null ? "cgui-autotest" : named;
    }

    static boolean enterWorld() {
        Minecraft mc = Minecraft.getMinecraft();
        // The MAIN MENU specifically. "Any screen" fires on the Mojang splash, before a GL context is
        // in the state a launch survives.
        if (!(mc.currentScreen instanceof GuiMainMenu)) return false;

        String world = resolveWorld(mc);
        boolean exists = new File(ClientGame.gameDir(), "saves/" + world + "/level.dat").isFile();
        CrystalGuiCore.LOGGER.info("[cgui] {} world '{}'", exists ? "loading" : "creating", world);
        mc.launchIntegratedServer(world, world, exists ? null : creativeFlat());
        return true;
    }

    /**
     * {@code new WorldSettings(0, CREATIVE, false, false, FLAT)}, with the game-mode type read off the
     * constructor: it is {@code WorldSettings.GameType} to 1.9.4 and {@code world.GameType} from 1.10, inside
     * one node's range.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static WorldSettings creativeFlat() {
        for (Constructor<?> constructor : WorldSettings.class.getConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length != 5 || !parameters[1].isEnum()) continue;
            try {
                Object creative = Enum.valueOf((Class) parameters[1], "CREATIVE");
                return (WorldSettings) constructor.newInstance(0L, creative, false, false, WorldType.FLAT);
            } catch (ReflectiveOperationException failed) {
                throw new IllegalStateException("cannot build WorldSettings", failed);
            }
        }
        throw new IllegalStateException("no WorldSettings(long, GameType, boolean, boolean, WorldType)");
    }

    /** Reads the bound framebuffer back to a PNG. Must be called from inside a frame. */
    static void capture(int width, int height, String path) {
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
            // WHETHER THE DESKTOP ACTUALLY PAINTED, beside the file, as 1.20.x states it. This host
            // reads back from inside its own paint method, so it is nearly always true -- the case it
            // catches is a paint that THREW, which drops HUD mode and leaves the previous frame in the
            // buffer. prodSmoke fails on a stated `false`.
            CrystalGuiCore.LOGGER.info("CGUI AUTOTEST wrote {}x{} capture to {} (desktop painted: {})",
                    width, height, out,
                    HostSession.isInstalled() && HostSession.session().hasPainted());
        } catch (Throwable t) {
            CrystalGuiCore.LOGGER.warn("CGUI AUTOTEST capture failed", t);
        }
    }
}
