package com.crystalgui.desktop.manager;

import com.crystalgui.core.window.WindowState;
import com.crystalgui.desktop.app.ApplicationKind;
import com.crystalgui.desktop.window.SnapZones;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.desktop.window.WindowRegistry;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIDocument;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The windows of one desktop as a set: stacking, the active window, placement, snapping, the HUD and
 * show-desktop. The shell draws frames; this decides which is in front, which has the keyboard, where a new
 * one goes and what a snap tiles. It owns no element: the work area and the visible frames come through its
 * {@link Shell}. (Windows' DWM keeps z-order and the foreground window apart from any window's chrome;
 * Chromium's {@code views::Widget} asks its {@code aura::WindowTreeHost} the same way.)
 *
 * <pre>{@code
 * WindowManager windows = desktop.windowManager();
 * windows.activate(frame);                 // raised, marked, focus restored
 * windows.snapFrameTo(frame, zone);        // tiled at the group's cuts
 * windows.toggleShowDesktop();             // Win+D, with its memory
 * }</pre>
 *
 * <p>A raise assigns a {@code z-index}; it never moves the frame in the child list, which would rebuild its
 * whole subtree on every click.</p>
 */
public final class WindowManager {

    /** What the manager needs of the shell that draws its windows. */
    public interface Shell {
        /** The work area's box, or null before layout. */
        @Nullable
        Box workArea();

        /** The frames on screen, in child order. */
        List<WindowFrame> visibleFrames();

        @Nullable
        UIDocument document();

        /** Hides or shows the taskbar, as a fullscreen window asks. */
        void setTaskbarVisible(boolean visible);
    }

    /** Where the pinned band starts, and the ceiling the normal band renormalises below. */
    public static final int PINNED_BAND = 1 << 20;

    /** Above every window, pinned ones included: a dialog is a question being put, a pin a standing preference. */
    public static final int DIALOG_BAND = PINNED_BAND << 1;

    /** How much of the work area a window takes when nothing sized it and nothing recorded it. */
    private static final float DEFAULT_WINDOW_FRACTION = 0.8f;

    private final Shell shell;
    private final WindowRegistry registry = new WindowRegistry();

    /** Hands out stacking order; monotonic, so a raise is O(1) and touches no other window. */
    private int raiseCounter;

    /** Windows the cascade has placed since it last wrapped (Win32's {@code CW_USEDEFAULT}). */
    private int cascadeStep;

    /** The window the keyboard is talking to, or null when the desktop itself has the press. */
    @Nullable
    private WindowFrame activeWindow;

    private final List<WindowFrame> hiddenForHud = new ArrayList<>();
    private boolean hudMode;

    /** The work area's cuts as fractions: one grid shared by every snapped window. */
    private float splitX = SnapZones.CENTRE_SPLIT;
    private float splitY = SnapZones.CENTRE_SPLIT;

    /** What the last show-desktop minimised, so a second one puts back exactly that. */
    private final List<WindowFrame> minimizedByShowDesktop = new ArrayList<>();

    public WindowManager(Shell shell) {
        this.shell = shell;
    }

    /** Every live window, visible or hidden: the model, not the tree. @see WindowRegistry */
    public WindowRegistry registry() {
        return registry;
    }

    /** Every live window in open order, hidden ones included: what the taskbar and the switcher offer a way back to. */
    public List<WindowFrame> windows() {
        return registry.windows();
    }

    /** The windows on screen, in child order: a live view, copy before adding or removing. */
    public List<WindowFrame> visibleWindows() {
        return shell.visibleFrames();
    }

    @Nullable
    public WindowFrame activeWindow() {
        return activeWindow;
    }

    private boolean owns(@Nullable WindowFrame frame) {
        return frame != null && frame.desktop() != null && frame.desktop().windowManager() == this;
    }

    // ── Stacking ────────────────────────────────────────────────────────────────────────────────

    /** The last stacking value handed out: what a window raised now would sit just above. */
    public int stackTop() {
        return raiseCounter;
    }

    /**
     * Brings a window to the front by assigning a {@code z-index}, never by moving it in the child list: a move
     * unregisters and re-registers the whole subtree, which a click on a widget must never do to itself. Paint
     * order and hit-testing both read z, so they cannot disagree about which window is on top.
     */
    public void raise(WindowFrame frame) {
        if (!owns(frame)) return;

        // The whole owner group moves, owner first and its owned windows just above it (Win32's rule):
        // raising an owned palette raises its document too.
        WindowFrame root = frame;
        for (WindowFrame walk = frame.ownerWindow(); walk != null; walk = walk.ownerWindow()) root = walk;

        List<WindowFrame> group = new ArrayList<>();
        for (WindowFrame candidate : shell.visibleFrames()) {
            if (candidate != root && ownedBy(candidate, root)) group.add(candidate);
        }
        group.sort(Comparator.comparingInt(WindowFrame::stackOrder));
        if (frame != root && group.remove(frame)) group.add(frame);

        if (raiseCounter >= PINNED_BAND - 2 - group.size()) renormaliseStack();

        // The band is an offset on the same counter, asked of the group's root so a pinned window's palette
        // rides into the band with it.
        int band = root.isPinned() ? PINNED_BAND : 0;
        root.setStackOrder(band + ++raiseCounter);
        for (WindowFrame owned : group) owned.setStackOrder(band + ++raiseCounter);
    }

    private static boolean ownedBy(WindowFrame frame, WindowFrame root) {
        for (WindowFrame walk = frame.ownerWindow(); walk != null; walk = walk.ownerWindow()) {
            if (walk == root) return true;
        }
        return false;
    }

    /** Re-spreads the stack over 1..n in its current order, so the counter cannot climb into the pinned band. */
    public void renormaliseStack() {
        List<WindowFrame> byDepth = new ArrayList<>(shell.visibleFrames());
        byDepth.sort(Comparator.comparingInt(WindowFrame::stackOrder));
        raiseCounter = 0;
        for (WindowFrame frame : byDepth) frame.setStackOrder(++raiseCounter);
    }

    // ── Activation ──────────────────────────────────────────────────────────────────────────────

    /**
     * Makes a window the active one: raised, marked, and given its focus back. Idempotent, because a press and
     * the focus owner moving into a window (Tab, a command, the switcher) both call it and neither suffices alone.
     */
    public void activate(WindowFrame frame) {
        activate(frame, false);
    }

    /** Activates for something the user did (a taskbar click, the switcher) and ends the window's attention flash. */
    public void activateByUser(WindowFrame frame) {
        if (!owns(frame)) return;
        frame.clearAttention();
        activate(frame, false);
    }

    /** @param programmatic see {@link WindowFrame#restoreFocus}: whether focus rings. */
    public void activate(WindowFrame frame, boolean programmatic) {
        activate(frame, programmatic, true);
    }

    /**
     * @param restoreFocus whether to put focus back where the window last had it; false for a press in its
     *                     content, which has already decided where focus goes
     */
    public void activate(WindowFrame frame, boolean programmatic, boolean restoreFocus) {
        if (!owns(frame)) return;
        // Activating a hidden window restores it, as a taskbar entry or the switcher means.
        if (frame.state() == WindowState.HIDDEN) frame.show(true);
        // Not clearing attention here: code activates freshly opened windows too. See activateByUser.
        // Using a window ends show-desktop's memory, as it does on Windows.
        minimizedByShowDesktop.clear();
        raise(frame);
        if (activeWindow != frame) {
            if (activeWindow != null) setGroupActive(activeWindow, false);
            activeWindow = frame;
            setGroupActive(frame, true);
        }
        // After the assignment: listeners re-render the model and must see the new active window.
        registry.activated(frame);
        if (restoreFocus) frame.restoreFocus(programmatic);
    }

    /**
     * Marks a window active and the window it belongs to with it: a tool window is part of its owner, so
     * focusing a floating panel must not grey the owner's caption (Windows and macOS agree). Old group off,
     * then new group on, so a window and its own panel hand over without a flicker.
     */
    private void setGroupActive(WindowFrame frame, boolean active) {
        frame.setActive(active);
        WindowFrame subject = frame.taskbarSubject();
        if (subject != frame) subject.setActive(active);
    }

    /** No window is active: what a press on bare desktop leaves behind. */
    public void deactivate() {
        if (activeWindow == null) return;
        setGroupActive(activeWindow, false);
        activeWindow = null;
        // Announced, or the taskbar keeps highlighting a window the keyboard has left.
        registry.changed();
    }

    /** Activates whatever is now in front. Only for an active window that was destroyed; a minimised one is still there. */
    public void activateTopmost() {
        WindowFrame front = null;
        for (WindowFrame frame : shell.visibleFrames()) {
            if (front == null || frame.stackOrder() >= front.stackOrder()) front = frame;
        }
        if (front != null) activate(front, true);
    }

    // ── HUD: pinned windows over the running game ───────────────────────────────────────────────

    /**
     * Puts the desktop on the HUD: every unpinned window is hidden, pinned ones keep running. Hiding detaches,
     * so nothing lays out or matches a selector, and the set is remembered so a window the user had already
     * minimised stays minimised on the way out.
     */
    public void enterHudMode() {
        if (hudMode) return;
        hudMode = true;
        hiddenForHud.clear();
        for (WindowFrame frame : new ArrayList<>(registry.windows())) {
            if (frame.isPinned() || frame.state() != WindowState.VISIBLE) continue;
            hiddenForHud.add(frame);
            frame.hide();
        }
        for (WindowFrame frame : registry.windows()) {
            if (frame.isPinned()) frame.addClass(WindowFrame.HUD_CLASS);
        }
    }

    /** Takes the desktop off the HUD, restoring exactly what {@link #enterHudMode} put away. */
    public void exitHudMode() {
        if (!hudMode) return;
        hudMode = false;
        for (WindowFrame frame : registry.windows()) frame.removeClass(WindowFrame.HUD_CLASS);
        for (WindowFrame frame : hiddenForHud) frame.show(true);
        hiddenForHud.clear();
    }

    public boolean isHudMode() {
        return hudMode;
    }

    /** Whether anything is pinned: what a host asks to choose between a suspend and a HUD. */
    public boolean hasPinnedWindows() {
        for (WindowFrame frame : registry.windows()) {
            if (frame.isPinned()) return true;
        }
        return false;
    }

    // ── Snapping: the tiled group ───────────────────────────────────────────────────────────────

    /** The work area's vertical cut, as a fraction. */
    public float splitX() {
        return splitX;
    }

    /** The work area's horizontal cut, as a fraction. */
    public float splitY() {
        return splitY;
    }

    /**
     * Snaps {@code frame} into {@code zone} at the group's current cuts. The one entry point, so the cuts and
     * the rect cannot disagree. A fresh group starts at halves: a divider must not outlive its group.
     */
    public void snapFrameTo(WindowFrame frame, SnapZones.Zone zone) {
        Box area = shell.workArea();
        if (frame == null || zone == null || area == null
                || area.width() <= 0f || area.height() <= 0f) return;
        if (!anySnappedBesides(frame)) {
            splitX = SnapZones.CENTRE_SPLIT;
            splitY = SnapZones.CENTRE_SPLIT;
        }
        float[] rect = SnapZones.rectFor(zone, area.width(), area.height(), splitX, splitY);
        frame.snapTo(zone, rect[0], rect[1], rect[2], rect[3]);
    }

    /**
     * Moves a shared divider and re-tiles everything it separates (Windows' {@code JointResize}). The state is
     * the cut, not the pair, so a four-window grid stays a grid and a corner handle moves both cuts. Applied
     * instantly: it runs per frame of a drag, and an ease would leave every window trailing the pointer.
     */
    public void jointResize(WindowFrame frame, int handleDx, int handleDy, float width, float height) {
        SnapZones.Zone zone = frame == null ? null : frame.snappedZone();
        Box area = shell.workArea();
        if (zone == null || area == null || area.width() <= 0f || area.height() <= 0f) return;

        boolean moved = false;
        if (zone.movesVerticalDivider(handleDx)) {
            splitX = SnapZones.splitFor(zone.xSide, frame.left(), width, area.width());
            moved = true;
        }
        if (zone.movesHorizontalDivider(handleDy)) {
            splitY = SnapZones.splitFor(zone.ySide, frame.top(), height, area.height());
            moved = true;
        }
        // An outer edge moves nothing else: pulling a window off its own border is not repartitioning.
        if (moved) applySnapLayout();
    }

    /** Re-tiles every snapped window from the current cuts, the dragged one included so the minimum split holds. */
    private void applySnapLayout() {
        Box area = shell.workArea();
        if (area == null || area.width() <= 0f || area.height() <= 0f) return;
        for (WindowFrame frame : registry.windows()) {
            SnapZones.Zone zone = frame.snappedZone();
            if (zone == null || frame.state() != WindowState.VISIBLE) continue;
            float[] rect = SnapZones.rectFor(zone, area.width(), area.height(), splitX, splitY);
            frame.resizeTo(rect[2], rect[3]);
            frame.moveTo(rect[0], rect[1]);
        }
    }

    private boolean anySnappedBesides(WindowFrame except) {
        for (WindowFrame frame : registry.windows()) {
            if (frame != except && frame.snappedZone() != null && frame.state() == WindowState.VISIBLE) {
                return true;
            }
        }
        return false;
    }

    // ── Show desktop and fullscreen ─────────────────────────────────────────────────────────────

    /**
     * Minimises everything, or puts back exactly what it minimised (Windows' {@code Win+D}). The memory makes
     * it a toggle: restoring everything would resurrect windows that were already minimised. Activating any
     * window forgets the memory.
     */
    public void toggleShowDesktop() {
        if (!minimizedByShowDesktop.isEmpty()) {
            List<WindowFrame> putBack = new ArrayList<>(minimizedByShowDesktop);
            minimizedByShowDesktop.clear();
            for (WindowFrame frame : putBack) {
                if (frame.state() == WindowState.HIDDEN) frame.show(true);
            }
            return;
        }
        for (WindowFrame frame : registry.windows()) {
            // A tool window hides with its owner; minimising it here would bring it back on its own.
            if (frame.isToolWindow() || frame.state() != WindowState.VISIBLE) continue;
            minimizedByShowDesktop.add(frame);
            frame.minimize();
        }
    }

    /** Whether the last show-desktop has not been undone. */
    public boolean isShowingDesktop() {
        return !minimizedByShowDesktop.isEmpty();
    }

    /** The taskbar follows whether any window is fullscreen: asked of the set, since two can be at once. */
    public void fullscreenChanged() {
        boolean anyFullscreen = false;
        for (WindowFrame frame : registry.windows()) {
            if (frame.isFullscreen() && frame.state() == WindowState.VISIBLE) {
                anyFullscreen = true;
                break;
            }
        }
        shell.setTaskbarVisible(!anyFullscreen);
    }

    // ── Placement ───────────────────────────────────────────────────────────────────────────────

    /**
     * Sizes an application's main window that nobody sized and nothing recorded: its kind's
     * {@link ApplicationKind#windowSize}, else {@value #DEFAULT_WINDOW_FRACTION} of the work area (Windows'
     * {@code CW_USEDEFAULT}). The size only; the cascade places it. With no measured work area yet it waits
     * for the first layout.
     */
    public void sizeByDefault(WindowFrame frame) {
        Box area = shell.workArea();
        if (area != null && area.width() > 0f && area.height() > 0f) {
            ApplicationKind kind = frame.application();
            boolean named = kind != null && kind.windowWidth() > 0f && kind.windowHeight() > 0f;
            float fraction = kind != null && kind.windowFraction() > 0f ? kind.windowFraction() : DEFAULT_WINDOW_FRACTION;
            float width = Math.round(Math.min(area.width(), named ? kind.windowWidth() : area.width() * fraction));
            float height = Math.round(Math.min(area.height(), named ? kind.windowHeight() : area.height() * fraction));
            frame.resizeTo(width, height);
            return;
        }
        UIDocument document = shell.document();
        if (document == null) return;
        document.animation().afterLayout(frame, delta -> {
            if (frame.isSized() || frame.state() == WindowState.DESTROYED) return false;
            Box laidOut = shell.workArea();
            if (laidOut == null || laidOut.width() <= 0f) return true;
            sizeByDefault(frame);
            return false;
        });
    }

    /**
     * Places a window nobody positioned: centred, then one caption height further down and across for each
     * window after it, wrapping when the next step would push it off the work area. Does nothing while the work
     * area or the frame is unmeasured; the layer asks again once layout has run.
     */
    public void placeByCascade(WindowFrame frame) {
        float step = frame.captionHeight();
        Box area = shell.workArea();
        Box frameBox = frame.box();
        float areaWidth = area == null ? 0f : area.width();
        float areaHeight = area == null ? 0f : area.height();
        float frameWidth = frameBox == null ? 0f : frameBox.width();
        float frameHeight = frameBox == null ? 0f : frameBox.height();
        if (step <= 0f || areaWidth <= 0f || areaHeight <= 0f || frameWidth <= 0f || frameHeight <= 0f) {
            return;
        }

        // Centred rather than the corner: every GuiContainer opens centred, and the corner is where an unplaced
        // window is drawn, so the two would look alike. Alone on the desktop there is nothing to cascade from.
        if (shell.visibleFrames().size() <= 1) cascadeStep = 0;
        float centreLeft = (areaWidth - frameWidth) / 2f;
        float centreTop = (areaHeight - frameHeight) / 2f;
        float offset = cascadeStep * step;
        if (centreLeft + offset + frameWidth > areaWidth || centreTop + offset + frameHeight > areaHeight) {
            cascadeStep = 0;
            offset = 0f;
        }
        cascadeStep++;
        frame.moveTo(Math.max(0f, centreLeft + offset), Math.max(0f, centreTop + offset));
    }
}
