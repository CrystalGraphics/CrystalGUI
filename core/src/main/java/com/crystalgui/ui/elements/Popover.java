package com.crystalgui.ui.elements;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.AnchoredPlacement;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIFrameTicker;
import com.crystalgui.ui.UIWindow;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nullable;

/**
 * A light-dismissable, anchored, top-layer popup — the web's <b>Popover API</b>.
 *
 * <p>The shared base under menus, dropdowns and context menus. Everything those widgets need beyond
 * their own content is here: promotion, anchored placement that tracks its anchor, Escape, and
 * click-outside-to-close.</p>
 *
 * <h3>A base class, not an attribute — and the divergence is deliberate</h3>
 * <p>On the web {@code popover} is an <em>attribute</em> usable on any element, the way {@code inert} is.
 * Here it is a class, because unlike {@code inert} — which is a single property with subtree semantics —
 * popover-ness is a bundle of <em>behaviour</em> (show/hide, placement, dismissal, focus restore) with no
 * meaning for an element that does not want all of it. Widget-shaped behaviour lives in widget classes in
 * this engine; {@code Tooltip} and {@code Dialog} set that precedent. What genuinely has to be
 * element-level to work — the invoker link that light dismiss consults — <em>is</em> on
 * {@code UIElement}.</p>
 *
 * <h3>{@code AUTO} vs {@code MANUAL}</h3>
 * <p>Straight from the spec, and the distinction is which of the two dismissal mechanisms applies:</p>
 * <ul>
 *   <li>{@link Mode#AUTO} — joins the popover stack. Light-dismissed by a press outside, closed by
 *       Escape, and closes when an unrelated auto popover opens. What a menu wants.</li>
 *   <li>{@link Mode#MANUAL} — neither. Stays open until code closes it. What a persistent inspector
 *       panel or a toast wants.</li>
 * </ul>
 *
 * <h3>Nesting</h3>
 * <p>A popover opened from inside another (a submenu) does not dismiss its parent, and pressing outside
 * both closes the chain from the top down. That falls out of {@link UIWindow#lightDismiss} finding the
 * target's innermost popover ancestor — see there for why the invoker counts as part of the popover.</p>
 */
public class Popover extends UIElement {

    /**
     * Present exactly while the popover is open, so a stylesheet can style — and <b>transition</b> — the open
     * state.
     *
     * <p>This is what makes the fade-in possible at all, and the shape is the standard CSS one rather than
     * anything invented here. A popover opens out of {@code display: none}, so writing {@code opacity: 1} on
     * open has nothing to interpolate <em>from</em>: the web hit that exact wall and answered it with
     * {@code @starting-style}, which this engine has no equivalent of.</p>
     *
     * <p>An earlier attempt hand-rolled the starting style — one frame of {@code opacity: 0} at IMPORTANT
     * origin, removed on the next tick — and it quietly defeated itself: that {@code 1 -> 0} write is a
     * transitionable change too, so the engine eased <em>toward</em> zero and the removal retargeted it back
     * before it ever arrived. Nothing visibly faded.</p>
     *
     * <p>Keeping the closed state at {@code opacity: 0} in the sheet and flipping this class instead gives the
     * transition a real "from" value, because the element genuinely was at zero. Timing and easing stay in
     * CSS, per the no-timings-in-Java rule — a theme that declares no transition gets a popover that snaps in,
     * with nothing broken.</p>
     */
    public static final String OPEN_CLASS = "__open__";

    public enum Mode {
        /** Light dismiss + Escape, and part of the popover stack. */
        AUTO,
        /** Dismissed only by code. */
        MANUAL
    }

    /** Emitted after the popover closes, however it closed. */
    public final Signal.Action onClosed = new Signal.Action();

    @Getter
    private Mode mode = Mode.AUTO;

    @Getter
    private boolean open;

    /** Which side of the anchor to prefer. Flipping and clamping are {@link AnchoredPlacement}'s job. */
    @Getter
    @Setter
    private AnchoredPlacement.Side preferredSide = AnchoredPlacement.Side.BOTTOM;

    /** Gap between anchor and popup, in logical px. */
    @Getter
    @Setter
    private float offset = 0f;

    /**
     * Which side of its anchor this opens on, and how far off it.
     *
     * <p>Protected because a popover attached to something at the EDGE of the window must not rely on the
     * flip to save it. {@code ProcessesPopover} hangs off the status bar, where {@code BOTTOM} has no room
     * at all — it flipped, and it flipped to a position flush against the bar, so the list appeared to be
     * growing out of the thing it was covering. Naming the side and a gap says what was meant instead of
     * depending on the fallback to arrive at it.</p>
     */
    protected final void setPreferredSide(AnchoredPlacement.Side side, float gap) {
        this.preferredSide = side == null ? AnchoredPlacement.Side.BOTTOM : side;
        this.offset = gap;
    }

    /** Set when anchored to an element; null when anchored to a bare point (a context menu). */
    @Nullable
    private UIElement anchor;
    private float pointX, pointY;
    private boolean anchoredToPoint;

    /** True once {@link #moveTo} has been called — the popup is where the user put it, not where its
     * anchor says. Cleared by every show, so this survives a drag but not a reopen. */
    @Getter
    private boolean freelyPositioned;

    /** Whether {@link #focusBeforeOpen} was drawing a focus ring when this opened — see {@link #hide}. */
    private boolean focusBeforeOpenWasVisible;

    /** Focus to hand back on close — the same restore {@code Dialog} does, for the same reason: a menu
     * that swallows your place in the page is worse than one that never took focus. */
    @Nullable
    private UIElement focusBeforeOpen;

    private boolean placementTickerRunning;

    /** When this was last shown, on {@code UIWindow}'s monotonic show sequence — see
     * {@link UIWindow#lightDismiss(UIElement, int)}. */
    @Getter
    private int lastShownSeq;

    public Popover() {
        // Closed popovers are display:none — out of layout, unpainted and unhittable in one property,
        // exactly as a closed popover is on the web.
        applyOpenState();
    }

    /**
     * <b>Always</b> — a resizable popover gets all eight handles.
     *
     * <p>This used to answer {@code false}, and the reason was real rather than cautious:
     * {@link AnchoredPlacement} is the single writer of {@code left} and {@code top} on an anchored popup,
     * a leading handle moves the box by writing exactly those, and two writers means the handle and the
     * placement fight every frame. What the constraint bought was the trailing three — bottom, right and
     * the corner — which is CSS's own default grabber, so it looked like a convention rather than a
     * limitation and went unquestioned.</p>
     *
     * <p><b>{@link #moveTo} dissolves it.</b> It does not add a second writer; it <em>hands ownership
     * over</em>, and {@link #reposition()} goes quiet from that point on — so there is still exactly one
     * writer at any moment, which is the property the refusal was protecting. {@link #applyResizeOrigin}
     * routes the leading edges through it, and re-showing re-anchors, so a popup resized from the top does
     * not open detached forever afterwards.</p>
     */
    @Override
    protected boolean canMoveResizeOrigin() {
        return true;
    }

    public Popover setMode(Mode mode) {
        this.mode = mode == null ? Mode.AUTO : mode;
        return this;
    }

    // ── Show / hide ─────────────────────────────────────────────────────────

    /** Opens anchored to an element — a dropdown under its button. {@code invoker} is what the user
     * pressed, and is excluded from light dismiss so the press that opens it cannot also close it. */
    public Popover showFor(UIElement anchor, @Nullable UIElement invoker) {
        this.anchor = anchor;
        // Self-attaching, so no caller has to remember. This is what makes Menu.addSubmenu's
        // "the caller parents the child" contract stop being a trap -- a submenu is always shown FOR its
        // parent item, which is in the tree by definition.
        attachIfNeeded(anchor);
        this.anchoredToPoint = false;
        this.freelyPositioned = false;
        setPopoverInvoker(invoker != null ? invoker : anchor);
        return open();
    }

    /**
     * Opens at a point in <b>root space</b> — a context menu at the pointer. Same primitive as
     * {@link #showFor}, with a zero-sized anchor; see {@link AnchoredPlacement#placeAtPoint}.
     *
     * <p><b>Pass {@code null} for {@code invoker} unless the popup genuinely toggles.</b> An invoker is
     * excluded from light dismiss, which is what a toggle button needs — otherwise its own press would close
     * the menu it just opened. A context menu is not a toggle: naming its trigger surface as the invoker
     * makes that whole surface unable to dismiss the menu, so left-clicking the very area you right-clicked
     * does nothing. (Observed exactly that way.) Nothing is lost by passing {@code null}: a popover opened
     * during a press is already protected from that press by
     * {@link UIWindow#lightDismiss(UIElement, int)}.</p>
     */
    public Popover showAt(float rootX, float rootY, @Nullable UIElement invoker) {
        this.pointX = rootX;
        this.pointY = rootY;
        this.anchoredToPoint = true;
        this.anchor = null;
        this.freelyPositioned = false;
        setPopoverInvoker(invoker);
        return open();
    }

    /**
     * Puts this popover in the tree if it is not there, next to whatever it is being shown from.
     *
     * <p><b>A popover that can be shown but is not attached is a trap, and it caught this codebase four
     * times in one afternoon.</b> Promotion needs a node in the tree, so every caller had to remember to
     * parent one first — and the failure arrives a frame later, from inside a hover ticker, with nothing
     * in the stack naming the show that caused it. Submenus made it worse: {@link Menu#addSubmenu}
     * deliberately does not parent its child, so a caller had to know to attach each one separately.</p>
     *
     * <p>Attaching on demand removes the requirement rather than documenting it. {@link #hostFor} picks
     * the nearest ancestor that accepts children, so the popover lands beside the thing it belongs to and
     * inherits its cascade — which is where a caller doing this by hand should have put it anyway.</p>
     */
    private void attachIfNeeded(@Nullable UIElement near) {
        if (getParent() != null || near == null) return;
        UIWindow window = near.getAttachedWindow();
        if (window == null) return;
        hostFor(window, near).addChild(this);
    }

    private Popover open() {
        UIWindow window = getAttachedWindow();
        if (window == null) throw new IllegalStateException(
                "A Popover must be attached to a window before it can be shown"
                        + " — and it could not attach itself, because " + (anchor == null
                        ? "it has no anchor to find a host from. Add it to the tree, or show it with an"
                                + " anchor that is already in one."
                        : "its anchor is not in a window either."));
        // Bumped for a re-show too, not just a first open: a context menu re-shown at a new position by a
        // press must be exempt from that press's light dismiss, or right-clicking elsewhere closes it instead
        // of moving it.
        lastShownSeq = window.nextPopoverShowSeq();

        if (open) { // already showing: re-anchor and raise, rather than making the caller close first
            reposition();
            addToTopLayer();
            if (mode == Mode.AUTO) window.pushAutoPopover(this);
            return this;
        }

        // Anything unrelated that is already open goes first. Nesting survives because a popover opened
        // from inside another has that one as its popover ancestor.
        if (mode == Mode.AUTO) window.lightDismiss(getPopoverInvoker() != null ? getPopoverInvoker() : this);

        open = true;
        applyOpenState();
        addToTopLayer();
        if (mode == Mode.AUTO) {
            window.pushAutoPopover(this);
            window.pushCloseWatcher(this);
        }

        focusBeforeOpen = window.getInputHandler().getFocusedElement();
        // Captured WITH its ring state — see hide(). Read now, because opening this popover is about to
        // take focus away and the flag goes with it.
        focusBeforeOpenWasVisible = focusBeforeOpen != null && focusBeforeOpen.isFocusVisible();
        onOpened();
        startPlacementTicker();
        reposition();
        return this;
    }



    /**
     * Somewhere legal to parent a popup, near {@code source}.
     *
     * <p><b>The window root is not a safe answer, and assuming it was is a shipped crash.</b>
     * {@code TopLayer.add} refuses an element that is not already attached, so a popover has to be
     * parented before it can be promoted — and every call site reached for {@code ui.rootElement}. That
     * works right up until the root is a composite: {@code CrystalEditor} returns
     * {@code acceptsPublicChildren() == false}, so right-clicking the Project panel threw
     * {@code UnsupportedOperationException} out of the mouse-down dispatch.</p>
     *
     * <p>Walking outward from the source to the first element that accepts children is the right answer
     * rather than merely a working one. Promotion reparents the <em>Taffy</em> node to the root anyway, so
     * the DOM parent decides only cascade inheritance and lifetime — and both of those are better served
     * by the nearest legal ancestor than by the root. A menu opened inside a themed panel inherits that
     * panel's colours, and it goes away when the panel does.</p>
     *
     * @param near where the popup belongs, usually the element that was clicked. Null falls back to the
     *             root, which is correct for a window-level popup like the command palette
     */
    public static UIElement hostFor(UIWindow window, @Nullable UIElement near) {
        return window.overlayHost(near);
    }

    /** Hook for subclasses to move focus inside, once the popover is open and promoted. */
    protected void onOpened() {}

    /** Closes and hands focus back to whatever held it beforehand. */
    public Popover hide() {
        if (!open) return this;

        // Everything stacked above this goes too — the spec's "hide all popovers until". Closing a parent
        // menu while its submenu stayed open would leave the child orphaned in the top layer, still painting
        // and still taking Escape, with nothing left to explain where it came from. Runs while this popover
        // is STILL in the stack, so it is found as their ancestor and only the ones above it are closed.
        UIWindow openWindow = getAttachedWindow();
        if (openWindow != null && mode == Mode.AUTO) openWindow.lightDismiss(this);

        open = false;
        applyOpenState();
        removeFromTopLayer();
        setPopoverInvoker(null);

        UIWindow window = getAttachedWindow();
        if (window != null) {
            window.popAutoPopover(this);
            window.popCloseWatcher(this);
            if (focusBeforeOpen != null && focusBeforeOpen.getAttachedWindow() == window) {
                // Hand back the focus RING exactly as it was, rather than deciding afresh.
                //
                // requestFocus is PROGRAMMATIC and therefore always rings. Using it unconditionally meant
                // creating a node from the create menu outlined the ENTIRE GraphView — the canvas had
                // been focused by a CLICK (no ring), and closing the menu silently promoted that to a
                // keyboard-style focus it never had.
                //
                // Deriving it from the policy instead is the trap this replaced: FocusPolicy.CLICK IS
                // tabbable (only CLICK_NOT_TABBABLE is not), so "is it tabbable?" answers yes for the
                // canvas and rings it anyway. How focus was ACQUIRED is the only thing that settles it,
                // and the element already records that as :focus-visible — so remember it and put it
                // back. A popover opened from a button the user tabbed to still returns the ring.
                if (focusBeforeOpenWasVisible) {
                    window.getInputHandler().requestFocus(focusBeforeOpen);
                } else {
                    window.getInputHandler().requestPointerFocus(focusBeforeOpen);
                }
            }
        }
        focusBeforeOpen = null;
        focusBeforeOpenWasVisible = false;
        anchor = null;

        onClosed.emit();
        return this;
    }

    /**
     * The popover this one was opened from, or {@code null} if it is the root of its chain.
     *
     * <p>Derived from the invoker rather than stored, because the invoker is already the relationship that
     * dismissal reasons about — a second, parallel parent link could disagree with it. Found by walking the
     * invoker's ancestors, so a submenu opened from a row inside a menu resolves to that menu.</p>
     */
    @Nullable
    public Popover parentPopover() {
        for (UIElement el = getPopoverInvoker(); el != null; el = el.getParent()) {
            if (el instanceof Popover popover && popover != this && popover.isOpen()) return popover;
        }
        return null;
    }

    /**
     * Closes this popover <b>and every popover it was opened from</b> — the whole chain, innermost first.
     *
     * <p>This is what activating a leaf item must do, and the distinction from {@link #hide()} is the whole
     * point: {@code hide()} closes this one and its descendants, which for a submenu leaves the parent menu
     * standing. The ARIA menu pattern is explicit that activating a menuitem "closes the menu" — the menu,
     * not the submenu — and every native menu collapses the full chain the same way. Escape is the operation
     * that peels one level; choosing something is not.</p>
     *
     * <p>The chain is collected <em>before</em> anything closes, because {@link #hide()} clears the invoker
     * link that {@link #parentPopover()} walks — gathering as it went would lose the rest of the chain after
     * the first step.</p>
     */
    public void hideChain() {
        java.util.List<Popover> chain = new java.util.ArrayList<>();
        for (Popover popover = this; popover != null; popover = popover.parentPopover()) {
            chain.add(popover);
        }
        for (Popover popover : chain) popover.hide();
    }

    /**
     * The close-watcher hook — Escape, and the target of {@link UIWindow#lightDismiss}.
     *
     * <p>Only {@code AUTO} responds, which is the whole of the mode distinction: a {@code MANUAL} popover
     * declines and Escape falls through to whatever is underneath it in the stack.</p>
     */
    @Override
    public boolean requestClose() {
        if (!open || mode != Mode.AUTO) return false;
        hide();
        return true;
    }

    private void applyOpenState() {
        StyleGroup.importantPipeline(getStyle().getLayoutGroup(),
                l -> l.display(open ? TaffyDisplay.FLEX : TaffyDisplay.NONE));
        if (open) addClass(OPEN_CLASS);
        else removeClass(OPEN_CLASS);
    }

    // ── Placement ───────────────────────────────────────────────────────────

    /**
     * Moves this popup to a point in root space and <b>detaches it from its anchor</b> — what a drag on a
     * title bar calls.
     *
     * <p>This does not break the "only {@code AnchoredPlacement} writes {@code left}/{@code top} on an
     * anchored popup" rule; it is how a popup stops being anchored. {@link #reposition()} goes quiet from
     * here on, so there is still exactly one writer at any moment — which is the property that mattered.
     * Without the handover the placement ticker simply overwrites the drag every frame and the popup
     * appears nailed down.</p>
     *
     * <p>Re-showing re-anchors: {@link #showFor} and {@link #showAt} both clear this, so a menu you moved
     * once does not open in that spot forever afterwards.</p>
     */
    public Popover moveTo(float rootLeft, float rootTop) {
        freelyPositioned = true;
        StyleGroup.importantPipeline(getStyle().getLayoutGroup(), l -> l.left(rootLeft).top(rootTop));
        return this;
    }

    /**
     * A leading-edge resize moves the origin, and here that means <b>handing over from the anchor</b>.
     *
     * <p>The base writes {@code left}/{@code top} directly, which on an anchored popup is the one thing
     * nothing else may do: {@link AnchoredPlacement} is the single writer, and {@link #reposition()} would
     * overwrite the drag on the very next tick — the top edge would follow the pointer for one frame and
     * snap back, every frame. {@link #moveTo} is the legal route, and it is legal precisely because it
     * transfers ownership rather than competing for it. Same handover a drag on the body uses; this is the
     * other gesture that moves a popup.</p>
     */
    @Override
    protected void applyResizeOrigin(float left, float top) {
        moveTo(left, top);
    }

    /** Re-runs placement against the current anchor. */
    public void reposition() {
        if (!open || freelyPositioned) return;
        if (anchoredToPoint) {
            AnchoredPlacement.placeAtPoint(this, pointX, pointY, preferredSide, offset);
        } else if (anchor != null) {
            AnchoredPlacement.place(this, anchor, preferredSide, offset);
        }
    }

    /**
     * Re-place once this element's own box is known.
     *
     * <p>{@code open()} runs before the promoted node has ever been laid out, so at that moment width and
     * height are both 0 — and flipping and clamping are decided by exactly those. Without this hook the
     * first frame is placed as if the popover were a point, and only the next frame's ticker corrects it:
     * a visible one-frame jump. Same hook, same reason, as {@code Tooltip} and {@code UIText}.</p>
     */
    @Override
    protected void onLayoutChanged() {
        super.onLayoutChanged();
        reposition();
    }

    private void startPlacementTicker() {
        if (placementTickerRunning) return;
        UIWindow window = getAttachedWindow();
        if (window == null) return;
        placementTickerRunning = true;
        window.registerTicker(new PlacementTicker());
    }

    /** Keeps placement current while open, then drops itself. Registration is idempotent
     * ({@code HashSet}-backed) but the flag avoids re-registering on every show. */
    private final class PlacementTicker implements UIFrameTicker {
        @Override
        public boolean tickFrame(float deltaSeconds) {
            if (!open) {
                placementTickerRunning = false;
                return false;
            }
            reposition();
            return true;
        }
    }
}
