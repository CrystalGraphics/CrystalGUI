package com.crystalgui.probe;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.app.ApplicationKind;
import com.crystalgui.desktop.host.HostSession;
import com.crystalgui.desktop.window.StartingMark;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.control.Button;

import javax.annotation.Nullable;

/**
 * An unattended open and close of one application, as a person does it: the desktop opens bare, then the
 * application's taskbar button is pressed, then its window's close button. What reproduces anything an open or a
 * close does to the frames around it.
 *
 * <pre>{@code
 * -Dcrystalgui.autotest=true -Dcrystalgui.autotest.world=* -Dcrystalgui.autotest.openClose=crystalgui:vfx-lab
 * }</pre>
 *
 * <ul>
 *   <li>Steps count the host's settling units after the desktop opens: ticks on 1.13+, painted frames below.</li>
 *   <li>The application must be pinned to the taskbar; one that is not is opened as the launcher would.</li>
 *   <li>The run quits at {@code crystalgui.autotest.frame}; set it past the close.</li>
 *   <li>An application with a starting window logs {@code handover:}, its longest frame while the mark left: over
 *       about 25 ms, the mark visibly stood still.</li>
 * </ul>
 */
public final class OpenCloseProbe {

    /** {@code -Dcrystalgui.autotest.openClose=<kind id>}: the application to open and close; none by default. */
    @Nullable
    public static final String KIND = System.getProperty("crystalgui.autotest.openClose");

    /** Settles after the open the taskbar button is pressed at: two seconds of ticks. */
    private static final int OPEN_AT = Integer.getInteger("crystalgui.autotest.openClose.openAt", 40);
    /** Settles after the press the close button is pressed at, once the window has loaded. */
    private static final int CLOSE_AFTER = Integer.getInteger("crystalgui.autotest.openClose.closeAfter", 60);

    private OpenCloseProbe() {
    }

    /** Whether the run opens the bare desktop: an application of its own is what it opens. */
    public static boolean enabled() {
        return AutoTest.ENABLED && KIND != null;
    }

    /** Registers the steps. Once, when the desktop opens. */
    static void start() {
        if (!enabled()) return;
        AutoTest.onFrame(OPEN_AT, () -> onDocument(OpenCloseProbe::open));
        AutoTest.onFrame(OPEN_AT + CLOSE_AFTER, () -> onDocument(OpenCloseProbe::close));
    }

    private static void onDocument(Runnable step) {
        if (HostSession.isInstalled()) HostSession.session().postToDocument(step);
    }

    private static void open() {
        Desktop desktop = HostSession.session().desktop();
        if (desktop == null) return;
        ApplicationKind kind = kind(desktop);
        if (kind == null) {
            CrystalGuiCore.LOGGER.warn("CGUI AUTOTEST openClose: no application {}", KIND);
            return;
        }
        Button pinned = desktop.taskbar().pinEntryFor(kind);
        CrystalGuiCore.LOGGER.info("CGUI AUTOTEST openClose: opening {} from the {}", KIND,
                pinned != null ? "taskbar" : "launcher");
        if (pinned != null) pinned.onPressed.emit();
        else desktop.applications().launchInStartingWindow(kind, desktop.workspace());
        watchHandover(desktop);
    }

    /**
     * Every document frame from the press until a starting window's handover is over, by phase: whether the mark
     * stood still while it left is the longest gap in {@code leave}. Its frames are the document's, so a frame the
     * host re-presents is a gap here.
     */
    private static void watchHandover(Desktop desktop) {
        long pressed = System.nanoTime();
        StringBuilder frames = new StringBuilder();
        long[] worst = {0L, 0L};   // longest gap while leaving, and when, ms since the press
        boolean[] seen = {false};
        desktop.document().animation().every(desktop.taskbar(), delta -> {
            char phase = handoverPhase(desktop);
            long at = (System.nanoTime() - pressed) / 1_000_000L;
            long gap = Math.round(delta * 1000.0);
            if (phase != '.') seen[0] = true;
            frames.append(' ').append(at).append(phase).append(gap);
            if (phase == 'h') {
                frames.append('/').append(desktop.workspace() == null ? -1 : desktop.workspace().health().inFlight());
            }
            if ((phase == 'l' || phase == 'x') && gap > worst[0]) {
                worst[0] = gap;
                worst[1] = at;
            }
            if (phase != '.' || !seen[0] && at < 20_000L) return true;
            CrystalGuiCore.LOGGER.info("CGUI AUTOTEST handover: longest frame while the mark left {} ms, {} ms after the "
                    + "press (s starting, h waiting with file requests out, l leaving, x crossfade; ms since the press, "
                    + "phase, frame ms):{}", worst[0], worst[1], frames);
            return false;
        });
    }

    /** {@code s} a starter showing its mark, {@code h} its successor waiting, {@code l} the mark leaving, {@code x} the crossfade. */
    private static char handoverPhase(Desktop desktop) {
        boolean starter = false, cover = false, handover = false;
        for (WindowFrame frame : desktop.windows()) {
            if (frame.hasClass(WindowFrame.HANDOVER_CLASS)) handover = true;
            if (!frame.content().hasClass(Desktop.STARTING_CLASS)) continue;
            starter = true;
            for (UIElement child : frame.content().children()) {
                if (child.hasClass(StartingMark.COVER_CLASS)) cover = true;
            }
        }
        if (!starter) return '.';
        if (cover) return handover ? 'l' : 'x';
        return handover ? 'h' : 's';
    }

    private static void close() {
        Desktop desktop = HostSession.session().desktop();
        if (desktop == null) return;
        String app = WindowFrame.applicationClass(KIND);
        for (WindowFrame frame : desktop.windows()) {
            if (!frame.hasClass(app)) continue;
            CrystalGuiCore.LOGGER.info("CGUI AUTOTEST openClose: closing {} from its close button", KIND);
            frame.closeButton().onPressed.emit();
            return;
        }
        CrystalGuiCore.LOGGER.warn("CGUI AUTOTEST openClose: no window of {} to close", KIND);
    }

    @Nullable
    private static ApplicationKind kind(Desktop desktop) {
        for (ApplicationKind kind : desktop.applications().installed()) {
            if (kind.id().equals(KIND)) return kind;
        }
        return null;
    }
}
