package com.crystalgui.ui.elements;

import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.contract.RatePolicy;
import com.crystalgui.ui.contract.Event;
import com.crystalgui.ui.contract.WidgetContracts;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.contract.StateTypes;
import com.crystalgui.ui.contract.State;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.core.signal.Signal;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.ui.EventListenerGroup;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIFrameTicker;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.desktop.WindowFrame;
import com.crystalgui.ui.event.CloseEvent;
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
 * <h3>Two entry points, and the difference is not cosmetic</h3>
 * <p>{@link #show()} is modeless, {@link #showModal()} is modal, and per the HTML spec only the modal
 * form joins the top layer. A modeless dialog stays in ordinary flow and ordinary stacking, which is the
 * right model for editor panels: several coexist, they order themselves against each other and against
 * page content by {@code z-index}, and none outranks the whole UI. A modal is the opposite by design —
 * it outranks everything and makes everything else {@link UIElement#isInert() inert}.</p>
 *
 * <h3>Escape closes a modal, and only a modal</h3>
 * <p>Only {@code showModal()} "establishes a close watcher" — the machinery that turns a close request
 * (Escape) into a cancelable {@code cancel} event and then a close. {@code show()}'s algorithm contains
 * no reference to one, so <b>a modeless dialog does not close on Escape in a browser either</b>, and does
 * not here. See {@link #requestClose()} and {@link #onCancel}.</p>
 *
 * <p>An earlier revision closed on Escape regardless, and worse, only when focus happened to be inside —
 * an accidental middle ground that was neither the web's behaviour nor a coherent one of its own.</p>
 *
 * <p>A modeless panel therefore needs a <b>close button</b>, which browsers leave entirely to the author
 * because their dialogs ship no chrome. This one has chrome — a title bar to drag — so it carries a
 * {@code __close__} button too.</p>
 *
 * <h3>Moving is ours</h3>
 * <p>Nothing in CSS or HTML moves an element by pointer; every draggable window on the web is library
 * code over pointer events, and so is this. It runs on P2's positional drag from the
 * {@code __title-bar__}, and writes {@code left}/{@code top} at <b>{@code INLINE}</b> origin — the
 * same choice CSS {@code resize} mandates for the size it writes, so the two stay consistent and an
 * author's {@code !important} can still pin a dialog in place.</p>
 */
public class Dialog extends UIElement {

    public static final State<Dialog, String> TITLE =
            State.<Dialog, String>of("title", StateTypes.STRING, Dialog::getTitle, Dialog::setTitle, "")
                    .omittedWhen("");

    /**
     * The user asked to close it. The veto path -- M4 is where the answer travels back, and until then
     * a server hears the request and decides what to do about it.
     */
    public static final Event<Dialog, Void> CLOSE_REQUESTED =
            Event.signal("closeRequested",
                    (dialog, sink) -> dialog.getCloseButton().attachListener(sink));

    /** A dialog is a container: its content is described children, not internals. */
    public static final WidgetContract<Dialog> CONTRACT = WidgetContracts.register(
            WidgetContract.of(Dialog.class, "dialog")
                    .state(TITLE)
                    .event(CLOSE_REQUESTED)
                    .withDescribedChildren()
                    .build());


    public static final String TITLE_BAR_CLASS = "__title-bar__";
    /** On the title text, so a theme can reach it — the same {@code __label__} hook Button and
     * Checkbox use for theirs. */
    public static final String LABEL_CLASS = "__label__";
    public static final String CONTENT_CLASS = "__content__";
    public static final String CLOSE_CLASS = "__close__";
    /** The modal scrim. Our stand-in for {@code ::backdrop} — see {@link #showModal()}. */
    public static final String BACKDROP_CLASS = "__backdrop__";

    /**
     * On the dialog while it is open — the same name and the same job as {@code Popover.OPEN_CLASS}.
     *
     * <p>Without it a dialog has no CSS-visible open state, and so cannot be <b>animated at all</b>: a box
     * coming out of {@code display: none} has no previous opacity to interpolate from, which is the exact
     * problem {@code @starting-style} exists to solve on the web. The pair — a resting value in the base
     * rule and the open value on this class — is what gives a transition something to run between, and it
     * is the arrangement {@code menu .__items__} already uses.</p>
     */
    public static final String OPEN_CLASS = "__open__";

    /**
     * On a modal for one beat after a click was swallowed by it — W13c.
     *
     * <p>The sheet gives it a brief scale-and-brighten; this class is what starts it, and it is dropped
     * on the next frame so pressing repeatedly re-plays rather than latching.</p>
     */
    public static final String PULSE_CLASS = "__pulse__";

    /**
     * Says "this dialog is why that click did nothing" — Windows' exact behaviour.
     *
     * <p>Without it, window-scoped modality's failure mode reads as <em>this window ignores my
     * clicks</em>, which is indistinguishable from a bug. The visible half is a class the sheet
     * transitions; the audible half is the <b>first real consumer of {@code CgPlatform.sound()}</b>, an
     * SPI wired on every loader that nothing used.</p>
     *
     * <p>The class is removed on the following frame rather than after a timer: a transition needs the
     * value to go back for the next press to move it again, and a one-frame round trip is enough because
     * the sheet's own duration is what the eye sees. Latching it instead would mean the second click at
     * a stuck window did nothing at all — which is the exact complaint this exists to answer.</p>
     */
    public void pulse() {
        UIWindow window = getAttachedWindow();
        if (window == null || pulsing) return;
        pulsing = true;
        addClass(PULSE_CLASS);
        CgPlatform.sound().play("dialog_blocked");
        window.registerTicker(delta -> {
            removeClass(PULSE_CLASS);
            pulsing = false;
            return false;
        });
    }

    private boolean pulsing;

    /** Emitted after the dialog closes, however it was closed. */
    public final Signal.Action onClosed = new Signal.Action();

    /** The spec's cancelable {@code cancel} event — Escape on a modal. {@code preventDefault()} keeps
     * the dialog open. Never fires for a modeless dialog, which establishes no close watcher. */
    public final EventListenerGroup<UIElement, CloseEvent.Cancel> onCancel = events.getGroup(CloseEvent.Cancel.class);

    private final UIElement titleBar;
    private final Button closeButton;
    private final UIText titleLabel;
    @Getter
    private final UIElement content;

    @Getter
    private boolean open;
    /** Whether this was opened with {@link #showModal()} rather than {@link #show()}. */
    @Getter
    private boolean modal;
    /** Built lazily: a modeless dialog never needs one, and most dialogs are modeless. */
    private UIElement backdrop;

    /** The window this dialog is modal INSIDE, or null when it is modal over the whole screen. */
    @Nullable
    private WindowFrame ownerFrame;

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
        installEscapeToClose();
    }

    /**
     * Escape closes it — but only while focus is inside it.
     *
     * <h3>A bubbling listener, deliberately not a close watcher</h3>
     *
     * <p>The close-watcher stack is the engine's usual Escape route, and it is a <b>window-wide</b> stack
     * whose topmost entry wins wherever focus happens to be. That is right for a modal, which already
     * establishes one in {@link #showModal()}, and wrong for a modeless dialog: it would eat Escape from
     * the editor behind it, where Escape already means something. Bubbling gets "while focused" for free,
     * since an event only reaches this dialog when the focused element is inside it.</p>
     *
     * <p>Goes through {@link #requestClose()} rather than {@link #close()}, so a cancelable
     * {@link #onCancel} can veto it — the same courtesy the modal path already gets, rather than a second
     * way out that skips the hook.</p>
     *
     * <p>A modal never reaches this: its close watcher consumes Escape before dispatch runs at all, so the
     * two cannot both fire.</p>
     */
    private void installEscapeToClose() {
        onKeyDown.attachListener((element, event) -> {
            if (!open || event.getKeyCode() != CgKeyCodes.KEY_ESCAPE) return;
            if (requestClose()) event.stopPropagation();
        }, false, true);
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
        runFocusingSteps();
        return this;
    }

    /**
     * Shows the dialog <b>modally</b> — the spec's {@code showModal()}. Three things happen that
     * {@link #show()} does not do.
     *
     * <ol>
     *   <li><b>It joins the top layer</b>, so it paints above everything and is clipped by nothing. Only
     *       the modal form promotes; a modeless dialog stays in normal flow and normal stacking, which is
     *       what lets several editor panels order themselves among ordinary content.</li>
     *   <li><b>Everything outside it becomes inert</b> — unhittable, unfocusable, and outside the tab
     *       sequence. That last part is focus trapping, and it falls out of inertness rather than being a
     *       separate mechanism. See {@link UIElement#isInert()}.</li>
     *   <li><b>Escape closes it</b>, via a close watcher: a cancelable {@link #onCancel} first, then
     *       {@link #close()}. A modeless dialog establishes no close watcher and so ignores Escape — on
     *       the web too.</li>
     * </ol>
     *
     * <p>Nesting is allowed and unwinds in order: a modal opened on top of a modal blocks it in turn, and
     * closing restores the one beneath.</p>
     *
     * @throws IllegalStateException if not attached to a window, or if already open <em>modelessly</em> —
     *         both mirror the spec, which throws {@code InvalidStateError} for each. Reopening an
     *         already-modal dialog is a no-op, also per spec.
     */
    public Dialog showModal() {
        if (open && modal) return this;
        if (open) throw new IllegalStateException(
                "Dialog is already open modelessly; close() it before showModal()");
        UIWindow window = getAttachedWindow();
        if (window == null) throw new IllegalStateException(
                "Dialog must be attached to a window before showModal()");

        open = true;
        modal = true;
        applyOpenState();
        startClampTicker();

        // INSIDE ITS OWN WINDOW, if it has one. A modal that promoted to the global top layer would
        // paint above every window rather than above its own -- the top layer paints after the whole
        // main tree, so raising a different window would leave this dialog floating over the wrong one.
        // Win32's owner/owned rule is the answer: an owned window stays above its owner AND TRAVELS
        // WITH IT, which parenting into the frame gets for nothing.
        //
        // The backdrop goes first either way, so it lands beneath the dialog -- both the top layer and
        // an ordinary child list stack by insertion order.
        ownerFrame = WindowFrame.of(this);
        if (ownerFrame != null) {
            ownerFrame.attachOwned(ensureBackdrop());
            ownerFrame.attachOwned(this);
        } else {
            ensureBackdrop().addToTopLayer();
            addToTopLayer();
        }
        window.pushModal(this);
        // Modality and Escape are separate registrations because they are separate concerns: a popover has
        // a close watcher without being modal, and a MANUAL popover is neither. Escape asks the topmost
        // watcher, so a dropdown opened inside this modal correctly closes before the modal does.
        window.pushCloseWatcher(this);

        runFocusingSteps();
        return this;
    }

    /**
     * The spec's <b>dialog focusing steps</b>: the focus delegate (first focusable descendant), else the
     * dialog itself. There is no {@code autofocus} attribute here, so that tier is skipped — a caller who
     * wants a specific control focused should request it after showing.
     *
     * <p>Reads {@code firstFocusableIn}, not {@code firstTabbableIn}: a focus delegate is the first thing
     * that <em>can</em> hold focus, which is a different question from where Tab lands.</p>
     */
    private void runFocusingSteps() {
        UIWindow window = getAttachedWindow();
        if (window == null) return;
        focusBeforeOpen = window.getInputHandler().getFocusedElement();
        UIElement delegate = UITreeTraversal.firstFocusableIn(content);
        window.getInputHandler().requestFocus(delegate != null ? delegate : this);
    }

    /**
     * The close-watcher hook. Fires a cancelable {@link #onCancel}; closes unless it was prevented.
     *
     * <p>Overrides {@link UIElement#requestClose()}, which {@code UIInputHandler} asks of the active modal
     * on Escape. Guarded on {@link #isModal()} too, so a stray call on a modeless dialog cannot give it
     * Escape behaviour the web would not.</p>
     */
    @Override
    public boolean requestClose() {
        // OPEN is the whole condition. This used to require `modal` as well, on the reasoning that only a
        // modal establishes a close watcher -- true, but it conflates "who calls this" with "what it
        // means". UIElement.requestClose is the general "ask this element to close" hook, and a modeless
        // dialog that answers false to it cannot be closed by anything that politely asks, including its
        // own Escape handler. Modals are unaffected: their close watcher is still the only thing that
        // reaches them, because Escape is consumed before dispatch ever runs.
        if (!open) return false;

        UIWindow window = getAttachedWindow();
        if (window != null) {
            CloseEvent.Cancel cancel = new CloseEvent.Cancel(this);
            window.getInputHandler().sendInputEvent(this, cancel);
            if (cancel.isDefaultPrevented()) return true; // handled: consumed, but stays open
        }
        close();
        return true;
    }

    /** The scrim. Not a {@code ::backdrop} pseudo-element — this engine has none — but the same idea via
     * the same substitute the widgets already use: an internal child carrying a {@code __} class a theme
     * can target. Promoted alongside the dialog so it covers the viewport rather than the dialog's own box,
     * and inert plus non-hit-testable because it is decoration, not a control. */
    private UIElement ensureBackdrop() {
        if (backdrop == null) {
            backdrop = new UIElement();
            backdrop.addClass(BACKDROP_CLASS);
            backdrop.setHitTest(false);
            backdrop.setInert(true);
            addInternalChild(backdrop);
        }
        applyBackdropVisibility();
        return backdrop;
    }

    /**
     * Shows the backdrop only while modal.
     *
     * <p>It is built lazily and then <b>kept</b>, so after one {@code showModal()} it is a permanent
     * internal child. Demotion drops the {@code position: absolute} the top layer forced, which turned it
     * back into an ordinary in-flow child sized {@code 100%} of the <em>dialog</em> — a dark panel painted
     * over the dialog's own content and spilling below it. That is what a modeless {@code show()} looked
     * like after any modal had ever been opened: a backdrop it should never have had.</p>
     *
     * <p>Driven by {@code display} rather than by removing the child, so the Taffy tree is not churned on
     * every open — the same reason {@code Tab} hides panes instead of detaching them.</p>
     */
    private void applyBackdropVisibility() {
        if (backdrop == null) return;
        StyleGroup.importantPipeline(backdrop.getStyle().getLayoutGroup(),
                l -> l.display(modal ? TaffyDisplay.FLEX : TaffyDisplay.NONE));
    }

    /** Closes the dialog and hands focus back to whatever held it beforehand. */
    public Dialog close() {
        if (!open) return this;
        open = false;
        applyOpenState();

        if (modal) {
            modal = false;
            applyBackdropVisibility();
            UIWindow modalWindow = getAttachedWindow();
            if (modalWindow != null) {
                modalWindow.popModal(this);
                modalWindow.popCloseWatcher(this);
                removeFromTopLayer();
                if (backdrop != null) backdrop.removeFromTopLayer();
            }
            // AND THE OWNER'S SLOT LETS GO OF ITS BOX -- a full-size owned surface hit-tests, so one
            // left open with nothing showing swallows every click on the window's own content. The
            // dialog stays PARENTED there (it is `display: none` while closed and re-shown from where
            // it already is); only its claim on the slot ends.
            if (ownerFrame != null) {
                if (backdrop != null) ownerFrame.releaseOwned(backdrop);
                ownerFrame.releaseOwned(this);
                ownerFrame = null;
            }
        }

        UIWindow window = getAttachedWindow();
        if (window != null && focusBeforeOpen != null
                && focusBeforeOpen.getAttachedWindow() == window) {
            window.getInputHandler().requestFocus(focusBeforeOpen);
        }
        focusBeforeOpen = null;

        onClosed.emit();
        return this;
    }

    /**
     * Closed dialogs are {@code display: none} — out of layout, unpainted and unhittable in one property,
     * exactly as a closed popover is on the web.
     *
     * <p>A fade-out is <b>not</b> arranged here. {@code display} is a transitionable property
     * ({@link com.crystalgui.style.property.layout.LayoutProperties#DISPLAY}, CSS's
     * {@code allow-discrete}), so a sheet that says {@code transition: display 120ms} keeps the box laid
     * out for that long and this write lands at the end of it — with no Java involved and nothing here
     * that could disagree with the sheet about the duration. A dialog whose sheet says nothing hides on
     * this frame, as it always did.</p>
     */
    private void applyOpenState() {
        // A CLASS as well as the display write, because a stylesheet cannot see an IMPORTANT-origin
        // layout value. @see #OPEN_CLASS
        if (open) addClass(OPEN_CLASS);
        else removeClass(OPEN_CLASS);

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
        UIElement container = resizeContainingBlock();
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
