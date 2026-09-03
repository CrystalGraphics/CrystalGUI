package com.crystalgui.mc.net;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.client.Workspace;
import com.crystalgui.mc.client.CgUiScreen;
import com.crystalgui.net.protocol.ProtocolConnection;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;

/**
 * The workspace <b>with the editor on screen</b> — {@code -PcgEditorProbe}.
 *
 * <h3>The one configuration no other probe covers</h3>
 *
 * <p>Every other probe here either closes the GUI or never opens one, and that is precisely the
 * configuration a player never uses. It hid a deadlock that took the whole feature down in
 * single-player: a {@code GuiScreen} whose {@code doesGuiPauseGame()} answers true stops
 * {@code MinecraftServer.tick}, so {@code ServerTickEvent} stops, so the connection is never pumped and
 * its mailbox is never drained — <b>the editor asks the integrated server for the project list and the
 * integrated server is not listening, because the editor being open is what stopped it.</b> Every call
 * then dies at its ten-second timeout, with nothing in the log at all.</p>
 *
 * <p>It presented as "the workspace is empty", with New File greyed because there was no project root
 * to create into rather than because anything was refused. And it was invisible to the whole probe
 * suite, because a dedicated server cannot be paused by a client GUI — so the two-process probe passes
 * against it and the configuration most players run is the one nobody exercised.</p>
 *
 * <h3>What it asserts</h3>
 *
 * <p>That a round trip <b>completes while the screen is up</b>, which is a statement about the game
 * loop rather than about the filesystem. Deliberately run on the INTEGRATED server, since that is the
 * only place a client GUI can stop the ticking that answers it — the assertion is vacuous anywhere
 * else, so this probe refuses to run in multiplayer exactly as its sibling refuses single-player.</p>
 *
 * <p>Reports {@link Workspace#health()} on the way out, which is the number that says whether an
 * optimistic explorer is ever worth building: below a threshold it is machinery for nothing.</p>
 */
public final class CgUiEditorOpenProbe {

    private static final boolean ENABLED = Boolean.getBoolean("crystalgui.editor.probe");

    /** Generous: a world has to load, a screen has to open and a listing has to cross the wire. */
    private static final int DEADLINE_TICKS = 20 * 60;

    /** How long the screen stays up after the answers land, so a person watching sees it. */
    private static final int LINGER_TICKS = 20 * 2;

    private static final String PROBE_FILE = "editor-open-probe.txt";
    private static final String TEXT = "written while the editor was on screen\n";

    private CgUiEditorOpenProbe() {
    }

    public static void register() {
        if (!ENABLED) return;
        FMLCommonHandler.instance().bus().register(new Handler());
        CrystalGuiCore.LOGGER.info("[editor-probe] armed — will open the editor and work through it");
    }

    private static final class Handler {

        private int ticks;
        private boolean opened;
        private boolean listed;
        private boolean listing;
        private boolean wrote;
        private boolean writing;
        private boolean readBack;
        private boolean reading;
        private int settledAt = -1;
        private boolean reported;

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END || reported) return;
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.theWorld == null) return;

            if (!mc.isSingleplayer()) {
                finish(false, "this is a REMOTE server; a client GUI cannot stop its ticking, so the "
                        + "deadlock this probe exists for is unreachable. Use -PcgRemoteProbe there.");
                return;
            }

            ticks++;

            if (!opened) {
                // THE SCREEN FIRST, and everything after it runs with the screen up. That ordering is
                // the entire probe: opening afterwards would test the same thing every other probe does.
                CgUiScreen.openEditor();
                opened = true;
                CrystalGuiCore.LOGGER.info("[editor-probe] editor open; the world is {}",
                        mc.currentScreen != null && !mc.currentScreen.doesGuiPauseGame()
                                ? "still ticking" : "PAUSED — every call below will time out");
                return;
            }

            // THE SCREEN HAS TO STILL BE UP. A probe that carried on after something closed it would be
            // asserting exactly what every other probe already asserts.
            if (mc.currentScreen == null) {
                finish(false, "the screen closed before the work finished; nothing was proven");
                return;
            }

            Workspace workspace = CgUiScreen.workspaceForProbe();
            if (workspace == null) {
                if (ticks > DEADLINE_TICKS) finish(false, "the editor never got a workspace");
                return;
            }

            step(workspace);

            if (listed && wrote && readBack) {
                if (settledAt < 0) settledAt = ticks;
                // LINGERING ON PURPOSE: the screen stays up for a moment after the answers land, so a
                // person running this sees a workspace with files in it rather than a flash.
                if (ticks - settledAt >= LINGER_TICKS) finish(true, "");
            } else if (ticks > DEADLINE_TICKS) {
                finish(false, "timed out — which is what a paused server looks like from here");
            }
        }

        /** One request in flight at a time, so a stall names the step it stalled on. */
        private void step(Workspace workspace) {
            CgPath root = CgPath.ofProject(CgUiWorkspaceHost.PROJECT_ID);
            Resource probe = Resource.of(CgPath.of(CgUiWorkspaceHost.PROJECT_ID, PROBE_FILE));

            if (!listed) {
                if (listing) return;
                listing = true;
                workspace.files().list(Resource.of(root))
                        .then(answer -> {
                            CrystalGuiCore.LOGGER.info("[editor-probe] 1/3 listed {} entries WITH the "
                                    + "editor on screen", answer.entries().size());
                            listed = true;
                        })
                        .onError(failure -> {
                            CrystalGuiCore.LOGGER.error("[editor-probe] listing failed: {}",
                                    failure.code());
                            listing = false;
                        });
                return;
            }

            if (!wrote) {
                if (writing) return;
                writing = true;
                workspace.files().write(probe, TEXT.getBytes(StandardCharsets.UTF_8), null)
                        .then(etag -> {
                            CrystalGuiCore.LOGGER.info("[editor-probe] 2/3 wrote {} (etag {})",
                                    PROBE_FILE, etag);
                            wrote = true;
                        })
                        .onError(failure -> {
                            CrystalGuiCore.LOGGER.error("[editor-probe] write failed: {}",
                                    failure.code());
                            writing = false;
                        });
                return;
            }

            if (!readBack) {
                if (reading) return;
                reading = true;
                workspace.files().read(probe)
                        .then(answer -> {
                            readBack = TEXT.equals(
                                    new String(answer.content(), StandardCharsets.UTF_8));
                            CrystalGuiCore.LOGGER.info("[editor-probe] 3/3 read it back, matches={}",
                                    readBack);
                            if (!readBack) reading = false;
                        })
                        .onError(failure -> {
                            CrystalGuiCore.LOGGER.error("[editor-probe] read failed: {}",
                                    failure.code());
                            reading = false;
                        });
            }
        }

        private void finish(boolean pass, String why) {
            reported = true;
            Workspace workspace = CgUiScreen.workspaceForProbe();
            String health = workspace == null ? "no workspace" : workspace.health().toString();
            if (pass) {
                CrystalGuiCore.LOGGER.info("[editor-probe] PASS — listed, wrote and read back a file "
                        + "with the editor on screen, so the integrated server kept ticking under it. "
                        + "Health: {}", health);
            } else {
                CrystalGuiCore.LOGGER.error("[editor-probe] FAIL ({}) — listed={} wrote={} readBack={}. "
                        + "Health: {}", why, listed, wrote, readBack, health);
            }
            Minecraft.getMinecraft().shutdown();
        }
    }
}
