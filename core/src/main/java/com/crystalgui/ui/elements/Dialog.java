package com.crystalgui.ui.elements;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIFrameTicker;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.ui.input.UIDragController;
import com.crystalgui.ui.tree.UITreeTraversal;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import dev.vfyjxf.taffy.style.TaffyPosition;
import lombok.Getter;

import javax.annotation.Nullable;

/**
 * A floating, movable panel — the web's {@code <dialog>}.
 *
 * <p>Named for the element it ports rather than "window", because {@link UIWindow} already means this
 * engine's {@code Document} analogue and reusing the word would be actively misleading.</p>
 *
 * <h3>Modeless, deliberately</h3>
 * <p>Ports {@code dialog.show()}, not {@code showModal()}. Per the HTML spec only the modal form is
 * added to the top layer; a modeless dialog stays in ordinary flow and ordinary stacking. That is the
 * right model for editor panels: several of them coexist, they stack against each other and against
 * page content by {@code z-index}, and none of them outranks the whole UI.</p>
 *
 * <p><b>Modal is not implemented</b>, and it is not a small addition: {@code showModal()} makes
 * everything outside the dialog {@code inert}, and this engine has no inertness concept at all. That
 * is a separate primitive, not a flag on this class.</p>
 *
 * <h3>Escape does not close this, and that is correct</h3>
 * <p>Only {@code showModal()} "establishes a close watcher" — the machinery that turns a close request
 * (Escape) into a {@code cancel} event and then a close. {@code show()}'s algorithm contains no
 * reference to one, so <b>a modeless dialog does not close on Escape in a browser either</b>.</p>
 *
 * <p>An earlier revision here did close on Escape, and worse, only when focus happened to be inside —
 * an accidental middle ground that was neither the web's behaviour nor a coherent one. Escape-to-close
 * arrives with {@code showModal()} and its close watcher, or not at all.</p>
 *
 * <p>What a floating panel actually needs instead is a <b>close button</b>, which browsers leave
 * entirely to the author because their dialogs ship no chrome. This one has chrome — a title bar to
 * drag — so it carries a {@code __close__} button too.</p>
 *
 * <h3>Moving is ours</h3>
 * <p>Nothing in CSS or HTML moves an element by pointer; every draggable window on the web is library
 * code over pointer events, and so is this. It runs on P2's positional drag from the
 * {@code __title-bar__}, and writes {@code left}/{@code top} at <b>{@code INLINE}</b> origin — the
 * same choice CSS {@code resize} mandates for the size it writes, so the two stay consistent and an
 * author's {@code !important} can still pin a dialog in place.</p>
 */
public class Dialog extends UIElement {

    public static final String TITLE_BAR_CLASS = "__title-bar__";
    /** On the title text, so a theme can reach it — the same {@code __label__} hook Button and
     * Checkbox use for theirs. */
    public static final String LABEL_CLASS = "__label__";
    public static final String CONTENT_CLASS = "__content__";
    public static final String CLOSE_CLASS = "__close__";

    /** Emitted after the dialog closes, however it was closed. */
    public final Signal.Action onClosed = new Signal.Action();

    private final UIElement titleBar;
    private final Button closeButton;
    private final UIText titleLabel;
    @Getter
    private final UIElement content;

    @Getter
    private boolean open;

    /** Position at the moment a move began. Accumulating from here rather than from the live box
     * keeps the drag from compounding its own deltas — same reason {@code UIResizer} snapshots size. */
    private float dragStartLeft, dragStartTop;

    /** The dialog's position, as last applied and clamped. The source of truth — see applyPosition
     * for why this must not be re-derived from the resolved box. */
    private float posLeft, posTop;

    /** Focus to hand back on close — the spec's "if a previously focused element exists, focus
     * returns to it". Without it, closing a dialog drops the user's place in the page entirely. */
    @Nullable
    private UIElement focusBeforeOpen;

    public Dialog(String title) {
        // Out of flow and positioned: a floating panel is placed by left/top against its containing
        // block, not laid out among its siblings.
        StyleGroup.defaultPipeline(getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE).flexDirection(dev.vfyjxf.taffy.style.FlexDirection.COLUMN));

        titleLabel = new UIText(title == null ? "" : title);
        titleLabel.addClass(LABEL_CLASS);
        titleLabel.setHitTest(false);

        titleBar = new UIElement();
        titleBar.addClass(TITLE_BAR_CLASS);
        titleBar.addChild(titleLabel);
        addInternalChild(titleBar);

        content = new UIElement();
        content.addClass(CONTENT_CLASS);
        addInternalChild(content);

        closeButton = new Button("x");
        closeButton.addClass(CLOSE_CLASS);
        closeButton.attachListener(this::close);
        titleBar.addChild(closeButton);

        titleBar.onMouseDown.attachListener((el, event) ->
                beginMove(event.getPosition().x(), event.getPosition().y()), false, false);

        setFocusPolicy(FocusPolicy.FOCUSABLE);
        applyOpenState();
    }

    /** A dialog owns its structure; put content in {@link #getContent()}. */
    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }

    public Dialog setTitle(String title) {
        titleLabel.setText(title == null ? "" : title);
        return this;
    }

    public String getTitle() {
        return titleLabel.getText();
    }

    /** The close affordance. Exposed so a caller can relabel or hide it. */
    public Button getCloseButton() {
        return closeButton;
    }

    /** The drag handle. Exposed so a theme or a caller can restyle or replace what it contains. */
    public UIElement getTitleBar() {
        return titleBar;
    }

    /** The title text. Exposed for the same reason {@link #getTitleBar()} is — and so a caller can turn
     * off the user-agent sheet's title ellipsis if a full title matters more than the close button. */
    public UIText getTitleLabel() {
        return titleLabel;
    }

    // ── Open / close ────────────────────────────────────────────────────────

    /**
     * Shows the dialog modeless — the spec's {@code show()}.
     *
     * <p>Focus follows the spec's order as far as this engine can express it: the first focusable
     * descendant (the "focus delegate"), else the dialog itself. There is no {@code autofocus}
     * attribute here, so that tier is skipped — a caller that wants a specific control focused should
     * request it after showing.</p>
     */
    public Dialog show() {
        if (open) return this;
        open = true;
        applyOpenState();
        startClampTicker();

        UIWindow window = getAttachedWindow();
        if (window != null) {
            focusBeforeOpen = window.getInputHandler().getFocusedElement();
            UIElement delegate = UITreeTraversal.firstFocusableIn(content);
            window.getInputHandler().requestFocus(delegate != null ? delegate : this);
        }
        return this;
    }

    /** Closes the dialog and hands focus back to whatever held it beforehand. */
    public Dialog close() {
        if (!open) return this;
        open = false;
        applyOpenState();

        UIWindow window = getAttachedWindow();
        if (window != null && focusBeforeOpen != null
                && focusBeforeOpen.getAttachedWindow() == window) {
            window.getInputHandler().requestFocus(focusBeforeOpen);
        }
        focusBeforeOpen = null;

        onClosed.emit();
        return this;
    }

    /** Closed dialogs are {@code display: none} — out of layout, unpainted and unhittable in one
     * property, exactly as a closed popover is on the web. */
    private void applyOpenState() {
        StyleGroup.importantPipeline(getStyle().getLayoutGroup(),
                l -> l.display(open ? TaffyDisplay.FLEX : TaffyDisplay.NONE));
    }

    // ── Position ────────────────────────────────────────────────────────────

    /** Places the dialog against its containing block, clamped so it cannot be put out of reach. */
    public Dialog moveTo(float left, float top) {
        applyPosition(left, top);
        return this;
    }

    /**
     * A dialog owns its own position, so a left/top resize handle has to go through it rather than
     * writing {@code left}/{@code top} directly.
     *
     * <p>Without this the two fight every frame: the handle would write the property, and the clamp
     * ticker would immediately put {@code posLeft}/{@code posTop} back — dragging the left edge would
     * resize the box while snapping its origin home on the next tick.</p>
     */
    @Override
    protected void applyResizeOrigin(float left, float top) {
        applyPosition(left, top);
    }

    @Override protected float resizeOriginLeft() { return posLeft; }
    @Override protected float resizeOriginTop() { return posTop; }

    private void beginMove(float pointerX, float pointerY) {
        UIWindow window = getAttachedWindow();
        if (window == null) return;

        // Clicking a window activates it — every window manager works this way, and without it the
        // focus ring is unreachable by hand: `show()` and `close()` focus a dialog programmatically
        // (the spec's focusing steps, and its focus-restore on close), but FocusPolicy.FOCUSABLE
        // means click never does. The ring then appears "out of nowhere" when some *other* dialog
        // closes and hands focus back, and no amount of clicking reproduces it.
        //
        // Deliberately here rather than FocusPolicy.CLICK: focus-on-click tests the click's *target*,
        // which for a title-bar press is the title bar, not the dialog. Requesting it explicitly is
        // what makes "click anywhere on the chrome activates the window" actually true.
        window.getInputHandler().requestFocus(this);

        dragStartLeft = posLeft;
        dragStartTop = posTop;

        UIDragController drag = window.getInputHandler().getDragController();
        // Positional drag, zero threshold: a window must track the very first pixel, and there is no
        // competing click interpretation on a title bar to protect.
        drag.startDrag(titleBar, pointerX, pointerY,
                (mx, my, sx, sy, dx, dy) -> applyPosition(dragStartLeft + dx, dragStartTop + dy));
    }

    /**
     * Writes the position, clamped into the containing block.
     *
     * <p>Clamping is ours — no spec covers it, because the web has no movable window. It matches what
     * OS window managers do, and the alternative (proportional re-anchoring) can drift a window
     * somewhere the user never put it.</p>
     *
     * <p>{@code INLINE} origin, matching CSS {@code resize}'s mandated behaviour for the size it
     * writes. Keeping the two consistent means one rule covers both: user-driven geometry is inline,
     * so an author's {@code !important} still wins.</p>
     */
    private void applyPosition(float left, float top) {
        UIElement container = getParent();
        float maxLeft = Float.MAX_VALUE, maxTop = Float.MAX_VALUE;
        if (container != null) {
            maxLeft = Math.max(0f, container.getRuntimeCache().getWidth() - getRuntimeCache().getWidth());
            maxTop = Math.max(0f, container.getRuntimeCache().getHeight() - getRuntimeCache().getHeight());
        }
        final float clampedLeft = Math.min(Math.max(0f, left), maxLeft);
        final float clampedTop = Math.min(Math.max(0f, top), maxTop);

        // The position lives HERE, in fields — never read back out of the resolved layout box.
        //
        // Deriving it from geometry instead was a real bug: the clamp ticker runs during
        // advanceFrame, which is BEFORE calculateLayout, so on the first frame after reopening a
        // dialog the box was still the zero-sized `display: none` one. Reading it gave 0, and the
        // ticker wrote that straight back — every reopened dialog snapped to the corner, and with
        // two of them stacked exactly on top of each other only the upper one appeared to drag.
        posLeft = clampedLeft;
        posTop = clampedTop;

        StyleGroup.inlinePipeline(getStyle().getLayoutGroup(),
                l -> l.left(clampedLeft).top(clampedTop));
    }

    /**
     * Re-clamps while open, so a dialog parked near an edge stays reachable when the container
     * shrinks under it.
     *
     * <p>A per-frame ticker rather than an {@code onLayoutChanged} override, and the reason is the
     * same one {@code Tooltip} hit: when the <em>container</em> resizes, this element's own box does
     * not change — it is absolutely positioned at a fixed size — so its layout callback never fires.
     * The thing that moved is somebody else. Ticking is the only hook that sees it.</p>
     *
     * <p>Cost is a comparison and a no-op style write per open dialog per frame:
     * {@code replaceOrPutCandidate} discards an unchanged value, so a stationary dialog stops
     * re-triggering layout after the first frame.</p>
     */
    private final class ClampTicker implements UIFrameTicker {
        @Override
        public boolean tickFrame(float deltaSeconds) {
            if (!open) {
                clampTickerRunning = false;
                return false;
            }
            applyPosition(posLeft, posTop);
            return true;
        }
    }

    private boolean clampTickerRunning;

    private void startClampTicker() {
        if (clampTickerRunning) return;
        UIWindow window = getAttachedWindow();
        if (window == null) return;
        clampTickerRunning = true;
        window.registerTicker(new ClampTicker());
    }

}
