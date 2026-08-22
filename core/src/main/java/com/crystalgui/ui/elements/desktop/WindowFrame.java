package com.crystalgui.ui.elements.desktop;

import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.dispose.Disposer;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.render.texture.CgUiSvg;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.Tooltip;
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
        minimizeButton.attachListener(this::hide);
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

        // TARGET AND BUBBLE, with the controls filtered out by hand -- see captionPressIsAControl.
        //
        // It was target-only, which is Dialog's spelling, and the reason was sound: the two booleans are
        // ADDITIVE, so subscribing the bubble phase also hears every press on the close button, and a
        // press there would start a window drag as well as closing the window.
        //
        // What that misses is that target-only can only ever hear presses on the BAR ITSELF, which works
        // exactly as long as everything in the caption is unhittable. The frame's own title label is, so
        // it held -- until a window ADOPTED somebody else's header into its caption (WindowChrome), and a
        // panel's title is an ordinary hittable UIText. So a floating Notifications window could not be
        // dragged by the word "Notifications", only by the gap beside it, which reads as the window
        // being stuck rather than as the label being in the way.
        //
        // The frame cannot fix that by reaching into the adopted chrome and unhitting parts of it: it
        // does not own that subtree, and setHitTest applies to a whole subtree, so it would take the
        // header's own buttons out with the label.
        titleBar.onMouseDown.attachListener((element, event) -> {
            if (captionPressIsAControl(event.getTarget())) return;
            beginMove(event.getPosition().x(), event.getPosition().y(), event.getDetail());
        }, false, true);

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
        if (contains(input.getFocusedElement())) return;

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

    // ── Maximise ────────────────────────────────────────────────────────────

    public boolean isMaximized() {
        return maximized;
    }

    /** Double-click on the caption, the maximise button, and (from W13) the command all land here. */
    public void toggleMaximized() {
        if (maximized) restore();
        else maximize();
    }

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
        restoreLeft = placedLeft;
        restoreTop = placedTop;
        restoreWidth = getRuntimeCache().getWidth();
        restoreHeight = getRuntimeCache().getHeight();
        maximized = true;
        addClass(MAXIMIZED_CLASS);
        maximizeTooltip.setText(RESTORE_TOOLTIP);
        StyleGroup.inlinePipeline(getStyle().getLayoutGroup(),
                l -> l.left(0).top(0).widthPercent(100f).heightPercent(100f));
    }

    /** Puts the window back exactly where it was. */
    public void restore() {
        if (!maximized) return;
        maximized = false;
        removeClass(MAXIMIZED_CLASS);
        maximizeTooltip.setText(MAXIMIZE_TOOLTIP);
        StyleGroup.inlinePipeline(getStyle().getLayoutGroup(),
                l -> l.width(restoreWidth).height(restoreHeight));
        // AFTER clearing the flag, because applyPosition deliberately does nothing while maximised --
        // a maximised window has no position to clamp, and letting the clamp write one would fight the
        // 100% rect every layout pass.
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
     * Whether a press in the caption belongs to something in it rather than to the caption.
     *
     * <p><b>Focusability is the test</b>, and it is not a proxy — it is the question. A control is a
     * thing you can put the keyboard on: every button in the caption is focusable, and nothing that is
     * merely being displayed there is. A window's title, an icon, an adopted panel header's label are
     * all {@code FocusPolicy.NONE}, so they read as caption, which is what they look like.</p>
     *
     * <p>Walked up to the bar and no further, so the FRAME's own focusability — it is
     * {@code CLICK_NOT_TABBABLE}, deliberately — cannot answer yes for every press in it.</p>
     */
    private boolean captionPressIsAControl(@Nullable UIElement target) {
        for (UIElement walk = target; walk != null && walk != titleBar; walk = walk.getParent()) {
            if (walk.focusable()) return true;
        }
        return false;
    }

    private void beginMove(float pointerX, float pointerY, int detail) {
        UIWindow window = getAttachedWindow();
        if (window == null) return;
        // A synthesized activation press (Space/Enter on a focused element) carries the cursor's position,
        // which may be nowhere near the bar. Honouring one teleports the window.
        if (!titleBar.containsScreenPoint(pointerX, pointerY)) return;

        // DOUBLE-CLICK TOGGLES, and starts no drag. Windows' gesture. Returning here matters: the second
        // press would otherwise begin a move as well, so the smallest tremor after a double-click would
        // drag the window it had just restored.
        if (detail >= 2) {
            toggleMaximized();
            return;
        }

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
                    // A MAXIMISED WINDOW RESTORES ON THE FIRST MOVEMENT, never on the press.
                    //
                    // Windows' restore-drag, and the ordering is what makes it work rather than a
                    // detail: restoring on the press means the FIRST press of a double-click restores
                    // and the second re-maximises, so double-clicking a maximised caption appears to do
                    // nothing at all. Click-and-hold on a maximised title bar does nothing there too --
                    // it is the movement that tears the window loose.
                    if (maximized) {
                        if (deltaX == 0f && deltaY == 0f) return;
                        restoreUnderPointer(mouseX, mouseY);
                        // Re-baselined so the delta already spent is not applied a second time: from
                        // here the drag continues from wherever the restore put the window.
                        dragStartLeft = placedLeft - deltaX;
                        dragStartTop = placedTop - deltaY;
                        return;
                    }
                    applyPosition(dragStartLeft + deltaX, dragStartTop + deltaY);
                });
    }

    /**
     * Restores a maximised window <b>around the pointer</b>, so a drag that begins on its caption
     * carries on from where the hand already is.
     *
     * <p>The pointer keeps its fraction across the caption: grab a maximised window three-quarters of
     * the way along its title bar and the restored window appears with the cursor three-quarters along
     * <em>its</em> title bar. Keeping the left edge instead — the obvious alternative — makes a window
     * grabbed on its right-hand side leap out from under the cursor, which is why no window manager
     * does it that way. The vertical offset inside the caption is simply preserved, since the caption's
     * height does not change.</p>
     *
     * <p>Measured before restoring and applied after, using the <em>recorded</em> restore width rather
     * than a fresh measurement: layout has not run yet at this point, so the box still reports the
     * maximised size.</p>
     */
    private void restoreUnderPointer(float pointerX, float pointerY) {
        // ALREADY IN LAYOUT UNITS. UIDragController.tick runs screenToLocal against the drag SOURCE
        // before it calls the listener -- that conversion is most of why the callback exists -- so a
        // second one here halves the coordinate and the window comes back at about half the distance
        // across the caption. The pointer position in a mouse-DOWN listener is the other way round:
        // that one is raw, which is why the guard above uses containsScreenPoint.
        float barWidth = titleBar.getRuntimeCache().getWidth();
        float alongCaption = pointerX - titleBar.getRuntimeCache().getX();
        float fraction = barWidth > 0f
                ? Math.max(0f, Math.min(1f, alongCaption / barWidth))
                : 0.5f;

        UIElement area = resizeContainingBlock();
        float areaX = area == null ? 0f : area.getRuntimeCache().getX();
        float areaY = area == null ? 0f : area.getRuntimeCache().getY();
        // The caption stays where it is vertically -- its height does not change, so preserving the
        // frame's own top keeps the pointer at the same place down the bar.
        float top = getRuntimeCache().getY() - areaY;

        restore();

        float width = restoreWidth > 0f ? restoreWidth : getRuntimeCache().getWidth();
        applyPosition(pointerX - areaX - fraction * width, top);
    }

    /** Records the intent, then writes it clamped. */
    private void applyPosition(float left, float top) {
        wantedLeft = left;
        wantedTop = top;
        // A MAXIMISED WINDOW HAS NO POSITION. The intent above is still recorded -- it is what restore
        // comes back to -- but writing it would fight the 100% rect on every layout pass, and this runs
        // from the layout callback and from the work-area re-clamp.
        if (maximized) return;

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
