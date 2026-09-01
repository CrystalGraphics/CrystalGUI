package com.crystalgui.workbench.toolwindow;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.core.window.WindowPolicy;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.overlay.Tooltip;
import com.crystalgui.desktop.window.WindowFrame;

import com.crystalgui.workbench.view.ViewContainer;
import javax.annotation.Nullable;

/**
 * A tool window in a window — what {@code FloatingDock} was trying to be, founded on the frame widget
 * instead of on {@code Dialog}.
 *
 * <h3>What was there before, and why it went</h3>
 *
 * <p>{@code FloatingDock extends Dialog} and opened its javadoc with <i>"Why this is not a second
 * window: VS Code's tear-out opens an actual OS window … Minecraft has one window, so none of that is
 * available and none of it is wanted."</i> That was true when it was written and CrystalOS made it
 * false: the workbench now sits in a real in-process window, and a second one costs a constructor.</p>
 *
 * <p>Three things were wrong with it beyond the premise, and they are worth keeping written down
 * because each is a shape that will look tempting again:</p>
 *
 * <ul>
 *   <li><b>It promoted itself to the top layer.</b> {@code show()} called {@code document().promote(this)}, and
 *       the top layer paints after the whole main tree — so a float torn out of one window would hover
 *       above whichever window was raised next. That is the exact failure {@code WindowFrame}'s owned
 *       surface exists to prevent, and it is invisible until there are two windows to get it wrong
 *       between.</li>
 *   <li><b>It inherited a bundle and then spent its javadoc disowning it.</b> Modality, light dismiss
 *       and Escape all had to be argued away in prose. A base class you have to write three paragraphs
 *       against is the wrong base class.</li>
 *   <li><b>Nothing ever called it.</b> Not core, not a test, not the harness — so none of the above was
 *       ever going to be found by running it.</li>
 * </ul>
 *
 * <h3>One header, because the container brings its own</h3>
 *
 * <p>{@link ViewContainer} already draws a header with the title, the view's contributed controls and a
 * hide button. Put in a frame naively that is <b>two headers</b> — the caption and the container's —
 * which is the problem client-side decorations exist to solve and which {@code WindowChrome} already
 * solves for the editor. So the container offers its header as caption chrome and {@code setContent}
 * <em>moves</em> it up into the caption, putting it back when the frame lets go.</p>
 *
 * <p>Which leaves the container's own hide ✕ sitting beside the frame's, doing the same thing. The
 * frame's is the one that stays: it is where every window in the system puts a close button, and the
 * container's is suppressed while adopted rather than removed, so the docked presentation is unchanged.
 * That is not the "hide yours" antipattern {@code WindowChrome} warns about — that warning is about
 * hiding a whole bar and leaving its listeners on an invisible copy. Here the bar is the thing being
 * moved, and one button inside it is redundant with a button four pixels away.</p>
 *
 * <h3>Dock, and what the other controls mean here</h3>
 *
 * <p>IntelliJ's floating tool window has a gear whose menu offers Dock/Undock/Float/Window, plus a hide
 * button. The gear is a menu because IntelliJ has five modes; three modes with one obvious destination
 * is a button — <b>Dock</b>, which returns the tool window to the region it belongs to. The region is
 * still recorded while it floats, so this needs no target chooser.</p>
 *
 * <p>So the caption offers <b>Dock and Hide</b>, which is IntelliJ's pair exactly, and the two window
 * controls a document frame has are turned off in the sheet.</p>
 *
 * <p><b>No close.</b> A document window's ✕ means something because a document can be finished with; a
 * tool window cannot. It is one of a fixed set that lives as long as the workbench does, its stripe
 * button is a permanent way back, and every route out of this frame — ✕, minimise, Escape, the taskbar
 * — ends in the same {@code hidePanel} anyway. A ✕ beside a Hide button is two marks for one verb, and
 * the more destructive-looking of the two is the one that is least true.</p>
 *
 * <p><b>No maximise.</b> A tool window's job is to sit beside the work; one filling the desktop is not
 * a thing anybody reaches for, and on an owned float it could not be un-maximised from a taskbar it has
 * no entry in.</p>
 *
 * <p><b>Which leaves minimise as Hide</b>, needing no new widget: its glyph is a single centred rule —
 * the mark every window manager draws for hide — and it already routes through {@code hide()} and
 * {@code onHidden}. The policy stays {@link WindowPolicy#HIDE_ON_CLOSE} because the routes that do not
 * go through a button (Escape, a taskbar close) must mean hide too, and because a destroyed container
 * would lose its view state.</p>
 */
public class ToolWindowFrame extends WindowFrame {

    /** On any tool window's frame, whichever mode it is in. */
    public static final String TOOL_WINDOW_CLASS = "__tool-window__";

    /** On a frame owned by the workbench's window — see {@link ToolWindowType#FLOATING}. */
    public static final String FLOATING_CLASS = "__floating__";

    /** On a top-level tool window frame — see {@link ToolWindowType#WINDOWED}. */
    public static final String WINDOWED_CLASS = "__windowed__";

    /** The "put it back in its region" affordance. */
    public static final String DOCK_CLASS = "__dock__";

    /** What it says on hover, beside the window controls it sits with. @see WindowFrame#MINIMIZE_TOOLTIP */
    public static final String DOCK_TOOLTIP = "Dock";

    /** Where a float first appears when nothing has been remembered, in logical pixels. */
    public static final float DEFAULT_WIDTH = 320f;
    public static final float DEFAULT_HEIGHT = 240f;

    private final String typeId;
    private final String windowTitle;
    private final ViewContainer container;
    private ToolWindowType mode = ToolWindowType.FLOATING;

    /** Dock pressed — put this tool window back in its region. */
    public final Signal.Action onDockRequested = new Signal.Action();

    public ToolWindowFrame(String typeId, String title, ViewContainer container) {
        super(title);
        this.typeId = typeId;
        this.windowTitle = title;
        this.container = container;
        addClass(TOOL_WINDOW_CLASS);
        setPolicy(WindowPolicy.HIDE_ON_CLOSE);
        // WS_EX_TOOLWINDOW: no taskbar entry, no switcher entry, and it hides and shows with whatever
        // owns it. This is what makes the caption's Dock-and-Hide pair honest -- a window with no way
        // back from a taskbar must not have controls that need one. @see WindowFrame#isToolWindow()
        setToolWindow(true);
        // Keyed, so a session restore can find the frame that belongs to this tool window rather than
        // opening a second one beside it. The key is NOT a claim on the desktop's record -- a tool
        // window's geometry is per project and lives in ToolWindowState. @see DesktopSession
        setKey("toolwindow:" + typeId);

        // BEFORE the window controls, which is where IntelliJ's gear sits and where every toolkit puts
        // an application's own caption actions: the window's own buttons are the rightmost thing in a
        // caption on every desktop, and a Dock button landing outside them would read as one of them.
        Button dock = new Button("");
        dock.addClass(DOCK_CLASS);
        dock.onPressed.connect(onDockRequested::emit);
        Tooltip.attach(dock, DOCK_TOOLTIP);
        controls().insertAt(0, dock);

        setContent(container);
        setMode(ToolWindowType.FLOATING);
        // EMPTIED, NOT HIDDEN, and the distinction is the whole drag region. The adopted header already
        // names this tool window, so the caption's own label would be the same word twice; but the label
        // is also the ONLY pointer-transparent element in the caption, which makes it the one thing a
        // press can fall through to reach the title bar's move gesture. Take it out of the layout and
        // the window cannot be dragged at all. So it stays, full width and empty, and getTitle() answers
        // from the field instead.
        setTitle("");
    }

    /**
     * {@code window} — the same tag its superclass answers, and the one case where that is right.
     *
     * <h3>A widget's cascade identity is its TAG, never its Java supertype</h3>
     *
     * <p>{@code ElementRegistry.tagOf} is an <b>exact class</b> lookup, so an unregistered subclass falls
     * back to its own lowercased name — {@code toolwindowframe} here. Every rule in {@code ua/desktop.css}
     * is scoped through {@code window}, so a float matched <em>none</em> of them: no background, no border,
     * unstyled controls, and both the caption's title and the adopted header's showing at once, because
     * the rule that collapses the first is a {@code window} rule too. It reads as the frame not having
     * been built rather than as the frame not being styled.</p>
     *
     * <p>The engine's standing example is the opposite case and the contrast is the point.
     * {@code Dropdown extends Button} and deliberately does <b>not</b> answer {@code button}, because a
     * dropdown taking a button's whole look wholesale is wrong — it needs its own. A tool window's frame
     * wants a window's look <em>entirely</em>, plus a modifier class for the handful of differences
     * ({@link #TOOL_WINDOW_CLASS}). When the answer is "everything, plus", the tag is the supertype's.</p>
     *
     * <p>Safe against the registry's tag↔class bijection because nothing decodes one of these: a float is
     * shell state rebuilt from {@link ToolWindowState}, never from a serialised description, so
     * {@code window} decoding to a plain {@code WindowFrame} is a question that is never asked.</p>
     */
    /**
      * Its own kind, and the {@code window} rules still reach it — through
      * {@link WindowFrame#WINDOW_CLASS}, not through the tag.
      *
      * <p>The comment this replaces argued, correctly for the old engine, that answering
      * {@code "window"} was safe here because a tool window is never decoded from a description. The
      * new engine's objection is different and is not about decoding: a {@code Name} is bound to a
      * FACTORY, so two classes claiming one name is ambiguous whether or not anybody decodes it, and
      * {@code NodeKindsCoverageTest} fails the commit either way.</p>
      */
    public static final Name NAME = Name.of("toolwindowframe");

    /**
     * The tool window's name — from the field, because the caption's label is deliberately empty.
     *
     * <p>{@code WindowFrame.getTitle()} reads the label, and emptying the label to stop the caption
     * saying "Inspector" twice would leave the window nameless everywhere else — a tooltip, a preview,
     * the message an illegal reopen throws. It no longer has a taskbar entry to be blank in, but a
     * window with no name is a debugging expense whatever is looking at it.</p>
     */
    @Override
    public String getTitle() {
        return windowTitle;
    }

    /** Which tool window this frame is showing. */
    public String typeId() {
        return typeId;
    }

    /** The container it hosts — the same instance the region shows when this is docked back. */
    public ViewContainer container() {
        return container;
    }

    public ToolWindowType mode() {
        return mode;
    }

    /**
     * Switches between the two windowed modes.
     *
     * <p>A class rather than a rebuild, because the frame is the same widget either way — what changes
     * is who owns it, and that is the caller's business. {@link ToolWindowType#DOCKED} is refused: a
     * docked tool window has no frame at all, so asking a frame to be docked is asking it to stop
     * existing, which is {@code ToolWindowManager}'s call and not a restyle.</p>
     */
    public void setMode(ToolWindowType nowMode) {
        if (nowMode == null || nowMode == ToolWindowType.DOCKED) return;
        this.mode = nowMode;
        removeClass(FLOATING_CLASS);
        removeClass(WINDOWED_CLASS);
        addClass(nowMode == ToolWindowType.FLOATING ? FLOATING_CLASS : WINDOWED_CLASS);
    }

    /** The last measurement taken while this frame actually had a box. @see #bounds() */
    @Nullable
    private ToolWindowState.Bounds lastMeasured;

    /**
     * Snapshots the geometry <b>before</b> leaving the tree.
     *
     * <p>The whole reason this override exists. {@code hide()} detaches and <em>then</em> emits
     * {@code onHidden}, which is how the manager learns to record where the float was — so by the time
     * it asks, the frame is out of the tree and its Taffy node has been freed. It measures zero, that
     * zero is written into {@link ToolWindowState#floatingBounds()}, and the next tear-out restores a
     * window with no size.</p>
     *
     * <p>Invisible from the manager's own path: calling {@code hidePanel} directly reads the box while
     * the frame is still attached and gets the right answer. Only the frame's own Hide button — which is
     * the way a user does it — goes through the detach first. A test that drives the manager passes
     * against the broken build.</p>
     */
    @Override
    public void hide() {
        measure();
        super.hide();
    }

    /**
     * Its geometry, for {@link ToolWindowState#floatingBounds()} — never a zero box.
     *
     * <p>Falls back to the last measurement taken while it had one, and to the defaults if it never did.
     * A zero here is not a small window, it is a window that cannot be seen or grabbed, and the caller
     * cannot tell the two apart from four floats.</p>
     */
    public ToolWindowState.Bounds bounds() {
        measure();
        return lastMeasured != null ? lastMeasured
                : new ToolWindowState.Bounds(getWantedLeft(), getWantedTop(), DEFAULT_WIDTH, DEFAULT_HEIGHT);
    }

    private void measure() {
        float width = box().width();
        float height = box().height();
        if (width > 0f && height > 0f) {
            lastMeasured = new ToolWindowState.Bounds(getWantedLeft(), getWantedTop(), width, height);
        }
    }
}
