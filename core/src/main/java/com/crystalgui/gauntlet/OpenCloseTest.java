package com.crystalgui.gauntlet;

import com.crystalgui.screen.CgUiScreen;
import javax.annotation.Nullable;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgraphics.variable.CgVar;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.app.ApplicationKind;
import com.crystalgui.desktop.window.StartingMark;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.control.Button;

/**
 * {@code crystalgui:open-close}: one application opened and closed as a person does it, the desktop bare, then its
 * taskbar button pressed, then its window's close button. What reproduces anything an open or a close does to the
 * frames around it.
 *
 * <pre>{@code
 * -Dcrystalgraphics.gauntlet=crystalgui:open-close -Dcrystalgui.openClose.kind=crystalgui:vfx-lab
 * }</pre>
 *
 * <ul>
 *   <li>The application is pressed on its taskbar pin, else launched as the launcher would.</li>
 *   <li>One with a starting window reports {@code handover}: its longest frame while the mark left; over about 25 ms
 *       the mark visibly stood still.</li>
 * </ul>
 */
public final class OpenCloseTest extends DesktopRun {

    public static final String ID = "crystalgui:open-close";

    private static final String KIND = CgVar.text("crystalgui.openClose.kind", "crystalgui:vfx-lab", CgVar.DEV | CgVar.RESTART,
            "The application crystalgui:open-close opens and closes.").get();
    /** Frames after the open the button is pressed at, then frames after that the close button is. */
    private static final int OPEN_AT = CgVar.integer("crystalgui.openClose.openAt", 120, 1, 100_000, CgVar.DEV | CgVar.RESTART,
            "Frames after the desktop opens crystalgui:open-close presses the application's button at.").get();
    private static final int CLOSE_AFTER = CgVar.integer("crystalgui.openClose.closeAfter", 180, 1, 100_000,
            CgVar.DEV | CgVar.RESTART, "Frames after the press crystalgui:open-close presses its close button at.").get();
    /** Frames after the close the window must be gone by. */
    private static final int GONE_AFTER = 60;

    public OpenCloseTest() {
        super(true);
    }

    @Override
    protected void onDesktopFrame(int frame) {
        if (frame == OPEN_AT) CgUiScreen.run(this::open);
        else if (frame == OPEN_AT + CLOSE_AFTER) CgUiScreen.run(this::close);
        else if (frame == OPEN_AT + CLOSE_AFTER + GONE_AFTER) {
            CgUiScreen.run(() -> check("open-close.closed", window() == null, KIND + "'s window is gone"));
            endTest();
        }
    }

    private void open() {
        Desktop desktop = CgUiScreen.desktop();
        ApplicationKind kind = desktop == null ? null : kind(desktop);
        if (kind == null) {
            fail("open-close.installed", "no application " + KIND + " is installed");
            return;
        }
        Button pinned = desktop.taskbar().pinEntryFor(kind);
        info("open-close.from", pinned != null ? "the taskbar" : "the launcher");
        if (pinned != null) pinned.onPressed.emit();
        else desktop.applications().launchInStartingWindow(kind, desktop.workspace());
        watchHandover(desktop);
    }

    private void close() {
        WindowFrame window = window();
        check("open-close.opened", window != null, KIND + "'s window opened");
        if (window != null) window.closeButton().onPressed.emit();
    }

    @Nullable
    private WindowFrame window() {
        Desktop desktop = CgUiScreen.desktop();
        if (desktop == null) return null;
        String app = WindowFrame.applicationClass(KIND);
        for (WindowFrame frame : desktop.windows()) if (frame.hasClass(app)) return frame;
        return null;
    }

    /**
     * Every document frame from the press until a starting window's handover is over, by phase: whether the mark stood
     * still while it left is the longest gap in {@code leave}. Its frames are the document's, so a frame the host
     * re-presents is a gap here.
     */
    private void watchHandover(Desktop desktop) {
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
            if (seen[0]) {
                info("open-close.handover", worst[0] + " ms longest frame while the mark left, " + worst[1]
                        + " ms after the press");
                CrystalGuiCore.LOGGER.info("{} handover frames (s starting, h waiting with file requests out, l leaving,"
                        + " x crossfade; ms since the press, phase, frame ms):{}", ID, frames);
            }
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

    @Nullable
    private static ApplicationKind kind(Desktop desktop) {
        for (ApplicationKind kind : desktop.applications().installed()) {
            if (kind.id().equals(KIND)) return kind;
        }
        return null;
    }
}
