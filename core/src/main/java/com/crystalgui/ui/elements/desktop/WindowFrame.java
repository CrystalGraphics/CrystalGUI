package com.crystalgui.ui.elements.desktop;

import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.ui.elements.chrome.ContextMenu;
import com.crystalgui.core.command.MenuId;
import com.crystalgui.core.data.DataKey;
import com.crystalgui.core.data.DataProvider;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.dispose.Disposer;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.render.texture.CgUiSvg;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.Tooltip;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.ui.input.UIInputHandler;
import com.crystalgui.ui.tree.UITreeTraversal;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.TaffyPosition;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
 * inherit, and {@code FloatingDock} paid for that lesson once already: it extended {@code Dialog} and
 * then spent three paragraphs of its own javadoc listing what it must not be allowed to inherit, which
 * is the tell for a wrong base class. (It was deleted at W8; {@code ToolWindowFrame} is a frame.) What is taken from
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

    /**
     * What the caption buttons say when the pointer rests on them.
     *
     * <p>Windows' own wording, including the one that reads like a slip: a maximised window's button
     * says <b>Restore Down</b> rather than "Restore", because restoring is also what a MINIMISED window
     * does and the two would otherwise share a name for opposite verbs.</p>
     *
     * <p>Constants rather than literals at the call sites, so the swap below cannot drift from the label
     * it swaps to — the failure would be a button that says "Maximize" while drawing the restore glyph,
     * which is worse than either alone.</p>
     */
    public static final String MINIMIZE_TOOLTIP = "Minimize";
    public static final String MAXIMIZE_TOOLTIP = "Maximize";
    public static final String RESTORE_TOOLTIP = "Restore Down";
    public static final String CLOSE_TOOLTIP = "Close";

    /** The window's icon slot, hidden until {@link #setIcon} gives it something to draw. */
    public static final String ICON_CLASS = "__icon__";

    /** The maximise affordance. Its glyph swaps to "restore" while {@link #MAXIMIZED_CLASS} is on. */
    public static final String MAXIMIZE_CLASS = "__maximize__";

    /**
     * Where content's own caption chrome is hosted — a menu bar, a toolbar, whatever the application
     * would otherwise have drawn in a second header of its own.
     *
     * @see WindowChrome
     */
    public static final String CAPTION_CHROME_CLASS = "__caption-chrome__";

    /**
     * On the adopted element itself, for as long as it is in a caption.
     *
     * <h3>Why a class and not a descendant selector</h3>
     *
     * <p>A bar in a caption needs its panel styling dropped — its own fill and its own indent are for
     * sitting at the top of a panel, and in a caption they draw a second stripe inside the first. The
     * obvious spelling is a descendant rule ({@code window > .__title-bar__ .__header__ { … }}), and it
     * works perfectly right up until the chrome goes home, at which point <b>it keeps applying</b>.</p>
     *
     * <p>{@link UIElement#invalidateStyleMatch()} runs on an id, a class or a state change — and
     * <em>not</em> on being reparented. So an element moved out from under a selector's ancestor keeps
     * the candidates that selector gave it, at its specificity, indefinitely. A tool window docked back
     * into its region came back with the caption's {@code padding-left: 0} and {@code flex-grow: 1}
     * still winning: a 30px header where the sheet says 22, squeezing the panel's content into what was
     * left. Nothing looked wrong with either rule, and the panel was only broken <em>after a round
     * trip</em>.</p>
     *
     * <p>A class is the engine's own answer to this, and it is the same one {@code :checked} and
     * {@code :hover} have each cost a round to arrive at: the cascade re-evaluates a pseudo-class or a
     * descendant match on its own terms, and a class on yours. {@link #releaseChrome} removes it, which
     * invalidates, which is what makes the caption styling actually stop.</p>
     */
    public static final String ADOPTED_CHROME_CLASS = "__caption-adopted__";

    /**
     * On a window filling the work area.
     *
     * <p>Carries more than a look: the sheet turns the resize handles off through it
     * ({@code resize: none}), which is the right layer for that — a maximised window is not resizable
     * on any desktop, and saying so in CSS keeps it out of Java where the handles are not built.</p>
     */
    public static final String MAXIMIZED_CLASS = "__maximized__";

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

    /** The open/close/minimise/maximise transitions. @see WindowAnimator */
    private final WindowAnimator animator = new WindowAnimator(this);

    /** This window's last frame, for previewing it once it is minimised. @see WindowSnapshot */
    private final WindowSnapshot snapshot = new WindowSnapshot();

    /** Set by a minimise, cleared by the paint that acts on it. @see #paintOverlay */
    private boolean snapshotPending;

    /** Photograph this window on its next paint. @see WindowSnapshot */
    void requestSnapshot() {
        snapshotPending = true;
    }

    /** The last photograph of this window, valid only after a minimise. @see WindowSnapshot */
    WindowSnapshot snapshot() {
        return snapshot;
    }

    /**
     * Takes the pending photograph, once its subtree has finished drawing for real.
     *
     * <p>The flag is cleared BEFORE the capture and that is not tidiness: capturing re-enters this very
     * subtree's {@code drawSubtree}, so a flag still set when the nested draw reaches here would recurse
     * without end.</p>
     *
     * <p>The scale comes off the live pose rather than from {@code uiScale} directly, because the pose is
     * what the subtree is actually about to be drawn with — {@code uiScale} times whatever any ancestor
     * has scaled. A snapshot allocated against the wrong one is blurry or four times too large.</p>
     */
    @Override
    protected void paintOverlay(CgUiPaintContext ctx) {
        super.paintOverlay(ctx);
        if (!snapshotPending) return;
        snapshotPending = false;
        snapshot.capture(ctx, this, ctx.getPoseStack().last().pose().m00());
    }
    private final UIElement captionChrome;
    private final UIElement overlays;
    private final UIText titleLabel;
    private final UIElement icon;
    private final Button closeButton;
    private final Button minimizeButton;
    private final Button maximizeButton;

    /** What was asked for, never clamped. @see WindowFrame */
    private float wantedLeft, wantedTop;
    /** What was written, after clamping. What a drag and {@code UIResizer} both measure from. */
    private float placedLeft, placedTop;
    /** Whether a position has been written at all — {@code Desktop} cascades the ones that have not. */
    private boolean placed;

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

    /** Which of the live owned windows are modal — the only ones that give the slot a box. */
    private final Set<UIElement> blockers = new LinkedHashSet<>();

    /** What is currently SHOWING on the owned surface. @see #releaseOwned */
    private final Set<UIElement> live = new LinkedHashSet<>();

    /** @see #adoptChrome */
    @Nullable
    private UIElement adoptedChrome;
    @Nullable
    private UIElement chromeOrigin;
    private int chromeOriginIndex = -1;
    private boolean chromeWasInternal;

    private boolean maximized;
    /** The maximise button's tooltip, kept because its text follows the state. @see #MAXIMIZE_TOOLTIP */
    private final Tooltip maximizeTooltip;
    /** Where to put the window back — the MEASURED rect at the moment it was maximised. */
    private float restoreLeft, restoreTop, restoreWidth, restoreHeight;

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
        // THROUGH THE ANIMATION, which then hides. Not hide() itself: hiding is DETACHING, and a
        // detached subtree paints nothing, so a window that hid on the press would animate to an empty
        // screen. @see WindowAnimator
        minimizeButton.attachListener(this::minimize);
        Tooltip.attach(minimizeButton, MINIMIZE_TOOLTIP);
        controls.addChild(minimizeButton);

        maximizeButton = new Button("");
        maximizeButton.addClass(MAXIMIZE_CLASS);
        maximizeButton.attachListener(this::toggleMaximized);
        // RETAINED, because this one's text follows the state -- and Tooltip.attach ADDS a listener pair
        // rather than replacing one, so calling it again to relabel would leave the first tooltip in
        // place and showing, with the new text on an instance nothing ever hovers.
        maximizeTooltip = Tooltip.attach(maximizeButton, MAXIMIZE_TOOLTIP);
        controls.addChild(maximizeButton);

        closeButton = new Button("");
        closeButton.addClass(CLOSE_CLASS);
        closeButton.attachListener(this::requestClose);
        Tooltip.attach(closeButton, CLOSE_TOOLTIP);
        controls.addChild(closeButton);

        // BUILT NOW AND HIDDEN, rather than created when an icon arrives. Creating an element from a
        // setter means creating it possibly mid-gesture, and the title bar has no `gap-all` for a hidden
        // child to occupy — the one cost that would have made the lazy version worth it.
        icon = new UIElement();
        icon.addClass(ICON_CLASS);
        icon.setHitTest(false);
        icon.setDisplayed(false);

        // AFTER the icon and BEFORE the title, which is where IntelliJ's New UI and VS Code's custom
        // title bar both put an application's menu: hard against the left, with the title taking
        // whatever is left. Hidden until something is adopted, and the caption has no `gap-all`, so an
        // empty slot occupies nothing.
        captionChrome = new UIElement();
        captionChrome.addClass(CAPTION_CHROME_CLASS);
        captionChrome.setDisplayed(false);

        titleBar = new UIElement();
        titleBar.addClass(TITLE_BAR_CLASS);
        titleBar.addChild(icon);
        titleBar.addChild(captionChrome);
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

        // THE POINTER MOVE GESTURES -- caption drag, Alt-drag, drag-to-edge snap. @see WindowMove
        //
        // Beside WindowKeyboardMove rather than inline, which is the asymmetry the split closes: the
        // keyboard half has been its own class since it was written, and the pointer half is the one
        // with two listeners, a live drag and the coordinate-space rules.
        WindowMove.install(this);

        // THE SYSTEM MENU ON A RIGHT-CLICK -- W13a's second of three routes. Every desktop puts it here,
        // and it costs nothing to add because the rows are MenuId.WINDOW_SYSTEM's: this route differs
        // from Alt+Space only in where it anchors. Built against the pressed element, whose walk reaches
        // this frame through the title bar, so the menu is always about the window it was opened on.
        ContextMenu.attach(titleBar, CommandRegistry.global(),
                pressed -> ContextMenu.of(MenuId.WINDOW_SYSTEM));

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
            if (desktop != null) {
                desktop.activate(this);
                return;
            }
            // AN OWNED WINDOW HAS NO DESKTOP TO ACTIVATE AGAINST, and pressing one must still focus it.
            //
            // attachOwned parents a frame into its owner's overlay slot and deliberately does NOT make it
            // a desktop citizen -- it is in no registry, has no taskbar entry, and has no z of its own to
            // raise. So activate() early-returned on the null and the press did nothing at all: grabbing
            // a floating tool window by its caption left focus wherever it had been, and its rail button
            // stayed dark because that button lights from focus being inside the panel's container.
            //
            // Focus is the whole of what activation means for a window with nothing to raise and nothing
            // to announce, so that is what is done. POINTER focus, never programmatic: a press must not
            // ring, which is what :focus-visible exists to separate.
            //
            // It matters most for a DRAG, which is how the gesture was reported. A drag never completes a
            // click -- the pointer moves, so no mouse-up lands on what the press went to -- and anything
            // waiting for that click never happens. The press is the only moment there is.
            restoreFocus(false);
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

    /**
     * Puts {@code content} in the window and <b>adopts any chrome it offers for the caption</b> —
     * client-side decorations, the answer to an application with a top bar of its own ending up with
     * two headers stacked on each other.
     *
     * <p>The one entry point that wires both, so a caller cannot get half of it. Adding through
     * {@link #content()} directly still works and simply does not adopt anything.</p>
     *
     * <p>Only the element handed in is asked, never its subtree. A search would be a walk over a whole
     * workbench for an answer almost always sitting on the first object, and "which of the nested
     * providers won" is not a question a caller should have to reason about — anything else can call
     * {@link #adoptChrome} itself.</p>
     */
    public WindowFrame setContent(UIElement newContent) {
        if (newContent == null) return this;
        content.addChild(newContent);
        if (newContent instanceof WindowChrome) adoptChrome((WindowChrome) newContent);
        return this;
    }

    /**
     * Moves a provider's chrome into this window's caption.
     *
     * <p><b>Moved, not copied</b> — see {@link WindowChrome}. Where it came from is remembered, so
     * {@link #releaseChrome()} can put it back exactly there, including its internal-child status: a
     * workbench's menu bar is an internal child of the workbench, and returning it as a public one
     * would leave it publicly removable by anything that walked the tree.</p>
     */
    public void adoptChrome(WindowChrome provider) {
        if (provider == null) return;
        UIElement chrome = provider.captionChrome();
        if (chrome == null || chrome == adoptedChrome) return;
        releaseChrome();

        chromeOrigin = chrome.getParent();
        chromeOriginIndex = chromeOrigin == null ? -1 : chromeOrigin.getChildren().indexOf(chrome);
        chromeWasInternal = chrome.isInternalUI();

        adoptedChrome = chrome;
        // addChild REPARENTS: addChildAtInternal detaches from the previous parent first, and falls back
        // to removeInternalChild when removeChild refuses -- which it does for an internal child. That
        // fallback is the only reason a workbench's own menu bar can move here at all.
        captionChrome.addChild(chrome);
        // AND THE CLASS, which is what a sheet must key its caption styling off. @see ADOPTED_CHROME_CLASS
        chrome.addClass(ADOPTED_CHROME_CLASS);
        captionChrome.setDisplayed(true);
    }

    /** Puts adopted chrome back where it came from. Safe to call when there is none. */
    public void releaseChrome() {
        if (adoptedChrome == null) return;
        UIElement chrome = adoptedChrome;
        adoptedChrome = null;
        // BEFORE the reparent, though either order works -- removeClass invalidates the match, and that
        // invalidation is the entire reason the class exists. @see ADOPTED_CHROME_CLASS
        chrome.removeClass(ADOPTED_CHROME_CLASS);
        captionChrome.setDisplayed(false);

        if (chromeOrigin == null) {
            captionChrome.removeChild(chrome);
            return;
        }
        int index = Math.max(0, Math.min(chromeOriginIndex, chromeOrigin.getChildren().size()));
        if (chromeWasInternal) chromeOrigin.insertInternalChildAt(chrome, index);
        else chromeOrigin.addChildAt(chrome, index);
        chromeOrigin = null;
    }

    /** What this window is currently hosting in its caption, or null. */
    @Nullable
    public UIElement adoptedChrome() {
        return adoptedChrome;
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

    public Button maximizeButton() {
        return maximizeButton;
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

    /** Parents an owned window onto this frame. Blocking — the caller is a modal. @see #attachOwned(UIElement, boolean) */
    public void attachOwned(UIElement owned) {
        attachOwned(owned, true);
    }

    /**
     * Parents an owned window onto this frame.
     *
     * <h3>{@code blocking} decides whether the slot has a box, and it is not a detail</h3>
     *
     * <p>The slot is sized to the whole frame while it holds something, which is exactly right for a
     * <b>modal</b>: a modal's business is that nothing behind it can be reached, and a full-size
     * transparent slot over the content is how that is spelled here.</p>
     *
     * <p>For a non-modal owned window — a floating tool window — it is a bug wearing modality's
     * clothes. The window itself works, and every click anywhere else in the owner lands on the slot
     * and does nothing: the panel reads as having opened <em>as a dialog</em>, which is what it was
     * reported as. It also confines the thing, because a frame clamps against its containing block and
     * that block is this slot.</p>
     *
     * <p>So a non-blocking owned window leaves the slot at zero, and <b>that does not hide it</b>:
     * {@code elementHitTest} recurses into children before it ever consults the parent's own box, gated
     * only on clipping, and the slot does not clip. A zero-sized non-clipping parent is therefore fully
     * transparent to the pointer while its children stay hittable — which is the property this needs
     * and the reason it can be spelled at all.</p>
     */
    public void attachOwned(UIElement owned, boolean blocking) {
        if (owned == null) return;
        if (owned.getParent() != overlays) overlays.addChild(owned);
        live.add(owned);
        if (blocking) blockers.add(owned);
        else blockers.remove(owned);
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
        blockers.remove(owned);
        syncOverlaySlot();
    }

    /** Whether anything is currently showing on this frame's owned surface. */
    public boolean hasOwnedWindows() {
        return !live.isEmpty();
    }

    /**
     * The window this one belongs to, or null — Win32's owner/owned relation.
     *
     * <h3>A relation, NOT the owned surface, and the pair is easy to conflate</h3>
     *
     * <p>{@link #attachOwned} makes a window a <b>child</b> of this frame. That gets the stacking right
     * for free (a child paints with its parent and cannot escape it) and it costs the two things a child
     * cannot have: the containing block is the owner, so the window is clamped inside it, and it is not
     * in the {@code WindowRegistry}, so it has no taskbar entry. Right for a modal dialog. Wrong for a
     * torn-out tool window, which is a first-class window that merely <em>belongs</em> to another.</p>
     *
     * <p>So this is the same relationship without the parenting: the frame stays top-level in the
     * desktop's window layer — free to be dragged anywhere, its own entry, its own stacking slot — and
     * the ONE thing it inherits is that {@link Desktop#raise} keeps it above its owner. Raising the
     * owner carries its owned windows up with it, which is the whole of "clicking the editor must not
     * bury the panel I pulled out of it".</p>
     *
     * <p><b>Not the pinned band.</b> Pinning means above <em>everything</em>, which is a different and
     * larger claim: a pinned tool window would also sit above a window it has nothing to do with, and
     * the band is reserved for the HUD case, where floating over the running game is the point.</p>
     */
    @Nullable
    public WindowFrame ownerWindow() {
        return ownerWindow;
    }

    /** @see #ownerWindow() */
    public WindowFrame setOwnerWindow(@Nullable WindowFrame owner) {
        // A window cannot own itself, and a cycle would make the raise walk below never terminate.
        for (WindowFrame walk = owner; walk != null; walk = walk.ownerWindow) {
            if (walk == this) return this;
        }
        this.ownerWindow = owner;
        if (owner != null && desktop() != null) desktop().raise(this);
        return this;
    }

    @Nullable
    private WindowFrame ownerWindow;

    /**
     * Whether this is a <b>tool window</b> — Win32's {@code WS_EX_TOOLWINDOW}, and the answer to
     * "should the taskbar know about this".
     *
     * <h3>Three consequences, and they are one idea</h3>
     *
     * <p>A tool window is part of the thing it belongs to rather than a destination of its own, so it is
     * absent from {@link WindowRegistry#taskbarOrder()}, absent from
     * {@link WindowRegistry#switcherOrder()}, and <b>hides and shows with its owner</b>. Win32 spells the
     * same idea in one extended style bit; IntelliJ's floating tool windows behave exactly this way,
     * which is also why they carry Dock and Hide and no maximise or close — a window nobody can reach
     * from a taskbar must not be able to put itself somewhere a taskbar would be needed to get it back.
     * </p>
     *
     * <p><b>It is not a second spelling of owned.</b> {@link #attachOwned} makes a window a child, which
     * gets all three of these for nothing: a child of a detached subtree is detached, and a child is in
     * no registry. This flag is for a window that is genuinely top-level — so that it can be dragged
     * anywhere on the desktop instead of being clamped inside its owner — and still not a citizen of it.
     * The two are orthogonal and both are in use: a {@code FLOATING} tool window is a child, a
     * {@code WINDOWED} one is top-level with this bit set.</p>
     *
     * <p>The counter-example is why this is worth a flag at all. A torn-out <em>editor</em> window is
     * also top-level and also came out of something, and it <b>does</b> deserve an entry — it is a place
     * work happens, and closing the window it came from must not take it. So {@code DockWindow} leaves
     * this false, and the line between "part of a window" and "a window" is one boolean rather than a
     * class check buried in the taskbar.</p>
     */
    public boolean isToolWindow() {
        return toolWindow;
    }

    /** @see #isToolWindow() */
    public WindowFrame setToolWindow(boolean nowToolWindow) {
        if (toolWindow == nowToolWindow) return this;
        this.toolWindow = nowToolWindow;
        // The taskbar and the switcher are built from filtered views, so flipping this changes what they
        // should be showing and nothing else would tell them.
        if (owner != null) owner.registry().changed();
        return this;
    }

    private boolean toolWindow;

    // ── The command surface ─────────────────────────────────────────────────────────────────────

    /**
     * The window a window command is about — W13a.
     *
     * <h3>Resolved by the walk, so focus decides and the frame is found for free</h3>
     *
     * <p>Every window operation is a {@code Command} first and chrome second, which is what keeps the
     * system menu, the taskbar's context menu, the title bar's and the keymap from drifting: they are
     * four renderers of one set of ids. What varies between them is only <em>which</em> window, and that
     * is a {@link DataContext} question rather than four separate lookups.</p>
     *
     * <p>{@link WindowFrame} answers it with itself, so anything focused inside a window resolves
     * outward to that window. {@code Desktop} answers it at <b>window level</b> with the active frame,
     * which is the documented last resort — a command invoked from the palette with nothing focused
     * still has a subject, and an element that answers still wins, so two open windows never both
     * resolve to whatever the desktop named.</p>
     */
    public static final DataKey<WindowFrame> WINDOW_FRAME =
            DataKey.create("windowFrame", WindowFrame.class);

    @Override
    @Nullable
    public Object getData(DataKey<?> key) {
        return key == WINDOW_FRAME ? this : null;
    }

    // ── What a taskbar entry says about this window ─────────────────────────────────────────────

    /**
     * Whether this window is <b>asking for attention</b> — Win32's {@code FlashWindowEx}, X11's urgency
     * hint, macOS's bouncing dock icon.
     *
     * <p>The other half of the no-steal rule: a window that appears without taking focus has to be able
     * to say it appeared, or it is a window nobody knows opened. Set by
     * {@link Desktop#addWindow(WindowFrame, boolean)} when a window opens in the background, and
     * <b>cleared by activation</b> — which is the only event that means "the user has seen it". Not by a
     * timer: a flash that gives up after five seconds is a notification you can miss by looking away,
     * which is exactly what the taskbar entry exists to prevent.</p>
     */
    public boolean isDemandingAttention() {
        return demandingAttention;
    }

    /** @see #isDemandingAttention() */
    public void requestAttention() {
        if (demandingAttention) return;
        demandingAttention = true;
        if (owner != null) owner.registry().changed();
    }

    /** @see #isDemandingAttention() */
    public void clearAttention() {
        if (!demandingAttention) return;
        demandingAttention = false;
        if (owner != null) owner.registry().changed();
    }

    private boolean demandingAttention;

    /**
     * A short overlay on this window's taskbar entry — an unsaved count, an error count, or null.
     *
     * <p>{@code FileDecoration}'s badge/colour split, one level up: the badge says <em>what</em> and a
     * class says how it should look, so a theme decides whether an error count is red without the window
     * knowing what red is. Kept to a few characters — it is drawn into the entry's icon slot, not given
     * a row of its own.</p>
     */
    @Nullable
    public String badge() {
        return badge;
    }

    /** @see #badge() */
    public WindowFrame setBadge(@Nullable String nowBadge) {
        String cleaned = nowBadge == null || nowBadge.isEmpty() ? null : nowBadge;
        if (java.util.Objects.equals(badge, cleaned)) return this;
        this.badge = cleaned;
        if (owner != null) owner.registry().changed();
        return this;
    }

    @Nullable
    private String badge;

    /**
     * How far along this window's work is, 0..1 — or <b>negative for "no progress to show"</b>, which is
     * every window until something says otherwise.
     *
     * <p>Windows' taskbar progress: a chunked transfer or a band download has a real duration, and the
     * entry is where a minimised window can report it. Negative rather than {@code null} because it is
     * read every refresh and a boxed float per entry per frame is a needless allocation on a path that
     * runs whenever the registry changes.</p>
     *
     * <p><b>Guarded with {@code !(x >= 0)}</b>, not {@code x < 0}: NaN fails every comparison, so the
     * obvious test lets one through to be multiplied into a width — the documented NaN-poisons-a-layout
     * trap, which cost a whole editor's row positions once.</p>
     */
    public float progress() {
        return progress;
    }

    /** @see #progress() */
    public WindowFrame setProgress(float nowProgress) {
        float cleaned = !(nowProgress >= 0f) ? -1f : Math.min(1f, nowProgress);
        if (progress == cleaned) return this;
        this.progress = cleaned;
        if (owner != null) owner.registry().changed();
        return this;
    }

    private float progress = -1f;

    /**
     * Tool windows this one took down with it when it hid, so showing it puts back exactly those.
     *
     * <p><b>Exactly those</b> is why this is a list rather than a re-walk of the owner group on the way
     * back. A re-walk restores every hidden owned tool window, which is only the same set if nothing else
     * can hide one — and "hidden because the owner went" and "hidden for any other reason" are
     * indistinguishable by then.</p>
     *
     * <p>Stated as the rule rather than as a scenario, because the obvious scenario is <em>not</em> live:
     * a tool window the user closes first is <b>destroyed</b>, not hidden ({@code hideFrame} drops the
     * frame outright, since what survives a hide is the placement record), so it leaves the registry and
     * could never be re-walked into. The discipline is what keeps that true as routes are added, and the
     * state check on the way back is what covers a frame that died while the owner was away.</p>
     */
    private final List<WindowFrame> hiddenWithOwner = new ArrayList<>();

    /**
     * Takes this window's owned <b>tool</b> windows down with it — Win32's owner/owned rule.
     *
     * <p>A {@code FLOATING} tool window needs none of this: it is a child of {@link #overlaySlot()}, so
     * detaching the owner detaches it, and re-attaching brings it back still in the tree. Only a
     * top-level owned window has a life of its own to suspend, which is exactly the case
     * {@link #isToolWindow()} exists for.</p>
     *
     * <h3>{@link Departure} is not decoration — it is WHEN this runs</h3>
     *
     * <p>The gesture rule the minimise deactivation already pays: a minimise's {@code hide()} is the
     * animation's <em>continuation</em>, so a cascade written only there starts 400ms late and detaches
     * with no animation of its own. What that looks like is the owner flying neatly into the taskbar and
     * its panels blinking out of existence once it lands — which reads as the panels not being part of
     * the gesture at all.</p>
     *
     * <p>So an animated departure cascades at gesture time and each tool window plays its own flight —
     * <b>the same one the owner is playing</b>, or a window fading in place while its panels sail off to
     * the taskbar reads as two unrelated things happening at once. Each ends in its own {@code hide()}.
     * The synchronous {@link Departure#NOW} call from {@link #hide()} stays as the backstop
     * for a direct API hide (which is documented to be synchronous) and skips anything already in
     * flight — {@link #hidingWithOwner} is both the suppression flag and the "already leaving with us"
     * marker.</p>
     */
    private enum Departure {
        /** Detach immediately — a direct {@code hide()}, which is documented to be synchronous. */
        NOW,
        /** Fly to the taskbar, as the owner is doing. */
        MINIMIZE,
        /** Shrink and fade in place, as the owner is doing. */
        CLOSE
    }

    private void cascadeHideOwnedToolWindows(Departure departure) {
        if (owner == null) return;
        // COPIED, because windows() is an unmodifiable VIEW of the registry's own list and hiding can
        // reach code that removes from it. It cannot today -- the cascade suppresses onHidden, so
        // hidePanel and its destroy() are never reached -- but that is a coupling between two files, not
        // a property of this loop, and the failure would be a ConcurrentModificationException from
        // minimising a window.
        for (WindowFrame candidate : new ArrayList<>(owner.registry().windows())) {
            if (candidate == this || !candidate.isToolWindow()) continue;
            // ALREADY ON ITS WAY OUT WITH US -- an animated cascade started at gesture time, and this is
            // the owner's own hide() landing afterwards. Hiding it again would cut its flight short at
            // exactly the moment the owner's finished, which is the bug this whole method is fixing.
            if (candidate.hidingWithOwner) continue;
            if (candidate.state() != WindowState.VISIBLE) continue;
            if (!ownsTransitively(candidate)) continue;
            hiddenWithOwner.add(candidate);
            candidate.hidingWithOwner = true;
            // THE SAME DEPARTURE THE OWNER IS PLAYING. A window and its panels leaving together by two
            // different animations reads as two unrelated things happening at once -- the owner fading
            // in place while its panels sail off to the taskbar.
            if (departure == Departure.MINIMIZE) {
                candidate.animator.playMinimize(candidate::finishHideWithOwner);
            } else if (departure == Departure.CLOSE) {
                candidate.animator.playClose(candidate::finishHideWithOwner);
            } else {
                candidate.finishHideWithOwner();
            }
        }
    }

    /** The end of one tool window's cascade — see {@link #cascadeHideOwnedToolWindows}. */
    private void finishHideWithOwner() {
        try {
            hide();
        } finally {
            hidingWithOwner = false;
        }
    }

    /** The other half — see {@link #hiddenWithOwner}. */
    private void showOwnedToolWindows() {
        if (hiddenWithOwner.isEmpty()) return;
        List<WindowFrame> putBack = new ArrayList<>(hiddenWithOwner);
        hiddenWithOwner.clear();
        for (WindowFrame frame : putBack) {
            if (frame.state() == WindowState.HIDDEN) frame.show(true);
        }
    }

    /** Whether {@code candidate} is owned by this window, at any depth. */
    private boolean ownsTransitively(WindowFrame candidate) {
        for (WindowFrame walk = candidate.ownerWindow; walk != null; walk = walk.ownerWindow) {
            if (walk == this) return true;
        }
        return false;
    }

    private void syncOverlaySlot() {
        // THE BLOCKERS, not the live set. Everything owned is live; only a modal wants the owner's
        // whole surface covered. See attachOwned(UIElement, boolean).
        boolean occupied = !blockers.isEmpty();
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
        // ALREADY SOMEWHERE INSIDE — but the FRAME ITSELF does not count, and that exception is the
        // whole of a reported bug.
        //
        // A frame is CLICK_NOT_TABBABLE, so `emitMouseDown` walks up from whatever was hit to the nearest
        // ancestor that focuses on click and lands on the frame BEFORE this ever runs. A plain
        // `contains()` then reads that as "focus is already in this window" and returns, so the delegate
        // never runs and focus stays on the frame -- which is a window that looks focused and whose
        // CONTENT is not. Grabbing a floating tool window by its caption left its rail button dark,
        // because that button lights from focus being inside the panel's own container.
        //
        // Focus on a real control inside (a caption button, something in the content) is still left
        // alone: that is somebody's deliberate target and moving it would be theft.
        UIElement focused = input.getFocusedElement();
        if (focused != null && focused != this && contains(focused)) return;

        UIElement wanted = contains(lastFocused) && lastFocused.focusable() ? lastFocused : null;
        if (wanted == null) wanted = focusDelegate();
        if (wanted == null) wanted = this;
        if (programmatic) input.requestFocus(wanted);
        else input.requestPointerFocus(wanted);
    }

    /**
     * Where focus lands when this window is activated and it remembers nothing — overridable.
     *
     * <h3>Why "the first focusable" is not always the answer</h3>
     *
     * <p>{@link UITreeTraversal#firstFocusableIn} walks depth-first and takes the first hit, which is
     * right for a dialog: its content is a box of controls and the first one is the one to start on.
     * It is wrong the moment the content ROOT is itself focusable — and several are, for a reason that
     * has nothing to do with focus. {@code DockArea}, {@code GraphView} and {@code ListView} take a
     * {@code FocusPolicy} so that COMMANDS resolve against them; a container that never sets one takes
     * no focus and its whole command set goes silently inert. The side effect is that such a container
     * intercepts every delegation aimed past it: the walk stops on the container, and everything it
     * holds — the editor you actually meant — is never reached.</p>
     *
     * <p>The symptom is precise and does not look like a focus bug. A torn-out editor window opened,
     * took focus off the window you tore it from, and then would not accept a keystroke: keyboard
     * events dispatch root→target→root, so a descendant of the target is never on the path. What it
     * DID show was a blue rectangle just inside its own border, tracing the caption's underside —
     * which is the pane-sized focus ring {@code ua/core.css} already carves several tags out of, and
     * the exact wording that note uses about {@code viewcontainer} having been reported the same way.</p>
     *
     * <p>So a window whose content knows better says so. {@code DockWindow} answers with the editor in
     * its active tab; anything that does not override this keeps the walk, which is still the right
     * default for content that is a plain box of controls.</p>
     */
    protected UIElement focusDelegate() {
        return UITreeTraversal.firstFocusableIn(content);
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
            // Same ordering as minimise -- this hide is a continuation, so the panels are told now --
            // and CLOSE rather than MINIMIZE, because that is the animation this window is playing.
            cascadeHideOwnedToolWindows(Departure.CLOSE);
            animator.playClose(this::hide);
            return true;
        }
        // THE VETO IS ASKED FIRST, and that ordering is the whole of it. A window that faded out and
        // then stayed because a guard said no is worse than no animation at all -- it says the close
        // happened. Both remaining outcomes are certain by the time anything is played.
        if (!canDiscard()) return true;
        animator.playClose(this::destroy);
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
        // FIRST, while this window is still the thing they are owned by and still has a box. Win32's
        // rule: an owner going away takes its owned windows with it. Only tool windows, and only
        // top-level ones -- a FLOATING tool window is a child of the overlay slot and leaves with the
        // subtree for free. SYNCHRONOUS here because a direct hide() is; an animated departure has
        // already cascaded at gesture time and this pass skips what it started. @see #isToolWindow()
        cascadeHideOwnedToolWindows(Departure.NOW);
        UIElement layer = getParent();
        if (layer == null) {
            markHidden();
            return;
        }
        // CAPTURED BEFORE THE DETACH, because after it there is nothing left to ask. An owned frame's
        // parent is its owner's overlay slot, and that slot is sized only while something is live on
        // it -- so a frame that leaves without saying so leaves a full-size transparent box over its
        // owner's content, swallowing every click on the window it came out of. Dialog releases itself
        // in close(); a frame has to do it here, because hide() is reached from requestClose, from
        // minimise, from dispose and from a caller, and only one of those is a place to remember it.
        WindowFrame ownedBy = null;
        UIElement above = layer.getParent();
        if (above instanceof WindowFrame candidate && candidate.overlaySlot() == layer) ownedBy = candidate;

        // The layer's removeChild is what flips the state and tells the registry -- so a bare
        // removeSelf() by some other caller means exactly the same thing as hide(), rather than leaving
        // a window that is detached and still claims to be visible.
        // MEASURED BEFORE THE DETACH, which is the whole of it -- see recordedWidth().
        captureVisibleSize();
        layer.removeChild(this);
        if (ownedBy != null) ownedBy.releaseOwned(this);
        // ...WHICH ONLY HOLDS FOR A WINDOW LAYER. An OWNED frame's parent is its owner's overlay slot,
        // an ordinary UIElement whose removeChild detaches and nothing else -- so the delegation above
        // silently did neither half of what hide() promises: the frame left the tree still claiming to
        // be VISIBLE, and onHidden never fired. Anything listening for a window being put away (a tool
        // window's manager, for one) heard nothing at all, and the second hide() early-returned on a
        // state that had never moved. Detaching is the only part the parent can be trusted with; the
        // state is ours either way, and markHidden is idempotent so the layer path is unaffected.
        markHidden();
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
        // AFTER the reattach, because an animation writes styles and invalidateStyleMatch early-returns
        // on a detached element -- the classes would be set and never matched.
        if (persisted) animator.playRestore();
        else animator.playOpen();
        // AFTER this window is back, so they stack above it rather than being raised against a window
        // that is not on the layer yet. @see #hiddenWithOwner
        showOwnedToolWindows();
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
        // BEFORE anything else: adopted chrome belongs to the content, so it goes home rather than being
        // destroyed with the window that borrowed it.
        releaseChrome();
        // OWNED, so nothing else frees it: createOwned bypasses CgFrameBufferRegistry by design, the
        // same arrangement CgUiPaintContext's layer pool has and the same obligation.
        snapshot.dispose();
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

    // ── A WINDOW IS NOT A CLOSE WATCHER, and this is where it used to register as one ────────────
    //
    // Escape dismisses what is TRANSIENT. That is what the close-watcher cascade is for and what every
    // reference does with the key: a menu, a popover, a dialog, a live drag, a rename box. No desktop
    // closes an application WINDOW on Escape -- Windows and GNOME want Alt+F4, macOS wants Cmd+W, and
    // IntelliJ spends plain Escape on returning focus to the editor and asks for Shift+Escape before it
    // will even HIDE a tool window. A window holds work; a key you press to back out of a menu must not
    // be able to put it away.
    //
    // It read as wrong the moment the compositor was somewhere you live rather than a frame around one
    // application: Escape in the editor put the editor away, and a second Escape left the desktop. Two
    // presses to leave, and the first one did something nobody asked for.
    //
    // In game the two rules agree, which is the strongest argument for this being right rather than
    // merely conventional: Minecraft's Escape means "give me back the game", and that is exactly what a
    // key nothing transient wanted should now do -- one press, from anywhere on the desktop.
    //
    // Everything Escape SHOULD reach is untouched, because none of it goes through here: a dropdown, a
    // popover and a modal dialog each push their own watcher, and a live drag eats Escape a rung above
    // the cascade entirely. Closing a window is still the close button, the system menu and
    // `window.close` -- and `requestClose()` is still the one policy those routes go through.
    //
    // It also un-breaks something: `getTopCloseWatcher` asks the active frame's stack first, so while a
    // frame registered itself LAST, a desktop-scoped watcher could never be reached at all.

    /** Set by {@link Desktop#addWindow}; cleared when the window is destroyed. */
    void setOwner(@Nullable Desktop desktop) {
        this.owner = desktop;
    }

    /**
     * Flips the state on the way out of the tree, whichever route detached it.
     *
     * <p><b>{@code onHidden} means "this was put away", and a cascade is not that.</b> Its one listener
     * is {@code ToolWindowManager}, which reads it as the user closing the panel and records it shut — so
     * firing it while an owner is merely minimising would mark every tool window on that window closed,
     * and the next session save would write it down. The window comes back; the panel does not.</p>
     */
    void markHidden() {
        if (state != WindowState.VISIBLE) return;
        state = WindowState.HIDDEN;
        if (!hidingWithOwner) onHidden.emit();
    }

    /** True for the duration of a cascade hide. @see #markHidden() */
    private boolean hidingWithOwner;

    // ── Maximise ────────────────────────────────────────────────────────────

    /**
     * The rect a maximised window goes back to. Meaningless while it is not maximised.
     *
     * <p>Package-private: this is state {@link DesktopSession} has to be able to read and write in order
     * to survive a restart, and nothing outside the compositor has any business with it.</p>
     */
    float restoreLeft() {
        return restoreLeft;
    }

    /**
     * The window's size as it should be RECORDED — measured while it is on screen, remembered once it is
     * not.
     *
     * <p>Hiding is <b>detaching</b>, so a hidden window's Taffy node is gone and its measured box is
     * zero. Persisting that writes a 0x0 window into the record, and a 0x0 rect is indistinguishable from
     * "never placed" — so the window is dropped on the way back in and simply does not come back. The
     * plan states the rule W8 paid for and this is it one level up: <em>capture before the thing goes
     * away, not after</em>. {@code ToolWindowFrame} snapshots in its own {@code hide()} for the same
     * reason.</p>
     *
     * <p>It cannot be answered from the inline style instead: a window sized by a drag has its width
     * written there, but one that has never been resized has no inline size at all and is whatever the
     * sheet made it.</p>
     */
    public float recordedWidth() {
        return getParent() == null ? lastVisibleWidth : getRuntimeCache().getWidth();
    }

    /** @see #recordedWidth() */
    public float recordedHeight() {
        return getParent() == null ? lastVisibleHeight : getRuntimeCache().getHeight();
    }

    private void captureVisibleSize() {
        float width = getRuntimeCache().getWidth();
        float height = getRuntimeCache().getHeight();
        // A NON-POSITIVE BOX IS REFUSED rather than stored: a window hidden before it was ever laid out
        // has nothing to remember, and overwriting a good remembered size with a zero is worse than
        // keeping a slightly old one.
        if (width > 0f && height > 0f) {
            lastVisibleWidth = width;
            lastVisibleHeight = height;
        }
    }

    private float lastVisibleWidth;
    private float lastVisibleHeight;

    /** @see #restoreLeft() */
    float restoreTop() {
        return restoreTop;
    }

    /** @see #restoreLeft() */
    float restoreWidth() {
        return restoreWidth;
    }

    /** @see #restoreLeft() */
    float restoreHeight() {
        return restoreHeight;
    }

    /**
     * Seeds the rect a maximised window will go back to — for a window restored already maximised.
     *
     * <p>Must be called <b>after</b> {@link #maximize()}, never before: maximising captures whatever the
     * window currently is as the rect to return to, so a value written first is overwritten by the very
     * call it exists for. And it cannot be left to that capture, because the capture reads the
     * <em>measured</em> box — which on a freshly restored window is whatever it was before this frame's
     * layout ran.</p>
     */
    void setRestoreRect(float left, float top, float width, float height) {
        restoreLeft = left;
        restoreTop = top;
        restoreWidth = width;
        restoreHeight = height;
    }

    public boolean isMaximized() {
        return maximized;
    }

    /** Double-click on the caption, the maximise button, and (from W13) the command all land here. */
    public void toggleMaximized() {
        if (maximized) restore();
        else maximize();
    }

    // ── Fullscreen — W13b ───────────────────────────────────────────────────────────────────────

    /**
     * Whether this window is filling the whole desktop, taskbar included.
     *
     * <h3>Maximise's sibling, and it needs almost nothing of its own</h3>
     *
     * <p>A frame is placed against the window layer and <b>the layer's box IS the work area</b> — the
     * taskbar is laid out as a bottom bar rather than overlaid. So hiding the strip re-flows the layer to
     * the full height and a maximised window follows it for nothing. Fullscreen is therefore
     * <em>maximise plus a hidden bar</em>, which is Windows' own model: maximise respects the taskbar,
     * fullscreen covers it.</p>
     *
     * <p>What it does need is memory of <b>how the window got here</b>. F11 from a maximised window must
     * come back maximised, and from a restored one must come back restored — a browser does exactly
     * this, and getting it wrong is the kind of thing that only shows up the second time somebody uses
     * it.</p>
     */
    public boolean isFullscreen() {
        return fullscreen;
    }

    /** {@code F11} and the command both land here. */
    public void toggleFullscreen() {
        if (fullscreen) exitFullscreen();
        else enterFullscreen();
    }

    public void enterFullscreen() {
        if (fullscreen || state != WindowState.VISIBLE) return;
        // REMEMBERED BEFORE maximising, or the answer is always "it was maximised".
        maximizedBeforeFullscreen = maximized;
        fullscreen = true;
        addClass(FULLSCREEN_CLASS);
        maximize();
        if (owner != null) owner.fullscreenChanged();
    }

    public void exitFullscreen() {
        if (!fullscreen) return;
        fullscreen = false;
        removeClass(FULLSCREEN_CLASS);
        if (!maximizedBeforeFullscreen) restore();
        if (owner != null) owner.fullscreenChanged();
    }

    /** @see #isFullscreen() */
    public static final String FULLSCREEN_CLASS = "__fullscreen__";

    private boolean fullscreen;
    private boolean maximizedBeforeFullscreen;

    /**
     * Fills the work area, remembering the rect to come back to.
     *
     * <p><b>The work area needs no special case.</b> A frame is placed against the window layer, and the
     * layer's box <em>is</em> the work area — the taskbar is laid out rather than overlaid — so
     * maximising is {@code left: 0; top: 0; width: 100%; height: 100%} and nothing anywhere has to
     * subtract a bar. It also means a maximised window follows the work area for free when the strip
     * hides (W13's fullscreen) or the desktop resizes.</p>
     *
     * <p>The restore rect is the <b>measured</b> box, not the declared one. A window may never have been
     * given an explicit size — its width would then be {@code auto} — and "put it back how it looked" is
     * the promise being made, which is a measurement. Windows keeps the same thing, and calls it the
     * window's restored rect.</p>
     *
     * <p>Written at <b>INLINE</b>, the origin the user's own drags and resizes write at, so maximising
     * replaces them rather than layering over them — and an author's {@code !important} still wins,
     * which is the rule {@code UIResizer} already states for the size it writes.</p>
     */
    public void maximize() {
        if (maximized) return;
        // A MAXIMISED WINDOW IS IN NO TILE. It covers the whole work area, so it shares an edge with
        // nothing and has no divider to move -- and leaving the cell recorded would let a joint resize
        // elsewhere re-tile it back down to a half. Restoring does not put it back in the group either:
        // it comes back to its pre-maximise rect, which is a position rather than a cell.
        snappedZone = null;
        restoreLeft = placedLeft;
        restoreTop = placedTop;
        restoreWidth = getRuntimeCache().getWidth();
        restoreHeight = getRuntimeCache().getHeight();
        maximized = true;
        addClass(MAXIMIZED_CLASS);
        maximizeTooltip.setText(RESTORE_TOOLTIP);
        UIElement area = resizeContainingBlock();
        var box = area == null ? null : area.getRuntimeCache();
        if (box != null && animateGeometry(restoreLeft, restoreTop, restoreWidth, restoreHeight,
                0f, 0f, box.getWidth(), box.getHeight(), this::applyMaximizedRect)) {
            // The animation writes px rects on the way and finishes by applying the rule below, which is
            // what makes a maximised window follow the work area afterwards.
            return;
        }
        applyMaximizedRect();
    }

    /** The rect a maximised window actually rests at — a rule, so it tracks the work area. */
    private void applyMaximizedRect() {
        StyleGroup.inlinePipeline(getStyle().getLayoutGroup(),
                l -> l.left(0).top(0).widthPercent(100f).heightPercent(100f));
    }

    /** Puts the window back exactly where it was. */
    public void restore() {
        if (!maximized) return;
        maximized = false;
        removeClass(MAXIMIZED_CLASS);
        maximizeTooltip.setText(MAXIMIZE_TOOLTIP);
        // MEASURED, not read from the fields: while maximised those still hold the PRE-maximise
        // position, because applyPosition declines to clamp a maximised window at all.
        UIElement area = resizeContainingBlock();
        var box = area == null ? null : area.getRuntimeCache();
        var self = getRuntimeCache();
        if (box != null && animateGeometry(self.getX() - box.getX(), self.getY() - box.getY(),
                self.getWidth(), self.getHeight(),
                restoreLeft, restoreTop, restoreWidth, restoreHeight, this::applyRestoredRect)) {
            return;
        }
        applyRestoredRect();
    }

    /** Where a restored window rests. @see #restore */
    private void applyRestoredRect() {
        StyleGroup.inlinePipeline(getStyle().getLayoutGroup(),
                l -> l.width(restoreWidth).height(restoreHeight));
        // AFTER the flag is clear and after any animation, because applyPosition deliberately does
        // nothing while maximised OR mid-resize -- letting the clamp write a position during either
        // would fight the rect being animated on every layout pass.
        applyPosition(restoreLeft, restoreTop);
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
     * Plays the window travelling between two rects, and settles on the second when it lands.
     *
     * <p>Guards {@link #applyPosition} for the animation's duration. The clamp runs from a layout
     * callback and writes {@code left}/{@code top} at the same origin the animation does, so without
     * this the two fight every pass — and it only ever mattered here, because a maximised window is
     * already exempt and a restore clears that flag before it animates.</p>
     *
     * @return whether it animated. {@code false} means the caller applies the final rect itself, which
     *         is what keeps the animations-off path synchronous for every existing caller.
     */
    private boolean animateGeometry(float fromLeft, float fromTop, float fromWidth, float fromHeight,
                                    float toLeft, float toTop, float toWidth, float toHeight,
                                    Runnable settle) {
        geometryAnimating = true;
        boolean started = animator.playResize(fromLeft, fromTop, fromWidth, fromHeight,
                toLeft, toTop, toWidth, toHeight, () -> {
                    geometryAnimating = false;
                    settle.run();
                });
        if (!started) geometryAnimating = false;
        return started;
    }

    /** True while a maximise or restore-down is playing. @see #animateGeometry */
    private boolean geometryAnimating;

    /**
     * Where the window was last <b>asked</b> to be, which is not always where it is.
     *
     * <p>The intent half of the pair the class note describes. Anything persisting a window's position
     * wants this one: {@link #getX()} reports the clamped placement, so saving that and restoring it on a
     * smaller desktop writes the clamp into the record permanently — each launch pulling the window a
     * little further in, with nothing to attribute the drift to.</p>
     */
    public float getWantedLeft() {
        return wantedLeft;
    }

    /** @see #getWantedLeft() */
    public float getWantedTop() {
        return wantedTop;
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

    /**
     * Moves and sizes the window in one gesture, <b>animating the change</b> — the tiled counterpart of
     * {@link #maximize()}.
     *
     * <h3>Why this exists beside {@code moveTo} + {@code resizeTo}, which are instant</h3>
     *
     * <p>Those two are how a caller <em>states</em> a geometry: a session restoring twelve windows, a
     * keyboard nudge that has to keep up with key repeat, a drag writing the pointer's position every
     * frame. None of them should animate, and a drag least of all — it is already a hand-driven
     * animation.</p>
     *
     * <p>A snap is the opposite kind of event. The window jumps somewhere the hand did not put it, and
     * it is the same jump a maximise makes — which has animated since W9. A half-snap that teleported
     * while a corner-snap-to-maximise eased was the two halves of one gesture behaving differently, and
     * the drag-to-edge preview morphing into place and then handing over to a window that simply
     * appeared made it unmissable.</p>
     *
     * <p>Through the same {@link WindowGeometryAnimation} a maximise uses, so it animates <b>layout</b>
     * rather than a transform: this changes the window's size, so its content reflows, and a transform
     * would draw the destination's layout at the source's geometry. And it starts from
     * {@link #left()}/{@link #top()} — where the window <em>is</em> — rather than the wanted position,
     * for the same reason a drag does: a window held at the edge by the clamp would otherwise open its
     * animation somewhere it has never been.</p>
     *
     * <p>Returns having already applied the rect when animations are off, so the disabled path stays
     * synchronous for every caller.</p>
     */
    public WindowFrame snapTo(SnapZones.Zone zone, float left, float top, float width, float height) {
        // THE ZONE IS RECORDED, and that is what makes the window a member of a tiled GROUP rather than
        // a window that happens to be half-width. Joint resize pairs on it: without a recorded cell there
        // is nothing to say which windows share a divider, and geometry cannot answer that -- a window
        // dragged to exactly the left half by hand is not snapped, and Windows will not tile it either.
        this.snappedZone = zone;
        var box = getRuntimeCache();
        if (animateGeometry(placedLeft, placedTop, box.getWidth(), box.getHeight(),
                left, top, width, height, () -> applySnappedRect(left, top, width, height))) {
            return this;
        }
        applySnappedRect(left, top, width, height);
        return this;
    }

    /** The settle. Ordinary writes, so the window ends in the state any other caller would leave it. */
    private void applySnappedRect(float left, float top, float width, float height) {
        resizeTo(width, height);
        moveTo(left, top);
    }

    /**
     * The tile this window occupies, or null — Windows' snapped state, which is <b>not</b> derivable
     * from where the window happens to be.
     *
     * <p>Cleared by anything that takes the window out of its cell: a move (by caption, by Alt-drag, by
     * the keyboard mode), a maximise, a fullscreen. Deliberately <em>not</em> cleared by a resize, which
     * is the whole point — a joint resize moves the divider and the window stays in its cell, exactly as
     * a column staying a column while you drag its edge.</p>
     */
    @Nullable
    public SnapZones.Zone snappedZone() {
        return snappedZone;
    }

    /** Leaves the tiled group. Safe to call when it was never in one. @see #snappedZone() */
    public void clearSnappedZone() {
        snappedZone = null;
    }

    @Nullable
    private SnapZones.Zone snappedZone;

    /**
     * Joint resize — dragging a shared divider moves everything the divider separates.
     *
     * <p>Windows calls this {@code JointResize} and has shipped it since Windows 10 build 10547; the
     * setting that used to gate it (<i>"When I resize a snapped window, simultaneously resize any
     * adjacent snapped window"</i>) was removed in Windows 11 22H2 and the behaviour is now always on.
     * Windows 10 could only pair TWO windows; 11 adapts the whole layout, which is what this does.</p>
     *
     * <p>Only an edge facing the middle counts. Dragging the OUTER edge of a snapped window resizes that
     * window alone and leaves the group untouched — it is not repartitioning anything, and treating it as
     * a divider would make the far side of the screen jump when you pulled a window away from its own
     * border.</p>
     */
    @Override
    protected void onUserResize(int handleDx, int handleDy, float width, float height) {
        Desktop desktop = desktop();
        if (desktop == null || snappedZone == null) return;
        desktop.jointResize(this, handleDx, handleDy, width, height);
    }

    /**
     * Whether an open/close/minimise/maximise animation is playing on this window right now.
     *
     * <p>The observable the animations otherwise have none of. They are driven on a per-frame ticker
     * writing at ANIMATION origin, so nothing about the element says "a timeline is running" — and the
     * one thing a caller may genuinely need to know is whether a teardown it asked for has happened yet
     * or is still waiting for the window to finish leaving.</p>
     */
    public boolean isAnimating() {
        return animator.isPlaying();
    }

    /**
     * Puts the window away — the GESTURE, animation included.
     *
     * <p><b>The one definition of minimising</b>, and it needed to be: the caption's button played the
     * flight into the taskbar and then hid, while the taskbar's own toggle — clicking the entry of the
     * window you are already in, which is Windows' third click case — called {@link #hide} straight out
     * and so did the same thing with no animation at all. Same gesture, two call sites, one of them
     * silently plainer than the other.</p>
     *
     * <p>{@link #hide} stays what it is: the synchronous state change, for a session restoring, an
     * eviction, or a caller that simply wants the window gone. An OS animates what the USER did.</p>
     */
    public void minimize() {
        if (state != WindowState.VISIBLE) return;
        // DEACTIVATED ON THE PRESS, not when the animation lands. Every window manager treats a minimise
        // as having happened the moment it is asked for and animates a window that has logically already
        // gone; deferring the state with the detach made the whole of it 400ms late, so the caption
        // stayed lit, the taskbar went on highlighting it, and anything rendering "the active window"
        // described a window that was visibly flying into the bar.
        //
        // Safe to move earlier precisely BECAUSE minimising hands over to nobody -- see Desktop.
        // deactivate, and hide()'s own call, which this makes a no-op rather than replacing. The close
        // path is deliberately NOT changed to match: dispose() reads whether the window was active to
        // hand the keyboard to the next one, so deactivating early there would leave nothing active
        // after a close.
        //
        // Guarded on being the active window, or minimising a background one would deactivate the
        // foreground.
        if (owner != null && owner.activeWindow() == this) owner.deactivate();
        // THE PANELS GO NOW, WITH IT -- same reason the deactivation above moved to the press. hide() is
        // this animation's continuation, so a cascade left to it starts when the flight LANDS: the window
        // sails into the taskbar and its tool windows blink out afterwards, which reads as them not being
        // part of the gesture. @see #cascadeHideOwnedToolWindows
        cascadeHideOwnedToolWindows(Departure.MINIMIZE);
        animator.playMinimize(this::hide);
    }

    /** The minimise flight, without the hide — so a test can ask where it is going. @see WindowAnimator */
    void playMinimizeAnimation(Runnable then) {
        animator.playMinimize(then);
    }

    /** Where the running animation is headed, or null if none is. @see WindowAnimator#currentTarget */
    @Nullable
    com.crystalgui.ui.UITransform animationTarget() {
        return animator.currentTarget();
    }

    /** Where the running animation started, or null if none is. @see WindowAnimator#currentStart */
    @Nullable
    com.crystalgui.ui.UITransform animationStart() {
        return animator.currentStart();
    }

    /** The close flight, without the teardown — so a test can inspect its endpoints. */
    void playCloseAnimation(Runnable then) {
        animator.playClose(then);
    }

    /** The entry animation, for the one path that attaches a window without going through {@link #show}. */
    void playOpenAnimation() {
        animator.playOpen();
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

    /**
     * The box this window is placed and clamped against — its containing block.
     *
     * <p>Package-private because {@code UIElement.resizeContainingBlock()} is {@code protected} and is
     * declared in another package, so a collaborator here cannot ask for it directly. {@code
     * CanvasOverlayMove} takes a supplier for exactly this reason and says so.</p>
     *
     * <p>Not {@code desktop().windowLayer()}, which is the same element only for a top-level window: an
     * OWNED frame's containing block is its owner's overlay slot, and clamping it against the layer
     * would let it be dragged out of the window it belongs to.</p>
     */
    @Nullable
    UIElement workArea() {
        return resizeContainingBlock();
    }

    /**
     * Marks the window deliberately positioned, so {@link Desktop} stops cascading it.
     *
     * <p>Separate from {@link #moveTo} for the one caller that has to claim the position before it has
     * one: a drag on a MAXIMISED caption is a placement decision from its first callback, while the
     * restore that gives the window a position does not happen until the pointer actually moves.</p>
     */
    void markPlaced() {
        placed = true;
    }

    /** Records the intent, then writes it clamped. */
    private void applyPosition(float left, float top) {
        wantedLeft = left;
        wantedTop = top;
        // A MAXIMISED WINDOW HAS NO POSITION, and neither does one mid-resize. The intent above is
        // still recorded -- it is what restore comes back to -- but writing it would fight the 100% rect
        // on every layout pass, and this runs from the layout callback and from the work-area re-clamp.
        if (maximized || geometryAnimating) return;

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
