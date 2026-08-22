package com.crystalgui.ui.elements.dock;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.Tab;
import com.crystalgui.ui.input.UIInputHandler;
import com.crystalgui.ui.elements.desktop.WindowFrame;
import com.crystalgui.ui.elements.desktop.WindowPolicy;
import com.crystalgui.ui.elements.desktop.WindowState;
import com.crystalgui.ui.tree.UITreeTraversal;

/**
 * A window hosting a dock area of its own — where an editor tab lands when it is torn out (W9).
 *
 * <h3>A peer, not an accessory</h3>
 *
 * <p>Deliberately <b>not</b> owned by the window it came from, which is the opposite of what a floating
 * tool window is ({@code ToolWindowFrame} sets {@code setOwnerWindow}). The two differ in what they
 * <em>are</em>: a tool window is an accessory to the thing it was pulled out of and belongs above it,
 * while a torn-out document is a second place to work — a peer. Clicking one should bury the other,
 * exactly as two document windows do on every desktop, and IntelliJ's detached editor and VS Code's
 * auxiliary window are both independent for the same reason.</p>
 *
 * <h3>It needed almost no new machinery, and that is the Design B payoff</h3>
 *
 * <p>Dragging a tab <em>between</em> areas already worked before this class existed:
 * {@code DockArea.detach} reads {@code payload.sourceArea()} and removes the panel from <b>that</b> area
 * before inserting it here, so a drop is cross-area by construction. Every window shares one
 * {@code UIWindow}, one {@code UIDragController} and one hit-test, so a drag out of this window and back
 * into the main one is the same gesture the dock has always handled.</p>
 *
 * <p>What W9 adds is only the case where <em>nobody</em> accepted the drop — which is the same hook W8's
 * tool-window tear-out uses, on the drag source's {@code onDragEnd}, and for the same reason: a release
 * over the desktop is dispatched to {@code Desktop}, which knows nothing about docks.</p>
 *
 * <h3>It closes when it empties</h3>
 *
 * <p>A window holding no documents is a window with nothing in it, and the way back is the tab that left.
 * Checked on the layout's own announcement rather than per frame, and only on {@code DESTROY_ON_CLOSE}
 * terms — there is nothing to retain, so hiding it would leave a taskbar entry for an empty dock.</p>
 *
 * <p><b>Not while a drag is live.</b> An empty dock is a legitimate transient state <em>during</em> a
 * drag — the panel has been detached and not yet dropped — and a window that closed itself mid-gesture
 * would take the drop target with it. {@code FloatingDock.closeIfEmpty} recorded exactly this hazard
 * before it was deleted, and it is the one piece of that class worth carrying forward.</p>
 */
public class DockWindow extends WindowFrame {

    /** On the frame, so a sheet can tell a document window from a tool window's. */
    public static final String DOCK_WINDOW_CLASS = "__dock-window__";

    /** Where a torn-out editor appears when nothing says otherwise, in logical pixels. */
    public static final float DEFAULT_WIDTH = 480f;
    public static final float DEFAULT_HEIGHT = 360f;

    private final DockArea area;

    public DockWindow(DockPanelRegistry<UIElement> registry, DockLayout layout, String title) {
        super(title);
        addClass(DOCK_WINDOW_CLASS);
        // DESTROY, not hide: a torn-out window's content is documents that live in OpenDocuments, and
        // the window itself holds nothing worth keeping once its last tab has gone.
        setPolicy(WindowPolicy.DESTROY_ON_CLOSE);

        this.area = new DockArea(registry, layout);
        StyleGroup.defaultPipeline(area.getStyle().getLayoutGroup(),
                l -> l.widthPercent(100f).height(0).flexGrow(1f));
        setContent(area);
        resizeTo(DEFAULT_WIDTH, DEFAULT_HEIGHT);
        // A LAYOUT CHANGE IS THE ONLY MOMENT THIS CAN BECOME TRUE, so it is the only moment worth
        // asking. Dragging the last tab back into the main editor detaches it from this area, which is
        // a layout change here -- and leaves a window with nothing in it and no way to get anything
        // back into it.
        area.onDidChangeLayout.connect(this::closeIfEmptyOnceIdle);
        // ...AND THE MOMENT THERE IS SOMETHING TO FOCUS. A dock builds its groups on a rebuild, which is
        // a frame later than the activation that focused this window -- see handOnFocus.
        area.onDidChangeLayout.connect(this::handOnFocus);
    }

    /**
     * {@code window} — see {@code ToolWindowFrame.tagName()} for the whole argument.
     *
     * <p>Short version: {@code ElementRegistry.tagOf} is an exact-class lookup, so an unregistered
     * subclass matches none of the {@code window} rules that make a frame look like one. This wants a
     * window's appearance entirely, plus {@link #DOCK_WINDOW_CLASS} for anything that differs.</p>
     */
    @Override
    public String tagName() {
        return "window";
    }

    /** The dock inside it — the same widget the main workbench uses, with its own layout. */
    public DockArea area() {
        return area;
    }

    /**
     * The editor in the active tab — never the dock that holds it.
     *
     * <p>{@code DockArea} is focusable so that commands resolve against it, which makes it the first
     * thing the default walk finds and therefore a wall that focus cannot get past. See
     * {@link WindowFrame#focusDelegate()} for the whole account; the short version is that a window
     * torn out of another one took focus away from it and then could not be typed into, because a
     * keystroke dispatched at the {@code DockArea} never reaches the editor inside it.</p>
     *
     * <p>Falls back to the walk when there is nothing to select — an empty dock, or a panel whose
     * content holds nothing focusable. The frame's own last-resort is itself, which is legal because a
     * frame is {@code CLICK_NOT_TABBABLE} rather than {@code NONE}.</p>
     */
    @Override
    protected UIElement focusDelegate() {
        DockGroup group = area.activeGroup();
        Tab tab = group == null ? null : group.tabView().getSelectedTab();
        UIElement inside = tab == null ? null : UITreeTraversal.firstFocusableIn(tab.content());
        return inside != null ? inside : super.focusDelegate();
    }

    /**
     * Passes focus on to the editor once the dock has built one.
     *
     * <h3>The window is focused a frame before it has any content</h3>
     *
     * <p>{@code openWindow} raises the frame and activates it, and activation focuses — but a
     * {@code DockArea} builds its groups on a <b>rebuild</b>, which is deferred to the next frame. So at
     * the only moment {@link #focusDelegate()} is asked, {@code activeGroup()} is null, there is no tab
     * and no content, and the delegate has no choice but to fall back to the dock itself. Which is the
     * bug the delegate exists to prevent, arriving through the back door.</p>
     *
     * <p>So the answer is given twice: once at activation, and again the first time the layout says
     * there is something to give it to.</p>
     *
     * <p><b>It hands focus on; it never takes it.</b> The guard is that focus is sitting on the dock
     * ITSELF, which is the one place it can be that is never useful — a {@code DockArea} is focusable so
     * that commands resolve against it, and a keystroke dispatched there reaches nothing, since events
     * travel root→target→root and the editor is a descendant. Focus anywhere else, including inside a
     * panel, is left exactly where it is. Same rule as {@code ListView.restoreFocusIfRealised}: reattach
     * what was lost, never take what somebody else has.</p>
     *
     * <p>Pointer focus rather than programmatic, so it does not ring: every route into a dock window
     * today is a drag, and a ring on the content of a window that has just appeared under the cursor is
     * exactly the noise {@code :focus-visible} exists to keep out of a mouse gesture.</p>
     */
    private void handOnFocus() {
        UIWindow window = getAttachedWindow();
        if (window == null) return;
        UIInputHandler input = window.getInputHandler();
        if (input.getFocusedElement() != area) return;
        UIElement wanted = focusDelegate();
        if (wanted != null && wanted != area) input.requestPointerFocus(wanted);
    }

    /** Whether this window still holds a panel. @see #closeIfEmpty */
    public boolean isEmpty() {
        for (DockLeaf leaf : area.layout().leaves()) {
            if (!leaf.isEmpty()) return false;
        }
        return true;
    }

    /**
     * Destroys the window if its last panel has gone.
     *
     * @return whether it closed
     */
    public boolean closeIfEmpty() {
        if (!isEmpty()) return false;
        destroy();
        return true;
    }

    /**
     * Closes when empty — but never while a drag is still running.
     *
     * <p>An empty dock is a legitimate transient state <em>during</em> a drag: the panel has been
     * detached from this area and not yet dropped anywhere. A window that closed itself at that moment
     * would take the drag's own source out of the tree mid-gesture, which cancels the drag and loses
     * the panel. So the decision is made here and the ACT is deferred to the first frame after the
     * pointer is released.</p>
     *
     * <p>The ticker returns false as soon as it has acted, and also if the window is destroyed by any
     * other route — a ticker whose element has left the tree must stop, and registration is one-way.</p>
     */
    private void closeIfEmptyOnceIdle() {
        if (!isEmpty() || closePending) return;
        UIWindow window = getAttachedWindow();
        if (window == null) return;
        closePending = true;
        window.registerTicker(delta -> {
            if (state() == WindowState.DESTROYED) return false;
            if (window.getInputHandler().getDragController().isDragging()) return true;
            closePending = false;
            closeIfEmpty();
            return false;
        });
    }

    private boolean closePending;
}
