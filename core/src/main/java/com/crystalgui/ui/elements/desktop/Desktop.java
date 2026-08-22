package com.crystalgui.ui.elements.desktop;

import com.crystalgui.core.signal.ConnectionGroup;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.TaffyPosition;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The compositor host — CrystalOS's desktop, and the parent of every {@link WindowFrame}.
 *
 * <p><b>Nobody constructs one.</b> Every {@code UIWindow} owns a desktop and hands it out through
 * {@code UIWindow.desktop()}; opening a UI is {@code window.openWindow(frame)} and nothing else. That
 * is the same ownership {@code UIWindow.windowOverlayLayer()} already has — an engine-owned layer built
 * on first use, which is observably "always there" while costing nothing to a window that never opens
 * one. A compositor that each application had to assemble for itself would be a compositor each
 * application assembled slightly differently.</p>
 *
 * <p>One desktop per {@code UIWindow}, which is the display surface rather than a window
 * ({@code plan_windowing.md}, Design B). Everything a window manager needs already existed at the
 * element layer — stacking that paint and hit-testing agree on, clipping, focus, drag, pointer capture —
 * so what is genuinely new is this: somewhere for frames to live, a work area to bound them, and a
 * placement rule for a window nobody positioned.</p>
 *
 * <h3>It sits over the root, in the windows band</h3>
 * <p>The desktop is an internal child of the window's root element, filling it, above whatever the root
 * already held. That IS the band model: <i>desktop content</i> (the root's own children) &lt;
 * <i>windows</i> (this) &lt; <i>pinned</i> (W14) &lt; the global top layer, which paints after the whole
 * main tree by construction. Its geometry is written from Java at <b>IMPORTANT</b> for the same reason
 * the overlay layer's is: the surface the compositor draws on must not be movable by a stylesheet.</p>
 *
 * <p><b>Zero-sized until a window exists</b>, which is what keeps it free rather than merely cheap.
 * An always-full-size overlay would sit in front of the root's own content and swallow every click that
 * missed a window — so it takes up no space and hit-tests nothing at all until there is genuinely a
 * compositor to be in front. Once a window IS open, clicks on bare desktop belong to the desktop (that
 * is what W2's empty-desktop blur means), and W7 removes the question entirely by making the editor
 * itself a frame.</p>
 *
 * <p><b>Exists, not is visible.</b> A desktop whose every window is minimised is a desktop in use: it
 * still has a taskbar, and that strip is the only way any of those windows comes back. Keying this on
 * the window layer instead collapsed the whole desktop the moment the last window was minimised — see
 * {@code syncPresence}.</p>
 *
 * <h3>The window layer is internal; the frames on it are public</h3>
 * <p>Exactly {@code UIWindow.windowOverlayLayer}'s arrangement, and for the same reason: a layer added
 * with {@code addInternalChild} may live under a root that accepts no children, while the frames added
 * to <em>it</em> stay ordinary public children — so a window can still remove itself
 * ({@code removeChild} silently refuses an internal child, and returns a boolean nobody checks).</p>
 *
 * <p>That relies on an ordering the engine states as a trap: {@code markAsInternal()} <b>recurses</b>,
 * so children a container already had when it was made internal become internal too. The window layer
 * is added in the constructor — before {@code UIWindow} attaches this desktop — and every frame arrives
 * afterwards, which is exactly the side of that rule a frame needs to be on.</p>
 *
 * <h3>The layer's box IS the work area</h3>
 * <p>The taskbar (W4) is <b>laid out</b> as a bottom bar rather than overlaid, so the space left for
 * windows needs no bar-shaped special case anywhere: maximise fills the layer, drags clamp at it, and
 * W13's fullscreen hiding the bar simply re-flows the layer to full height. Windows' own model —
 * maximise respects the taskbar, fullscreen covers it — falls out of the flex column.</p>
 *
 * <h3>What is deliberately not here yet</h3>
 * <p>Raise-on-click, the active window and empty-desktop blur are W2; the taskbar is W4. <b>When raise
 * arrives it must be a {@code z-index} assignment and never a child-list move</b>: {@code removeChild}/
 * {@code addChild} run {@code unregisterElement}/{@code registerElement} over the whole frame subtree —
 * session capture, modal and popover stack pops, every Taffy node destroyed and rebuilt — and a raise
 * happens on a click, which is precisely when a widget must never rebuild the elements it is being
 * clicked on. {@code sortedChildren} already keeps paint order and hit-testing agreeing by z.</p>
 */
public class Desktop extends UIElement {

    /** The layer frames live on, and the work area they are bounded by. */
    public static final String WINDOW_LAYER_CLASS = "__windows__";

    private final WindowLayer windows = new WindowLayer();

    /**
     * How many windows the cascade has placed since it last wrapped — Win32's {@code CW_USEDEFAULT},
     * which offsets each successive window by a caption height and starts over when it walks off.
     */
    private int cascadeStep;

    /**
     * {@code UIWindow} builds the one desktop a window has; {@code UIWindow.desktop()} is how to reach
     * it. Public because the tag registry needs a factory and a test needs to be able to make one, not
     * because an application should.
     */
    public Desktop() {
        StyleGroup.defaultPipeline(getStyle().getLayoutGroup(),
                l -> l.flexDirection(FlexDirection.COLUMN));
        // THE CLASS IS THE WHOLE OF THE LAYER'S GEOMETRY. Without it `desktop .__windows__` matches
        // nothing, the work area sizes to content, and its children are all absolutely positioned --
        // so it measures 0x0 and every rule that reads the work area quietly stands down instead of
        // failing: no clamp, no cascade, windows written wherever they were asked to go.
        windows.addClass(WINDOW_LAYER_CLASS);
        addInternalChild(windows);
        // AFTER the layer, so the strip is laid out below it. That order IS the work area: the taskbar is
        // laid out rather than overlaid, so what is left for windows needs no bar-shaped subtraction
        // anywhere -- maximise (W6) fills the layer, drags clamp at it, and W13's fullscreen hiding the
        // bar simply re-flows the layer to full height.
        taskbar = new Taskbar();
        addInternalChild(taskbar);

        // DRIVEN BY THE REGISTRY, not by the layer. The layer never sees a window destroyed while it was
        // already hidden, and it sees a hide as a removal -- so hanging presence off it both missed the
        // moment the surface should be given back and gave it back while windows were still retained.
        registry.onDidChange.connect(this::syncPresence);
        syncPresence();

        // A PRESS ON BARE DESKTOP DEACTIVATES. Target-only on both elements, so a press inside a window
        // never reaches it -- and both are needed rather than one: the window layer covers the desktop
        // entirely, so bare background is nearly always the LAYER's hit, while the desktop itself is
        // what remains once the taskbar (W4) shortens the layer.
        onMouseDown.attachListener((element, event) -> deactivate(), false, false);
        windows.onMouseDown.attachListener((element, event) -> deactivate(), false, false);
    }

    /**
     * Fills the root while any window <b>exists</b>, and takes up no space at all while none does.
     *
     * <p><b>IMPORTANT origin</b>, matching {@code UIWindow.windowOverlayLayer}: this is the compositor's
     * own surface, and a stylesheet that could move or resize it could put every window somewhere the
     * clamp does not agree with. Everything else about a desktop — what it paints, what its taskbar
     * looks like — stays in {@code ua/desktop.css} where a theme can reach it.</p>
     *
     * <p>The empty case is not an optimisation. A full-size overlay hit-tests, so an empty desktop
     * sitting over an application's own root would eat every click that landed on bare background —
     * a UI that had never opened a window would simply stop responding, and nothing about the symptom
     * would point here.</p>
     *
     * <h3>"Live" is the REGISTRY, never the window layer</h3>
     *
     * <p>Written against the layer first, and it is the same mistake the taskbar exists to correct, one
     * level down: the layer holds the <em>visible</em> windows, so minimising the last one emptied it
     * and collapsed the whole desktop to 0×0 in the corner — <b>taking the taskbar with it</b>. Every
     * window was retained and there was no longer anything on screen to bring one back with, which is
     * precisely the failure W3 and W4 ship together to prevent. Reported from the harness as "the bar
     * goes off screen to the top left", which is exactly what a zero-sized desktop at the origin looks
     * like.</p>
     *
     * <p>A desktop with a hidden window is a desktop in use. The zero-sized case is for one that has
     * never held a window at all — and once every window is genuinely gone, the surface goes back.</p>
     */
    private void syncPresence() {
        boolean live = isLive();
        StyleGroup.importantPipeline(getStyle().getLayoutGroup(), l -> {
            l.positionType(TaffyPosition.ABSOLUTE).left(0).top(0);
            if (live) l.widthPercent(100f).heightPercent(100f);
            else l.width(0).height(0);
        });
    }

    /** Whether any window <b>exists</b>, visible or hidden — i.e. whether the compositor is the surface. */
    public boolean isLive() {
        return !registry.windows().isEmpty();
    }

    // ── Stacking and activation ─────────────────────────────────────────────

    /**
     * The offset the pinned band (W14) sits at, and therefore the ceiling the normal band renormalises
     * below. Always-on-top is <b>one addition</b> in this scheme — which is what "the band model absorbs
     * it without redesign" meant when always-on-top was refused for having no consumer.
     */
    static final int PINNED_BAND = 1 << 20;

    /** Hands out stacking order. Monotonic, so a raise is O(1) and never touches another window. */
    private int raiseCounter;

    /** Every live window, visible or hidden. @see WindowRegistry */
    private final WindowRegistry registry = new WindowRegistry();

    /** The strip along the bottom. @see Taskbar */
    private final Taskbar taskbar;

    @Nullable
    private WindowFrame activeWindow;

    /** The window the keyboard is talking to, or null when the desktop itself has the press. */
    @Nullable
    public WindowFrame activeWindow() {
        return activeWindow;
    }

    /**
     * Brings a window to the front — <b>by assigning a {@code z-index}, never by moving it in the child
     * list.</b>
     *
     * <p>This is the rule the whole design rests on, and the obvious implementation is the trap. Moving
     * a frame to the end of the layer's children runs {@code removeChild}/{@code addChild}, which is
     * {@code unregisterElement}/{@code registerElement} over the <b>entire frame subtree</b>: session
     * state captured and re-applied, modal, popover and close-watcher stacks popped, every Taffy node
     * destroyed and rebuilt. Per click. And a widget must never rebuild the elements it is being clicked
     * on — the invariant that froze the table header — which is precisely what a raise is.</p>
     *
     * <p>{@code sortedChildren} then does the rest with no new machinery: it orders by z descending and
     * painting walks it reversed, so paint order and hit-testing cannot disagree about which window is
     * on top. That agreement is the invariant this must not re-implement.</p>
     */
    public void raise(WindowFrame frame) {
        if (frame == null || frame.desktop() != this) return;

        // THE WHOLE OWNER GROUP MOVES, owner first and its owned windows immediately above it — Win32's
        // rule, and the reason a torn-out tool window does not fall behind the editor the moment the
        // editor is clicked. Raising an OWNED window raises its owner too rather than lifting it out of
        // the group: on every desktop, clicking a palette brings its document forward with it.
        //
        // Expressed as z-index over a group, not as a band. A band ("always on top") is a bigger claim
        // than the one wanted here -- it would put a panel above windows it has nothing to do with, and
        // PINNED_BAND above is reserved for the case where that IS the point.
        WindowFrame root = frame;
        for (WindowFrame walk = frame.ownerWindow(); walk != null; walk = walk.ownerWindow()) root = walk;

        List<WindowFrame> group = new ArrayList<>();
        for (WindowFrame candidate : windows.frames) {
            if (candidate != root && ownedBy(candidate, root)) group.add(candidate);
        }
        // Their existing order is kept, except that the one actually raised ends up on top of its group.
        group.sort(Comparator.comparingInt(WindowFrame::stackOrder));
        if (frame != root && group.remove(frame)) group.add(frame);

        if (raiseCounter >= PINNED_BAND - 2 - group.size()) renormaliseStack();
        root.setStackOrder(++raiseCounter);
        for (WindowFrame owned : group) owned.setStackOrder(++raiseCounter);
    }

    /** Whether {@code frame} is owned by {@code root}, directly or through a chain of owners. */
    private static boolean ownedBy(WindowFrame frame, WindowFrame root) {
        for (WindowFrame walk = frame.ownerWindow(); walk != null; walk = walk.ownerWindow()) {
            if (walk == root) return true;
        }
        return false;
    }

    /**
     * Re-spreads the stack over 1..n in its current order, so the counter cannot climb into the pinned
     * band above it.
     *
     * <p>Unreachable by hand — nobody raises a window a million times — which is exactly why it is
     * written to be called directly by a test rather than only by the counter. A renormalisation that
     * has never run is a renormalisation that reorders the desktop the first time it does. Public for
     * that reason and for the same one the constructor is, rather than because an application has any
     * business calling it.</p>
     */
    public void renormaliseStack() {
        List<WindowFrame> byDepth = new ArrayList<>(windows.frames);
        byDepth.sort(Comparator.comparingInt(WindowFrame::stackOrder));
        raiseCounter = 0;
        for (WindowFrame frame : byDepth) frame.setStackOrder(++raiseCounter);
    }

    /**
     * Makes a window the active one: raised, marked, and given its focus back.
     *
     * <p>Idempotent, because it is called from two directions that legitimately overlap — a press in the
     * capture phase of a frame, and the focus owner moving into one (Tab, a command, W10's switcher).
     * Neither is sufficient alone: a right-click moves no focus at all ({@code emitMouseDown} keeps the
     * focus owner for a non-primary button), and Tab moves focus without any press.</p>
     */
    public void activate(WindowFrame frame) {
        activate(frame, false);
    }

    /** @param programmatic see {@link WindowFrame#restoreFocus} — it decides whether focus rings. */
    public void activate(WindowFrame frame, boolean programmatic) {
        if (frame == null || frame.desktop() != this) return;
        // ACTIVATING A HIDDEN WINDOW RESTORES IT, which is what a taskbar entry does (W4) and what a
        // switcher does (W10) -- both of them "activate", and a minimised window has to come back for
        // that to mean anything. Restored as PERSISTED: it is coming out of retention, which is exactly
        // the distinction the flag carries.
        if (frame.state() == WindowState.HIDDEN) frame.show(true);
        raise(frame);
        if (activeWindow != frame) {
            if (activeWindow != null) activeWindow.setActive(false);
            activeWindow = frame;
            frame.setActive(true);
        }
        // AFTER the assignment above, not before it. This emits, and what listens re-renders the whole
        // model -- so announcing while `activeWindow` still points at the previous window highlights the
        // wrong entry until the next unrelated change happens to correct it.
        registry.activated(frame);
        frame.restoreFocus(programmatic);
    }

    /**
     * No window is active — the state a press on bare desktop leaves behind.
     *
     * <p>A legal state, not a degenerate one: {@code emitMouseDown} blurs before it dispatches and
     * nothing on the desktop takes the focus it gave up, so "clicking the background deselects" is
     * already what the input layer does. This is the chrome catching up with it.</p>
     */
    public void deactivate() {
        if (activeWindow == null) return;
        activeWindow.setActive(false);
        activeWindow = null;
        // AND SAY SO. Everything that renders the registry renders the active window as part of it, and
        // this is the one mutation that used to happen in silence -- so the taskbar went on highlighting
        // the last window to have been active after it was minimised or the desktop was clicked. A
        // highlight is a claim about where the keyboard is going; a stale one is a lie that persists,
        // because nothing else was going to re-render the strip.
        registry.changed();
    }

    /**
     * Activates whatever is now in front, or nothing if the desktop is empty.
     *
     * <p>Called when the active window was <b>destroyed</b> and only then — see the note in the layer's
     * {@code removeChild}. A minimised window is still there to go back to; a destroyed one leaves the
     * keyboard with nowhere to be.</p>
     */
    void activateTopmost() {
        WindowFrame front = null;
        for (WindowFrame frame : windows.frames) {
            if (front == null || frame.stackOrder() >= front.stackOrder()) front = frame;
        }
        if (front != null) activate(front, true);
    }

    /**
     * Activation follows the focus owner, wherever it came from.
     *
     * <p>The press half is handled by each frame's own capture listener; this is the half that covers
     * everything else — Tab crossing from one window into another, a command focusing a control, the
     * switcher (W10). Both funnel into the same idempotent {@link #activate}.</p>
     *
     * <p>Subscribed only while attached, {@code StatusBarView}'s pattern: the signal lives on the input
     * handler, which outlives any desktop that has left the tree.</p>
     */
    @Override
    protected void onWindowChanged(@Nullable UIWindow previous, @Nullable UIWindow current) {
        super.onWindowChanged(previous, current);
        subscriptions.disconnectAll();
        if (current == null) return;
        subscriptions.add(current.getInputHandler().onDidChangeFocus.connect(this::focusMoved));
    }

    private final ConnectionGroup subscriptions = new ConnectionGroup();

    private void focusMoved(@Nullable UIElement focused) {
        for (UIElement walk = focused; walk != null; walk = walk.getParent()) {
            if (walk instanceof WindowFrame && ((WindowFrame) walk).desktop() == this) {
                activate((WindowFrame) walk);
                return;
            }
        }
        // NOT a deactivate. Focus leaving every window is the ordinary middle of a click -- emitMouseDown
        // blurs, announces null, and only then focuses what was pressed -- so treating it as "the desktop
        // was clicked" would drop the active window on every press and take it back a moment later. The
        // press on bare desktop is what deactivates, and it says so itself.
    }

    /** A desktop owns its chrome; windows go through {@link #addWindow}. */
    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }

    /**
     * Puts a window on the desktop, cascading it into place unless it already has one.
     *
     * <p>Placement is <b>deferred to the frame's first layout</b> rather than computed here: the offset
     * needs the caption's measured height and the work area's measured box, and at this point neither
     * exists. {@code WindowFrame.onLayoutChanged} calls back into {@link #placeByCascade} once they do.</p>
     *
     * <p>The new window is raised and activated, which is right for every opener there is today — every
     * one of them is a user gesture. <b>W12 is where that stops being true</b>: a server may open a
     * window mid-keystroke, and every OS converged on the same answer for that case (Windows'
     * foreground lock plus {@code FlashWindowEx}, X11's urgency hint, macOS's bouncing icon) — appear,
     * but take no focus and ask for attention instead.</p>
     */
    public <T extends WindowFrame> T addWindow(T frame) {
        if (frame.state() == WindowState.DESTROYED) {
            throw new IllegalStateException("a destroyed window cannot be reopened: " + frame.getTitle());
        }
        frame.setOwner(this);
        registry.opened(frame);
        windows.addChild(frame);
        // PROGRAMMATIC, so focus rings: nobody pointed at this window, so a keyboard user needs to be
        // told where focus went. The ring lands on whatever inside it takes focus -- never on the frame,
        // which ua/core.css exempts along with every other pane-sized widget.
        activate(frame, true);
        return frame;
    }

    /** Puts a hidden window back on the layer. Driven by {@link WindowFrame#show}, which owns the state. */
    void reattach(WindowFrame frame) {
        if (frame.getParent() == null) windows.addChild(frame);
        raise(frame);
        registry.changed();
    }

    /** The strip along the bottom — the registry, rendered. @see Taskbar */
    public Taskbar taskbar() {
        return taskbar;
    }

    /** The window layer — the work area's box, and the containing block every frame is placed in. */
    public UIElement windowLayer() {
        return windows;
    }

    /** Every live window, visible or hidden — the model, not the tree. @see WindowRegistry */
    public WindowRegistry registry() {
        return registry;
    }

    /**
     * Every live window in open order, <b>including hidden ones</b>.
     *
     * <p>The registry's list rather than the layer's children, and the difference is the whole of W3: a
     * minimised window is still a window, and anything offering a way back to it — the taskbar, the
     * switcher — has to see it. {@link #visibleWindows()} is the other question.</p>
     */
    public List<WindowFrame> windows() {
        return registry.windows();
    }

    /**
     * Just the windows currently on the desktop, in child order.
     *
     * <p>A live view of the layer's own typed list, not a copy and not a filtered scan — see
     * {@link WindowLayer}. Iterating it while adding or removing a window is the usual
     * {@code ConcurrentModificationException}; copy first if a caller needs that.</p>
     */
    public List<WindowFrame> visibleWindows() {
        return windows.frames();
    }

    /**
     * Places a window nobody positioned, one caption height further down and across than the last —
     * Win32's cascade, wrapping back to the origin when the next step would put the window's own body
     * off the work area.
     *
     * <p>Reads only measured values, and does nothing while any of them is zero: a box measuring zero
     * carries no information about where anything belongs, and the frame will ask again on the layout
     * pass that gives it one.</p>
     */
    void placeByCascade(WindowFrame frame) {
        float step = frame.captionHeight();
        float areaWidth = windows.getRuntimeCache().getWidth();
        float areaHeight = windows.getRuntimeCache().getHeight();
        float frameWidth = frame.getRuntimeCache().getWidth();
        float frameHeight = frame.getRuntimeCache().getHeight();
        if (step <= 0f || areaWidth <= 0f || areaHeight <= 0f || frameWidth <= 0f || frameHeight <= 0f) {
            return;
        }

        float offset = cascadeStep * step;
        if (offset + frameWidth > areaWidth || offset + frameHeight > areaHeight) {
            cascadeStep = 0;
            offset = 0f;
        }
        cascadeStep++;
        frame.moveTo(offset, offset);
    }

    /**
     * The work area, as an element that tells its windows when it changed size.
     *
     * <p>A subclass for two overrides. The first is the layout callback: the alternative is a per-frame
     * clamp ticker, which is what {@code Dialog} has to do, having no container it owns. Here the
     * container is ours, and its own callback fires exactly when the work area changes and at no other
     * time — a window moving inside the layer does not resize the layer.</p>
     *
     * <h3>The frame list is maintained, never derived</h3>
     * <p>The obvious spelling of "tell every window" is a walk over {@code getChildren()} with an
     * {@code instanceof} filter, and it is the wrong shape here for a reason that is not about how many
     * windows there are: this runs inside a <b>layout callback</b>, which fires on every pass that
     * resizes the work area, and a re-clamp writes style and re-dirties layout — so the walk repeats
     * for as long as the geometry is settling. Anything on that path is worth keeping O(windows) with
     * no per-element type test, and the list costs one {@code instanceof} per <em>mutation</em> instead.</p>
     *
     * <p>Both public mutation paths are covered and there are exactly two: {@code addChild} delegates to
     * {@link #addChildAt}, and {@code removeSelf}, {@code clearAllChildren} and a reparent to another
     * parent all go through {@link #removeChild}. Internal children never reach either, which is why a
     * resize handle or an overlay slot cannot desynchronise the list.</p>
     *
     * <p>And it <b>refuses anything that is not a window</b>. Not defensiveness: it is what makes the
     * list provably the layer's children rather than a cache that could drift from them, so nothing
     * downstream ever has to re-filter. {@code Desktop.addWindow} is the sanctioned way in.</p>
     */
    private final class WindowLayer extends UIElement {

        private final List<WindowFrame> frames = new ArrayList<>();

        List<WindowFrame> frames() {
            return Collections.unmodifiableList(frames);
        }

        @Override
        public UIElement addChildAt(UIElement child, int index) {
            if (!(child instanceof WindowFrame)) {
                throw new UnsupportedOperationException(
                        "The desktop's window layer holds WindowFrames — use Desktop.addWindow(frame)");
            }
            super.addChildAt(child, index);
            // AFTER the super call, and at the same index: addChildAtInternal may have re-entered this
            // class through removeChild (a frame moving from another desktop), and inserting first would
            // then be undone by that removal. Index-matched so the list order is the child order, which
            // is what makes "insertion order" in windows() mean anything.
            frames.add(Math.min(index, frames.size()), (WindowFrame) child);
            return this;
        }

        @Override
        public boolean removeChild(UIElement child) {
            boolean removed = super.removeChild(child);
            if (removed) {
                frames.remove(child);
                // THE STATE FOLLOWS THE TREE. Detaching a frame IS hiding it, whichever route did the
                // detaching -- so a bare removeSelf() cannot leave a window that is out of the tree and
                // still claims to be visible.
                WindowFrame frame = (WindowFrame) child;
                frame.markHidden();
                // AND THE ACTIVE WINDOW CANNOT BE ONE THAT LEFT. Windows activates the next one down
                // when you close the front one; leaving the field pointing at a detached frame would
                // instead leave a desktop whose keyboard target is not in the tree.
                //
                // Through deactivate() rather than by clearing the field, because that is what announces
                // it -- and BEFORE the announcement below, so anything rendering the registry only ever
                // sees a settled state. Announcing first showed the strip a window that was already
                // hidden and still marked active, and nothing came along afterwards to correct it.
                //
                // AND IT HANDS OVER TO NOBODY. Leaving the tree is what MINIMISING looks like from here,
                // and minimising is "put this away", not "switch to that one" -- activation moves
                // keyboard focus into whatever it lands on, so handing over drops the caret into a
                // window the user was not asking for, once per minimise. Windows does hand over; ours
                // does not, because ours carries focus with it. Destroying the active window is the
                // case that genuinely has nowhere to leave the keyboard, and it hands over from
                // WindowFrame.dispose() where that distinction can still be seen.
                if (activeWindow == child) deactivate();
                registry.changed();
                // LAST, and after the activation above rather than before it: eviction can destroy a
                // window, and destroying one re-enters this method. Doing it while the active window is
                // still the frame that just left would have activateTopmost() choosing between windows
                // one of which is mid-removal.
                registry.evictIfNeeded();
            }
            return removed;
        }

        @Override
        protected void onLayoutChanged() {
            super.onLayoutChanged();
            for (int i = 0; i < frames.size(); i++) frames.get(i).reclamp();
        }
    }
}
