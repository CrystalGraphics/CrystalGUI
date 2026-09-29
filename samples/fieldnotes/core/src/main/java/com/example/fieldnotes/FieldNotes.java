package com.example.fieldnotes;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.host.HostSession;
import com.crystalgui.fs.client.Workspace;
import com.crystalgui.probe.AutoTest;

/**
 * Where the loader side hands over: each loader's variant calls {@link #start} once, with what only the
 * Minecraft side can know, and everything after that is this Minecraft-free module's.
 *
 * <pre>{@code
 * // a loader's variant entry, compiled once per Minecraft version
 * public void start(Object context) {
 *     FieldNotes.start("Forge", Game.version(), Game.id("notes"));
 * }
 * }</pre>
 */
public final class FieldNotes {

    public static final String MODID = "fieldnotes";

    static final Logger LOGGER = LogManager.getLogger("FieldNotes");

    /**
     * When an unattended run opens the window: after the host has brought its own application forward,
     * which it keeps doing until that launch completes, and before prodSmoke's late capture at 120.
     */
    private static final int PROBE_FRAME = 100;

    private static volatile String where = "not started";
    private static volatile String noteId = "";

    private FieldNotes() {
    }

    /**
     * Records which loader and Minecraft this jar's variant is running on.
     *
     * @param loader    the loader's name, for the window
     * @param minecraft the running version, as the game reports it
     * @param noteId    an identifier built by the Minecraft side, proving its calls resolved
     */
    public static void start(String loader, String minecraft, String noteId) {
        where = loader + " on Minecraft " + minecraft;
        FieldNotes.noteId = noteId;
        LOGGER.info("[fieldnotes] started: {} ({})", where, noteId);
        if (AutoTest.ENABLED) AutoTest.onFrame(PROBE_FRAME, FieldNotes::launchForProbe);
    }

    /** "Forge on Minecraft 1.20.1", or "not started" before a variant has run. */
    public static String where() {
        return where;
    }

    public static String noteId() {
        return noteId;
    }

    /** An unattended run opens the window, so the capture shows this jar's variant ran. */
    private static void launchForProbe() {
        Desktop desktop = HostSession.isInstalled() ? HostSession.session().desktop() : null;
        if (desktop == null) {
            LOGGER.warn("[fieldnotes] probe: no desktop to open the window on");
            return;
        }
        // No workspace: the window needs no server.
        boolean launched = desktop.applications().launch(FieldNotesApplication.KIND, (Workspace) null) != null;
        LOGGER.info("[fieldnotes] probe: window launched {} -- {}", launched, where);
    }
}
