package com.crystalgui.ui.elements.desktop;

import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.dispose.Disposer;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.render.texture.CgUiSvg;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.ui.input.UIDragController;
import com.crystalgui.ui.input.UIInputHandler;
import com.crystalgui.ui.tree.UITreeTraversal;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.TaffyPosition;

import javax.annotation.Nullable;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * One window on the {@link Desktop} — chrome around a content slot, moved by its title bar and resized
 * by its eight edges.
 *
 * <p>CrystalOS's unit of stacking ({@code plan_windowing.md}). A window is an <b>element subtree</b>,
 * not a {@link UIWindow}: that class is the engine's {@code Document} analogue and the display surface
 * every frame here shares. The network layer already models a window as {@code (windowId, UIElement
 * root)}, so this is the visual home that model never had.</p>
 *
 * <h3>Extends {@link UIElement}, never {@code Dialog}</h3>
 * <p>{@code Dialog}'s bundle is modality, a close watcher and a backdrop — exactly what a frame must not
 * inherit, and {@code FloatingDock}'s javadoc already paid for that lesson once. What is taken from
 * {@code Dialog} is the <em>pattern</em>: a positional drag from the title bar writing {@code left}/
 * {@code top} at <b>INLINE</b> origin, matching what CSS {@code resize} mandates for the size
 * {@code UIResizer} writes, so an author's {@code !important} can still pin a window down. The two
 * halves of user-driven geometry stay in one origin.</p>
 *
 * <h3>The clamp is Windows', not {@code Dialog}'s</h3>
 * <p>{@code Dialog} and {@code CanvasOverlayMove} both clamp a panel <em>fully inside</em> its container,
 * which is right for a panel over a canvas and wrong for a window: a window wider than the desktop could
 * then never be dragged far enough to reach its own right-hand side. Every window manager clamps the
 * other way round — <b>the title bar must stay reachable</b>, and the body may hang off the sides and the
 * bottom. So {@code top} is pinned into {@code [0, workArea - caption]} and {@code left} may travel until
 * only a caption's width is left on screen. The sliver is <b>measured from the title bar</b> rather than
 * written here as a pixel constant, which is the same trick {@code NodePort.typeColor()} uses to keep a
 * number in the sheet where a theme can move it.</p>
 *
 * <h3>Intent and placement are two fields, deliberately</h3>
 * <p>{@link #wantedLeft}/{@link #wantedTop} is what was <em>asked</em> for; {@link #placedLeft}/
 * {@link #placedTop} is what was written after clamping. Keeping both is what makes a desktop resize
 * non-destructive: shrinking the desktop pulls a window in, and growing it back returns the window to
 * where the user actually put it. Clamping the stored value instead — which is what a single field
 * forces — quietly rewrites the user's intent, and the window never comes back.</p>
 *
 * <p>Neither is re-derived from the resolved box. {@code Dialog} records why: the clamp runs during
 * {@code advanceFrame}, <em>before</em> {@code calculateLayout}, so a frame that has not laid out yet
 * measures zero and reading it back writes that zero straight into the position.</p>
 *
 * <h3>What is deliberately not here yet</h3>
 * <ul>
 *   <li><b>A maximise button.</b> Its behaviour is W6. A control that looks clickable and does nothing
 *       is the lie the disabled-control rule already forbids, so it arrives with the geometry it
 *       operates rather than as greyed furniture.</li>
 * </ul>
 *
 * <p><b>One known W1 artefact.</b> {@code UIResizer} keeps a resize inside the containing block, which
 * this class's move-clamp deliberately does not. So dragging the <em>trailing</em> edge of a window that
 * is currently hanging off that edge pulls it back to the desktop's boundary instead of growing it. The
 * leading edges are unaffected (the same containment stops the origin going negative, which is where
 * this clamp starts), and the fix belongs with W6's maximise/restore geometry rather than in a special
 * case here.</p>
 */
public class WindowFrame extends UIElement implements Disposable {

    /** The drag handle, and everything drawn in it. */
    public static final String TITLE_BAR_CLASS = "__title-bar__";
    /**
     * The window's title text.
     *
     * <p><b>Not {@code __label__}</b>, which is the engine's usual hook for a widget's own text. A frame
     * contains other widgets that have one — every {@code Button} in the title bar for a start — so
     * {@code window .__label__} would reach them too, and the close button's glyph would be styled as a
     * window title. {@code Dialog} scopes its way out of that with {@code .__title-bar__ .__label__};
     * naming the role is cheaper and cannot be got wrong by a theme.</p>
     */
    public static final String TITLE_CLASS = "__title__";
    /** The button strip at the trailing end of the title bar. */
    public static final String CONTROLS_CLASS = "__controls__";
    /** The close affordance, sharing {@code Dialog}'s class so a theme styles both at once. */
    public static final String CLOSE_CLASS = "__close__";
    /**
     * The content slot.
     *
     * <p><b>Never target it with a descendant selector.</b> {@code CrystalEditor}, {@code ProjectFileTree}
     * and {@code ConfiguratorGroup} all name a child of their own {@code __content__}, so
     * {@code window .__content__} reaches every one of them inside any window and zeroes their heights —
     * the trap that has now been sprung three times. The sheet uses {@code window > .__content__}.</p>
     */
    public static final String CONTENT_CLASS = "__content__";

    /**
     * On the active window — the one the keyboard is talking to.
     *
     * <p>A <b>class</b>, not a pseudo-class, because this is state the compositor flips from its own
     * listeners: the engine re-evaluates a pseudo-class on its terms and a class on yours, and there is
     * no {@code :active-window} to add. {@code :checked}, {@code :disabled} and {@code :hover} have each
     * cost a round by being tried this way round first.</p>
     */
    public static final String ACTIVE_CLASS = "__active__";

    /** The minimise affordance. Hides; never destroys, whatever the policy says. */
    public static final String MINIMIZE_CLASS = "__minimize__";

    /** The window's icon slot, hidden until {@link #setIcon} gives it something to draw. */
    public static final String ICON_CLASS = "__icon__";

    /**
     * Where this window's <b>owned</b> windows live — its modal dialogs, and from W8 its floating tool
     * windows. The same class the window-level layer uses, because it is the same role one level down.
     *
     * @see #overlaySlot()
     */
    public static final String OVERLAY_CLASS = "__overlays__";

    /**
     * Emitted when the window comes back, carrying <b>{@code persisted}</b> — whether it was restored
     * from retention rather than shown for the first time.
     *
     * <p>bfcache's {@code pageshow} event and its {@code event.persisted} flag, and the reason it is a
     * flag rather than two signals is the reason that spec gives: a restored page must
     * <em>revalidate</em> — reconnect what it disconnected, re-read what may have changed — while a
     * first show has nothing to revalidate against. "The user pressed Escape" and "the world went away"
     * have to stay different signals, and this is where they diverge. Nothing in the engine reads it
     * yet; W11 is where a workspace client rebinds on it.</p>
     */
    public final Signal.Value<Boolean> onShown = new Signal.Value<>();

    /** Emitted when the window is hidden — retained, but no longer participating in anything. */
    public final Signal.Action onHidden = new Signal.Action();

    /** Emitted when the window is destroyed, after {@code Disposer} has run. */
    public final Signal.Action onDestroyed = new Signal.Action();

    private final UIElement titleBar;
    private final UIElement controls;
    private final UIElement content;
    private final UIElement overlays;
    private final UIText titleLabel;
    private final UIElement icon;
    private final Button closeButton;
    private final Button minimizeButton;

    /** What was asked for, never clamped. @see WindowFrame */
    private float wantedLeft, wantedTop;
    /** What was written, after clamping. What a drag and {@code UIResizer} both measure from. */
    private float placedLeft, placedTop;
    /** Whether a position has been written at all — {@code Desktop} cascades the ones that have not. */
    private boolean placed;

    /** Origin at the moment a move began. Accumulating from here rather than from the live box keeps a
     * drag from compounding its own deltas — the same reason {@code UIResizer} snapshots its size. */
    private float dragStartLeft, dragStartTop;

    /** Where focus was when this window last had it. @see #restoreFocus */
    @Nullable
    private UIElement lastFocused;

    /** This window's place in the stack, as last assigned. @see Desktop#raise */
    private int stackOrder;

    private WindowState state = WindowState.VISIBLE;
    private WindowPolicy policy = WindowPolicy.DESTROY_ON_CLOSE;

    /**
     * The desktop this window belongs to — a <b>field, not a tree walk</b>.
     *
     * <p>It has to be, once hiding exists: a hidden window is detached, so walking up from it finds
     * nothing, and yet it very much still belongs to a desktop — that is what retention means. The
     * walk was only ever a way of asking this question while the answer happened to be reachable.</p>
     */
    @Nullable
    private Desktop owner;

    /** @see #key() */
    @Nullable
    private String key;

    /** @see #setIcon */
    @Nullable
    private String iconName;

    /** @see #setDiscardGuard */
    private BooleanSupplier discardGuard = () -> true;

    /** What is currently SHOWING on the owned surface. @see #releaseOwned */
    private final Set<UIElement> live = new LinkedHashSet<>();

    public WindowFrame(String title) {
        // Out of flow and positioned: a window is placed by left/top against the desktop's window layer,
        // not laid out among its siblings. This also earns the four LEADING resize handles --
        // rebuildResizers withholds them from anything an origin write cannot actually move.
        StyleGroup.defaultPipeline(getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE).flexDirection(FlexDirection.COLUMN));

        titleLabel = new UIText(title == null ? "" : title);
        titleLabel.addClass(TITLE_CLASS);
        // FALLS THROUGH TO THE BAR. The move listener below is target-only, so a press that lands on the
        // title text would otherwise begin nothing at all -- and "the title bar drags except where the
        // title is" is indistinguishable from a broken drag.
        titleLabel.setHitTest(false);

        controls = new UIElement();
        controls.addClass(CONTROLS_CLASS);

        // MINIMISE FIRST, so the strip reads minimise-then-close left to right as every window manager
        // draws it, and so the destructive control is the one furthest from the rest.
        minimizeButton = new Button("");
        minimizeButton.addClass(MINIMIZE_CLASS);
        minimizeButton.attachListener(this::hide);
        controls.addChild(minimizeButton);

        closeButton = new Button("");
        closeButton.addClass(CLOSE_CLASS);
        closeButton.attachListener(this::requestClose);
        controls.addChild(closeButton);

        // BUILT NOW AND HIDDEN, rather than created when an icon arrives. Creating an element from a
        // setter means creating it possibly mid-gesture, and the title bar has no `gap-all` for a hidden
        // child to occupy — the one cost that would have made the lazy version worth it.
        icon = new UIElement();
        icon.addClass(ICON_CLASS);
        icon.setHitTest(false);
        icon.setDisplayed(false);

        titleBar = new UIElement();
        titleBar.addClass(TITLE_BAR_CLASS);
        titleBar.addChild(icon);
        titleBar.addChild(titleLabel);
        titleBar.addChild(controls);
        addInternalChild(titleBar);

        content = new UIElement();
        content.addClass(CONTENT_CLASS);
        addInternalChild(content);

        overlays = new UIElement();
        overlays.addClass(OVERLAY_CLASS);
        addInternalChild(overlays);
        syncOverlaySlot();

        // TARGET-ONLY (false, false), which is Dialog's spelling and not CanvasOverlayMove's. The two
        // booleans are ADDITIVE -- the target phase is always subscribed -- so (false, true) would also
        // fire for anything that BUBBLES here, and the close button is inside this bar: a press on it
        // would start a window drag as well as closing the window.
        titleBar.onMouseDown.attachListener((element, event) ->
                beginMove(event.getPosition().x(), event.getPosition().y()), false, false);

        installActivation();
        // CLICK_NOT_TABBABLE is the web's tabindex="-1", and both halves are wanted. A frame must be able
        // to HOLD focus -- it is where focus lands when a window's content has nowhere to put it, and
        // (from W13) where its commands resolve from. It must not be a TAB STOP: Tab moves between the
        // controls inside a window, and a tablist-of-ten's worth of extra stops is exactly what the
        // roving-tabindex pattern exists to avoid.
        //
        // It also earns click-to-focus for free. emitMouseDown walks up to the nearest ancestor that
        // focusesOnClick(), so a press on a window's title bar or bare background -- neither of which is
        // focusable -- lands focus on the frame rather than nowhere.
        setFocusPolicy(FocusPolicy.CLICK_NOT_TABBABLE);
    }

    /**
     * Raise-and-activate on a press, in the <b>capture</b> phase.
     *
     * <p>Capture on the frame, not the target phase and not a listener on whatever was clicked: a widget
     * that stops propagation in its own handler would otherwise pre-empt this, and
     * {@code stopPropagation()} is {@code stopImmediatePropagation} <em>within a phase</em> — so even a
     * listener on the same element attached later can be starved. {@code TextEditor}'s unconditional
     * {@code stopPropagation()} on mouse-down is the recorded case, and it cost two rounds of looking at
     * coordinates before anyone looked at the phase. Capture runs before any of it.</p>
     *
     * <p>Attached here rather than by {@code Desktop.addWindow} because listeners are additive — the
     * {@code Tooltip.attach} trap — so a frame that left a desktop and came back would raise twice per
     * press. The desktop is found at event time instead, which is two hops up the tree.</p>
     */
    private void installActivation() {
        onMouseDown.attachListener((element, event) -> {
            Desktop desktop = desktop();
            if (desktop != null) desktop.activate(this);
        }, true, false);

        // FOCUS MEMORY. Win32 records the focus owner per window and restores it on WM_ACTIVATE; without
        // it, coming back to a window puts the caret wherever the delegate happens to be rather than
        // where the user left it.
        //
        // Bubbled, so it sees focus landing anywhere inside the frame, and it deliberately does NOT
        // record the frame ITSELF: a press on the title bar focuses the frame (the ancestor walk above),
        // so recording that would let dragging a window forget the field you were typing in.
        onFocus.attachListener((element, event) -> {
            if (event.getTarget() != null && event.getTarget() != this) lastFocused = event.getTarget();
        }, false, true);
    }

    /** A window owns its chrome; put content in {@link #content()}. */
    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }

    // ── The parts ───────────────────────────────────────────────────────────

    /** Where a window's content goes. The named accessor a composite owes its callers. */
    public UIElement content() {
        return content;
    }

    /** The drag handle. Exposed so a caller may add chrome of its own beside the title. */
    public UIElement titleBar() {
        return titleBar;
    }

    /** The button strip. Exposed for the same reason {@link #titleBar()} is. */
    public UIElement controls() {
        return controls;
    }

    public Button closeButton() {
        return closeButton;
    }

    public Button minimizeButton() {
        return minimizeButton;
    }

    /** The icon this window declares, or null. @see #setIcon */
    @Nullable
    public String iconName() {
        return iconName;
    }

    /**
     * Declares the window's icon — {@code "namespace:name"}, the way a file type does.
     *
     * <p>Drawn in <b>two</b> places, the title bar and the taskbar entry, which is why this is a name on
     * the window rather than an element a caller builds: an icon set in one place and not the other is
     * how a window comes to look like two different windows.</p>
     *
     * <p>Resolved through {@link CgUiSvg#ofIcon}, never {@code of(path)} — that is what binds the
     * light/dark variant at draw time. The one time a caller reached past it, every {@code icon()} in
     * every stylesheet drew the light file forever and a theme swap changed nothing.</p>
     */
    public WindowFrame setIcon(@Nullable String namespacedIcon) {
        this.iconName = namespacedIcon;
        if (namespacedIcon == null) {
            icon.setDisplayed(false);
            return this;
        }
        CgUiSvg glyph = CgUiSvg.ofIcon(namespacedIcon);
        if (glyph == null) return this;
        icon.setDisplayed(true);
        StyleGroup.defaultPipeline(icon.getStyle().getGeneralGroup(), g -> g.overlay(glyph));
        Desktop desktop = owner;
        if (desktop != null) desktop.registry().changed();
        return this;
    }

    public WindowFrame setTitle(String title) {
        titleLabel.setText(title == null ? "" : title);
        return this;
    }

    public String getTitle() {
        return titleLabel.getText();
    }

    // ── Owned windows ───────────────────────────────────────────────────────

    /**
     * The surface this window's <b>owned</b> windows are parented on — a modal dialog today, a floating
     * tool window at W8.
     *
     * <h3>Owned, not promoted</h3>
     * <p>Win32's rule decides this: an owned window stays above its owner <em>and travels with it</em>.
     * A modal in the global top layer would float above whichever window happened to be raised next,
     * because the top layer paints after the whole main tree by construction — so a dialog opened in
     * one window would end up over another. Parenting it <em>inside</em> the frame gets the whole group
     * raising, lowering and hiding as one with no bookkeeping at all: the owner's {@code z-index}
     * carries its owned windows with it, and detaching the owner detaches them.</p>
     *
     * <p>The slot sits above {@code __content__} within the frame (a {@code z-index} in the sheet), and
     * the frame itself keeps {@code overflow: visible} while only the content clips — so an owned
     * window may legally overhang its owner's edge, which a dialog wider than a narrow window must.</p>
     *
     * <h3>Sized only while it holds something</h3>
     * <p>The same rule the desktop follows, for the same reason: a full-size slot hit-tests, so an
     * empty one would sit over the window's own content and swallow every click. Use
     * {@link #attachOwned}/{@link #detachOwned} rather than parenting into it by hand — they are what
     * keep that in step.</p>
     */
    public UIElement overlaySlot() {
        return overlays;
    }

    /** Parents an owned window onto this frame and gives the slot a box to hold it in. */
    public void attachOwned(UIElement owned) {
        if (owned == null) return;
        if (owned.getParent() != overlays) overlays.addChild(owned);
        live.add(owned);
        syncOverlaySlot();
    }

    /**
     * The counterpart — {@code owned} is no longer showing, so it stops holding the slot open.
     *
     * <p><b>It stays parented</b>, and that is not an oversight: a {@code Dialog} is closed with
     * {@code display: none} and re-shown from wherever it already is, so removing it from the tree
     * would make the second {@code show()} put it nowhere. What has to end is the slot's <em>box</em> —
     * a full-size slot hit-tests, so one left open over a window with nothing in it swallows every
     * click on that window's content. Hence a set of what is live rather than a look at the children:
     * "parented here" and "currently showing" are different questions and only the second one sizes
     * anything.</p>
     */
    public void releaseOwned(UIElement owned) {
        if (owned == null) return;
        live.remove(owned);
        syncOverlaySlot();
    }

    /** Whether anything is currently showing on this frame's owned surface. */
    public boolean hasOwnedWindows() {
        return !live.isEmpty();
    }

    private void syncOverlaySlot() {
        boolean occupied = !live.isEmpty();
        StyleGroup.importantPipeline(overlays.getStyle().getLayoutGroup(), l -> {
            l.positionType(TaffyPosition.ABSOLUTE).left(0).top(0);
            if (occupied) l.widthPercent(100f).heightPercent(100f);
            else l.width(0).height(0);
        });
    }

    /**
     * The window {@code element} belongs to, or null — its nearest frame ancestor.
     *
     * <p>The DOM chain, which is the right one: promotion moves a Taffy node and never a DOM parent, so
     * a promoted dialog is still inside the window that opened it.</p>
     */
    @Nullable
    public static WindowFrame of(@Nullable UIElement element) {
        for (UIElement el = element; el != null; el = el.getParent()) {
            if (el instanceof WindowFrame) return (WindowFrame) el;
        }
        return null;
    }

    // ── Activation ──────────────────────────────────────────────────────────

    /** Whether this is the window the keyboard is talking to. */
    public boolean isActive() {
        return hasClass(ACTIVE_CLASS);
    }

    /** Driven by {@link Desktop#activate}, which is the only thing that may decide this. */
    void setActive(boolean active) {
        if (active) addClass(ACTIVE_CLASS);
        else removeClass(ACTIVE_CLASS);
    }

    /** Where this window's stacking order currently sits. @see Desktop#raise */
    int stackOrder() {
        return stackOrder;
    }

    /**
     * Assigns this window's place in the stack.
     *
     * <p><b>IMPORTANT origin</b>, so a stylesheet cannot fight activation: a theme that gave
     * {@code window} a {@code z-index} would otherwise pin every window at the same depth and the
     * compositor would silently stop stacking. Everything else about a window's appearance stays in the
     * sheet; this one number is the engine's.</p>
     */
    void setStackOrder(int order) {
        this.stackOrder = order;
        StyleGroup.importantPipeline(getStyle().getGeneralGroup(), g -> g.zIndex(order));
    }

    /**
     * Puts focus back where this window left it — <b>restoring, never stealing</b>.
     *
     * <p>The {@code ListView.restoreFocusIfRealised} rule, and the first line is the whole of it: if
     * focus is already somewhere inside this window, it was never lost and moving it would be theft.
     * That is what makes this safe to call on every activation, including the one a click causes —
     * {@code emitMouseDown} has already focused whatever was pressed by the time this runs, so a click
     * on a control inside an inactive window activates the window <em>and</em> keeps the control.</p>
     *
     * <p>Falls back the way the dialog focusing steps do: the remembered element if it is still here and
     * still focusable, else the first focusable in the content, else the frame itself — which is legal
     * precisely because a frame is {@code CLICK_NOT_TABBABLE} rather than {@code NONE}.</p>
     *
     * @param programmatic whether this activation came from somewhere other than the pointer — a
     *                     command, the switcher, a taskbar entry. Programmatic focus <b>rings</b> and
     *                     pointer focus does not, and that distinction is exactly what
     *                     {@code :focus-visible} exists for; handing a click the ringing one outlines a
     *                     window on every press.
     */
    void restoreFocus(boolean programmatic) {
        UIWindow window = getAttachedWindow();
        if (window == null) return;
        UIInputHandler input = window.getInputHandler();
        if (contains(input.getFocusedElement())) return;

        UIElement wanted = contains(lastFocused) && lastFocused.focusable() ? lastFocused : null;
        if (wanted == null) wanted = UITreeTraversal.firstFocusableIn(content);
        if (wanted == null) wanted = this;
        if (programmatic) input.requestFocus(wanted);
        else input.requestPointerFocus(wanted);
    }

    /** Whether {@code element} is this frame or inside it. */
    private boolean contains(@Nullable UIElement element) {
        for (UIElement walk = element; walk != null; walk = walk.getParent()) {
            if (walk == this) return true;
        }
        return false;
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    public WindowState state() {
        return state;
    }

    public WindowPolicy policy() {
        return policy;
    }

    /** @see WindowPolicy */
    public WindowFrame setPolicy(WindowPolicy policy) {
        this.policy = policy == null ? WindowPolicy.DESTROY_ON_CLOSE : policy;
        return this;
    }

    /**
     * A stable name for this window, or null.
     *
     * <p>What geometry is persisted against (W12) and what "reopen the thing I had open" looks a window
     * up by. Anonymous windows are legal and simply never match.</p>
     */
    @Nullable
    public String key() {
        return key;
    }

    public WindowFrame setKey(@Nullable String key) {
        this.key = key;
        return this;
    }

    /**
     * Whether this window's content may be thrown away — asked before a destroying close and before
     * eviction, so content answers it once and both paths agree.
     *
     * <p>The dock's {@code setCloseGuard} in a second place, with its contract intact: a caller that
     * needs to <em>prompt</em> runs the prompt itself and re-enters from its own callback, because the
     * prompt is asynchronous and the honest answer at the moment of the veto is "not now" rather than
     * "yes eventually".</p>
     */
    public WindowFrame setDiscardGuard(@Nullable BooleanSupplier guard) {
        this.discardGuard = guard == null ? () -> true : guard;
        return this;
    }

    boolean canDiscard() {
        return discardGuard.getAsBoolean();
    }

    /**
     * The close-watcher hook — "dismiss me" — routed through {@link WindowPolicy}.
     *
     * <p>The close button and Escape both arrive here, so there is exactly one dismissal path. By the
     * time anything reaches a window, the Escape cascade has already filtered: a live drag, a popover
     * and a modal each consume it first, and all three genuinely should.</p>
     *
     * <p>Returns {@code true} for "handled", which includes a refusal: a guard that says no has still
     * dealt with the request, and answering false would let the key fall through to whatever is behind
     * — closing the screen the window is on, in the worst case.</p>
     */
    @Override
    public boolean requestClose() {
        if (state == WindowState.DESTROYED) return false;
        if (policy == WindowPolicy.HIDE_ON_CLOSE) {
            hide();
            return true;
        }
        if (!canDiscard()) return true;
        destroy();
        return true;
    }

    /**
     * Retains the window and takes it off the desktop — <b>by detaching it</b>, which is what makes the
     * freeze real. See {@link WindowState#HIDDEN}.
     *
     * <p>Everything that has to happen on the way out already happens at the seams:
     * {@code unregisterElement} captures session state and pops the modal, popover, close-watcher and
     * top-layer entries, and {@code onRemoved} recurses the subtree telling the input handler to forget
     * every element in it. A window hidden with a dialog open therefore cannot leave the desktop inert,
     * which is the documented unrecoverable state.</p>
     */
    public void hide() {
        if (state != WindowState.VISIBLE) return;
        UIElement layer = getParent();
        if (layer == null) {
            state = WindowState.HIDDEN;
            onHidden.emit();
            return;
        }
        // The layer's removeChild is what flips the state and tells the registry -- so a bare
        // removeSelf() by some other caller means exactly the same thing as hide(), rather than leaving
        // a window that is detached and still claims to be visible.
        layer.removeChild(this);
    }

    /**
     * Puts the window back on its desktop.
     *
     * @param persisted whether this is a restore rather than a first show — see {@link #onShown}.
     */
    public void show(boolean persisted) {
        if (state == WindowState.DESTROYED) {
            throw new IllegalStateException("a destroyed window cannot be shown again: " + getTitle());
        }
        if (state == WindowState.VISIBLE && getParent() != null) return;
        if (owner == null) return;
        state = WindowState.VISIBLE;
        owner.reattach(this);
        onShown.emit(persisted);
    }

    /**
     * Ends the window: {@code Disposer} runs, the registry drops it, and the instance must never be
     * shown again.
     *
     * <p>{@code Disposer.dispose} rather than a direct call, so anything registered against this frame
     * — a workspace client, a subscription, a document view — goes with it, in reverse registration
     * order and with a throw from one teardown not stopping the rest. That ownership tree is what the
     * class exists for, and eviction leans on it entirely.</p>
     */
    public void destroy() {
        Disposer.dispose(this);
    }

    /**
     * The {@code Disposable} half of {@link #destroy()} — the teardown itself.
     *
     * <p>Called by {@code Disposer}, which has already marked it disposed and will not call it twice,
     * so this needs no re-entrancy guard of its own beyond the state check.</p>
     */
    @Override
    public void dispose() {
        if (state == WindowState.DESTROYED) return;
        // READ BEFORE hide() clears it, and this is the whole of the hide/destroy distinction. Hiding
        // hands activation to nobody -- putting a window away is not asking for another one, and
        // activation drags the keyboard with it. Destroying is different: the window it was in is gone,
        // so leaving nothing active would leave the keyboard nowhere until the user clicked. Windows
        // hands over in both cases; ours only does here, because ours moves focus.
        //
        // Also false for a window that was already hidden -- eviction destroys those, and evicting
        // something the user put away must not reach in and change what they are looking at.
        boolean wasActive = owner != null && owner.activeWindow() == this;

        hide();
        state = WindowState.DESTROYED;
        Desktop desktop = owner;
        owner = null;
        if (desktop != null) {
            desktop.registry().destroyed(this);
            if (wasActive) desktop.activateTopmost();
        }
        onDestroyed.emit();
    }

    /**
     * A window is its own last close watcher, so Escape reaches its {@link #requestClose() policy}
     * once everything it contains has had a turn.
     *
     * <p>Registered from the attach hook rather than from {@code Desktop.addWindow}, because a hidden
     * window is DETACHED and {@code unregisterElement} pops its watchers on the way out — so showing it
     * again has to re-register, and this is the one place both routes pass through.</p>
     *
     * <p>The frame goes on the stack FIRST, before any dialog it later opens, which is what makes the
     * cascade come out in the right order: the dropdown, then the modal, then the window itself.</p>
     */
    @Override
    protected void onWindowChanged(@Nullable UIWindow previous, @Nullable UIWindow current) {
        super.onWindowChanged(previous, current);
        if (current != null) current.pushCloseWatcher(this);
    }

    /** Set by {@link Desktop#addWindow}; cleared when the window is destroyed. */
    void setOwner(@Nullable Desktop desktop) {
        this.owner = desktop;
    }

    /** Flips the state on the way out of the tree, whichever route detached it. */
    void markHidden() {
        if (state != WindowState.VISIBLE) return;
        state = WindowState.HIDDEN;
        onHidden.emit();
    }

    // ── Geometry ────────────────────────────────────────────────────────────

    /**
     * Places the window against the desktop's window layer, clamped so its title bar stays reachable.
     *
     * <p>Marks the frame <b>placed</b>, so {@link Desktop} stops cascading it. A position handed in by a
     * caller — or read back from a session — is every bit as deliberate as one dragged to, which is the
     * correction {@code CanvasOverlayMove.markPlaced} had to make after gating on drags alone.</p>
     */
    public WindowFrame moveTo(float left, float top) {
        placed = true;
        applyPosition(left, top);
        return this;
    }

    /**
     * Sizes the window.
     *
     * <p><b>INLINE</b>, which is the same slot {@code UIResizer} writes — so a user's drag legitimately
     * replaces what a caller asked for rather than fighting it every frame. That collision is the correct
     * one: the spec has a user resize replace "existing property declaration(s) in the style attribute".</p>
     */
    public WindowFrame resizeTo(float width, float height) {
        StyleGroup.inlinePipeline(getStyle().getLayoutGroup(), l -> l.width(width).height(height));
        return this;
    }

    /** Whether this frame has been given a position — by a caller, a drag, or the desktop's cascade. */
    public boolean isPlaced() {
        return placed;
    }

    /** The window's origin inside the work area, as last written. */
    public float left() {
        return placedLeft;
    }

    public float top() {
        return placedTop;
    }

    /**
     * A window owns its position, so a top/left resize handle has to go through it rather than writing
     * {@code left}/{@code top} itself — otherwise the handle's write and the next clamp fight, and the
     * window resizes while snapping its origin home. {@code Dialog} records the same trap.
     */
    @Override
    protected void applyResizeOrigin(float left, float top) {
        placed = true;
        applyPosition(left, top);
    }

    /** The written position once there is one; the measured offset until then. @see UIElement#resizeOriginLeft */
    @Override
    protected float resizeOriginLeft() {
        return placed ? placedLeft : super.resizeOriginLeft();
    }

    @Override
    protected float resizeOriginTop() {
        return placed ? placedTop : super.resizeOriginTop();
    }

    /**
     * Re-clamps this window against the work area as it is now.
     *
     * <p>Driven by {@link Desktop}'s window layer when <em>it</em> resizes, because that is the thing
     * that moved: an absolutely positioned frame at a fixed size sees no layout change of its own when
     * the desktop shrinks under it, so its own callback never fires. {@code CanvasOverlayMove} records
     * the same asymmetry, and the failure it causes — an edge sliding past a panel that never moved,
     * which reads as a z-order bug and is nowhere near one.</p>
     *
     * <p>Re-clamps from the <b>wanted</b> position, so a window pushed in by a shrinking desktop returns
     * to where the user put it when the room comes back.</p>
     */
    void reclamp() {
        if (!placed) return;
        // NOT WHILE A DRAG IS LIVE. The clamp reads measured boxes, which lag the drag by a frame, so
        // running both writes last frame's answer over the one the pointer just asked for.
        UIWindow window = getAttachedWindow();
        if (window != null && window.getInputHandler().getDragController().isDragging()) return;
        applyPosition(wantedLeft, wantedTop);
    }

    /**
     * Post-layout is where an unplaced window learns where it goes: the cascade offset needs a measured
     * caption height and a measured work area, and neither exists when {@code addWindow} runs.
     *
     * <p>Writing style from here re-dirties layout, which is deliberate and settles — the same shape
     * {@code UIText.recompute} uses, and for the same reason {@code replaceOrPutCandidate} exists.</p>
     */
    @Override
    protected void onLayoutChanged() {
        super.onLayoutChanged();
        if (placed) {
            applyPosition(wantedLeft, wantedTop);
            return;
        }
        Desktop desktop = desktop();
        if (desktop != null) desktop.placeByCascade(this);
    }

    /**
     * The desktop this window belongs to, or null once it has been destroyed.
     *
     * <p><b>Not a tree walk.</b> A hidden window is detached and still belongs to its desktop — that is
     * what retention means — so the walk would answer null for exactly the windows the registry, the
     * taskbar and the switcher are all about.</p>
     */
    @Nullable
    public Desktop desktop() {
        return owner;
    }

    /** The caption's measured height — the cascade step, and the sliver the clamp keeps on screen. */
    float captionHeight() {
        return titleBar.getRuntimeCache().getHeight();
    }

    private void beginMove(float pointerX, float pointerY) {
        UIWindow window = getAttachedWindow();
        if (window == null) return;
        // A synthesized activation press (Space/Enter on a focused element) carries the cursor's position,
        // which may be nowhere near the bar. Honouring one teleports the window.
        if (!titleBar.containsScreenPoint(pointerX, pointerY)) return;

        // FROM WHERE THE WINDOW IS, not from what was last asked for. A window currently held at the
        // edge by the clamp has a wanted position further out; starting a drag from that would spend the
        // difference before anything moved.
        dragStartLeft = placedLeft;
        dragStartTop = placedTop;

        UIDragController drag = window.getInputHandler().getDragController();
        // Positional drag, zero threshold: a window must track the very first pixel, and a title bar has
        // no competing click interpretation to protect.
        drag.startDrag(titleBar, pointerX, pointerY,
                (mouseX, mouseY, startX, startY, deltaX, deltaY) -> {
                    placed = true;
                    applyPosition(dragStartLeft + deltaX, dragStartTop + deltaY);
                });
    }

    /** Records the intent, then writes it clamped. */
    private void applyPosition(float left, float top) {
        wantedLeft = left;
        wantedTop = top;

        float clampedLeft = left;
        float clampedTop = top;

        UIElement area = resizeContainingBlock();
        float areaWidth = area == null ? 0f : area.getRuntimeCache().getWidth();
        float areaHeight = area == null ? 0f : area.getRuntimeCache().getHeight();
        float frameWidth = getRuntimeCache().getWidth();
        float caption = captionHeight();

        // A ZERO BOX CARRIES NO INFORMATION, so the intent is written through unclamped rather than
        // clamped against nothing. CanvasOverlayMove's version returns early instead and loses the write
        // entirely -- which is survivable there because something re-places the panel every frame, and
        // would strand a window here on the one frame that matters, its first.
        if (areaWidth > 0f && areaHeight > 0f && frameWidth > 0f && caption > 0f) {
            clampedLeft = clamp(left, caption - frameWidth, areaWidth - caption);
            clampedTop = clamp(top, 0f, areaHeight - caption);
        }

        placedLeft = clampedLeft;
        placedTop = clampedTop;

        final float writtenLeft = clampedLeft;
        final float writtenTop = clampedTop;
        // INLINE, matching UIResizer. No-ops when unchanged -- replaceOrPutCandidate drops an identical
        // value, which is what lets this run per layout pass without re-dirtying layout forever.
        StyleGroup.inlinePipeline(getStyle().getLayoutGroup(),
                l -> l.left(writtenLeft).top(writtenTop));
    }

    /** {@code lo} is allowed to exceed {@code hi} — a window narrower than its own caption — and the
     * upper bound wins, which keeps the title bar on screen rather than the body. */
    private static float clamp(float value, float lo, float hi) {
        return Math.max(Math.min(lo, hi), Math.min(value, hi));
    }
}
