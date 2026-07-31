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

    /** Set when anchored to an element; null when anchored to a bare point (a context menu). */
    @Nullable
    private UIElement anchor;
    private float pointX, pointY;
    private boolean anchoredToPoint;

    /** True once {@link #moveTo} has been called — the popup is where the user put it, not where its
     * anchor says. Cleared by every show, so this survives a drag but not a reopen. */
    @Getter
    private boolean freelyPositioned;

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
     * <b>Never</b> — so a resizable popover gets the trailing handles only.
     *
     * <p>A popover is absolutely positioned, which would normally earn it all eight resize handles. It
     * must not have the leading four: {@code AnchoredPlacement} is the single writer of {@code left} and
     * {@code top} on an anchored popup, and a top or left handle moves the box by writing exactly those.
     * Two writers means the handle and the placement fight every frame.</p>
     *
     * <p>What is left is the bottom, the right and the bottom-right corner — which is also CSS's own
     * default grabber, and what Unity's Create Node window offers. The constraint and the convention
     * happen to agree.</p>
     */
    @Override
    protected boolean canMoveResizeOrigin() {
        return false;
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

    private Popover open() {
        UIWindow window = getAttachedWindow();
        if (window == null) throw new IllegalStateException(
                "A Popover must be attached to a window before it can be shown");
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
        onOpened();
        startPlacementTicker();
        reposition();
        return this;
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
                window.getInputHandler().requestFocus(focusBeforeOpen);
            }
        }
        focusBeforeOpen = null;
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
