package com.crystalgui.desktop;

import com.crystalgui.desktop.app.ApplicationRegistry;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.ui.box.BoxPainter;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.desktop.host.ScreenOverlay;
import com.crystalgui.core.window.DesktopPresentation;
import com.crystalgui.core.window.WindowState;
import com.crystalgui.desktop.motion.WindowAnimator;
import com.crystalgui.desktop.motion.WindowGeometryAnimation;
import com.crystalgui.desktop.motion.WindowMotion;
import com.crystalgui.desktop.switcher.WindowSwitcher;
import com.crystalgui.desktop.taskbar.Taskbar;
import com.crystalgui.desktop.window.SnapZones;
import com.crystalgui.desktop.window.WindowCommands;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.desktop.window.WindowKeyboardMove;
import com.crystalgui.desktop.window.WindowRegistry;
import com.crystalgui.core.storage.ConfigStorage;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.command.MenuId;
import com.crystalgui.core.data.DataKey;
import com.crystalgui.core.data.DataProvider;
import com.crystalgui.core.notify.Notification;
import com.crystalgui.core.notify.Notifications;
import com.crystalgui.core.signal.ConnectionGroup;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.easing.Easing;
import com.crystalgui.style.easing.ProgressFunctions;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.overlay.ContextMenu;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.input.keymap.KeyChord;
import com.crystalgui.ui.input.keymap.Keymap;
import dev.vfyjxf.taffy.style.FlexDirection;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

/**
 * The compositor host — CrystalOS's desktop, and the parent of every {@link WindowFrame}.
 *
 * <p><b>Nobody constructs one.</b> Every {@code UIDocument} owns a desktop and hands it out through
 * {@code UIDocument.desktop()}; opening a UI is {@code window.openWindow(frame)} and nothing else. That
 * is the same ownership {@code UIDocument.windowOverlayLayer()} already has — an engine-owned layer built
 * on first use, which is observably "always there" while costing nothing to a window that never opens
 * one. A compositor that each application had to assemble for itself would be a compositor each
 * application assembled slightly differently.</p>
 *
 * <p>One desktop per {@code UIDocument}, which is the display surface rather than a window
 * ({@code plan/shell-windowing.md}, Design B). Everything a window manager needs already existed at the
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
 * <p>Exactly {@code UIDocument.windowOverlayLayer}'s arrangement, and for the same reason: a layer added
 * with {@code addInternalChild} may live under a root that accepts no children, while the frames added
 * to <em>it</em> stay ordinary public children — so a window can still remove itself
 * ({@code removeChild} silently refuses an internal child, and returns a boolean nobody checks).</p>
 *
 * <p>That relies on an ordering the engine states as a trap: {@code markAsInternal()} <b>recurses</b>,
 * so children a container already had when it was made internal become internal too. The window layer
 * is added in the constructor — before {@code UIDocument} attaches this desktop — and every frame arrives
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
public class Desktop extends UIElement implements DataProvider {

    /** The cascade identity `ua/desktop.css` names. @see com.crystalgui.ui.dom.Name */
    public static final Name NAME = Name.of("desktop");

    /** The layer frames live on, and the work area they are bounded by. */
    public static final String WINDOW_LAYER_CLASS = "__windows__";

    private final WindowLayer windows = new WindowLayer();

    /**
     * How many windows the cascade has placed since it last wrapped — Win32's {@code CW_USEDEFAULT},
     * which offsets each successive window by a caption height and starts over when it walks off.
     */
    private int cascadeStep;

    /**
     * The one compositor on {@code document}, attaching it on first use.
     *
     * <p><b>The engine no longer owns this, and the inversion is the point.</b> On the old engine
     * {@code UIWindow.desktop()} built it, which meant the engine's own document class named a widget:
     * fine there, and the exact coupling the three-tree design removed. Here the compositor names the
     * document instead, so {@code ui.dom} stays free of it and the rule the invariant table states —
     * "the desktop is engine-owned and nothing else may build one" — is enforced by there being ONE
     * factory rather than by a comment.</p>
     *
     * <p><b>And there is no cached field, which retires a documented trap.</b> The old version kept the
     * desktop in a {@code UIWindow} field and put it back with {@code addInternalChild} after a suspend
     * — which re-declared it internal and RECURSED, marking every window that had arrived since as an
     * internal child, so {@code hide()} silently detached nothing and windows could not be closed. The
     * tree is the record now: a desktop is on screen exactly when it is a child of the document.</p>
     *
     * <p>It sits <em>over</em> the document's own content, which is the band model: the root's other
     * children are the desktop-content band, the desktop is the windows band above it, and the top
     * layer is above both by construction. While no window is open the desktop is zero-sized and
     * hit-tests nothing, so it cannot swallow clicks meant for the application underneath.</p>
     */
    public static Desktop of(UIDocument document) {
        Desktop existing = ifPresent(document);
        if (existing != null) return existing;
        Desktop desktop = new Desktop();
        document.append(desktop);
        BY_DOCUMENT.put(document, desktop);
        return desktop;
    }

    /**
     * <b>Which compositor belongs to which document, held so it survives a SUSPEND.</b>
     *
     * <p>Suspending takes the desktop out of the tree — that is what makes the freeze real — and
     * {@link #ifPresent} looked for it by walking the document's children. So while suspended a
     * document appeared to have no compositor at all, and {@link #of} answered by building a SECOND
     * one: a fresh, empty desktop, with every retained window still held by the first and reachable
     * from nothing. Nothing throws; a window opened in that state simply appears on a desktop that
     * is not the one about to be resumed.</p>
     *
     * <p>The map is here rather than a field on {@code UIDocument} for the standing reason the engine
     * may not name a compositor: the compositor names the document. Weak keys, so a document that
     * goes away takes its entry with it.</p>
     */
    private static final java.util.Map<UIDocument, Desktop> BY_DOCUMENT = new java.util.WeakHashMap<>();

    /**
     * The compositor <b>only if one is already on screen</b> — never the call that puts it there.
     *
     * <p>{@link #of} attaches on first use, which is right for opening a window and wrong for every
     * question <em>about</em> windows. A command's {@code enabledWhen} runs whenever a menu is drawn or
     * the palette is filtered, so routing one through the building accessor would grow a desktop on an
     * application that has never opened a window — and a desktop that is present but empty is precisely
     * what the zero-size rule exists to keep harmless.</p>
     */
    @Nullable
    public static Desktop ifPresent(@Nullable UIDocument document) {
        if (document == null) return null;
        for (UIElement child : document.children()) {
            if (child instanceof Desktop desktop) return desktop;
        }
        // ...and a SUSPENDED one, which is not a child of anything. Only a suspended desktop is
        // answered from the map: a desktop that was destroyed or replaced must not be resurrected by
        // it, and being attached is otherwise the honest test.
        Desktop remembered = BY_DOCUMENT.get(document);
        return remembered != null && remembered.isSuspended() ? remembered : null;
    }

    /**
     * Public because the tag registry needs a factory and a test needs to be able to make one, not
     * because an application should. Reach a document's compositor with {@link #of}.
     */
    public Desktop() {
        super(NAME);
        StyleGroup.defaultPipeline(getStyle().getLayoutGroup(),
                l -> l.flexDirection(FlexDirection.COLUMN));
        // THE CLASS IS THE WHOLE OF THE LAYER'S GEOMETRY. Without it `desktop .__windows__` matches
        // nothing, the work area sizes to content, and its children are all absolutely positioned --
        // so it measures 0x0 and every rule that reads the work area quietly stands down instead of
        // failing: no clamp, no cascade, windows written wherever they were asked to go.
        windows.addClass(WINDOW_LAYER_CLASS);
        append(windows);
        // AFTER the layer, so the strip is laid out below it. That order IS the work area: the taskbar is
        // laid out rather than overlaid, so what is left for windows needs no bar-shaped subtraction
        // anywhere -- maximise (W6) fills the layer, drags clamp at it, and W13's fullscreen hiding the
        // bar simply re-flows the layer to full height.
        taskbar = new Taskbar();
        append(taskbar);

        // PARKED HERE so it has a tree to be promoted OUT of -- the same idiom DragGhost and the taskbar's
        // hover preview both need, and for the same reason: an element must be somewhere before the top
        // layer can take it. It is display:none until a gesture opens it, so it costs a layout skip.
        switcher = new WindowSwitcher(this);
        append(switcher);

        // HIDDEN AND UNHITTABLE. A full-size element over the work area is this codebase's most-repeated
        // failure, and the rule that makes one safe is that it takes no box at all while it is not on
        // screen -- so it is display:none until a snap is being previewed. Unhittable regardless: it is
        // shown only DURING a drag, which has pointer capture anyway, and a hittable preview would be
        // one more thing for the drop to land on.
        snapPreview.addClass(SNAP_PREVIEW_CLASS);
        snapPreview.setHitTest(false);
        snapPreview.setDisplayed(false);

        // THE WIDGET THAT OWNS THE COMMANDS REGISTERS THEM, as DockArea does for DockCommands. Registering
        // from anywhere else is how a command ends up existing only once something unrelated has been
        // constructed -- registered but unreachable, or bound but pointing at nothing.
        DesktopCommands.register();
        WindowCommands.register();

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

        // AND A RIGHT-CLICK ON BARE DESKTOP OPENS THE DESKTOP MENU -- the only affordance left once
        // every window is minimised, since the taskbar's menu needs the strip and a system menu needs a
        // window. Attached to BOTH for the same reason the deactivate listeners above are: the window
        // layer covers the desktop entirely, so bare background is nearly always the LAYER's hit, and
        // the desktop itself is only what remains once the taskbar has shortened the layer.
        //
        // GUARDED ON THE TARGET BEING THE SURFACE ITSELF, because ContextMenu.attach subscribes the
        // BUBBLE phase: without this, a right-click on a window's bare content -- anything inside it with
        // no menu of its own to consume the press -- would bubble out here and open the DESKTOP's menu
        // on top of the window. A builder answering null is the seam for exactly that; attach returns
        // before it builds or consumes anything, so the press carries on as if nothing were attached.
        // ATTACHED TO THE DESKTOP AND NOT THE LAYER, though the layer is what a press on bare background
        // usually hits. `present` parents the menu at the attachment site, and WindowLayer refuses any
        // child that is not a WindowFrame -- so attaching there throws the moment somebody right-clicks.
        // The bubble phase carries the layer's press out to here anyway, which is why the guard names
        // both: this fires for a press on either surface and for nothing inside a window.
        ContextMenu.attach(this, CommandRegistry.global(),
                pressed -> pressed == this || pressed == windows
                        ? ContextMenu.of(MenuId.DESKTOP_CONTEXT) : null);
    }

    /**
     * Fills the root while any window <b>exists</b>, and takes up no space at all while none does.
     *
     * <p><b>IMPORTANT origin</b>, matching {@code UIDocument.windowOverlayLayer}: this is the compositor's
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
    /** The class the sheet sizes the compositor from. @see #syncPresence */
    /**
     * Whether the compositor is on the tree — true on a resume, false on a suspend.
     *
     * <p>A host with a window mounted needs to know, because a suspend detaches the whole subtree:
     * anything holding a frame has to stop driving it and pick it up again on the way back.</p>
     */
    public final Signal.Value<Boolean> onSuspendedChanged = new Signal.Value<>();

    private boolean suspended;
    @Nullable
    private UIDocument suspendedIn;
    @Nullable
    private ScreenOverlay screenOverlay;

    public static final String LIVE_CLASS = "__live__";

    private void syncPresence() {
        // A CLASS, where the old engine wrote the size at IMPORTANT. Both halves of the geometry are in
        // ua/desktop.css now -- the out-of-flow position on `desktop`, the full size on `desktop.__live__`
        // -- because the new engine may not write into the cascade at all, and because state a widget
        // flips from its own bookkeeping belongs on a class rather than in Java: a theme can then restyle
        // it, and the base rule is the SAFE state (no space) rather than the dangerous one.
        setClass(this, LIVE_CLASS, isLive());
    }

    private static void setClass(UIElement node, String className, boolean on) {
        if (on) node.addClass(className);
        else node.removeClass(className);
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
    private final WindowSwitcher switcher;

    @Nullable
    private WindowFrame activeWindow;

    /** The window the keyboard is talking to, or null when the desktop itself has the press. */
    @Nullable
    /**
     * Turns window open/close/minimise/maximise animations on or off, for the whole process.
     *
     * <p>Every desktop offers this — Windows' "Animate windows when minimizing and maximizing", macOS's
     * Reduce Motion — for motion sensitivity and for remote sessions where the frames cost bandwidth.
     * <b>Off means off, including the waiting</b>: a close takes effect on the same call rather than
     * three frames later, so nothing downstream has to know which mode it is in.</p>
     *
     * <p>Static rather than per-desktop because it is a user preference about motion, not a property of
     * one screen — and because a process has one user.</p>
     */
    public static void setAnimationsEnabled(boolean enabled) {
        WindowAnimator.setEnabled(enabled);
    }

    /** @see #setAnimationsEnabled */
    public static boolean animationsEnabled() {
        return WindowAnimator.isEnabled();
    }

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

        // THE BAND IS AN OFFSET ON THE SAME COUNTER, and that is the entire implementation of
        // always-on-top -- exactly what was predicted when the feature was refused for having no
        // consumer. `sortedChildren` then keeps paint order and hit-testing agreeing with no new
        // machinery, which is the invariant that must never be re-implemented.
        //
        // Asked of the ROOT of the owner group, not of each frame: an owner group moves as a unit, so a
        // pinned window's owned palette must ride into the band with it rather than being left below
        // every unpinned window on the desktop.
        int band = root.isPinned() ? PINNED_BAND : 0;
        root.setStackOrder(band + ++raiseCounter);
        for (WindowFrame owned : group) owned.setStackOrder(band + ++raiseCounter);
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
        activate(frame, programmatic, true);
    }

    /**
     * @param restoreFocus whether to put focus back where this window last had it. False for a press in
     *                     the window's content, which has already decided where focus goes -- see
     *                     {@code WindowFrame.installActivation}; true for everything else, which is
     *                     what activation has always meant.
     */
    public void activate(WindowFrame frame, boolean programmatic, boolean restoreFocus) {
        if (frame == null || frame.desktop() != this) return;
        // ACTIVATING A HIDDEN WINDOW RESTORES IT, which is what a taskbar entry does (W4) and what a
        // switcher does (W10) -- both of them "activate", and a minimised window has to come back for
        // that to mean anything. Restored as PERSISTED: it is coming out of retention, which is exactly
        // the distinction the flag carries.
        if (frame.state() == WindowState.HIDDEN) frame.show(true);
        // SEEN. Activation is the only event that means the user has looked at it, which is why the
        // flash is not on a timer -- one that gave up after a few seconds would be a notification you
        // could miss by looking away. @see WindowFrame#isDemandingAttention()
        frame.clearAttention();
        // THE SHOW-DESKTOP MEMORY IS DROPPED HERE. Once a window has been used, "put it back how it was"
        // no longer describes anything the user would recognise, and a second Win+D should mean a fresh
        // minimise-all. Windows drops its own on exactly this event. @see #toggleShowDesktop()
        //
        // Cleared rather than checked for membership: restoring a window FROM the show-desktop set also
        // counts, because at that point the set no longer describes the screen either.
        minimizedByShowDesktop.clear();
        raise(frame);
        if (activeWindow != frame) {
            if (activeWindow != null) setGroupActive(activeWindow, false);
            activeWindow = frame;
            setGroupActive(frame, true);
        }
        // AFTER the assignment above, not before it. This emits, and what listens re-renders the whole
        // model -- so announcing while `activeWindow` still points at the previous window highlights the
        // wrong entry until the next unrelated change happens to correct it.
        registry.activated(frame);
        if (restoreFocus) frame.restoreFocus(programmatic);
    }

    /**
     * Marks a window active, <b>and the window it belongs to with it</b>.
     *
     * <p>A tool window is part of another window rather than a window of its own — that is the whole of
     * what {@link WindowFrame#isToolWindow()} means — so focusing one must not take the active look away
     * from the thing it came out of. Click into a floating Run panel and the editor's caption went grey
     * with the panel plainly in front of it, which reads as having lost the application.</p>
     *
     * <p>Both desktops this borrows from agree: Windows leaves an owner's title bar active while its
     * {@code WS_EX_TOOLWINDOW} palette has focus, and macOS does not deactivate a document window when a
     * panel takes the keyboard. {@link WindowFrame#taskbarSubject()} is the same walk the strip uses, so
     * the caption and the entry cannot disagree about which application is in front.</p>
     *
     * <p>Deactivating runs the same walk, which is what keeps it symmetric: the group goes dark together
     * when something outside it is activated, and the order in {@link #activate} — old group off, then new
     * group on — is what lets a window and its own panel hand over without a flicker.</p>
     */
    private void setGroupActive(WindowFrame frame, boolean active) {
        frame.setActive(active);
        WindowFrame subject = frame.taskbarSubject();
        if (subject != frame) subject.setActive(active);
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
        setGroupActive(activeWindow, false);
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
    public void activateTopmost() {
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
    /**
     * <p>{@code onWindowChanged(previous, current)} has no counterpart — the node tree reports connect
     * and disconnect separately — and the split is faithful, because the old hook did two unrelated
     * things and each belongs to one half.</p>
     */
    @Override
    protected void disconnected() {
        super.disconnected();
        // OFF SCREEN IS THE MOMENT TO WRITE. Suspending a compositor is what a host's screen closing
        // does, and it deliberately touches no window's state -- so what is recorded here is exactly what
        // was on the desktop.
        savePersistedState();
        subscriptions.disconnectAll();
        UIDocument previous = lastDocument;
        lastDocument = null;
        if (previous != null) previous.removeDataProvider(this);
    }

    @Override
    protected void connected() {
        super.connected();
        UIDocument current = document();
        if (current == null) return;
        lastDocument = current;
        // ARMED ON THE WAY IN: a desktop attached after persistTo -- which is every host, since
        // persistTo is called on a fresh one -- would otherwise have no window to register its
        // one-shot restore pass with.
        armRestorePass();
        subscriptions.disconnectAll();
        // THE WINDOW-LEVEL ANSWER TO "which window is this about", and the LAST resort by construction:
        // DataContext walks the element chain first and only asks the window's providers when nothing
        // answered. So a command invoked from inside a frame gets that frame, one invoked from a taskbar
        // entry gets the entry's frame, and one invoked from the palette with nothing focused gets the
        // active window -- which is the only sensible answer there and the reason this exists.
        current.addDataProvider(this);
        subscriptions.add(current.focus().onDidChangeFocus.connect(this::focusMoved));
    }

    /**
     * The document this was last connected to.
     *
     * <p><b>Remembered because {@code disconnected()} runs when there is no longer one to ask.</b> The
     * old two-argument hook was handed the previous window; here the node is already off the tree by
     * the time it is told, and a provider left registered on a document the desktop has left goes on
     * answering "the active window" for a compositor that is not on screen. Same shape as the standing
     * rule that a window's geometry must be captured BEFORE it leaves the tree.</p>
     */
    @Nullable
    private UIDocument lastDocument;

    private final ConnectionGroup subscriptions = new ConnectionGroup();

    /** @see #connected — the window-level fallback for {@link WindowFrame#WINDOW_FRAME}. */
    @Override
    @Nullable
    public Object getData(DataKey<?> key) {
        return key == WindowFrame.WINDOW_FRAME ? activeWindow : null;
    }

    private void focusMoved(@Nullable UIElement focused) {
        for (UIElement walk = focused; walk != null; walk = walk.parentElement()) {
            if (walk instanceof WindowFrame && ((WindowFrame) walk).desktop() == this) {
                // ONLY A WINDOW THAT IS ON SCREEN. Focus landing inside a HIDDEN one is never the user
                // working in it: a hidden window is DETACHED, so it cannot be clicked, cannot be tabbed
                // into, and matches no selector. What reaches here instead is stale -- input state still
                // naming an element in the subtree that has just left, or a promoted popover whose DOM
                // parent is still the frame it was opened from.
                //
                // Activating on that UN-HIDES the window the user has just put away, which is what made
                // minimise, hide and close all read as "the window will not close". Restoring a window IS
                // activate's job, but through the routes that MEAN it -- a taskbar entry, the switcher, a
                // command -- each of which calls activate directly. This one is incidental by
                // construction, so it is the one that must not.
                if (((WindowFrame) walk).state() != WindowState.VISIBLE) return;
                // MINIMISING IS NOT WORKING IN A WINDOW. Click-focus lands on the minimise BUTTON, so
                // this route brought a background window forward on the way to putting it away -- and it
                // is the route that does it, not the frame's own press listener: focus moves before any
                // listener runs, so a guard there is both too late and unable to see which control was
                // pressed (a listener on a shadow host is retargeted to the host).
                // @see WindowFrame#isMinimizeControl
                if (((WindowFrame) walk).isMinimizeControl(focused)) return;
                // FOLLOWING FOCUS, NEVER OVERRULING IT. Focus has just moved, so something has already
                // decided where it goes; activation here brings the window forward and must not then
                // consult the window's focus memory on top of that decision. It did, and the "frame
                // itself does not count as focused-inside" exception in restoreFocus turned every press on
                // bare content -- which click-focus lands on the frame -- into a restore: a field you had
                // just clicked away from took focus straight back. The one route that legitimately
                // restores is a press on CHROME, and the frame's own press listener does that itself.
                activate((WindowFrame) walk, false, false);
                return;
            }
        }
        // NOT a deactivate. Focus leaving every window is the ordinary middle of a click -- emitMouseDown
        // blurs, announces null, and only then focuses what was pressed -- so treating it as "the desktop
        // was clicked" would drop the active window on every press and take it back a moment later. The
        // press on bare desktop is what deactivates, and it says so itself.
    }

    /** A desktop owns its chrome; windows go through {@link #addWindow}. */

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
        return addWindow(frame, true);
    }

    /**
     * Puts a window on the desktop, optionally <b>without taking focus</b> — the no-steal rule (W12).
     *
     * <h3>Every OS converged on the same answer, and it is not "don't open the window"</h3>
     *
     * <p>A window opened by something other than a user gesture — a server pushing a UI, a background
     * job finishing — must not take the keyboard out from under whatever is being typed. Windows has a
     * foreground lock plus {@code FlashWindowEx}, X11 has the urgency hint, macOS bounces the dock icon:
     * <em>appear, take no focus, and ask for attention instead</em>. So the window is registered,
     * attached and animated exactly as any other, and only the raise and the activation are skipped.</p>
     *
     * <p>It goes in at the <b>back</b> of the MRU as well, which {@code WindowRegistry.opened} already
     * arranges and states the reason for: a window that never took focus must not become the switcher's
     * first offer, or the attention flash becomes a focus steal with one keystroke of delay.</p>
     *
     * <p>Asking for attention is part of appearing in the background rather than a second call the
     * caller has to remember. A window that appears with no focus and no announcement is a window nobody
     * knows opened, which is worse than either alternative — and clearing it is one line
     * ({@link WindowFrame#clearAttention()}) for the rare caller that genuinely wants silence.</p>
     */
    public <T extends WindowFrame> T addWindow(T frame, boolean activate) {
        if (frame.state() == WindowState.DESTROYED) {
            throw new IllegalStateException("a destroyed window cannot be reopened: " + frame.getTitle());
        }
        frame.setOwner(this);
        registry.opened(frame);
        windows.append(frame);
        // BEFORE the animation and the activation below: a window restored from a record should open AT
        // the size and place it is meant to be, not fly in at a default and jump. @see #persistTo
        applyPersistedGeometry(frame);
        // A FIRST OPEN DOES NOT GO THROUGH show(), so the entry animation has to be played here as well.
        // The two paths are genuinely different -- this one registers the window and hands it an owner,
        // show() puts a hidden one back -- and the animation is the only thing they share.
        frame.playOpenAnimation();
        if (!activate) {
            // NO RAISE EITHER. A background window that jumped to the front of the stack would be a
            // focus steal missing only the focus -- it would cover the window being typed in. Its stack
            // order is left where a fresh frame starts, which puts it behind everything raised since.
            frame.requestAttention();
            return frame;
        }
        // PROGRAMMATIC, so focus rings: nobody pointed at this window, so a keyboard user needs to be
        // told where focus went. The ring lands on whatever inside it takes focus -- never on the frame,
        // which ua/core.css exempts along with every other pane-sized widget.
        activate(frame, true);
        return frame;
    }

    /** Puts a hidden window back on the layer. Driven by {@link WindowFrame#show}, which owns the state. */
    public void reattach(WindowFrame frame) {
        if (frame.parent() == null) windows.append(frame);
        raise(frame);
        registry.changed();
    }

    /** The strip along the bottom — the registry, rendered. @see Taskbar */
    public Taskbar taskbar() {
        return taskbar;
    }

    /**
     * The strip follows whether <b>any</b> window is fullscreen — W13b.
     *
     * <p>Asked of the whole set rather than tracked as one window, because two can be fullscreen at once
     * (one behind the other) and the bar must not come back when the front one exits. A field holding
     * "the fullscreen window" would need every exit to know whether it was the one being remembered,
     * which is a bookkeeping question the registry can simply be asked.</p>
     *
     * <p>Nothing else has to move: a frame is placed against the window layer and the layer's box IS the
     * work area, so hiding the bar re-flows the layer to full height and every maximised window follows
     * it. That is the same property that makes maximise need no taskbar special case.</p>
     */
    /**
     * The modifier that turns a press anywhere inside a window into a move — W13b's Alt-drag.
     *
     * <h3>Why this is a setting and not a keymap binding</h3>
     *
     * <p>{@code plan/shell-windowing.md} asks for the chord to be keymap-resolved, "never a hardcoded Alt",
     * and the reason is sound: Alt is contested territory here — {@code TextField} refuses Alt chords and
     * {@code MenuBarView} claims Alt+letter mnemonics, and both of those were paid for.</p>
     *
     * <p>It cannot literally be a binding, and that is a fact about the keymap rather than a shortcut:
     * a {@link com.crystalgui.ui.input.keymap.KeyStroke} is a <em>key</em> plus modifiers, and
     * {@code parse("Alt")} reads "Alt" as the key name and fails. There is no way to spell a
     * modifier-only binding, and inventing one for a single gesture would mean teaching the resolver,
     * the palette and the accelerator renderer about a stroke that can never be pressed.</p>
     *
     * <p>So this is the substance of the requirement without the letter: <b>one place</b>, changeable at
     * runtime, and nothing in {@code WindowFrame} naming a modifier. GNOME keeps the same thing as a
     * setting for the same reason — {@code org.gnome.desktop.wm.preferences.mouse-button-modifier}.</p>
     */
    public int moveModifier() {
        return moveModifier;
    }

    /** @see #moveModifier() */
    public Desktop setMoveModifier(int mask) {
        this.moveModifier = mask;
        return this;
    }

    /** GNOME's own default, and the one every Linux WM ships. */
    private int moveModifier = CgModifiers.ALT;

    /**
     * Shows — or re-aims — the rect a window would land in, <b>animating between shapes</b>.
     *
     * <h3>It morphs from the WINDOW, which is what makes it a proposal rather than a label</h3>
     *
     * <p>A preview that simply appeared at its destination states the answer; one that grows out of the
     * window being dragged states the <em>change</em>, which is the question the user is actually asking.
     * It also means both ends of every animation are a real place on screen, so nothing has to fade or
     * pop: the first show morphs from the window's own rect, a change of zone morphs from wherever the
     * preview currently is, and an animated hide morphs back to the window.</p>
     *
     * <p>Through {@link WindowGeometryAnimation} — the same driver a maximise uses — and not a CSS
     * transition, for the reason the whole compositor follows: an animation is a <b>timeline</b>, and
     * the cascade is for rest states. Layout rather than a transform costs nothing here that it costs a
     * window, because this element has no content to reflow. It is an empty box.</p>
     *
     * <p><b>Called every frame of a drag while the pointer stays in one zone</b>, so it must be a no-op
     * for an unchanged zone. Restarting the timeline per frame would ease from the live rect toward the
     * same target for ever — approaching it and never arriving, which reads as a sluggish preview
     * rather than as an animation being re-armed sixty times a second.</p>
     */
    public void showSnapPreview(SnapZones.Zone zone, WindowFrame source) {
        Box box = windows.box();
        if (box == null || box.width() <= 0f || box.height() <= 0f) return;
        if (snapShowing && zone == snapZone) return;

        // AT THE GROUP'S LIVE CUTS, not at halves: the preview promises where the window will land,
        // and snapping into an occupied zone gives it that zone's CURRENT size.
        float[] to = SnapZones.rectFor(zone, box.width(), box.height(), splitX, splitY);
        float[] from = snapShowing ? livePreviewRect() : frameRect(source);

        snapSource = source;
        snapZone = zone;
        snapShowing = true;
        windows.hostDecoration(snapPreview);
        // BEING OUT OF FLOW IS THE SHEET'S, the RECT is inline. The split is what the animation needs:
        // out-of-flow is a structural fact about this element, while its geometry is what a timeline
        // writes -- and writing the rect at a higher origin than the timeline, as an earlier version
        // did, puts a static value permanently above it so nothing ever moves. On this engine the
        // structural half cannot be written from Java at all, so it is `desktop .__snap-preview__`.
        stackPreviewUnder(source);
        snapPreview.setDisplayed(true);
        animateSnapPreview(from, to, null);
    }

    /**
     * Puts the preview <b>above every other window and below the one being dragged</b>.
     *
     * <h3>A decoration with no z is a decoration at z = 0, which is behind everything</h3>
     *
     * <p>{@link #raise} gives every window a {@code z-index} from a counter that only ever goes up, so
     * a window that has been clicked even once sits above a preview that was never given one. That was
     * survivable while the preview was larger than the windows it was under — you saw it around them and
     * it read as working. Put a window in the very half being previewed and the preview is <b>entirely
     * behind it</b>: no highlight, no feedback, and the gesture reads as refusing an occupied zone.
     * It is not refusing anything; the snap lands correctly, which is what made the report so specific.
     * </p>
     *
     * <p><b>Below the dragged window, not above it.</b> Above is one line and looks fine until you watch
     * it: the wash is drawn over the window in your hand, so the thing you are holding greys out and
     * reads as disabled at the exact moment it is being acted on. Windows draws its preview behind the
     * dragged window for the same reason.</p>
     *
     * <p>The preview takes the slot the dragged window currently holds and the window moves up one, so
     * the two can never tie — {@code raiseCounter} hands out each value once, so nothing else is left at
     * the value being vacated. (If that raise happens to trip {@code renormaliseStack}, the preview ends
     * up above everything for that one drag, which is the mild version of the artefact and is not worth
     * a second mechanism to avoid.)</p>
     */
    private void stackPreviewUnder(WindowFrame source) {
        int previewZ = raiseCounter;
        raise(source);
        // INLINE, not IMPORTANT: a stacking order computed per gesture is exactly the "geometry a
        // caller wrote" slot, and the engine may no longer write above an author's sheet anyway.
        StyleGroup.inlinePipeline(snapPreview.getStyle().getGeneralGroup(),
                g -> g.zIndex(previewZ));
    }

    /**
     * Takes the snap preview off screen, <b>contracting back into the window</b> it belongs to.
     *
     * <p>For a drag that wandered off the edge, or one abandoned with Escape — the cases where nothing
     * replaces the preview, so it has to be seen to go somewhere. Safe to call when it was never shown.
     * </p>
     */
    public void hideSnapPreview() {
        if (!snapShowing) return;
        snapShowing = false;
        snapZone = null;
        // NEVER null while showing: snapShowing is only set by showSnapPreview, which requires a source.
        animateSnapPreview(livePreviewRect(), frameRect(snapSource), this::hideSnapPreviewNow);
    }

    /**
     * Takes it off screen at once — for a snap that was <b>accepted</b>.
     *
     * <p>Not symmetrical with {@link #hideSnapPreview}, and deliberately: on release the window itself
     * animates into the very rect the preview is occupying, so contracting the preview back to where the
     * window <em>used</em> to be would play the gesture backwards beside the thing doing it forwards.
     * The window is the animation once there is a window to watch.</p>
     */
    public void hideSnapPreviewNow() {
        cancelSnapMotion();
        snapShowing = false;
        snapZone = null;
        snapSource = null;
        snapPreview.setDisplayed(false);
    }

    /** Replaces whatever was running, and settles synchronously when animations are off. */
    private void animateSnapPreview(@Nullable float[] from, @Nullable float[] to,
                                    @Nullable Runnable then) {
        cancelSnapMotion();
        UIDocument window = document();
        // NO RECT MEANS NO BOX, WHICH MEANS NOTHING TO ANIMATE BETWEEN. `Box` is nullable where the old
        // runtime cache always answered, so a source or a preview that is not laid out -- a window
        // opened and dragged inside one frame -- now has no measurable rect. Settling on `to` is the
        // same outcome the animations-off arm already gives; with no destination there is nothing to
        // settle to either, and the caller's continuation still runs so a hide cannot get stuck.
        if (to == null) {
            if (then != null) then.run();
            return;
        }
        if (from == null) {
            applySnapRect(to);
            if (then != null) then.run();
            return;
        }
        // ANIMATIONS OFF MUST TURN OFF THE WAITING TOO, or `then` lands a frame late and a hide is
        // asynchronous in a mode where nothing is animating. Same contract WindowAnimator states.
        if (!WindowAnimator.isEnabled() || window == null) {
            applySnapRect(to);
            if (then != null) then.run();
            return;
        }
        WindowGeometryAnimation motion = new WindowGeometryAnimation(
                snapPreview, () -> snapPreview.parent() != null,
                from[0], from[1], from[2], from[3],
                to[0], to[1], to[2], to[3],
                true, true, SNAP_PREVIEW_NANOS, SNAP_PREVIEW_EASING,
                () -> {
                    snapMotion = null;
                    if (then != null) then.run();
                });
        snapMotion = motion;
        // OWNED BY THE THING IT MOVES. The old registration was one-way and stopped only by returning
        // false, which is why `snapPreview.parent() != null` is passed in as a liveness test at all:
        // the animation had to notice its own subject had gone. Ownership answers it structurally --
        // detaching the preview drops the hook -- and the predicate stays because it is also what
        // ends the motion when the snap is COMMITTED, which detaches nothing.
        window.animation().every(snapPreview, motion);
    }

    private void cancelSnapMotion() {
        if (snapMotion != null) snapMotion.cancel();
        snapMotion = null;
    }

    /** INLINE, which is the slot {@link WindowGeometryAnimation} writes — so the two never disagree. */
    private void applySnapRect(float[] rect) {
        StyleGroup.inlinePipeline(snapPreview.getStyle().getLayoutGroup(),
                l -> l.left(rect[0]).top(rect[1]).width(rect[2]).height(rect[3]));
    }

    /** Where the preview is RIGHT NOW, in the work area's own coordinates. */
    @Nullable
    private float[] livePreviewRect() {
        // NO SUBTRACTION. `Box.x()` is the offset from the HOST's border-box origin and the preview is
        // a child of the work area, so its own x IS the work-area inset -- where the old accessor
        // accumulated through every ancestor and had to have the area's origin taken back off. Keeping
        // the subtraction here would count the work area's own offset twice, and be wrong by whatever
        // the taskbar and the chrome above it happen to occupy. @see plan/engine-port.md 6.4
        Box box = snapPreview.box();
        return box == null ? null
                : new float[] {box.x(), box.y(), box.width(), box.height()};
    }

    /** A frame's rect in the same space — {@code left()}/{@code top()} are already work-area insets. */
    @Nullable
    private float[] frameRect(WindowFrame frame) {
        Box box = frame.box();
        return box == null ? null
                : new float[] {frame.left(), frame.top(), box.width(), box.height()};
    }

    // ── HUD: pinned windows over the running game ───────────────────────────────────────────────

    /** What {@link #enterHudMode} put away, so {@link #exitHudMode} can put it back. */
    private final List<WindowFrame> hiddenForHud = new ArrayList<>();
    private boolean hudMode;

    /**
     * Puts the desktop on the HUD: every unpinned window goes away, pinned ones keep running.
     *
     * <p>What a host calls instead of taking the compositor off screen when something is pinned —
     * Discord's and Steam's in-game overlays are the precedent, and live debugging is the use case: pin
     * the Run console, close the screen, play, and watch it stream.</p>
     *
     * <p><b>Unpinned windows are HIDDEN, not merely unpainted</b>, which is what makes the freeze
     * contract hold unchanged: hiding detaches, so nothing matches a selector, nothing lays out, an
     * owned per-frame hook is dropped and the input service forgets every node in the subtree. It is
     * also why the set has to be REMEMBERED rather than inferred on the way out — a window the user had
     * already minimised must stay minimised when the screen comes back.</p>
     *
     * <p><b>Visible stays live.</b> The freeze contract keys on <em>hidden</em>, not on the screen being
     * closed, so a pinned window keeps its hooks, its transitions and its connections. Watching live
     * data is the entire point; a frozen HUD would be a screenshot.</p>
     *
     * <p><b>On the compositor rather than on the document, which is where the old engine had it.</b>
     * Every line of it is about this desktop's own windows, and {@code ui.dom} may not name one — the
     * same inversion {@link #of} makes for the compositor itself.</p>
     */
    public void enterHudMode() {
        if (hudMode) return;
        hudMode = true;
        hiddenForHud.clear();
        for (WindowFrame frame : new ArrayList<>(registry.windows())) {
            if (frame.isPinned() || frame.state() != WindowState.VISIBLE) continue;
            hiddenForHud.add(frame);
            frame.hide();
        }
        for (WindowFrame frame : registry.windows()) {
            if (frame.isPinned()) frame.addClass(WindowFrame.HUD_CLASS);
        }
    }

    /** Takes the desktop off the HUD, restoring exactly what {@link #enterHudMode} put away. */
    public void exitHudMode() {
        if (!hudMode) return;
        hudMode = false;
        for (WindowFrame frame : registry.windows()) {
            frame.removeClass(WindowFrame.HUD_CLASS);
        }
        // persisted: a restore of a window that WAS on screen, never a first show.
        for (WindowFrame frame : hiddenForHud) frame.show(true);
        hiddenForHud.clear();
    }

    /** @see #enterHudMode */
    public boolean isHudMode() {
        return hudMode;
    }

    // ── Joint resize — the tiled group ──────────────────────────────────────────────────────────

    /**
     * Snaps {@code frame} into {@code zone}, at the group's <b>current</b> divider positions.
     *
     * <p>The one entry point, so the split state and the rect can never disagree. A caller computing
     * the rect itself would tile against halves and undo whatever the group had been dragged to — which
     * is exactly what {@code commitSnap} used to do.</p>
     */
    public void snapFrameTo(WindowFrame frame, SnapZones.Zone zone) {
        Box area = windows.box();
        if (frame == null || zone == null || area == null
                || area.width() <= 0f || area.height() <= 0f) return;

        // A FRESH GROUP STARTS AT HALVES. The divider belongs to the GROUP and must not outlive one:
        // closing a pair that had been dragged to 3:1 and then snapping a single window left would
        // otherwise hand it that ratio, with nothing left on screen to explain where it came from.
        if (!anySnappedBesides(frame)) {
            splitX = SnapZones.CENTRE_SPLIT;
            splitY = SnapZones.CENTRE_SPLIT;
        }
        float[] rect = SnapZones.rectFor(zone, area.width(), area.height(), splitX, splitY);
        frame.snapTo(zone, rect[0], rect[1], rect[2], rect[3]);
    }

    /**
     * Moves a shared divider and re-tiles everything it separates — Windows' {@code JointResize}.
     *
     * <h3>The state is the CUT, not the pair</h3>
     *
     * <p>Two windows sharing an edge are {@code n} and {@code 1 − n} of one axis, so the group keeps the
     * fraction and every member's rect is derived from it. That is what makes them unable to drift
     * apart, and it is why a four-window layout needs no extra machinery: the vertical cut is shared by
     * both rows, so dragging it between the top pair moves the bottom pair too and the grid stays a
     * grid. Windows 11 describes exactly that — <i>"the rest of the windows will be adapted to maintain
     * the design"</i> — where Windows 10 could pair two windows and no more.</p>
     *
     * <p><b>One grid, not a cut per row.</b> Independent cuts per row would be more general and are not
     * what a tiled desktop means: the moment the two rows disagree, the layout is no longer a grid and
     * the corner where four windows meet stops being one place. Dragging that corner here is a single
     * gesture moving both cuts, which falls out of a corner handle reporting both axes.</p>
     *
     * <p>Applied INSTANTLY, never through {@link WindowFrame#snapTo}: this runs once per frame of a
     * hand-driven drag, and a 250ms ease per frame would leave every window trailing the pointer.</p>
     */
    public void jointResize(WindowFrame frame, int handleDx, int handleDy, float width, float height) {
        SnapZones.Zone zone = frame == null ? null : frame.snappedZone();
        Box area = windows.box();
        if (zone == null || area == null || area.width() <= 0f || area.height() <= 0f) return;

        boolean moved = false;
        if (zone.movesVerticalDivider(handleDx)) {
            splitX = SnapZones.splitFor(zone.xSide, frame.left(), width, area.width());
            moved = true;
        }
        if (zone.movesHorizontalDivider(handleDy)) {
            splitY = SnapZones.splitFor(zone.ySide, frame.top(), height, area.height());
            moved = true;
        }
        // AN OUTER EDGE MOVES NOTHING ELSE. Dragging the left edge of a left-snapped window resizes one
        // window; it is not repartitioning the screen, and treating it as a divider would make the far
        // side of the desktop jump whenever somebody pulled a window off its own border.
        if (moved) applySnapLayout();
    }

    /**
     * Re-tiles every snapped window from the current cuts.
     *
     * <p>Includes the window being dragged, which is what makes {@link SnapZones#MIN_SPLIT} hold: the
     * resizer has already written an unclamped size, and this writes the clamped one into the same
     * INLINE slot afterwards.</p>
     */
    private void applySnapLayout() {
        Box area = windows.box();
        if (area == null || area.width() <= 0f || area.height() <= 0f) return;
        for (WindowFrame frame : registry.windows()) {
            SnapZones.Zone zone = frame.snappedZone();
            if (zone == null || frame.state() != WindowState.VISIBLE) continue;
            float[] rect = SnapZones.rectFor(zone, area.width(), area.height(), splitX, splitY);
            frame.resizeTo(rect[2], rect[3]);
            frame.moveTo(rect[0], rect[1]);
        }
    }

    /** Whether any window other than {@code except} is currently tiled. */
    private boolean anySnappedBesides(WindowFrame except) {
        for (WindowFrame frame : registry.windows()) {
            if (frame != except && frame.snappedZone() != null
                    && frame.state() == WindowState.VISIBLE) {
                return true;
            }
        }
        return false;
    }

    /** Where the work area is cut, as a fraction. @see #jointResize */
    private float splitX = SnapZones.CENTRE_SPLIT;
    private float splitY = SnapZones.CENTRE_SPLIT;

    /** @see #showSnapPreview */
    public static final String SNAP_PREVIEW_CLASS = "__snap-preview__";

    /**
     * Short, because this is feedback and not a window.
     *
     * <p>The window timings do not transfer: a minimise is 400ms because the whole information content
     * of a minimise is <em>where the window went</em>, and it has a screen to cross. A preview is
     * answering a question the hand is still asking, so it has to keep up with the hand.</p>
     */
    private static final long SNAP_PREVIEW_NANOS = 150L * 1_000_000L;

    /**
     * {@code OUT_QUAD}, by the rule that what makes individual frames visible is <b>peak velocity</b>.
     *
     * <p>A preview crossing between halves travels a large part of the work area, and {@code OUT_EXPO}
     * opens at nearly seven times its average speed — a quarter of the journey in the first frame. Expo
     * is kept for things that only scale within their own box.</p>
     */
    private static final Easing SNAP_PREVIEW_EASING = ProgressFunctions.Premade.OUT_QUAD;

    /**
     * The translucent rectangle showing where a snap would land — W13b.
     *
     * <p>Built once and hidden, never per drag, so it costs a display-skip when nothing is being
     * dragged. Its Z is assigned per show: see {@link #stackPreviewUnder}.</p>
     */
    private final UIElement snapPreview = new UIElement();

    /** Whether the preview is on screen or on its way off. @see #showSnapPreview */
    private boolean snapShowing;

    /** The zone currently being previewed — the guard against re-arming the timeline every frame. */
    @Nullable
    private SnapZones.Zone snapZone;

    /** The window the preview morphs out of and back into. */
    @Nullable
    private WindowFrame snapSource;

    @Nullable
    private WindowMotion snapMotion;

    /**
     * Minimise everything, or put back exactly what was minimised — Windows' {@code Win+D}, W13c.
     *
     * <h3>A toggle with a memory, and the memory is what makes it a toggle</h3>
     *
     * <p>"Restore everything" would be wrong: a desktop where three windows were already minimised
     * before the gesture would come back with three windows nobody asked for. So it puts back
     * <em>exactly the set it took down</em> — which is also the only definition under which pressing it
     * twice is a no-op.</p>
     *
     * <p><b>And it forgets the moment anything is activated in between.</b> Once the user has gone and
     * used a window, "put it back how it was" no longer describes anything they would recognise, and a
     * second press should mean a fresh minimise-all rather than resurrecting a set from before whatever
     * they just did. Windows drops its own memory on exactly the same event.</p>
     */
    public void toggleShowDesktop() {
        if (!minimizedByShowDesktop.isEmpty()) {
            List<WindowFrame> putBack = new ArrayList<>(minimizedByShowDesktop);
            minimizedByShowDesktop.clear();
            for (WindowFrame frame : putBack) {
                if (frame.state() == WindowState.HIDDEN) frame.show(true);
            }
            return;
        }
        for (WindowFrame frame : registry.windows()) {
            // TOOL WINDOWS RIDE WITH THEIR OWNER and are not taken down separately -- a WINDOWED one
            // hides when its owner does, so minimising it here would put it in the memory twice and
            // bring it back on its own. @see WindowFrame#isToolWindow()
            if (frame.isToolWindow() || frame.state() != WindowState.VISIBLE) continue;
            minimizedByShowDesktop.add(frame);
            frame.minimize();
        }
    }

    /** Whether the last thing this desktop did was a show-desktop that has not been undone. */
    public boolean isShowingDesktop() {
        return !minimizedByShowDesktop.isEmpty();
    }

    /** @see #toggleShowDesktop() */
    private final List<WindowFrame> minimizedByShowDesktop = new ArrayList<>();

    /** Keyboard Move/Size — W13c. @see WindowKeyboardMove */
    public WindowKeyboardMove keyboardMove() {
        return keyboardMove;
    }

    private final WindowKeyboardMove keyboardMove = new WindowKeyboardMove();

    public void fullscreenChanged() {
        boolean anyFullscreen = false;
        for (WindowFrame frame : registry.windows()) {
            if (frame.isFullscreen() && frame.state() == WindowState.VISIBLE) {
                anyFullscreen = true;
                break;
            }
        }
        taskbar.setBarVisible(!anyFullscreen);
    }

    /** The MRU switcher. @see WindowSwitcher */
    public WindowSwitcher switcher() {
        return switcher;
    }

    // ── Persistence — CrystalOS W12 ─────────────────────────────────────────────────────────────

    @Nullable
    private DesktopSession persistence;
    @Nullable
    private String persistenceId;

    /** Recorded placements not yet claimed by a window, by key. @see #persistTo */
    private final Map<String, DesktopSession.Placement> pendingPlacements = new HashMap<>();
    private List<String> pendingMru = List.of();
    private boolean restorePassArmed;

    /**
     * Remembers this desktop's arrangement, and puts back the one it finds — CrystalOS <b>W12</b>.
     *
     * <h3>A platform says WHERE, and nothing else</h3>
     *
     * <p>The compositor is engine-owned, and so is everything about it that survives a restart. A host
     * supplies a {@link ConfigStorage} and an id — where the record lives and which desktop it is — and
     * gets the rest for nothing. The alternative was tried and is the reason this note exists: the same
     * read-apply-write orchestration written once in the Minecraft screen and once in the harness scene,
     * which is two copies of a policy that has to agree, in two places nobody reads together.</p>
     *
     * <h3>Geometry is APPLIED TO WINDOWS AS THEY OPEN, never used to build them</h3>
     *
     * <p>The obvious design hands core a factory — key in, window out — and it cannot work: only the
     * application knows what its windows contain, so the factory is a second thing every host must write,
     * and a host that forgets one silently loses that window. Inverting it removes the question. A host
     * opens whatever it opens, however it likes; a window that carries a {@link WindowFrame#key()} this
     * record names is placed where it was, and one it does not is left alone. Nothing is constructed
     * here, so nothing needs to be known here.</p>
     *
     * <p>The cost is honest and worth stating: a window the host does <em>not</em> reopen does not come
     * back. Core cannot invent it, and a record that claimed otherwise would be describing windows that
     * cannot exist.</p>
     */
    public Desktop persistTo(ConfigStorage storage, String id) {
        persistence = new DesktopSession(this, storage);
        persistenceId = id;
        pendingPlacements.clear();
        for (DesktopSession.Placement placement : persistence.read(id)) {
            pendingPlacements.put(placement.key(), placement);
        }
        pendingMru = persistence.readMruOrder(id);
        // Windows already open when a host installs this are placed too -- a host that opens its editor
        // and then asks for persistence is doing nothing wrong.
        for (WindowFrame frame : registry.windows()) applyPersistedGeometry(frame);
        armRestorePass();
        return this;
    }

    /**
     * Writes the arrangement now.
     *
     * <p>Called automatically when the desktop leaves the tree, which is what suspending a compositor
     * does and therefore covers a host whose screen closes. A host that tears down some other way — the
     * harness disposes its scene without ever detaching — calls this itself.</p>
     */
    public void savePersistedState() {
        if (persistence != null && persistenceId != null) persistence.save(persistenceId);
    }

    private void applyPersistedGeometry(WindowFrame frame) {
        String key = frame.key();
        // A TOOL WINDOW IS PLACED BY WHATEVER OWNS IT, per project -- and this record is per host. It is
        // no longer written here, but a record from before that was, so this has to refuse to apply one
        // rather than merely stop producing them. @see DesktopSession#isPersistable
        if (frame.isToolWindow()) return;
        if (key == null || pendingPlacements.isEmpty()) return;
        DesktopSession.Placement placement = pendingPlacements.remove(key);
        if (placement == null) return;
        frame.resizeTo(placement.width(), placement.height());
        frame.moveTo(placement.left(), placement.top());
        if (placement.maximized()) {
            frame.maximize();
            // AFTER the maximise: maximising captures whatever the window currently is as the rect to go
            // back to, and on a window opened this frame that is the box from before layout ran.
            frame.setRestoreRect(placement.left(), placement.top(),
                    placement.width(), placement.height());
        }
        if (placement.hidden()) hideAfterRestore.add(frame);
    }

    private final List<WindowFrame> hideAfterRestore = new ArrayList<>();

    /**
     * Replays activation and minimisation once the host has finished opening windows.
     *
     * <p>Deferred by exactly one frame rather than done per window, because both are statements about the
     * SET: the front window is whichever was activated last, and hiding one during {@code addWindow} would
     * fight the open animation and the activation that follows it. A host opens its windows in one go
     * during setup, so the next frame is when the set is complete.</p>
     */
    private void armRestorePass() {
        if (restorePassArmed) return;
        UIDocument window = document();
        if (window == null) return;
        restorePassArmed = true;
        window.animation().every(this, deltaSeconds -> {
            restorePassArmed = false;
            runRestorePass();
            return false;
        });
    }

    private void runRestorePass() {
        // LEAST RECENT FIRST, so the most recently used window is activated last and ends in front.
        // Forwards would leave the desktop showing whatever had been looked at least recently.
        for (int index = pendingMru.size() - 1; index >= 0; index--) {
            WindowFrame frame = registry.byKey(pendingMru.get(index));
            if (frame != null && frame.state() == WindowState.VISIBLE
                    && !hideAfterRestore.contains(frame)) {
                activate(frame, true);
            }
        }
        pendingMru = List.of();
        // AFTER the activation pass, or activating a window that was put away would bring it back.
        for (WindowFrame frame : hideAfterRestore) {
            if (frame.state() == WindowState.VISIBLE) frame.hide();
        }
        hideAfterRestore.clear();
    }

    /** @see #announceTheSwitcherOnce */
    private static boolean switcherAnnounced;

    /**
     * Tells the user how to switch windows, the first time one is put away.
     *
     * <p><b>A keybinding nobody can discover is a keybinding that does not exist.</b> The taskbar is the
     * safety net and is visible; the switcher is the fast path and is invisible until you already know
     * about it, so the moment a window first disappears is the one moment the offer is both relevant and
     * unmissable. Windows makes the same offer with its "your window is here" taskbar bubble.</p>
     *
     * <p><b>The chord is read from the keymap, never spelled.</b> A literal is a promise the widget cannot
     * keep the moment anything rebinds the command, and it fails silently — the notification goes on
     * confidently naming a key that does nothing. {@code Keymap.acceleratorFor} is what every menu item
     * and tooltip in the engine already uses, and it falls back to the command's declared default, so this
     * is correct before anybody has installed a keymap at all. If the command is genuinely unbound there
     * is nothing to advertise and nothing is said.</p>
     *
     * <p>Once per process, and separately suppressible for good: the static flag stops it repeating in a
     * session, and {@code neverShowAgain} is the user's own switch, which {@code Notifications} persists
     * by id. Two mechanisms because they answer different questions — "have I said this yet" and "does
     * this person want to be told".</p>
     */
    private void announceTheSwitcherOnce() {
        if (switcherAnnounced) return;
        // NOT WORTH SAYING WITH ONE WINDOW. Advertising a switcher on a desktop that has nothing to switch
        // between teaches a chord that will appear broken the first time it is pressed.
        if (registry.size() < 2) return;
        KeyChord chord = Keymap.acceleratorFor(this, DesktopCommands.SWITCH_WINDOW);
        if (chord == null) return;
        switcherAnnounced = true;
        Notifications.show(Notification.info("Window minimised")
                .withDetail("Press " + chord + " to switch between windows")
                .withNeverShowAgain("desktop.switcherHint"));
    }

    /** Lets a test drive the first-hide announcement more than once. */
    public static void resetSwitcherAnnouncementForTesting() {
        switcherAnnounced = false;
    }

    // ── The host seam ────────────────────────────────────────────────────────────────────
    //
    // What a LOADER calls, and the half of the compositor a harness never needs: a scene is always
    // the thing on screen, so it never asks whether it is, never suspends, and never paints a
    // pinned window over somebody else's GUI. All of it is the COMPOSITOR's rather than the
    // document's -- the engine may not name a desktop, which is why `Desktop.of` names the document
    // and not the reverse.

    /** Whether anything is pinned — what a host asks to choose between a suspend and a HUD. */
    public boolean hasPinnedWindows() {
        for (WindowFrame frame : registry().windows()) {
            if (frame.isPinned()) return true;
        }
        return false;
    }

    /** @see #suspend() */
    public boolean isSuspended() {
        return suspended;
    }

    /**
     * Takes the compositor off the tree — what a host calls when its screen closes.
     *
     * <p>Detaching is the same mechanism a hidden window uses one level down and buys the same
     * things: nothing matches a selector, nothing lays out, nothing paints, and the services are
     * told to forget every node in the subtree, so the hover, the press target and any live drag
     * are dropped rather than left describing a screen that is no longer up.</p>
     *
     * <p><b>The windows themselves are untouched.</b> Their states stay {@code VISIBLE}, their
     * positions and their z-order stay exactly as they were, and {@link #resume} puts the desktop
     * back with everything where it was left. Hiding each window instead would lose which of them
     * were on screen, which is the thing a resume has to know.</p>
     */
    public void suspend() {
        if (suspended) return;
        UIDocument document = document();
        if (document == null) return;
        suspended = true;
        suspendedIn = document;
        document.remove(this);
        onSuspendedChanged.emit(false);
    }

    /** Puts the compositor back. @see #suspend() */
    public void resume() {
        if (!suspended) return;
        suspended = false;
        UIDocument document = suspendedIn;
        suspendedIn = null;
        if (document != null && parent() == null) document.append(this);
        onSuspendedChanged.emit(true);
    }

    /**
     * What the compositor should be showing, given what the host has on screen.
     *
     * <p>The host answers two booleans it can see and this answers the one thing it cannot: what
     * state the desktop is actually in. Keeping the decision here rather than at each hook is what
     * removed the close flicker — @see DesktopPresentation.</p>
     *
     * @param ourScreenIsUp the host's own CrystalGUI screen is the current one
     * @param anyScreenIsUp some GuiScreen is up, ours or a foreign one
     */
    public DesktopPresentation presentation(boolean ourScreenIsUp, boolean anyScreenIsUp) {
        if (ourScreenIsUp) return DesktopPresentation.DESKTOP;
        if (parent() == null) return DesktopPresentation.NONE;
        if (!hasPinnedWindows()) return DesktopPresentation.NONE;
        return anyScreenIsUp ? DesktopPresentation.OVERLAY : DesktopPresentation.HUD;
    }

    /** What {@link #enterHudMode} put away, so a foreign screen's input can still reach a pinned window. */
    public ScreenOverlay screenOverlay() {
        UIDocument document = document();
        if (document == null) return null;
        if (screenOverlay == null) screenOverlay = new ScreenOverlay(document);
        return screenOverlay;
    }

    /**
     * Paints one frame in {@code presentation} — <b>the one paint entry a host calls</b>.
     *
     * <p>Folding the arms together is not tidiness: it is what stops two callers each deciding
     * whether it is their turn, which is what dropped a frame every time the screen closed.</p>
     *
     * <p>Three things vary and nothing else does — <b>what</b> is painted (the whole document, or
     * the window layer alone), whether the <b>top layer</b> goes with it, and whether <b>input</b>
     * runs. Each is a question the presentation answers, so a new situation is a new arm rather than
     * a new path. The root transform is the box tree's in every arm, which is what makes a pinned
     * window pixel-identical on the desktop, over a foreign GUI and on the HUD.</p>
     */
    public void paint(DesktopPresentation presentation, float deltaSeconds,
                      int surfaceWidth, int surfaceHeight) {
        if (presentation == null || !presentation.paintsAnything()) return;
        UIDocument document = document();
        if (document == null) return;
        if (!presentation.paintsWholeDesktop() && parent() == null) return;

        // SURFACE pixels in, LOGICAL units to lay out in: the scale lives on the box tree's root
        // transform and nowhere else, so this is the only division and painting picks it up by
        // reading the matrix it already reads.
        float scale = document.boxes().uiScale();
        document.frame(deltaSeconds, surfaceWidth / scale, surfaceHeight / scale);

        CgUiPaintContext ctx = CgUiPaintContext.getInstance();
        ctx.beginFrame(surfaceWidth, surfaceHeight);
        if (presentation.paintsWholeDesktop()) {
            document.paint(ctx);
        } else {
            // THE WINDOW LAYER, not the desktop: the taskbar is chrome for a desktop that is not up,
            // and a strip listing windows most of which are hidden is not something to put over a
            // game. The top layer is skipped for the same reason unless the presentation asks.
            Box layer = windowLayer().box();
            if (layer != null) BoxPainter.paintSubtree(layer, ctx);
            if (presentation.paintsTopLayer()) {
                Box top = document.hasTopLayerContent() ? document.topLayer() : null;
                if (top != null) BoxPainter.paintSubtree(top, ctx);
            }
        }
        ctx.endFrame();
    }

    private final ApplicationRegistry applications = new ApplicationRegistry(this);

    /** The window layer — the work area's box, and the containing block every frame is placed in. */
    public UIElement windowLayer() {
        return windows;
    }

    /** Every live window, visible or hidden — the model, not the tree. @see WindowRegistry */
    public WindowRegistry registry() {
        return registry;
    }

    /**
     * What is installed on this desktop and what is running — the shell's side of an application.
     *
     * <p>Per desktop rather than process-wide, for the reason every other per-scope registry here is:
     * two desktops in one installation (a game client and a dedicated tool) offer different products and
     * neither should have to know the other exists.</p>
     */
    public ApplicationRegistry applications() {
        return applications;
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
    public void placeByCascade(WindowFrame frame) {
        float step = frame.captionHeight();
        // A NULL BOX IS A ZERO ONE HERE. The guard below already refuses a non-positive size -- the
        // first window on an empty desktop cannot be placed because nothing has been measured yet --
        // and a node that has not been laid out has no box at all, which is the same statement.
        Box area = windows.box();
        Box frameBox = frame.box();
        float areaWidth = area == null ? 0f : area.width();
        float areaHeight = area == null ? 0f : area.height();
        float frameWidth = frameBox == null ? 0f : frameBox.width();
        float frameHeight = frameBox == null ? 0f : frameBox.height();
        if (step <= 0f || areaWidth <= 0f || areaHeight <= 0f || frameWidth <= 0f || frameHeight <= 0f) {
            return;
        }

        // CENTRED, and cascading FROM the centre. Every GuiContainer in Minecraft opens centred, and a
        // window in the top-left corner reads as unplaced -- because that is exactly where an unplaced
        // one is drawn, so the two were indistinguishable on screen. Win32's CW_USEDEFAULT cascades
        // from the corner; macOS and KDE centre; the game this runs inside has already decided.
        //
        // ALONE ON THE DESKTOP, THERE IS NOTHING TO CASCADE FROM. The counter only ever grew, so a
        // window closed and reopened landed one step away from where it had been, and a third open one
        // step further -- drifting across the screen a caption at a time, once per reopen.
        if (windows.frames().size() <= 1) cascadeStep = 0;
        float centreLeft = (areaWidth - frameWidth) / 2f;
        float centreTop = (areaHeight - frameHeight) / 2f;
        float offset = cascadeStep * step;
        if (centreLeft + offset + frameWidth > areaWidth || centreTop + offset + frameHeight > areaHeight) {
            cascadeStep = 0;
            offset = 0f;
        }
        cascadeStep++;
        frame.moveTo(Math.max(0f, centreLeft + offset), Math.max(0f, centreTop + offset));
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
     * {@link #insertAt}, and {@code removeSelf}, {@code clearAllChildren} and a reparent to another
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

        /**
         * Parks the compositor's own decoration on the layer — the snap preview.
         *
         * <p>An <b>internal</b> child, because the public door is deliberately shut: this layer holds
         * {@code WindowFrame}s and says so by throwing. That refusal is worth keeping (it is what stops
         * an application parenting content into the work area), so the one thing the compositor itself
         * needs to put there goes in through a door of its own rather than by widening that one.</p>
         *
         * <p>It is still an ordinary child for layout and paint order, so a preview at stack order 0
         * sits below every window that has ever been raised — which is every open window.</p>
         */
        void hostDecoration(UIElement decoration) {
            if (decoration.parent() == this) return;
            hosted = decoration;
            append(decoration);
        }

        /**
         * The one non-window this layer holds. @see #hostDecoration
         *
         * <p><b>A field where the old engine had a separate method.</b> There, {@code addInternalChild}
         * bypassed the {@code addChildAt} override entirely, so the refusal and the exception could be
         * written as one unconditional throw. Here there is no bypass — every insertion goes through
         * {@code insertAt} — so the exemption has to be nameable, which is a small improvement: the
         * layer now says exactly which node it is making an exception for instead of trusting that
         * only the compositor knows the private door.</p>
         */
        @Nullable
        private UIElement hosted;

        @Override
        public UIElement insertAt(int index, UIElement child) {
            if (!(child instanceof WindowFrame) && child != hosted) {
                throw new UnsupportedOperationException(
                        "The desktop's window layer holds WindowFrames — use Desktop.addWindow(frame)");
            }
            super.insertAt(index, child);
            if (!(child instanceof WindowFrame)) return this;
            // AFTER the super call, and at the same index: the insertion may have re-entered this class
            // through remove (a frame moving from another desktop), and inserting first would then be
            // undone by that removal. Index-matched so the list order is the child order, which is what
            // makes "insertion order" in windows() mean anything.
            frames.add(Math.min(index, frames.size()), (WindowFrame) child);
            return this;
        }

        @Override
        public boolean remove(UIElement child) {
            boolean removed = super.remove(child);
            if (removed && child == hosted) hosted = null;
            if (removed && !(child instanceof WindowFrame)) return true;
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
                announceTheSwitcherOnce();
                registry.changed();
                // LAST, and after the activation above rather than before it: eviction can destroy a
                // window, and destroying one re-enters this method. Doing it while the active window is
                // still the frame that just left would have activateTopmost() choosing between windows
                // one of which is mid-removal.
                registry.evictIfNeeded();
            }
            return removed;
        }

        /**
         * Places what is unplaced and re-clamps what is not, once layout has settled.
         *
         * <p><b>An {@code afterLayout} hook where the old engine overrode {@code onLayoutChanged}.</b>
         * Every line of it READS measured geometry — the work area's box and the frame's — and an
         * ordinary per-frame hook runs BEFORE layout, so on the pass that first puts a window on an
         * empty desktop it would measure the boxes from before the layer existed. That is the same
         * frame the paragraph below is about, reached one step earlier.</p>
         *
         * <p>Registered from {@link #connected()} and never unregistered: the hook is OWNED by this
         * layer, so freezing or detaching the compositor drops it, which is the whole point of
         * ownership replacing the old one-way ticker.</p>
         */
        @Override
        protected void connected() {
            super.connected();
            UIDocument document = document();
            if (document != null) document.animation().afterLayout(this, delta -> {
                placeAndClamp();
                return true;
            });
        }

        private void placeAndClamp() {
            for (int i = 0; i < frames.size(); i++) {
                WindowFrame frame = frames.get(i);
                // AN UNPLACED WINDOW IS PLACED HERE, because THIS is the moment it becomes possible.
                //
                // placeByCascade needs the work area AND the frame measured, and on the pass that first
                // puts a window on an empty desktop neither is: the compositor deliberately takes up no
                // space until a window exists, so the layer is 0x0 when the frame's own onLayoutChanged
                // asks. It returns without placing and without marking the frame placed -- correct, and
                // it leaves nobody to try again, because growing the work area does not resize a frame
                // that has a width and a height of its own, so the frame's callback never fires a second
                // time.
                //
                // The window is therefore DRAWN at its unplaced position -- hard against the left of the
                // work area -- for as long as that lasts, and lands at its cascade offset whenever
                // something else finally dirties layout. During an entry animation that reads as the
                // window flying in from the left of the screen, and only ever on a window's FIRST open,
                // which is exactly how it was reported.
                if (frame.isPlaced()) frame.reclamp();
                else placeByCascade(frame);
            }
        }
    }
}
