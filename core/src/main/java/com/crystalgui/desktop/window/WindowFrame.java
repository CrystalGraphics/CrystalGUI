package com.crystalgui.desktop.window;

import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.window.WindowPolicy;
import com.crystalgui.core.window.WindowState;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.DesktopSession;
import com.crystalgui.desktop.motion.WindowAnimation;
import com.crystalgui.desktop.motion.WindowAnimator;
import com.crystalgui.desktop.motion.WindowGeometryAnimation;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.widget.overlay.ContextMenu;
import com.crystalgui.core.command.MenuId;
import com.crystalgui.core.data.DataKey;
import com.crystalgui.core.data.DataProvider;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.dispose.Disposer;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.render.texture.CgUiSvg;
import com.crystalgui.style.ElementStyle;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.ui.service.Drag;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.widget.dnd.Resizer;
import com.crystalgui.ui.UITransform;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.service.Focus;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.overlay.Dialog;
import com.crystalgui.widget.overlay.Tooltip;
import com.crystalgui.widget.scroll.ScrollerView;
import com.crystalgui.widget.text.UIText;
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
 * not a {@link UIDocument}: that class is the engine's {@code Document} analogue and the display surface
 * every frame here shares. The network layer already models a window as {@code (windowId, UINode
 * root)}, so this is the visual home that model never had.</p>
 *
 * <h3>Extends {@link UINode}, never {@code Dialog}</h3>
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
public class WindowFrame extends UINode implements Disposable, DataProvider {

    /** The cascade identity `ua/desktop.css` names. @see com.crystalgui.ui.dom.Name */
    public static final Name NAME = Name.of("window");

    /**
     * The class every frame wears, and what {@code ua/desktop.css} actually keys on.
     *
     * <p><b>Not the tag.</b> A {@code Name} is registered to a factory, so a subclass cannot answer
     * {@code window} the way the old engine's {@code tagName()} let it — two classes claiming one
     * name makes a description ambiguous to decode, and {@code NodeKindsCoverageTest} says so. A
     * subclass therefore has a kind of its own and would match none of the {@code window} rules,
     * which is exactly the failure {@code ToolWindowFrame} is on record for: no background, no
     * border, unstyled controls, and it reads as the widget not having been built.</p>
     *
     * <p>Keyed on a class instead, every subclass is styled as a window by construction and states
     * only what differs. {@code .__v-scroller__} is the same decision for the same reason.</p>
     */
    public static final String WINDOW_CLASS = "__window__";

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
     * The pin affordance, and the class the frame itself carries while pinned.
     *
     * <p>Two names because they mark different things: the button is a control in the caption, and
     * {@link #PINNED_CLASS} is state on the window — which is what a theme keys off to restyle the
     * whole frame, and what {@code :checked} would be if a frame were a checkbox.</p>
     */
    public static final String PIN_CLASS = "__pin__";
    /** @see #isPinned() */
    public static final String PINNED_CLASS = "__pinned__";

    /**
     * On a frame while it is painting on the HUD rather than on the desktop.
     *
     * <p>What it turns off is the caption controls, and that is a rule rather than a preference: in
     * game the cursor is grabbed and the keyboard is the game's, so nothing on the HUD can be clicked.
     * A control that cannot be clicked but still looks clickable is exactly the lie the disabled-control
     * rule already forbids. Pinning, unpinning, moving and sizing all happen from the desktop.</p>
     */
    public static final String HUD_CLASS = "__hud__";

    public static final String PIN_TOOLTIP = "Pin";
    public static final String UNPIN_TOOLTIP = "Unpin";

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

    /**
     * The window's icon slot: the icon this window declares, or the same monogram tile the taskbar draws
     * for it when it declares none. Hidden only on a tool window, which has no taskbar entry to agree
     * with. @see #refreshIcon
     */
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
     * <p>{@link UINode#invalidateStyleMatch()} runs on an id, a class or a state change — and
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

    private final UINode titleBar;
    private final UINode controls;
    private final UINode content;

    /** The open/close/minimise/maximise transitions. @see WindowAnimator */
    private final WindowAnimator animator = new WindowAnimator(this);

    /** This window's last frame, for previewing it once it is minimised. @see WindowSnapshot */
    private final WindowSnapshot snapshot = new WindowSnapshot();

    /** Set by a minimise, cleared by the paint that acts on it. @see #paintDecoration */
    private boolean snapshotPending;

    /** Photograph this window on its next paint. @see WindowSnapshot */
    public void requestSnapshot() {
        snapshotPending = true;
    }

    /** The last photograph of this window, valid only after a minimise. @see WindowSnapshot */
    public WindowSnapshot snapshot() {
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
    /**
     * While a surface animation is playing, draw the PHOTOGRAPH instead of the whole window.
     *
     * <h3>The cost this removes</h3>
     *
     * <p>An animating window has {@code opacity < 1}, and a group opacity makes {@code drawSubtree}
     * isolate the subtree in a layer FBO — which re-renders <b>everything inside the window, every
     * frame</b>. Measured in a client: a minimise of the editor dropped the render loop from a steady
     * 120Hz to between 60 and, in the worst case seen, 9 — and because a window animation is ticked
     * once per rendered frame, the animation's own smoothness collapsed with it. Reported as animations
     * running at "10-20fps while the game holds 120", which is precisely backwards: the game was not
     * holding 120 for those 400ms, and nothing else was slow enough to notice.</p>
     *
     * <p>So the window is photographed once when the animation starts and the picture is what moves and
     * fades — {@code DWM}, {@code Quartz} and Mutter all animate a surface, and this file's own note on
     * {@code WindowAnimation} already said a compositor animates the window's surface. It was animating
     * the live tree.</p>
     *
     * <h3>Two guards, and both are load-bearing</h3>
     *
     * <p><b>Not while the capture is pending</b>: {@code paintOverlay} is where the photograph is taken,
     * and returning early here would skip it — so the animation would run for ever on whatever stale
     * picture a previous minimise had left, or on nothing at all.</p>
     *
     * <p><b>Only for a SURFACE animation</b>: a maximise animates layout, so its content genuinely
     * reflows and a stretched photograph of the old layout is the artefact {@code WindowGeometryAnimation}
     * exists to avoid.</p>
     */

    @Override
    public void paintDecoration(CgUiPaintContext ctx, Box box) {
        super.paintDecoration(ctx, box);
        if (!snapshotPending) return;
        snapshotPending = false;
        UIDocument window = document();
        if (window == null) return;

        // THE ROOT SCALE, never the live pose's. pose().m00() here is uiScale MULTIPLIED BY the
        // animation's own scale, so an open -- which starts at a sliver -- sized its photograph to the
        // sliver and then stretched it back over the whole window.
        float uiScale = window.boxes().rootTransform().m00();

        // AND THE ANIMATION'S OPACITY IS SUPPRESSED FOR THE DURATION OF THE SHOT. The other half of the
        // same rule: drawSubtree reads this element's opacity, so a photograph taken mid-fade is a faded
        // photograph, which is then faded AGAIN every frame it is drawn. playOpen and the restore from a
        // minimise both start at opacity 0, so the picture came out at about 6% and the window appeared
        // to snap into existence at the end of the animation with nothing visible before it.
        //
        // A PHOTOGRAPH IS OF THE WINDOW AT REST, so the running animation's own transform and opacity
        // are suppressed for the length of the shot -- and through the ANIMATION SLOT, because that is
        // where WindowAnimation writes them. A StyleGroup write at the same origin is simply outranked
        // by the slot and does nothing at all.
        //
        // Both halves were learned from the same photograph. drawSubtree reads this element's opacity,
        // so one taken mid-fade is a faded picture that is then faded AGAIN wherever it is drawn; and
        // clearing the ambient pose in renderInto is not enough, because the capture re-enters
        // drawSubtree and the first thing drawSubtree does is push this element's own transform.
        //
        // Only a minimise photographs itself now, and it does so on a frame where both are already
        // neutral -- so this guards a picture taken at any other moment rather than fixing a live bug.
        //
        // SUPPRESSED ON THE BOX, where the animation writes it. The old engine wrote ANIMATION-origin
        // slots and had to withdraw the same slots; here the compositor's overrides ARE the animation,
        // so setting them aside and putting them back is the whole of it -- and the transform origin
        // goes with the transform, because a photograph taken about a pinned corner is the artefact
        // this suppression exists to avoid.
        Float wasOpacity = box.opacity() < 1f ? box.opacity() : null;
        UITransform wasTransform = box.transform().isIdentity() ? null : box.transform();
        Float wasOriginX = box.transformOriginX();
        Float wasOriginY = box.transformOriginY();
        if (wasOpacity != null) box.setOpacity(1f);
        if (wasTransform != null) {
            box.setTransform(UITransform.IDENTITY);
            box.setTransformOrigin(null, null);
        }
        try {
            snapshot.capture(ctx, this, uiScale);
        } finally {
            if (wasOpacity != null) box.setOpacity(wasOpacity);
            if (wasTransform != null) {
                box.setTransform(wasTransform);
                box.setTransformOrigin(wasOriginX, wasOriginY);
            }
        }
    }
    private final UINode captionChrome;
    private final UIText titleLabel;
    private final WindowIcon icon;
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
    private UINode lastFocused;

    /** This window's place in the stack, as last assigned. @see Desktop#raise */
    private int stackOrder;
    /** @see #isPinned() */
    private boolean pinned;

    /** Package-private for {@code WindowPinTest}, which asserts the three-state overlay. */
    final Button pinButton;
    /** Retained: its text follows the state, and Tooltip.attach ADDS a pair rather than replacing one. */
    private final Tooltip pinTooltip;

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

    /**
     * Set while the retention cap is discarding this window, so a listener can tell that apart from a
     * close somebody asked for.
     *
     * <p>Both end in {@code destroy()} and {@code onDestroyed}, so without this a listener sees one
     * event and has to guess — and guessing "the user closed it" for an eviction is how a server comes
     * to record a decision nobody made.</p>
     */
    private boolean evicting;

    /** Called by {@code WindowRegistry} just before it discards this window. @see #evicting */
    void markEvicting() {
        this.evicting = true;
    }

    /** @see #evicting */
    public boolean isBeingEvicted() {
        return evicting;
    }

    /** Which of the live owned windows are modal — the only ones that give the slot a box. */

    /** @see #adoptChrome */
    @Nullable
    private UINode adoptedChrome;
    @Nullable
    private UINode chromeOrigin;
    private int chromeOriginIndex = -1;

    private boolean maximized;
    /** The maximise button's tooltip, kept because its text follows the state. @see #MAXIMIZE_TOOLTIP */
    private final Tooltip maximizeTooltip;
    /** Where to put the window back — the MEASURED rect at the moment it was maximised. */
    private float restoreLeft, restoreTop, restoreWidth, restoreHeight;

    public WindowFrame(String title) {
        super(NAME);
        addClass(WINDOW_CLASS);
        // A FOCUS NAVIGATION SCOPE, which is what makes modality PER-WINDOW rather than per-document.
        //
        // `Focus.blockedScopeOf` asks for the nearest enclosing scope above a modal and makes exactly
        // that inert; with nothing in the tree declaring one, the answer is always the document and a
        // dialog opened in one window blocked every other window on the desktop. The service was
        // written for this and `Attribute.FOCUS_SCOPE`'s own javadoc names a window frame as an
        // example -- it was simply never set on anything, so the scoping had no effect it could have.
        //
        // A window is the right and only granularity here. Smaller and a modal stops blocking the
        // window it belongs to, which is the whole of what a modal is for; larger is the document,
        // which is where this started. A modal opened OUTSIDE every frame still blocks the whole
        // document, which is what desktop chrome's own dialogs need.
        set(Attribute.FOCUS_SCOPE, true);
        // Out of flow and positioned: a window is placed by left/top against the desktop's window layer,
        // not laid out among its siblings. This is also what earns the four LEADING resize handles --
        // an origin write only means anything for a box that is positioned.
        StyleGroup.defaultPipeline(getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE).flexDirection(FlexDirection.COLUMN));

        titleLabel = new UIText(title == null ? "" : title);
        titleLabel.addClass(TITLE_CLASS);
        // FALLS THROUGH TO THE BAR. The move listener below is target-only, so a press that lands on the
        // title text would otherwise begin nothing at all -- and "the title bar drags except where the
        // title is" is indistinguishable from a broken drag.
        titleLabel.setHitTest(false);

        controls = new UINode();
        controls.addClass(CONTROLS_CLASS);

        // PIN FIRST, left of minimise. It is the only control here that is not about this window's
        // presence on the desktop, so it sits outside the minimise/maximise/close triplet every window
        // manager draws as a unit -- the same reason a tool window's Dock button sits there.
        pinButton = new Button("");
        pinButton.addClass(PIN_CLASS);
        pinButton.attachListener(() -> setPinned(!isPinned()));
        pinTooltip = Tooltip.attach(pinButton, PIN_TOOLTIP);
        controls.append(pinButton);

        // MINIMISE, so the strip reads minimise-then-close left to right as every window manager
        // draws it, and so the destructive control is the one furthest from the rest.
        minimizeButton = new Button("");
        minimizeButton.addClass(MINIMIZE_CLASS);
        // THROUGH THE ANIMATION, which then hides. Not hide() itself: hiding is DETACHING, and a
        // detached subtree paints nothing, so a window that hid on the press would animate to an empty
        // screen. @see WindowAnimator
        minimizeButton.attachListener(this::minimize);
        Tooltip.attach(minimizeButton, MINIMIZE_TOOLTIP);
        controls.append(minimizeButton);

        maximizeButton = new Button("");
        maximizeButton.addClass(MAXIMIZE_CLASS);
        maximizeButton.attachListener(this::toggleMaximized);
        // RETAINED, because this one's text follows the state -- and Tooltip.attach ADDS a listener pair
        // rather than replacing one, so calling it again to relabel would leave the first tooltip in
        // place and showing, with the new text on an instance nothing ever hovers.
        maximizeTooltip = Tooltip.attach(maximizeButton, MAXIMIZE_TOOLTIP);
        controls.append(maximizeButton);

        closeButton = new Button("");
        closeButton.addClass(CLOSE_CLASS);
        closeButton.attachListener(this::requestClose);
        Tooltip.attach(closeButton, CLOSE_TOOLTIP);
        controls.append(closeButton);

        // BUILT NOW AND HIDDEN, rather than created when an icon arrives. Creating an element from a
        // setter means creating it possibly mid-gesture, and the title bar has no `gap-all` for a hidden
        // child to occupy — the one cost that would have made the lazy version worth it.
        // THE SAME DRAWING THE STRIP USES. It was a bare element with the glyph as an overlay, which
        // predates WindowIcon and meant a caption showed an uncoloured mark while the entry, the hover
        // preview and the switcher tile all showed the same window as a coloured tile — one window with
        // two appearances, differing only in which of them had been written first. WindowIcon carries
        // the tile, the palette keyed on the icon NAME (so the caption and the entry cannot disagree
        // about the hue) and the branded-artwork case; the SIZE stays the context's, which for a caption
        // is `window > .__title-bar__ > .__icon__`.
        icon = new WindowIcon();
        icon.addClass(ICON_CLASS);
        refreshIcon();

        // AFTER the icon and BEFORE the title, which is where IntelliJ's New UI and VS Code's custom
        // title bar both put an application's menu: hard against the left, with the title taking
        // whatever is left. Hidden until something is adopted, and the caption has no `gap-all`, so an
        // empty slot occupies nothing.
        captionChrome = new UINode();
        captionChrome.addClass(CAPTION_CHROME_CLASS);
        captionChrome.setDisplayed(false);

        titleBar = new UINode();
        titleBar.addClass(TITLE_BAR_CLASS);
        titleBar.append(icon);
        titleBar.append(captionChrome);
        titleBar.append(titleLabel);
        titleBar.append(controls);
        append(titleBar);

        // A SCROLL VIEW, so a window smaller than its content gets a bar rather than a clip. The slot
        // has always scrolled -- overflow: hidden is a scroll container, and scrollIntoView from inside
        // a panel relies on it -- but nothing drew a bar, so a panel that grew past the work area (the
        // machine window's engine section unfolding) was cut off with nothing to grab. A browser scrolls
        // its document when the viewport is smaller than it; a window here does the same for its content.
        // ScrollerView's bars are overlays and it takes no focus, so nothing about layout, click-focus
        // or the frame's focus delegate changes for content that fits.
        content = new ScrollerView();
        content.addClass(CONTENT_CLASS);
        append(content);

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

    /**
     * Whether {@code node} is this window's minimise control, or inside it.
     *
     * <p>Asked by {@code Desktop.focusMoved}, which is handed the real focus owner and so can use
     * identity where the press listener cannot. Click-focus lands on the button itself.</p>
     */
    public boolean isMinimizeControl(@Nullable UINode node) {
        for (UINode at = node; at != null; at = at.composedParent()) {
            if (at == minimizeButton) return true;
        }
        return false;
    }

    /**
     * Whether a press landed over {@code control}, asked by POSITION.
     *
     * <p><b>The target cannot answer this.</b> A listener on a shadow host never sees its own parts —
     * {@code getTarget()} is retargeted to the host before the listener runs — and the caption controls
     * are this frame's parts, so a walk up from the target finds the frame and never the button. The
     * press point is not retargeted, and the control's box is in the same surface pixels a mouse-down
     * listener's coordinates already are.</p>
     */
    private static boolean pressedOver(float x, float y, @Nullable UINode control) {
        if (control == null) return false;
        Box box = control.box();
        if (box == null) return false;
        return x >= box.worldX() && x <= box.worldX() + box.width()
                && y >= box.worldY() && y <= box.worldY() + box.height();
    }

    private void installActivation() {
        onMouseDown.attachListener((element, event) -> {
            // A PRESS IN THE CONTENT HAS ALREADY DECIDED WHERE FOCUS GOES. emitMouseDown blurred what
            // was focused and focused what was hit -- a control, or this frame when the press landed on
            // nothing focusable -- before this listener ran. That is the engine's rule everywhere
            // (clicking bare background deselects), and restoring the window's focus MEMORY on top of
            // it undid it: typing in a field, clicking the panel beside it to leave, and the field
            // committed on the blur and then took focus straight back, caret and ring and all, unless
            // the click had happened to land on another control. A press on CHROME -- the caption, a
            // resize edge, the slot's own scrollbar -- is the case the memory exists for: dragging a
            // window by its title bar must not lose the field you were typing in.
            boolean inContent = pressedInContent(((UINode) event.getTarget()));
            if (inContent) rememberFocusChosenByPress();
            Desktop desktop = desktop();
            if (desktop != null) {
                // MINIMISING IS NOT WORKING IN A WINDOW, so its button does not raise one.
                //
                // Every other press here means "I am using this window" and raising is the whole point.
                // A press on MINIMISE means the opposite, and raising first is visible: pressing a
                // background window's minimise lit its taskbar entry for the press, then `minimize()`
                // deactivated it -- so the entry flashed and faded out over its own transition, which
                // reads as a flicker lasting about as long as the flight.
                //
                // It also defeated a guard that says so in its own words. `minimize()` deactivates only
                // `if (owner.activeWindow() == this)`, "or minimising a background one would deactivate
                // the foreground" -- but the press had just made the background one active, so the test
                // passed and the foreground was deactivated anyway. Measured: before the gesture the
                // front window is active, after it NOBODY is, and the front window's entry goes dark
                // with the one that left.
                //
                // CLOSE is deliberately left raising. It ends with `dispose()`, which reads whether the
                // window was active in order to hand the keyboard on, and that path is documented and
                // self-correcting: activate, destroy, `activateTopmost()`. Minimise is the one gesture
                // documented to hand over to nobody, which is exactly why it cannot afford to take the
                // foreground with it.
                if (!pressedOver(event.getPosition().x(), event.getPosition().y(), minimizeButton)) {
                    desktop.activate(this, false, !inContent);
                }
                return;
            }
            if (inContent) return;
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
            if (((UINode) event.getTarget()) != null && ((UINode) event.getTarget()) != this) lastFocused = ((UINode) event.getTarget());
        }, false, true);
    }

    /** A window owns its chrome; put content in {@link #content()}. */
    /**
     * A content press that landed on nothing focusable is the user choosing nothing in particular —
     * and THAT is now what this window last had.
     *
     * <p>Without this the memory still named the field the user had just clicked away from, so the next
     * press on the caption — to drag the window — restored it, caret and ring and all. Recorded here
     * rather than by the focus listener below, which must never record the frame: a caption press
     * focuses the frame too, through the same click-focus walk, and that one must not forget the
     * field. The two presses are told apart by where they landed, which only this listener knows.</p>
     */
    private void rememberFocusChosenByPress() {
        UIDocument window = document();
        if (window != null && window.focus().focused() == this) lastFocused = this;
    }

    /**
     * Whether a press landed in the window's CONTENT rather than on its chrome.
     *
     * <p>Content is the slot and anything under it, except the slot's own parts: a
     * {@link ScrollerView}'s bars are chrome in every browser, and a press on one must not cost the
     * field its caret. The frame, the caption and the resize handles are chrome.</p>
     *
     * <p><b>The exclusion needs no test of its own here, and that is the shadow boundary doing its
     * job.</b> The old engine asked whether a child of the slot was an INTERNAL child, which is a flag
     * anything could carry; a {@code ScrollerView}'s bars now live in its shadow tree, so a LIGHT walk
     * up from one reaches the shadow root and stops rather than reaching the slot. A press on real
     * content goes through the slot's assignment and reaches it. Two answers, one walk.</p>
     */
    private boolean pressedInContent(@Nullable UINode target) {
        if (target == null || target == this) return false;
        for (UINode at = target; at != null; at = at.parent()) {
            if (at == content) return true;
        }
        return false;
    }

    // ── The parts ───────────────────────────────────────────────────────────

    /** Where a window's content goes. The named accessor a composite owes its callers. */
    public UINode content() {
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
    public WindowFrame setContent(UINode newContent) {
        if (newContent == null) return this;
        content.append(newContent);
        if (newContent instanceof WindowChrome) adoptChrome((WindowChrome) newContent);
        return this;
    }

    /**
     * Moves a provider's chrome into this window's caption.
     *
     * <p><b>Moved, not copied</b> — see {@link WindowChrome}. Where it came from is remembered, so
     * {@link #releaseChrome()} can put it back exactly there.</p>
     *
     * <p><b>The internal-child bookkeeping this used to carry is gone, and the new engine is why.</b>
     * The old version remembered {@code isInternalUI()} as well as the parent and the index, because
     * a workbench's menu bar was an internal child and returning it as a public one would have left it
     * publicly removable — and because {@code removeChild} silently REFUSED an internal child, so the
     * detach had to know which of two methods to call. Here a node is simply a child of whatever holds
     * it, shadow or light, and {@code insertAt} moves it back into the same parent at the same index
     * whichever that was: the flag was recording a distinction the tree no longer makes.</p>
     */
    public void adoptChrome(WindowChrome provider) {
        if (provider == null) return;
        UINode chrome = provider.captionChrome();
        if (chrome == null || chrome == adoptedChrome) return;
        releaseChrome();

        chromeOrigin = chrome.parent();
        chromeOriginIndex = chromeOrigin == null ? -1 : chromeOrigin.indexOf(chrome);

        adoptedChrome = chrome;
        // append REPARENTS, and reports it as ONE `moved` -- which is the difference that matters over
        // the wire. `insertAt` detaches from the previous parent through `moveTo` rather than through a
        // remove followed by an insert, so a mirroring peer keeps the element instead of destroying and
        // rebuilding it. On the old engine this needed a two-method fallback and the comment that went
        // with it; here it is one call.
        captionChrome.append(chrome);
        // AND THE CLASS, which is what a sheet must key its caption styling off. @see ADOPTED_CHROME_CLASS
        chrome.addClass(ADOPTED_CHROME_CLASS);
        captionChrome.setDisplayed(true);
    }

    /** Puts adopted chrome back where it came from. Safe to call when there is none. */
    public void releaseChrome() {
        if (adoptedChrome == null) return;
        UINode chrome = adoptedChrome;
        adoptedChrome = null;
        // BEFORE the reparent, though either order works -- removeClass invalidates the match, and that
        // invalidation is the entire reason the class exists. @see ADOPTED_CHROME_CLASS
        chrome.removeClass(ADOPTED_CHROME_CLASS);
        captionChrome.setDisplayed(false);

        if (chromeOrigin == null) {
            captionChrome.remove(chrome);
            return;
        }
        int index = Math.max(0, Math.min(chromeOriginIndex, chromeOrigin.children().size()));
        chromeOrigin.insertAt(index, chrome);
        chromeOrigin = null;
    }

    /** What this window is currently hosting in its caption, or null. */
    @Nullable
    public UINode adoptedChrome() {
        return adoptedChrome;
    }

    /** The drag handle. Exposed so a caller may add chrome of its own beside the title. */
    public UINode titleBar() {
        return titleBar;
    }

    /** The button strip. Exposed for the same reason {@link #titleBar()} is. */
    public UINode controls() {
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

    /**
     * The caption's tile — the same {@link WindowIcon} the strip, the preview and the switcher draw.
     * Package-private: it exists so a test can read the hue, which nothing else can observe.
     */
    WindowIcon icon() {
        return icon;
    }

    /** The icon this window declares, or null. @see #setIcon */
    @Nullable
    public String iconName() {
        return iconName;
    }

    /**
     * THE CAPTION SHOWS WHATEVER THE TASKBAR SHOWS FOR THIS WINDOW.
     *
     * <p>One window, one appearance: the strip, the hover preview and the switcher all draw this window
     * as a tile — its icon, or its title's initial on a coloured square when it declares none. The
     * caption used to show a tile only for a declared icon, which left the commonest window of all — a
     * server's, which declares nothing — with a caption that disagreed with its own taskbar entry: a red
     * "M" in the strip, and nothing beside the title it stood for. Same fallback, keyed the same way
     * ({@link WindowIcon#show} decides both), so the two cannot drift.</p>
     *
     * <p>The exception is the one the old rule was protecting. A <b>tool window</b> has no taskbar entry
     * to agree with, and its caption IS its panel's header, adopted from the panel — a filled square
     * there is noise on something the taskbar does not consider a window. It shows a declared icon and
     * nothing otherwise. An icon that names artwork nothing can load falls back to the monogram, as the
     * strip's does, rather than to whatever was drawn before.</p>
     */
    private void refreshIcon() {
        boolean declared = iconName != null && CgUiSvg.ofIcon(iconName) != null;
        if (!declared && toolWindow) {
            icon.setDisplayed(false);
            return;
        }
        icon.show(declared ? iconName : null, getTitle());
        icon.setDisplayed(true);
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
        refreshIcon();
        Desktop desktop = owner;
        if (desktop != null) desktop.registry().changed();
        return this;
    }

    public WindowFrame setTitle(String title) {
        titleLabel.setText(title == null ? "" : title);
        // The monogram is the title's initial, and the taskbar entry carries the title too.
        refreshIcon();
        if (owner != null) owner.registry().changed();
        return this;
    }

    public String getTitle() {
        return titleLabel.getText();
    }

    // ── Owned windows ───────────────────────────────────────────────────────

    /**
     * Where this window's <b>owned</b> surfaces live: the window itself.
     *
     * <p><b>There is no slot, and removing it is the fix rather than a simplification.</b> An owned
     * surface is {@code position: absolute} over the frame, so the frame is already the containing
     * block, already the paint parent (an out-of-flow child draws above the content), and already the
     * hit-test entry. A slot in between added nothing but a second box that had to be told when to
     * exist.</p>
     *
     * <p>What that cost is the whole reason this is worth writing down. The slot took its size from a
     * class, the class from a set of what was showing, and the set from somebody remembering to call a
     * release — so a dialog that closed without one left a full-size transparent box over the window,
     * and every click on the window's own chrome went into it. That is not a bookkeeping slip: the
     * engine already answers "is this showing" exactly, because <b>a node that is not displayed has no
     * box at all</b>, and the slot was a hand-maintained copy of that answer which could disagree with
     * it. A copy of a fact the engine owns is a fact that goes stale.</p>
     *
     * <p>Blocking needs none of it either. A modal blocks because its own {@code __backdrop__} is
     * {@code 100%} of its containing block — which is now the frame — and that backdrop goes away with
     * the dialog. Nothing has to be told.</p>
     */
    public UINode overlaySlot() {
        return this;
    }

    /**
     * Parents an owned surface onto this frame — a modal dialog, or a floating tool window.
     *
     * <p>The whole implementation, and that is the point: an owned surface is out of flow, so being a
     * child of the frame already gives it the frame as its containing block, a place above the content
     * in paint order, and a hit-test entry. @see #overlaySlot()</p>
     *
     * <p><b>There is no {@code blocking} flag and no counterpart to this method.</b> The flag chose
     * whether a slot took a full-size box; a modal blocks through its own backdrop instead, which is
     * sized by the sheet and destroyed with the dialog. And nothing has to announce that an owned
     * surface has stopped showing, because a node that is not displayed has no box — the question the
     * old {@code releaseOwned} existed to answer is one the engine answers on every layout.</p>
     */
    public void attachOwned(UINode owned) {
        if (owned == null) return;
        if (owned.parent() != this) append(owned);
        // AND IT IS TOLD, because being parented here is not something it can read as ownership --
        // every overlay is parented somewhere. Without this a modal promotes itself to the DOCUMENT's
        // top layer on show and is laid out against the whole screen, which is the standing rule
        // inverted: a window's modal is owned by it and never globally promoted. Idempotent, so the
        // repeated attach a re-shown dialog makes costs a field write.
        if (owned instanceof Dialog dialog) dialog.setOwned(true);
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

    /**
     * Whether any live window names this one as its owner.
     *
     * <p>The relation is stored on the OWNED window ({@link #ownerWindow()}), which is the right way
     * round -- a window has one owner and any number of owned -- so the reverse question has to be
     * asked of the registry. Worth having as a query because the ANSWER is what several behaviours
     * turn on: an owner takes its owned windows down with it, a raise moves the whole group, and an
     * owned window's slot must be released when the last one goes or it sits full-size over its
     * owner's content swallowing every click.</p>
     */
    public boolean hasOwnedWindows() {
        // ATTACHED surfaces only -- never the `setOwnerWindow` relation, which is a different claim
        // and the distinction this whole pair exists to make. `attachOwned` parents a surface INTO
        // this frame, so it is clamped inside it, has no registry entry and no taskbar button: a
        // modal, or a FLOATING tool window. `setOwnerWindow` is the same belonging WITHOUT the
        // parenting -- a first-class top-level window that merely belongs to another, which is what
        // a torn-out tool window is. Counting the relation makes a tear-out report the editor as
        // still holding it, which is precisely what the gesture was supposed to end.
        for (UINode child : children()) {
            if (child instanceof WindowFrame) return true;
        }
        return false;
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
        // Whether the caption falls back to a monogram is decided by this. @see #refreshIcon
        refreshIcon();
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
            DataKey.create("windowFrame.new", WindowFrame.class);

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


    /**
     * The window {@code element} belongs to, or null — its nearest frame ancestor.
     *
     * <p>The DOM chain, which is the right one: promotion moves a Taffy node and never a DOM parent, so
     * a promoted dialog is still inside the window that opened it.</p>
     */
    @Nullable
    public static WindowFrame of(@Nullable UINode element) {
        for (UINode el = element; el != null; el = el.parent()) {
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
    public void setActive(boolean active) {
        if (active) addClass(ACTIVE_CLASS);
        else removeClass(ACTIVE_CLASS);
    }

    /** Where this window's stacking order currently sits. @see Desktop#raise */
    public int stackOrder() {
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
    /**
     * Whether this window sits in the always-on-top band and survives the desktop closing.
     *
     * <p>One toggle, two effects — Win32's {@code WS_EX_TOPMOST} and EWMH's
     * {@code _NET_WM_STATE_ABOVE}, plus the thing those cannot express because they have no desktop to
     * close: a pinned window keeps rendering on the HUD after the screen is put away. @see
     * Desktop#enterHudMode</p>
     *
     * <p>The band itself is one line in {@link Desktop#raise} — an offset on the same monotonic
     * counter — which is exactly what the band model predicted when always-on-top was refused for
     * having no consumer.</p>
     */
    public boolean isPinned() {
        return pinned;
    }

    /**
     * Pins or unpins, moving the frame between bands.
     *
     * <p><b>Re-raises through the desktop rather than writing z itself.</b> The band is an offset on
     * the raise counter, so the only thing that can put a frame in the right place is the thing that
     * hands out stacking order — and re-raising also keeps the owner group together, which a bare
     * z-write would silently break.</p>
     */
    public WindowFrame setPinned(boolean pinned) {
        if (this.pinned == pinned) return this;
        this.pinned = pinned;
        if (pinned) addClass(PINNED_CLASS); else removeClass(PINNED_CLASS);
        pinTooltip.setText(pinned ? UNPIN_TOOLTIP : PIN_TOOLTIP);
        Desktop desktop = desktop();
        if (desktop != null) desktop.raise(this);
        return this;
    }

    public void setStackOrder(int order) {
        this.stackOrder = order;
        StyleGroup.inlinePipeline(getStyle().getGeneralGroup(), g -> g.zIndex(order));
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
    public void restoreFocus(boolean programmatic) {
        UIDocument window = document();
        if (window == null) return;
        Focus focus = window.focus();
        // ALREADY SOMEWHERE INSIDE — but the FRAME ITSELF does not count, and that exception is the
        // whole of a reported bug.
        //
        // A frame is CLICK_NOT_TABBABLE, so `emitMouseDown` walks up from whatever was hit to the nearest
        // ancestor that focuses on click and lands on the frame BEFORE this ever runs. A plain
        // `isInclusiveAncestorOf()` then reads that as "focus is already in this window" and returns, so the delegate
        // never runs and focus stays on the frame -- which is a window that looks focused and whose
        // CONTENT is not. Grabbing a floating tool window by its caption left its rail button dark,
        // because that button lights from focus being inside the panel's own container.
        //
        // Focus on a real control inside (a caption button, something in the content) is still left
        // alone: that is somebody's deliberate target and moving it would be theft.
        UINode focused = focus.focused();
        if (focused != null && focused != this && isInclusiveAncestorOf(focused)) return;

        UINode wanted = isInclusiveAncestorOf(lastFocused) && focus.focusable(lastFocused)
                ? lastFocused : null;
        if (wanted == null) wanted = focusDelegate();
        if (wanted == null) wanted = this;
        if (programmatic) focus.requestFocus(wanted);
        else focus.requestPointerFocus(wanted);
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
    @Nullable
    protected UINode focusDelegate() {
        UIDocument window = document();
        return window == null ? null : window.focus().firstFocusableIn(content);
    }

    /**
     * Whether {@code element} is this frame or inside it.
     *
     * <p>Renamed off {@code contains}, which {@link UINode} now declares with a different meaning —
     * <em>light</em> containment, excluding the node itself. Overriding it to mean "or is me" would
     * have been a silent widening of a method every caller in the engine already relies on.</p>
     */
    private boolean isInclusiveAncestorOf(@Nullable UINode element) {
        for (UINode walk = element; walk != null; walk = walk.parent()) {
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
        UINode layer = parent();
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
        UINode above = layer.parent();
        // AN OWNED FRAME IS A CHILD OF ITS OWNER, with no slot in between since the slot was
        // deleted -- so the question is simply whether the layer it is leaving IS a window.
        if (layer instanceof WindowFrame candidate) ownedBy = candidate;

        // The layer's removeChild is what flips the state and tells the registry -- so a bare
        // removeSelf() by some other caller means exactly the same thing as hide(), rather than leaving
        // a window that is detached and still claims to be visible.
        // MEASURED BEFORE THE DETACH, which is the whole of it -- see recordedWidth().
        captureVisibleSize();
        layer.remove(this);
        // ...WHICH ONLY HOLDS FOR A WINDOW LAYER. An OWNED frame's parent is its owner's overlay slot,
        // an ordinary UINode whose removeChild detaches and nothing else -- so the delegation above
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
        if (state == WindowState.VISIBLE && parent() != null) return;
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

        // SHOWN WHILE THE DESKTOP IS OFF SCREEN MEANS PINNED. The switcher is the path that found this:
        // Ctrl+Tab reaches a pinned window's keyboard, and the registry keeps HIDDEN windows, so cycling
        // could show a window the HUD had put away. It then painted -- the overlay draws the whole window
        // layer -- while every click fell through it, because a window that is merely visible was never
        // what the overlay accepted input for.
        //
        // Auto-pinning is the honest reading rather than a patch: with no desktop on screen, "bring this
        // window to the front" and "put this window over the game" are the same request, and pinning is
        // what that means here. It also keeps the state truthful -- the caption's pin shows pressed, the
        // band puts it above, and it can be unpinned like anything else.
        //
        // exitHudMode clears hudMode BEFORE it restores what it hid, so a restore never lands here.
        Desktop attached = desktop();
        if (attached != null && attached.isHudMode() && !isPinned()) {
            setPinned(true);
            addClass(HUD_CLASS);
        }

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
    public void setOwner(@Nullable Desktop desktop) {
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
    public void markHidden() {
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
    public float restoreLeft() {
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
        return parent() == null || box() == null ? lastVisibleWidth : box().width();
    }

    /** @see #recordedWidth() */
    public float recordedHeight() {
        return parent() == null || box() == null ? lastVisibleHeight : box().height();
    }

    private void captureVisibleSize() {
        Box box = box();
        float width = box == null ? 0f : box.width();
        float height = box == null ? 0f : box.height();
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
    public float restoreTop() {
        return restoreTop;
    }

    /** @see #restoreLeft() */
    public float restoreWidth() {
        return restoreWidth;
    }

    /** @see #restoreLeft() */
    public float restoreHeight() {
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
    public void setRestoreRect(float left, float top, float width, float height) {
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
        Box current = box();
        restoreWidth = current == null ? 0f : current.width();
        restoreHeight = current == null ? 0f : current.height();
        maximized = true;
        addClass(MAXIMIZED_CLASS);
        maximizeTooltip.setText(RESTORE_TOOLTIP);
        UINode area = resizeContainingBlock();
        Box areaBox = area == null ? null : area.box();
        if (areaBox != null && animateGeometry(restoreLeft, restoreTop, restoreWidth, restoreHeight,
                0f, 0f, areaBox.width(), areaBox.height(), this::applyMaximizedRect)) {
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
        UINode area = resizeContainingBlock();
        Box areaBox = area == null ? null : area.box();
        Box self = box();
        // NO SUBTRACTION. A frame IS a child of its containing block, so `Box.x()` -- the offset from
        // the host's border-box origin -- already is the work-area inset the old absolute accessor had
        // to have the area's origin taken back off. @see plan_m6.md 6.4
        if (areaBox != null && self != null && animateGeometry(self.x(), self.y(),
                self.width(), self.height(),
                restoreLeft, restoreTop, restoreWidth, restoreHeight, this::applyRestoredRect)) {
            return;
        }
        applyRestoredRect();
    }

    /**
     * Un-maximises with the SIZE animating and the POSITION left to the caller — a drag tearing a
     * maximised window loose.
     *
     * <p>The window shrinks toward the cursor over {@code SIZE_NANOS} while the drag goes on placing it
     * every frame, which is what tearing one loose looks like everywhere else. An ordinary
     * {@link #restore()} cannot be used: it animates the position too and blocks {@link #applyPosition}
     * while it runs, so the window travelled to its stored rect and ignored the pointer.</p>
     *
     * <p>The settle writes the resting SIZE and nothing else. Writing the stored position there would
     * yank the window out from under the hand at the end of the shrink, which is the same bug arriving
     * one animation later.</p>
     */
    void restoreShrinkingUnderDrag() {
        if (!maximized) return;
        maximized = false;
        removeClass(MAXIMIZED_CLASS);
        maximizeTooltip.setText(MAXIMIZE_TOOLTIP);

        Box self = box();
        if (self == null || !animator.playShrink(self.width(), self.height(),
                restoreWidth, restoreHeight, this::applyRestoredSize)) {
            applyRestoredSize();
        }
    }

    /** The resting SIZE, without touching the position. @see #restoreShrinkingUnderDrag */
    private void applyRestoredSize() {
        StyleGroup.inlinePipeline(getStyle().getLayoutGroup(),
                l -> l.width(restoreWidth).height(restoreHeight));
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
        Box box = box();
        if (box != null && animateGeometry(placedLeft, placedTop, box.width(), box.height(),
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
    public void onUserResize(int handleDx, int handleDy, float width, float height) {
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

    /** Ends whatever is playing on this window, leaving no resting value. @see WindowAnimator#cancel */
    public void cancelAnimation() {
        animator.cancel();
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
    public void playOpenAnimation() {
        animator.playOpen();
    }

    // ── Last measured box ───────────────────────────────────────────────────────────────────────

    private float lastBoxW, lastBoxH;


    private void rememberBox() {
        Box box = box();
        if (box == null || box.width() <= 0f || box.height() <= 0f) return;
        lastBoxW = box.width();
        lastBoxH = box.height();
    }

    /**
     * This frame's box, falling back to the last one that was actually measured.
     *
     * <p><b>A REATTACHED WINDOW HAS NO BOX YET, and a restore reads one in the same breath as the
     * reattach.</b> {@code show(true)} calls {@code owner.reattach(this)} and then starts the restore
     * animation immediately — deliberately, since an animation writes styles and a detached element
     * matches no selector — but the reattach has only just registered a fresh Taffy node, whose layout
     * does not run until the next {@code calculateLayout}. So the live box is 0x0 for exactly one frame.</p>
     *
     * <p>Everything downstream then failed quietly and in order: {@code toward} refuses a zero-sized
     * self, so did the fallback, so {@code towardTaskbar} answered null and {@code playRestore} took its
     * last resort and played the ENTRY animation instead — a window that had flown into the taskbar came
     * back unfolding from its own centre. The minimise is immune because a window is fully laid out on
     * the way out, which is what makes the pair look asymmetric rather than broken.</p>
     *
     * <p>Deferring the animation a frame is the other repair and is worse: frame one would then draw the
     * window at rest, full size, which is the flash the "write the START value in the constructor" rule
     * exists to prevent. The geometry is known — it simply is not in the live cache yet.</p>
     */
    /**
     * <b>The POSITION is always live; only the SIZE is ever remembered.</b>
     *
     * <p>Measured, not assumed: a detached frame's runtime cache keeps its x/y and zeroes only its
     * width and height. Falling back to a remembered POSITION was therefore both unnecessary and wrong,
     * because {@code rememberBox} runs from {@code onLayoutChanged} <em>before</em> {@code applyPosition}
     * corrects the window — so it records where the window was BEFORE the correction, and a window that
     * moves once (cascaded, then moved to its persisted position) leaves a remembered position that never
     * catches up. The probe caught it exactly: remembered {@code 871,435.5} against a live {@code 391,165.5}.</p>
     *
     * <p>A restore then aimed its flight from a point far up and to the left of the window, which reads as
     * the window flying in from the left of the screen. <b>Only on the FIRST restore</b> — afterwards the
     * window has been laid out where it really is, so the remembered value is finally correct, which is
     * precisely how it was reported.</p>
     */
    public float boxX() {
        Box box = box();
        return box == null ? placedLeft : box.x();
    }

    /** @see #boxX() */
    public float boxY() {
        Box box = box();
        return box == null ? placedTop : box.y();
    }

    /** @see #boxX() */
    public float boxWidth() {
        Box box = box();
        float live = box == null ? 0f : box.width();
        return live > 0f ? live : lastBoxW;
    }

    /** @see #boxX() */
    public float boxHeight() {
        Box box = box();
        float live = box == null ? 0f : box.height();
        return live > 0f ? live : lastBoxH;
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
    public void applyResizeOrigin(float left, float top) {
        placed = true;
        applyPosition(left, top);
    }

    /**
     * The written position once there is one; the measured offset until then.
     *
     * <p><b>The fallback is measured, never zero.</b> The old base class read the {@code left} inset
     * and answered {@code 0} for {@code auto} — the teleport-to-the-corner bug a leading drag on an
     * un-placed window would reproduce exactly. Here the offset within the containing block is what
     * {@code Box.x()} already IS, so there is nothing to derive: a box is positioned relative to its
     * host, which is what "wherever the static position put it" means.</p>
     */
    @Override
    public float resizeOriginLeft() {
        if (placed) return placedLeft;
        Box box = box();
        return box == null ? 0f : box.x();
    }

    /** @see #resizeOriginLeft */
    @Override
    public float resizeOriginTop() {
        if (placed) return placedTop;
        Box box = box();
        return box == null ? 0f : box.y();
    }

    /**
     * The box this window is placed, clamped and resized against — its containing block.
     *
     * <p>Not {@code Desktop.windowLayer()}, which is the same element only for a top-level window: an
     * OWNED frame's parent is its owner's overlay slot, and clamping it against the layer would let it
     * be dragged out of the window it belongs to.</p>
     */
    @Override
    @Nullable
    public UINode resizeContainingBlock() {
        return parent();
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
    public void reclamp() {
        if (!placed) return;
        // NOT WHILE A DRAG IS LIVE. The clamp reads measured boxes, which lag the drag by a frame, so
        // running both writes last frame's answer over the one the pointer just asked for.
        UIDocument window = document();
        if (window != null && window.input().mode(Drag.class) != null) return;
        applyPosition(wantedLeft, wantedTop);
    }

    /**
     * Post-layout is where an unplaced window learns where it goes: the cascade offset needs a measured
     * caption height and a measured work area, and neither exists when {@code addWindow} runs.
     *
     * <p><b>An {@code afterLayout} hook where the old engine overrode {@code onLayoutChanged}.</b> Both
     * inputs are measured boxes, and an ordinary per-frame hook runs BEFORE layout — so on the frame a
     * window first appears it would read the caption's height and the work area from before either
     * existed, which is the "measures zero on the same frame" trap the old engine documents.</p>
     *
     * <p>Writing style from here re-dirties layout, which the old engine relied on settling within the
     * same pass. It does not here — layout runs ONCE — so a placement written now lands on the next
     * frame. That is why an unplaced window is laid out off-screen rather than at the origin: the same
     * answer {@code Popover} reached for a popup drawn before it could be placed.</p>
     */
    private void placeAfterLayout() {
        rememberBox();
        if (placed) {
            applyPosition(wantedLeft, wantedTop);
            return;
        }
        Desktop desktop = desktop();
        if (desktop != null) desktop.placeByCascade(this);
    }

    @Override
    protected void connected() {
        super.connected();
        UIDocument document = document();
        if (document == null) return;
        document.animation().afterLayout(this, delta -> {
            placeAfterLayout();
            return true;
        });
    }

    /**
     * A press landed on this window and its own modal ate it: raise, and flash the dialog.
     *
     * <p><b>This window's mouse-down listener never runs for that press.</b> Everything in a blocked
     * window is inert, inertness is {@code pointer-events: none}, so the hit resolves to nothing at all
     * -- which is also exactly what a press on bare desktop looks like. So clicking a blocked window's
     * caption did nothing whatsoever: it did not come forward, it did not take focus, and the desktop
     * reported no active window, which is indistinguishable from the application having hung. The only
     * way to reach the window was to click the dialog itself.</p>
     *
     * <p>Every desktop raises the owner group and draws attention to the dialog instead -- Windows
     * flashes it and dings -- because the one thing the user needs told is <em>where the click went</em>.
     * Raising is safe while blocked: it is a {@code z-index} write and a focus restore, and focus can
     * only land inside the modal because {@code requestFocus} consults the full inertness predicate.</p>
     */
    @Override
    public void pressBlocked(UINode modal) {
        Desktop desktop = desktop();
        if (desktop != null) desktop.activate(this, false, true);
        // THE FRAME KNOWS WHAT A DIALOG IS and the engine does not -- `desktop` sits above
        // `widget.overlay`, so this is the legal direction for the type check, and it is why the hook
        // is on the SCOPE rather than on the modal.
        if (modal instanceof Dialog dialog) dialog.pulse();
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
    public float captionHeight() {
        // ZERO WHEN THERE IS NO BOX, which every caller already handles: the clamp in
        // `applyPosition` guards on `caption > 0f` and the cascade step falls back to a constant.
        // A frame that has not been laid out has no title bar box, and `moveTo` before `addWindow`
        // reaches here first.
        Box bar = titleBar.box();
        return bar == null ? 0f : bar.height();
    }

    /**
     * The box this window is placed and clamped against — its containing block.
     *
     * <p>Package-private because {@code UINode.resizeContainingBlock()} is {@code protected} and is
     * declared in another package, so a collaborator here cannot ask for it directly. {@code
     * CanvasOverlayMove} takes a supplier for exactly this reason and says so.</p>
     *
     * <p>Not {@code desktop().windowLayer()}, which is the same element only for a top-level window: an
     * OWNED frame's containing block is its owner's overlay slot, and clamping it against the layer
     * would let it be dragged out of the window it belongs to.</p>
     */
    @Nullable
    UINode workArea() {
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
    /** Whether a move drag is live — see the top clamp in {@link #applyPosition}. */
    private boolean moving;

    /** @see #moving */
    void setMoving(boolean moving) {
        if (this.moving == moving) return;
        this.moving = moving;
        // WITHDRAWING THE HEADROOM HAS TO BRING THE WINDOW BACK DOWN, and nothing else will: reclamp()
        // deliberately declines while a drag is live, so a window released with its caption above the
        // work area would simply stay there, unreachable -- the exact thing the resting clamp exists to
        // prevent. A snap commits before this runs and a maximised window is exempt, so neither is
        // disturbed.
        if (!moving && placed) applyPosition(wantedLeft, wantedTop);
    }

    private void applyPosition(float left, float top) {
        wantedLeft = left;
        wantedTop = top;
        // A MAXIMISED WINDOW HAS NO POSITION, and neither does one mid-resize. The intent above is
        // still recorded -- it is what restore comes back to -- but writing it would fight the 100% rect
        // on every layout pass, and this runs from the layout callback and from the work-area re-clamp.
        if (maximized || geometryAnimating) return;

        float clampedLeft = left;
        float clampedTop = top;

        UINode area = resizeContainingBlock();
        Box areaBox = area == null ? null : area.box();
        Box frameBox = box();
        float areaWidth = areaBox == null ? 0f : areaBox.width();
        float areaHeight = areaBox == null ? 0f : areaBox.height();
        float frameWidth = frameBox == null ? 0f : frameBox.width();
        float caption = captionHeight();

        // A ZERO BOX CARRIES NO INFORMATION, so the intent is written through unclamped rather than
        // clamped against nothing -- AND A NULL ONE IS THE SAME STATEMENT. A node that is not laid
        // out has no box at all, where the old engine's cache always answered, so `moveTo` before
        // `addWindow` threw here: the most natural call order there is, and the first thing the
        // desktop scene did. Zero and null mean one thing to the clamp below and both must reach it. CanvasOverlayMove's version returns early instead and loses the write
        // entirely -- which is survivable there because something re-places the panel every frame, and
        // would strand a window here on the one frame that matters, its first.
        if (areaWidth > 0f && areaHeight > 0f && frameWidth > 0f && caption > 0f) {
            clampedLeft = clamp(left, caption - frameWidth, areaWidth - caption);
            // THE CAPTION MAY RISE ABOVE THE WORK AREA WHILE BEING DRAGGED, and only while.
            //
            // The resting rule is that a title bar stays reachable, so a window cannot park above the
            // top. During a MOVE that rule makes the top snap zone unreachable: the pointer rides at a
            // fixed offset INSIDE the caption, so a window clamped at top 0 leaves the pointer that same
            // offset below the border -- and a zone read from the pointer can then only be entered by
            // someone who happened to grab the caption's topmost pixels. It is why the band used to be a
            // whole caption deep, which made the top the one edge triggered by the WINDOW rather than by
            // the cursor.
            //
            // One caption of headroom is exactly enough for any grab to bring the cursor to the border,
            // and no more. Windows does the same -- drag a window up and its title bar goes off the top
            // while the cursor reaches the edge. reclamp() on drag end brings back anything that did not
            // snap. @see WindowMove
            clampedTop = clamp(top, moving ? -caption : 0f, areaHeight - caption);
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
