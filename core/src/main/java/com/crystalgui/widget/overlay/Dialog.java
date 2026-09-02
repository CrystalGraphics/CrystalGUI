package com.crystalgui.widget.overlay;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.ui.EventListenerGroup;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.contract.Event;
import com.crystalgui.ui.contract.RatePolicy;
import com.crystalgui.ui.contract.State;
import com.crystalgui.ui.contract.StateTypes;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.contract.WidgetContracts;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.event.CloseEvent;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.ui.service.Animation;
import com.crystalgui.ui.service.Drag;
import com.crystalgui.ui.tree.UITreeTraversal;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.text.UIText;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import dev.vfyjxf.taffy.style.TaffyPosition;
import javax.annotation.Nullable;
import lombok.Getter;

/**
 * A floating, movable panel — the web's {@code <dialog>}.
 *
 * <p>Named for the element it ports rather than "window", because {@link UIDocument} already means this
 * engine's {@code Document} analogue and reusing the word would be actively misleading.</p>
 *
 * <h3>Two entry points, and the difference is not cosmetic</h3>
 * <p>{@link #show()} is modeless, {@link #showModal()} is modal, and per the HTML spec only the modal
 * form joins the top layer. A modeless dialog stays in ordinary flow and ordinary stacking, which is the
 * right model for editor panels: several coexist, they order themselves against each other and against
 * page content by {@code z-index}, and none outranks the whole UI. A modal is the opposite by design —
 * it outranks everything and makes everything else {@link UINode#isInert() inert}.</p>
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
public class Dialog extends UINode {

    public static final Name NAME = Name.of("dialog");

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
        UIDocument window = document();
        if (window == null || pulsing) return;
        pulsing = true;
        addClass(PULSE_CLASS);
        CgPlatform.sound().play("dialog_blocked");
        window.animation().every(this, delta -> {
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
    public final EventListenerGroup<UINode, CloseEvent.Cancel> onCancel = events.getGroup(CloseEvent.Cancel.class);

    private final UINode titleBar;
    private final Button closeButton;
    private final UIText titleLabel;
    @Getter
    private final UINode content;

    @Getter
    private boolean open;
    /** Whether this was opened with {@link #showModal()} rather than {@link #show()}. */
    @Getter
    private boolean modal;
    /** Built lazily: a modeless dialog never needs one, and most dialogs are modeless. */
    private UINode backdrop;

    /** The window this dialog is modal INSIDE, or null when it is modal over the whole screen. */
    @Nullable

    /** Position at the moment a move began. Accumulating from here rather than from the live box
     * keeps the drag from compounding its own deltas — same reason {@code UIResizer} snapshots size. */
    private float dragStartLeft, dragStartTop;

    /** The dialog's position, as last applied and clamped. The source of truth — see applyPosition
     * for why this must not be re-derived from the resolved box. */
    private float posLeft, posTop;

    /** Focus to hand back on close — the spec's "if a previously focused element exists, focus
     * returns to it". Without it, closing a dialog drops the user's place in the page entirely. */
    @Nullable
    private UINode focusBeforeOpen;

    /** The no-argument constructor the registry's factory needs. @see Button#Button() */
    public Dialog() {
        this("");
    }

    public Dialog(String title) {
        super(NAME);
        // On the constructor every other one chains THROUGH, not on the no-arg one: put it there and
        // a dialog built with a title -- which is most of them, and is what the covering test uses --
        // never makes the declaration at all.
        refusePublicChildren();
        // Out of flow and positioned: a floating panel is placed by left/top against its containing
        // block, not laid out among its siblings.
        StyleGroup.defaultPipeline(getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE).flexDirection(FlexDirection.COLUMN));

        titleLabel = new UIText(title == null ? "" : title);
        titleLabel.addClass(LABEL_CLASS);
        titleLabel.setHitTest(false);

        titleBar = new UINode();
        titleBar.addClass(TITLE_BAR_CLASS);
        titleBar.append(titleLabel);
        appendStructural(titleBar);

        content = new UINode();
        content.addClass(CONTENT_CLASS);
        appendStructural(content);

        closeButton = new Button("x");
        closeButton.addClass(CLOSE_CLASS);
        closeButton.attachListener(this::close);
        titleBar.append(closeButton);

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
    public UINode getTitleBar() {
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
     *       separate mechanism. See {@link UINode#isInert()}.</li>
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
        UIDocument window = document();
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
        // OWNED BY WHATEVER CONTAINS IT, resolved rather than looked up. The old engine asked
        // `WindowFrame.of(this)` and fell back to the global top layer; `overlayHost` asks the same
        // question without naming the answer -- it walks up to the nearest ancestor that accepts
        // children, which is the document while there is no desktop and is the WindowFrame the moment
        // 6.6 lands one. So this class needs no forward reference to a batch that depends on it, and
        // the owner/owned behaviour arrives with the frame rather than with an edit here.
        // PROMOTED ONLY IF NOTHING OWNS IT. The paragraph above is right that the owner/owned
        // behaviour belongs to the frame, and the two `promote` calls under it contradicted it: the top
        // layer is the DOCUMENT's, so an owned modal was hauled out of its window and laid out against
        // the whole screen. That is the standing rule stated backwards -- "a window's modal is OWNED by
        // it, never promoted to the global top layer", because the top layer paints after the entire
        // main tree, so a dialog promoted from one window floats above whichever window is raised next.
        //
        // An owned dialog needs none of it: it is already an out-of-flow child of its frame, which puts
        // it above that frame's content and nowhere else. What it gains by staying there is the frame as
        // its containing block -- so it centres and clamps against the window it belongs to rather than
        // against the screen, which is what "owned" is supposed to mean.
        if (!owned) {
            window.addOverlay(ensureBackdrop(), this);
            window.promote(ensureBackdrop());
            window.promote(this);
        } else if (backdrop == null) {
            // THE BACKDROP GOES BESIDE THE DIALOG, not inside it: the sheet sizes it to 100% of its
            // containing block, and inside the dialog that is the dialog.
            UINode host = parent();
            if (host != null) host.append(ensureBackdrop());
        }
        window.focus().pushModal(this);
        // Modality and Escape are separate registrations because they are separate concerns: a popover has
        // a close watcher without being modal, and a MANUAL popover is neither. Escape asks the topmost
        // watcher, so a dropdown opened inside this modal correctly closes before the modal does.
        window.dismiss().pushCloseWatcher(this);

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
        UIDocument window = document();
        if (window == null) return;
        focusBeforeOpen = window.focus().focused();
        UINode delegate = window.focus().firstFocusableIn(content);
        window.focus().requestFocus(delegate != null ? delegate : this);
    }

    /**
     * The close-watcher hook. Fires a cancelable {@link #onCancel}; closes unless it was prevented.
     *
     * <p>Overrides {@link UINode#requestClose()}, which {@code UIInputHandler} asks of the active modal
     * on Escape. Guarded on {@link #isModal()} too, so a stray call on a modeless dialog cannot give it
     * Escape behaviour the web would not.</p>
     */
    @Override
    public boolean requestClose() {
        // OPEN is the whole condition. This used to require `modal` as well, on the reasoning that only a
        // modal establishes a close watcher -- true, but it conflates "who calls this" with "what it
        // means". UINode.requestClose is the general "ask this element to close" hook, and a modeless
        // dialog that answers false to it cannot be closed by anything that politely asks, including its
        // own Escape handler. Modals are unaffected: their close watcher is still the only thing that
        // reaches them, because Escape is consumed before dispatch ever runs.
        if (!open) return false;

        UIDocument window = document();
        if (window != null) {
            CloseEvent.Cancel cancel = new CloseEvent.Cancel(this);
            window.input().send(this, cancel);
            if (cancel.isDefaultPrevented()) return true; // handled: consumed, but stays open
        }
        close();
        return true;
    }

    /** The scrim. Not a {@code ::backdrop} pseudo-element — this engine has none — but the same idea via
     * the same substitute the widgets already use: an internal child carrying a {@code __} class a theme
     * can target. Promoted alongside the dialog so it covers the viewport rather than the dialog's own box,
     * and inert plus non-hit-testable because it is decoration, not a control. */
    private UINode ensureBackdrop() {
        if (backdrop == null) {
            backdrop = new UINode();
            backdrop.addClass(BACKDROP_CLASS);
            backdrop.setHitTest(false);
            backdrop.setInert(true);
            appendStructural(backdrop);
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
        StyleGroup.inlinePipeline(backdrop.getStyle().getLayoutGroup(),
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
            UIDocument modalWindow = document();
            if (modalWindow != null) {
                modalWindow.focus().popModal(this);
                modalWindow.dismiss().popCloseWatcher(this);
                document().demote(this);
                if (backdrop != null) document().demote(backdrop);
            }
            // AND THE OWNER'S SLOT LETS GO OF ITS BOX -- a full-size owned surface hit-tests, so one
            // left open with nothing showing swallows every click on the window's own content. The
            // dialog stays PARENTED there (it is `display: none` while closed and re-shown from where
            // it already is); only its claim on the slot ends.
            //
            // ANNOUNCED, NOT CALLED. The old engine released the slot from here, which a dialog on
            // this engine cannot do: `WindowFrame` is `desktop` and this is `widget.overlay`, one
            // layer below, so naming it is the upward reference LayeringTest refuses. `onClosed`
            // below is what a frame connects to in `attachOwned` -- the dependency points one way
            // and the release goes with it.
        }

        UIDocument window = document();
        if (window != null && focusBeforeOpen != null
                && focusBeforeOpen.document() == window) {
            window.focus().requestFocus(focusBeforeOpen);
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

        StyleGroup.inlinePipeline(getStyle().getLayoutGroup(),
                l -> l.display(open ? TaffyDisplay.FLEX : TaffyDisplay.NONE));
    }


    // ── Position ────────────────────────────────────────────────────────────

    /** Places the dialog against its containing block, clamped so it cannot be put out of reach. */
    public Dialog moveTo(float left, float top) {
        placed = true;
        applyPosition(left, top);
        return this;
    }

    /**
     * Whether anything has said where this dialog goes.
     *
     * <p>Until something does it centres itself, which is what every toolkit does with a dialog nobody
     * placed and what this engine's own windows do ({@code Desktop.placeByCascade}). The alternative is
     * the {@code left}/{@code top} initial of zero, which puts it in the corner of its containing block
     * -- indistinguishable on screen from a dialog whose placement was never run at all, which is the
     * failure the compositor already records for an unplaced window.</p>
     */
    private boolean placed;

    /**
     * Centres an unplaced dialog, once there is something to measure.
     *
     * <p><b>Not at {@code show()}</b>: a dialog that has just been displayed has never been laid out,
     * so both its own size and its containing block's are unknown -- the "measures zero on the same
     * frame" trap. Centring against zeroes is centring at the origin, which is exactly the corner this
     * exists to avoid, and it would then be {@code placed} and never corrected.</p>
     */
    private void centreIfUnplaced() {
        if (placed) return;
        Box self = box();
        Box container = self == null ? null : self.host();
        if (container == null) return;
        // A ZERO-SIZED BOX IS NOT A MEASURED ONE. Both are legal answers from a laid-out tree, and
        // waiting costs a frame in which the dialog is at the corner; centring on them costs the
        // placement for good.
        if (self.width() <= 0f || self.height() <= 0f) return;
        if (container.width() <= 0f || container.height() <= 0f) return;
        placed = true;
        applyPosition((container.width() - self.width()) * 0.5f,
                (container.height() - self.height()) * 0.5f);
    }

    /**
     * <b>{@code resize:} has no counterpart on this engine yet, so the three hooks it needed are
     * gone.</b>
     *
     * <p>They were {@code applyResizeOrigin}, {@code resizeOriginLeft} and {@code resizeOriginTop} —
     * {@code UIResizer}'s seam for a box whose position is state the widget owns rather than a
     * stylesheet inset. The reason they existed is unchanged and worth keeping written down: without
     * them a leading-edge drag and this dialog's own clamp fight every frame, the handle writing the
     * inset and the clamp putting {@code posLeft}/{@code posTop} back on the next tick, so dragging
     * the left edge resizes the box while snapping its origin home.</p>
     *
     * <p>M6 D6 chose a resize MODE over an edge band rather than eight handle nodes, and 6.0 did not
     * build it — nothing before this needed one. So a ported {@code Dialog} MOVES and does not RESIZE,
     * which is a visible, stated gap rather than a silent one, and {@code ResizeTest} stays on the old
     * engine until the mode exists. Moving is unaffected: it goes through {@code Drag} and writes the
     * same INLINE insets it always did.</p>
     */

    private void beginMove(float pointerX, float pointerY) {
        UIDocument window = document();
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
        // POINTER, not programmatic. `requestFocus` scrolls its target into view -- correct for
        // focus that lands off-screen, and wrong for a press, which by definition landed on something
        // already visible. Through the programmatic entry, grabbing a dialog's title bar scrolled the
        // whole page under it.
        window.focus().requestPointerFocus(this);

        dragStartLeft = posLeft;
        dragStartTop = posTop;

        // Positional drag, zero threshold: a window must track the very first pixel, and there is no
        // competing click interpretation on a title bar to protect.
        Drag.start(titleBar, pointerX, pointerY,
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
        // THE CONTAINING BLOCK IS THE BOX'S HOST, and asking the box is what makes that true for both
        // cases at once: a dialog promoted to the top layer is hosted by the document, and one owned by
        // a window is an out-of-flow child of the frame and hosted by it. The two diverge exactly here
        // -- the standing rule that a promoted node's containing block is not its node parent -- and
        // neither is named. This used to read `document()` with a note that at 6.6 it would become the
        // WindowFrame; 6.6 landed and the note stayed, so every owned modal was clamped, and centred,
        // against the whole screen rather than against the window it belongs to.
        Box self = box();
        Box containerBox = self == null ? null : self.host();
        float maxLeft = Float.MAX_VALUE, maxTop = Float.MAX_VALUE;
        // UNCLAMPED until both have been laid out, rather than clamped to zero: a dialog positioned
        // before its first layout would otherwise be pinned to the corner and stay there, which is
        // the shape the standing row warns about -- "zero-sized" and "never laid out" are different
        // facts and only one of them is a constraint.
        if (containerBox != null && self != null) {
            maxLeft = Math.max(0f, containerBox.width() - self.width());
            maxTop = Math.max(0f, containerBox.height() - self.height());
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
     * Keeps the dialog inside its containing block for as long as it is open.
     *
     * <p>A standing per-frame hook, which is what the {@code ClampTicker} inner class was — the
     * engine has no ticker INTERFACE any more, so a one-method class implementing one is just a
     * lambda. It still returns {@code false} to drop itself when the dialog closes, and it is owned
     * by this node, so a dialog that leaves the tree stops costing anything without saying so.</p>
     *
     * <p>Cost is a comparison and a no-op style write per open dialog per frame:
     * {@code replaceOrPutCandidate} discards an unchanged value, so a stationary dialog stops
     * re-triggering layout after the first frame.</p>
     */
    private void startClampTicker() {
        if (clampTickerRunning) return;
        UIDocument window = document();
        if (window == null) return;
        clampTickerRunning = true;
        // AFTER LAYOUT, because every question this hook asks is about a MEASURED box: the clamp needs
        // the containing block's size and this dialog's own, and the centring needs both to exist at
        // all. Run BEFORE layout it reads the previous frame's answer -- which on the frame a dialog is
        // first shown is no answer at all, since a box that was `display: none` is not a box. That is
        // the whole of why an owned modal appeared in its window's corner for one frame before
        // centring; `UIDocument.settleAfterLayout` is what carries what this writes into the same
        // frame's picture rather than the next one's.
        window.animation().afterLayout(this, delta -> {
            if (!open) {
                clampTickerRunning = false;
                return false;
            }
            centreIfUnplaced();
            applyPosition(posLeft, posTop);
            return true;
        });
    }

    /**
     * Whether a host has taken responsibility for placing this dialog — set by {@code attachOwned}.
     *
     * <p>The one thing a dialog cannot work out for itself. Being parented somewhere is not the same
     * as being OWNED there: every overlay is parented somewhere. What the flag records is that whoever
     * did it intends to host the dialog, which is exactly what promotion would undo.</p>
     */
    private boolean owned;

    /** Declared by whatever hosts this dialog, so {@code showModal} leaves it where it was put. */
    public Dialog setOwned(boolean owned) {
        this.owned = owned;
        return this;
    }

    private boolean clampTickerRunning;


    /** A dialog owns its structure; content goes in {@link #getContent()}. */
    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }
}
