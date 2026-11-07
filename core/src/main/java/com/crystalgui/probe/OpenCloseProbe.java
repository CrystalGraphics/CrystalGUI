package com.crystalgui.probe;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.app.ApplicationKind;
import com.crystalgui.desktop.host.HostSession;
import com.crystalgui.desktop.window.WindowFrame;
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
