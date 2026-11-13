package com.crystalgui.desktop;

import com.crystalgui.core.settings.PreferencesStore;
import com.crystalgui.core.storage.CrystalGuiStorage;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgraphics.net.protocol.CgProtocolConnection;
import com.crystalgraphics.storage.config.CgLocalConfigStorage;
import com.crystalgui.desktop.app.ApplicationKind;
import com.crystalgui.desktop.window.StartingMark;
import com.crystalgui.desktop.app.ApplicationRegistry;
import com.crystalgui.fs.client.WorkspaceClient;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.core.window.WindowState;
import com.crystalgui.desktop.motion.WindowAnimator;
import com.crystalgui.desktop.dialog.FailureWindow;
import com.crystalgui.desktop.switcher.WindowSwitcher;
import com.crystalgui.desktop.taskbar.Taskbar;
import com.crystalgui.desktop.taskbar.TaskbarPins;
import com.crystalgui.desktop.window.WindowCommands;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.desktop.window.WindowKeyboardMove;
import com.crystalgui.desktop.manager.SessionRecord;
import com.crystalgui.desktop.manager.WindowManager;
import com.crystalgui.desktop.window.WindowRegistry;
import com.crystalgraphics.storage.config.CgConfigStorage;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgui.core.command.Commands;
import com.crystalgui.core.command.MenuId;
import com.crystalgui.core.data.DataKey;
import com.crystalgui.core.data.DataProvider;
import com.crystalgui.core.notify.Notification;
import com.crystalgui.core.notify.Notifications;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.core.notify.NotificationService;
import com.crystalgui.desktop.notification.NotificationBalloons;
import com.crystalgui.widget.overlay.ContextMenu;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.service.Animation;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.input.keymap.KeyChord;
import com.crystalgui.ui.input.keymap.Keymap;
import dev.vfyjxf.taffy.style.FlexDirection;

import javax.annotation.Nullable;

import java.nio.file.Path;
import java.util.function.Supplier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The compositor host: CrystalOS's desktop and the parent of every {@link WindowFrame}. One per
 * {@code UIDocument}, found with {@link #of(UIDocument)}; nobody constructs one. Opening a UI is
 * {@code desktop.addWindow(frame)}.
 *
 * <p>It is an internal child of the root, above the root's own content and below the top layer, written
 * from Java at IMPORTANT so a stylesheet cannot move it. Full size when it is the surface, zero-sized and
 * hit-transparent when laid over a game screen with no window, so it cannot swallow clicks. A window
 * <em>exists</em> or not; a desktop whose windows are all minimised keeps its taskbar.</p>
 *
 * <p>The window layer is internal while the frames on it are public children, so a frame can remove itself;
 * it is added in the constructor, before the document attaches this desktop, because
 * {@code markAsInternal()} recurses into existing children. The layer's box is the work area: the taskbar
 * is laid out below it, so maximise fills the layer and fullscreen re-flows it to full height.</p>
 *
 * <p>Stacking, activation, placement and snapping are the {@link WindowManager}'s.</p>
 */
public class Desktop extends UIElement implements DataProvider {

    /** The cascade identity `ua/desktop.css` names. @see com.crystalgui.ui.dom.Name */
    public static final Name NAME = Name.of("desktop");

    /** The layer frames live on, and the work area they are bounded by. */
    public static final String WINDOW_LAYER_CLASS = "__windows__";

    private final WindowLayer windows = new WindowLayer();

    /** @see #overlayLayer() */
    private final UIElement overlayLayer = new UIElement();

    /** The geometry class for the dialog band — {@code desktop .__overlays__}. */
    public static final String OVERLAY_LAYER_CLASS = "__overlays__";

    /**
     * The one compositor on {@code document}, attached on first use. The compositor names the document, never
     * the reverse, so {@code ui.dom} stays free of widgets. There is no cached field: a desktop is on screen
     * exactly when it is a child of the document (a re-added internal child once marked every window internal).
     */
    public static Desktop of(UIDocument document) {
        Desktop existing = ifPresent(document);
        if (existing != null) return existing;
        Desktop desktop = new Desktop();
        document.append(desktop);
        synchronized (BY_DOCUMENT) {
            BY_DOCUMENT.put(document, desktop);
        }
        return desktop;
    }

    /**
     * Which compositor belongs to which document, kept so it survives a suspend: a suspended desktop is out of
     * the tree, and {@link #of} would build a second one. Weak keys.
     */
    // Threads: guarded by its own monitor.
    private static final java.util.Map<UIDocument, Desktop> BY_DOCUMENT = new java.util.WeakHashMap<>();

    /**
     * The compositor only if one is already on screen. Questions about windows (a command's {@code enabledWhen})
     * use this, since {@link #of} would grow a desktop on an application that never opened a window.
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
        Desktop remembered;
        synchronized (BY_DOCUMENT) {
            remembered = BY_DOCUMENT.get(document);
        }
        return remembered != null && remembered.isSuspended() ? remembered : null;
    }

    /**
     * Public because the tag registry needs a factory and a test needs to be able to make one, not
     * because an application should. Reach a document's compositor with {@link #of}.
     */
    public Desktop() {
        super(NAME);
        whileConnected(() -> document().focus().onDidChangeFocus.connect(this::focusMoved));
        StyleGroup.defaultPipeline(getStyle().getLayoutGroup(),
                l -> l.flexDirection(FlexDirection.COLUMN));
        // THE CLASS IS THE WHOLE OF THE LAYER'S GEOMETRY. Without it `desktop .__windows__` matches
        // nothing, the work area sizes to content, and its children are all absolutely positioned --
        // so it measures 0x0 and every rule that reads the work area quietly stands down instead of
        // failing: no clamp, no cascade, windows written wherever they were asked to go.
        windows.addClass(WINDOW_LAYER_CLASS);
        append(windows);
        // Inside the work area so a free dialog clamps where a window does, above every window by z-index, and
        // below the taskbar. Hit-transparent: a full-size sheet over the work area swallows clicks.
        overlayLayer.addClass(OVERLAY_LAYER_CLASS);
        overlayLayer.set(Attribute.HIT_TRANSPARENT, true);
        StyleGroup.defaultPipeline(overlayLayer.getStyle().getGeneralGroup(),
                g -> g.zIndex(WindowManager.DIALOG_BAND));
        windows.hostOverlays(overlayLayer);
        // THE TOASTS ARE THE SHELL'S: one place above every window, whichever application said it (Windows'
        // notification centre). An application's own list is a view of the same service.
        overlayLayer.append(new NotificationBalloons());
        // STATIC ROUTES ONLY: the service outlives a desktop's screen, so nothing it holds may name the desktop.
        NotificationService.install(notifications(), Desktop::notificationsOfRunningDocument,
                Desktop::focusedApplicationOfRunningDocument);
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

        // THE WIDGET THAT OWNS THE COMMANDS REGISTERS THEM, as DockArea does for DockCommands. Registering
        // from anywhere else is how a command ends up existing only once something unrelated has been
        // constructed -- registered but unreachable, or bound but pointing at nothing.
        DesktopCommands.register();
        WindowCommands.register();

        // DRIVEN BY THE REGISTRY, not by the layer. The layer never sees a window destroyed while it was
        // already hidden, and it sees a hide as a removal -- so hanging presence off it both missed the
        // moment the surface should be given back and gave it back while windows were still retained.
        windowManager.registry().onDidChange.connect(this::syncPresence);
        syncPresence();

        // A PRESS ON BARE DESKTOP DEACTIVATES. Target-only on both elements, so a press inside a window
        // never reaches it -- and both are needed rather than one: the window layer covers the desktop
        // entirely, so bare background is nearly always the LAYER's hit, while the desktop itself is
        // what remains once the taskbar (W4) shortens the layer.
        onMouseDown.attachListener((element, event) -> windowManager.deactivate(), false, false);
        windows.onMouseDown.attachListener((element, event) -> windowManager.deactivate(), false, false);

        // THE USER WORKING IN A WINDOW ENDS ITS ATTENTION FLASH: a press or a key landing anywhere inside it.
        // Capture, so nothing in the content can swallow it first.
        windows.onMouseDown.attachListener((element, event) -> seen(event.getTarget()), true, false);
        windows.onKeyDown.attachListener((element, event) -> seen(event.getTarget()), true, false);

        // A right-click on bare desktop opens the desktop menu, the only affordance once every window is minimised.
        // Attached to the desktop, not the layer (which refuses non-frame children), and guarded on the target being
        // the surface itself: the bubble phase would otherwise open it for a click inside a window.
        ContextMenu.attach(this, Commands.process(),
                pressed -> pressed == this || pressed == windows
                        ? ContextMenu.of(MenuId.DESKTOP_CONTEXT) : null);
    }

    // Live (full size) while any window exists, or when the desktop is the surface; see syncPresence.
    // The geometry is the sheet's (ua/desktop.css `desktop`, `desktop.__live__`), never a stylesheet's to move
    // beyond that: a theme styles what the desktop paints, not where the compositor sits.
    // Live is the REGISTRY, not the window layer: the layer holds only visible windows, so minimising the
    // last one would collapse the desktop and its taskbar.
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

    public static final String LIVE_CLASS = "__live__";

    /** Whether this desktop is being painted as the whole surface rather than over someone's screen. */
    private boolean coversSurface;

    /**
     * Whether the desktop is painted as the whole surface, taskbar and all, rather than its window layer over another
     * screen: what its live class answers. Its host says so each frame. On the document.
     */
    public void setCoversSurface(boolean covers) {
        if (covers == coversSurface) return;
        coversSurface = covers;
        syncPresence();
    }

    private void syncPresence() {
        // A class, not a size: ua/desktop.css sizes `desktop.__live__` full and the base rule takes no space,
        // so forgetting it fails safe. Live when the desktop is the surface (its taskbar always shows) or
        // holds a window; one laid over a game screen with none stays zero-sized.
        setClass(this, LIVE_CLASS, coversSurface || isLive());
    }

    private static void setClass(UIElement node, String className, boolean on) {
        if (on) node.addClass(className);
        else node.removeClass(className);
    }

    /** The window manager: stacking, the active window, placement, snapping, the HUD. */
    public WindowManager windowManager() {
        return windowManager;
    }

    /** Whether any window <b>exists</b>, visible or hidden — i.e. whether the compositor is the surface. */
    public boolean isLive() {
        return !windowManager.registry().windows().isEmpty();
    }

    // ── The window manager ──────────────────────────────────────────────────

    /** The windows as a set: stacking, activation, placement, snapping. */
    private final WindowManager windowManager = new WindowManager(new WindowManager.Shell() {
        @Override
        public Box workArea() {
            return windows.box();
        }

        @Override
        public List<WindowFrame> visibleFrames() {
            return windows.frames();
        }

        @Override
        public UIDocument document() {
            return Desktop.this.document();
        }

        @Override
        public void setTaskbarVisible(boolean visible) {
            taskbar.setBarVisible(visible);
        }
    });

    /** The strip along the bottom. @see Taskbar */
    private final Taskbar taskbar;
    private final WindowSwitcher switcher;

    /**
     * Turns window animations on or off for the whole process (Windows' "Animate windows", macOS Reduce Motion).
     * Off means off, including the waiting: a close takes effect on the same call.
     */
    public static void setAnimationsEnabled(boolean enabled) {
        WindowAnimator.setEnabled(enabled);
    }

    /** @see #setAnimationsEnabled */
    public static boolean animationsEnabled() {
        return WindowAnimator.isEnabled();
    }

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
        // WHAT THE TASKBAR LEAVES, told to the document so a layer that may not name a compositor can
        // still be clamped by one -- a Dialog is the case. It stays true for a suspend without being
        // withdrawn: the layer goes out of the tree with us, and workArea() falls back on its own.
        current.setWorkArea(overlayLayer);
        // ARMED ON THE WAY IN: a desktop attached after persistTo -- which is every host, since
        // persistTo is called on a fresh one -- would otherwise have no window to register its
        // one-shot restore pass with.
        armRestorePass();
        // THE WINDOW-LEVEL ANSWER TO "which window is this about", and the LAST resort by construction:
        // DataContext walks the element chain first and only asks the window's providers when nothing
        // answered. So a command invoked from inside a frame gets that frame, one invoked from a taskbar
        // entry gets the entry's frame, and one invoked from the palette with nothing focused gets the
        // active window -- which is the only sensible answer there and the reason this exists.
        current.addDataProvider(this);
    }

    /** The document last connected to: {@code disconnected()} runs when there is no longer one to ask. */
    @Nullable
    private UIDocument lastDocument;

    /** @see #connected — the window-level fallback for {@link WindowFrame#WINDOW_FRAME}. */
    @Override
    @Nullable
    public Object getData(DataKey<?> key) {
        return key == WindowFrame.WINDOW_FRAME ? windowManager.activeWindow() : null;
    }

    private void seen(@Nullable Object target) {
        for (UIElement walk = target instanceof UIElement ? (UIElement) target : null; walk != null;
             walk = walk.parentElement()) {
            if (walk instanceof WindowFrame) {
                ((WindowFrame) walk).clearAttention();
                return;
            }
        }
    }

    private void focusMoved(@Nullable UIElement focused) {
        for (UIElement walk = focused; walk != null; walk = walk.parentElement()) {
            if (walk instanceof WindowFrame && ((WindowFrame) walk).desktop() == this) {
                // Only a window on screen: focus inside a hidden one is stale input state, and activating it would
                // un-hide a window the user just put away. Taskbar and switcher activate directly.
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
                windowManager.activate((WindowFrame) walk, false, false);
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
     * Puts a window on the desktop, raised and activated, and cascades it into place unless it has one.
     * Placement waits for the first layout, when the caption and the work area are measured.
     */
    public <T extends WindowFrame> T addWindow(T frame) {
        return addWindow(frame, true);
    }

    /**
     * Puts a window on the desktop, optionally without taking focus (the no-steal rule): a window opened by a
     * server or a background job must not take the keyboard mid-keystroke. It is registered, attached and
     * animated like any other; only the raise and the activation are skipped, and it asks for attention
     * instead (Windows' foreground lock and {@code FlashWindowEx}). It joins the back of the MRU, so it is not the
     * switcher's first offer.
     */
    public <T extends WindowFrame> T addWindow(T frame, boolean activate) {
        if (frame.state() == WindowState.DESTROYED) {
            throw new IllegalStateException("a destroyed window cannot be reopened: " + frame.getTitle());
        }
        frame.setOwner(this);
        try {
            return attach(frame, activate);
        } catch (RuntimeException failed) {
            // Registered but never attached would be a taskbar entry for a window that is not there.
            windowManager.registry().withdrawn(frame);
            frame.setOwner(null);
            try {
                if (frame.parent() != null) frame.parent().remove(frame);
            } catch (RuntimeException ignored) {
                // The tree refused the same way the attach did.
            }
            throw failed;
        }
    }

    private <T extends WindowFrame> T attach(T frame, boolean activate) {
        windowManager.registry().opened(frame);
        windows.append(frame);
        // BEFORE the animation and the activation below: a window restored from a record should open AT
        // the size and place it is meant to be, not fly in at a default and jump. @see #persistTo
        // AN APPLICATION'S STARTING WINDOW GIVES WAY to its main window: the same place, already open, no second flight
        WindowFrame starter = startingFor(frame);
        if (starter != null) {
            // ITS STARTER'S, NOT THE RECORD'S: the starter opened at the record, and a resize or maximise made while it
            // loaded is the newer word. The record is spent, or it would apply to the next window under this key.
            if (frame.key() != null) pendingPlacements.remove(frame.key());
            takeGeometry(frame, starter);
        } else if (!applyPersistedGeometry(frame) && !frame.isSized() && frame.isApplicationMain()) {
            windowManager.sizeByDefault(frame);
        }
        // A FIRST OPEN DOES NOT GO THROUGH show(), so the entry animation has to be played here as well.
        // The two paths are genuinely different -- this one registers the window and hands it an owner,
        // show() puts a hidden one back -- and the animation is the only thing they share.
        // A minimised starter's application waits minimised: activated, it would open over the minimise
        if (starter != null && (starter.state() == WindowState.HIDDEN || starter.isMinimizing())) activate = false;
        if (starter == null) frame.playOpenAnimation();
        else replaceStarting(starter, frame);
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
        windowManager.activate(frame, true);
        return frame;
    }

    /**
     * Tells the user something could not be opened: a modal box over the desktop with a red mark, {@code message}
     * and the cause's root message, and an OK.
     *
     * <pre>{@code
     * try {
     *     open();
     * } catch (RuntimeException failed) {
     *     desktop.reportFailure("Machine Example", "Machine Example could not be opened.", failed);
     * }
     * }</pre>
     *
     * <p>Callable from any thread: it posts to the document's sequence when it is on another. A desktop with no
     * document yet reports nothing.</p>
     *
     * @param title   the caption: the application or window that failed
     * @param message the sentence saying what did not happen
     * @param cause   why, or null
     */
    public void reportFailure(String title, String message, @Nullable Throwable cause) {
        reportFailure(null, title, message, cause);
    }

    /**
     * As above, for an application: the box is that application's window, so it takes the application's taskbar
     * button, flashes it for attention as Windows' does, and closes, minimises and switches like any window.
     *
     * <pre>{@code
     * desktop.reportFailure("mymod:editor", "My Editor", "My Editor could not be opened.", failed);
     * }</pre>
     *
     * <p>One box per application: a second failure brings the open one forward.</p>
     *
     * @param applicationId the {@code ApplicationKind} id, or null for a failure with no application
     */
    public void reportFailure(@Nullable String applicationId, String title, String message, @Nullable Throwable cause) {
        UIDocument document = document();
        if (document == null) return;
        Runnable show = () -> {
            WindowFrame open = applicationId == null ? null : failureWindows.get(applicationId);
            if (open != null && open.state() != WindowState.DESTROYED && !open.isClosing()) {
                windowManager.activateByUser(open);
                return;
            }
            ApplicationKind kind = applicationId == null ? null : applications().byId(applicationId);
            WindowFrame box = FailureWindow.open(this, kind, title, message, cause);
            if (applicationId != null) {
                failureWindows.put(applicationId, box);
                box.onDestroyed.connect(() -> failureWindows.remove(applicationId, box));
            }
        };
        document.execute(show);
    }

    /**
     * Runs {@code hook} every frame of the desktop's document, for as long as the desktop is on it; a hook that answers
     * false ends.
     *
     * <pre>{@code
     * desktop.everyFrame(delta -> {
     *     poll();
     *     return true;
     * });
     * }</pre>
     */
    public void everyFrame(Animation.Hook hook) {
        UIDocument shell = document();
        if (shell != null) shell.animation().every(windows, hook);
    }

    /** Each application's open failure window. */
    private final Map<String, WindowFrame> failureWindows = new HashMap<>();

    /** Puts a hidden window back on the layer. Driven by {@link WindowFrame#show}, which owns the state. */
    public void reattach(WindowFrame frame) {
        if (frame.parent() == null) windows.append(frame);
        windowManager.raise(frame);
        windowManager.registry().changed();
    }

    /** The strip along the bottom — the registry, rendered. @see Taskbar */
    public Taskbar taskbar() {
        return taskbar;
    }

    /**
     * The modifier that turns a press anywhere inside a window into a move (Alt-drag). A setting rather than a
     * keymap binding: a {@code KeyStroke} is a key plus modifiers and cannot spell a modifier alone. One
     * place, changeable at runtime, as GNOME's {@code mouse-button-modifier}.
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

    /** The preview of where a dragged window would snap. */
    public SnapPreview snapPreview() {
        return snapPreview;
    }

    private final SnapPreview snapPreview = new SnapPreview(windows, windowManager, windows::hostDecoration);

    /** Keyboard Move/Size — W13c. @see WindowKeyboardMove */
    public WindowKeyboardMove keyboardMove() {
        return keyboardMove;
    }

    private final WindowKeyboardMove keyboardMove = new WindowKeyboardMove();

    /** The MRU switcher. @see WindowSwitcher */
    public WindowSwitcher switcher() {
        return switcher;
    }

    // ── Persistence — CrystalOS W12 ─────────────────────────────────────────────────────────────

    @Nullable
    private SessionRecord persistence;
    @Nullable
    private String persistenceId;

    /** This desktop's own store — preferences, the arrangement, every workspace's state. */
    @Nullable
    private CgConfigStorage config;

    /** {@code crystalgui/cache}. Disposable in its entirety. @see #useStorage */
    @Nullable
    private Path cacheRoot;

    /** Recorded placements not yet claimed by a window, by key. @see #persistTo */
    private final Map<String, SessionRecord.Placement> pendingPlacements = new HashMap<>();
    private List<String> pendingMru = List.of();
    private boolean restorePassArmed;

    /**
     * Remembers this desktop's arrangement and puts back the one it finds. A host says where and which desktop;
     * geometry is applied to windows as they open, matched by {@link WindowFrame#key()}, and nothing is
     * constructed from the record, so a window the host does not reopen does not come back.
     */
    public Desktop persistTo(CgConfigStorage storage, String id) {
        // AND IT BECOMES THIS DESKTOP'S STORE, which is what an application launched onto it is given.
        // A caller that named one place for the arrangement did not mean a second place for everything
        // else. @see #useStorage
        this.config = storage;
        desktopSettings.useStorage(storage);
        applications().autostart();
        persistence = new SessionRecord(windowManager, storage);
        persistenceId = id;
        pendingPlacements.clear();
        for (SessionRecord.Placement placement : persistence.read(id)) {
            pendingPlacements.put(placement.key(), placement);
        }
        pendingMru = persistence.readMruOrder(id);
        applications().expect(persistence.readApplications(id));
        restoringPins = true;
        try {
            pins.restore(persistence.readPins(id));
        } finally {
            restoringPins = false;
        }
        // Windows already open when a host installs this are placed too -- a host that opens its editor
        // and then asks for persistence is doing nothing wrong.
        for (WindowFrame frame : windowManager.registry().windows()) applyPersistedGeometry(frame);
        armRestorePass();
        return this;
    }

    /**
     * Where this desktop keeps everything, under the game's directory: its config store and cache root come from
     * {@link CrystalGuiStorage}. Call once, before launching anything; there is no default.
     *
     * <pre>{@code
     * desktop.useStorage(gameDir);                 // where crystalgui/ goes
     * desktop.applications().launch(kind, workspace);
     * desktop.persistAs("client");                 // and start recording the arrangement
     * }</pre>
     */
    private final ConcurrentHashMap<Class<?>, Object> services = new ConcurrentHashMap<>();

    /**
     * A service this desktop keeps for one of its applications, made on first use: state that belongs to the desktop
     * and not to a process, so two desktops never share it.
     *
     * <pre>{@code
     * ProfilerSettings settings = desktop.service(ProfilerSettings.class, ProfilerSettings::new);
     * }</pre>
     */
    public <T> T service(Class<T> type, Supplier<? extends T> create) {
        return type.cast(services.computeIfAbsent(type, key -> create.get()));
    }

    public Desktop useStorage(Path installation) {
        this.config = new CgLocalConfigStorage(CrystalGuiStorage.configIn(installation));
        desktopSettings.useStorage(config);
        PreferencesStore.attach(config);
        // A PATH, NOT A STORAGE: CgLocalConfigStorage creates its directory eagerly, and a cache/ that
        // exists before anything derived does is a directory nobody can explain.
        this.cacheRoot = CrystalGuiStorage.LAYOUT.cacheIn(installation);
        // AUTOSTART AS SOON AS THERE IS SOMEWHERE TO READ SETTINGS FROM: a hook that records from the
        // first frame is only worth anything before the first frame.
        applications().autostart();
        return this;
    }

    /**
     * Starts recording the arrangement under {@code id}, in the store {@link #useStorage} gave.
     *
     * <p>Separate from {@code useStorage} because the two want opposite moments: storage has to exist
     * before anything launches, and the arrangement has to be applied <em>after</em> a host has opened
     * its windows, or the record loses to whatever was opened next.</p>
     */
    public Desktop persistAs(String id) {
        if (config == null) {
            CrystalGuiCore.LOGGER.warn("[cgui] the desktop has nowhere to write, so the arrangement "
                    + "'{}' is not recorded. Call useStorage(installation) first.", id);
            return this;
        }
        return persistTo(config, id);
    }

    /**
     * Where a workspace served by <b>this process</b> keeps its files, or null when there is none.
     *
     * <p>Re-asked, never cached: a client joins and leaves single-player worlds without the desktop
     * being rebuilt.</p>
     */
    @Nullable
    private Supplier<Path> localWorld;

    /** Tells this desktop how to find the world it is itself serving. @see #workspaceStore */
    public Desktop useLocalWorld(@Nullable Supplier<Path> worldDirectory) {
        this.localWorld = worldDirectory;
        return this;
    }

    @Nullable
    private Supplier<WorkspaceClient> workspace;

    /**
     * Tells this desktop how to find the workspace its host is connected to, which every launch from the shell
     * (a pin, Start, an entry's menu) runs against. Asked at each launch, since a host rebinds it on reconnect.
     *
     * <pre>{@code
     * desktop.useWorkspace(host::workspace);
     * }</pre>
     */
    public Desktop useWorkspace(@Nullable Supplier<WorkspaceClient> connected) {
        this.workspace = connected;
        return this;
    }

    @Nullable
    private Supplier<CgProtocolConnection<Object>> connection;

    /** Tells this desktop which connection its host is on. Asked at each use, since a host rebinds it on reconnect. */
    public Desktop useConnection(@Nullable Supplier<CgProtocolConnection<Object>> connected) {
        this.connection = connected;
        return this;
    }

    /** The host's connection to its server, or null when there is none. What an application asks its server through. */
    @Nullable
    public CgProtocolConnection<Object> connection() {
        return connection == null ? null : connection.get();
    }

    /**
     * What anything on this desktop says happened, and where it is kept: the history, the unread count, the groups.
     *
     * <pre>{@code
     * desktop.notifications().onDidChange.subscribe(event -> …);
     * desktop.notifications().show(Notification.info("Saved"));
     * }</pre>
     */
    public NotificationService notifications() {
        return service(NotificationService.class, NotificationService::new);
    }

    @Nullable
    private static NotificationService notificationsOfRunningDocument() {
        UIDocument running = UIDocument.current();
        Desktop desktop = running == null ? null : ifPresent(running);
        return desktop == null ? null : desktop.notifications();
    }

    /** {@code {id, name}} of the application in front on the running document's desktop, or null when none is. */
    @Nullable
    private static String[] focusedApplicationOfRunningDocument() {
        UIDocument running = UIDocument.current();
        Desktop in = running == null ? null : ifPresent(running);
        WindowFrame front = in == null ? null : in.windowManager.activeWindow();
        ApplicationKind kind = front == null ? null : front.application();
        return kind == null ? null : new String[] {kind.id(), kind.displayName()};
    }

    /** The host's workspace, or null when there is no connection, as on a title screen. @see #useWorkspace */
    @Nullable
    public WorkspaceClient workspace() {
        return workspace == null ? null : workspace.get();
    }

    /**
     * Where one workspace's own state goes: its session, backups and local history. Beside the world when this
     * process serves it; otherwise in this installation's tree, keyed by the identity the server greeted with.
     * Placement by process stops being right once a client also serves its own global root.
     */
    public CgConfigStorage workspaceStore(String identity) {
        Path world = localWorld == null ? null : localWorld.get();
        CgConfigStorage root = world == null
                ? config()
                : new CgLocalConfigStorage(CrystalGuiStorage.configIn(world));
        return root.scoped(CrystalGuiStorage.PROJECTS).scoped(identity);
    }

    /**
     * Where one workbench extension keeps what a user made with it, the same in every application and workspace
     * on this installation — or null when this desktop was given nowhere to write.
     *
     * <pre>{@code
     * CgConfigStorage library = desktop.extensionStore("crystalgui:uibuilder");   // …/extensions/crystalgui.uibuilder
     * }</pre>
     */
    @Nullable
    public CgConfigStorage extensionStore(String extensionId) {
        return config == null ? null : config.scoped(CrystalGuiStorage.EXTENSIONS).scoped(extensionId);
    }

    /** Where this desktop's private records go, or null when it was never given anywhere. */
    @Nullable
    public CgConfigStorage config() {
        return config;
    }

    /** Where derived output goes — disposable in its entirety. Null when there is nowhere to write. */
    @Nullable
    public Path cacheRoot() {
        return cacheRoot;
    }

    /**
     * Writes the arrangement now.
     *
     * <p>Called automatically when the desktop leaves the tree, which is what suspending a compositor
     * does and therefore covers a host whose screen closes, and by {@code CgUiScreen.dispose}. A host that
     * tears down some other way calls this itself.</p>
     */
    public void savePersistedState() {
        if (persistence != null && persistenceId != null) {
            persistence.save(persistenceId, pendingPlacements.values(), pins.ids(), applications().sessionApps());
        }
    }

    /** Writes the arrangement on the shell's thread, from any: what an application's {@code saveState} asks for. */
    public void saveSoon() {
        UIDocument shell = document();
        if (shell != null) shell.execute(this::savePersistedState);
    }

    /** @return whether a recorded placement was applied */
    private boolean applyPersistedGeometry(WindowFrame frame) {
        String key = frame.key();
        // A TOOL WINDOW IS PLACED BY WHATEVER OWNS IT, per project -- and this record is per host. It is
        // no longer written here, but a record from before that was, so this has to refuse to apply one
        // rather than merely stop producing them. @see SessionRecord#isPersistable
        if (frame.isToolWindow()) return false;
        if (key == null || pendingPlacements.isEmpty()) return false;
        SessionRecord.Placement placement = pendingPlacements.remove(key);
        if (placement == null) return false;
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
        return true;
    }

    /**
     * Forgets every saved window size and position, and puts each open application's main window back at its default
     * size ({@link ApplicationKind#windowSize}, else most of the screen). The next launch of anything opens at its default.
     *
     * <pre>{@code
     * desktop.resetWindowPlacements();    // the taskbar's "Reset window sizes"
     * }</pre>
     *
     * <p>A maximised window stays maximised; its restore size is what it would return to, and is not touched.</p>
     */
    public void resetWindowPlacements() {
        pendingPlacements.clear();
        for (WindowFrame frame : new ArrayList<>(windowManager.registry().windows())) {
            if (frame.isApplicationMain() && !frame.isMaximized()) windowManager.sizeByDefault(frame);
        }
        savePersistedState();
    }

    private final List<WindowFrame> hideAfterRestore = new ArrayList<>();

    /** On a starting window's content: the placeholder an application's window shows until it has built. */
    public static final String STARTING_CLASS = "__starting__";

    /** Each launching application's starting window, by its kind's id. @see #openStartingWindow */
    private final Map<String, WindowFrame> starting = new HashMap<>();

    /**
     * Opens {@code kind}'s window at once, before the application has built it: its title and icon, where its main
     * window was last closed or at its kind's size, and its icon ({@link StartingMark}). The application's main window takes
     * its place when it is added. Android's starting window and Windows' splash: the click is answered on this frame,
     * however long the application takes.
     *
     * <pre>{@code
     * WindowFrame starter = desktop.openStartingWindow(kind);
     * // a frame or more later: kind's factory adds its main window, which replaces the starter
     * }</pre>
     *
     * <p>Closing it before the application has built cancels the launch: the launcher asks {@link #isStarting}.
     * Closing it once the application has built, while its mark is still leaving, closes the application.
     * {@link #endStartingWindow} takes it down when the launch fails.</p>
     */
    public WindowFrame openStartingWindow(ApplicationKind kind) {
        WindowFrame open = starting.get(kind.id());
        if (open != null && open.state() != WindowState.DESTROYED && !open.isClosing()) return open;
        WindowFrame frame = new WindowFrame(kind.displayName());
        if (kind.icon() != null) frame.setIcon(kind.icon());
        // ITS APPLICATION'S: the pin's slot on the bar, and its kind's size when nothing was recorded
        frame.setApplication(kind).markApplicationMain();
        frame.content().addClass(STARTING_CLASS);
        frame.content().append(new StartingMark(kind.icon(), kind.displayName()));
        SessionRecord.Placement remembered = placementOfApplication(kind.id());
        if (remembered != null && remembered.isUsable()) {
            frame.resizeTo(remembered.width(), remembered.height());
            frame.moveTo(remembered.left(), remembered.top());
        }
        addWindow(frame);
        if (remembered != null && remembered.maximized()) {
            frame.maximize();
            frame.setRestoreRect(remembered.left(), remembered.top(), remembered.width(), remembered.height());
        }
        starting.put(kind.id(), frame);
        return frame;
    }

    /** Whether {@code kind}'s starting window is still waiting for its application: not closed, not replaced. */
    public boolean isStarting(ApplicationKind kind) {
        WindowFrame frame = starting.get(kind.id());
        // CLOSING COUNTS AS CLOSED: its mark may settle during the close's flight, and the build would reopen it
        return frame != null && frame.state() != WindowState.DESTROYED && !frame.isClosing();
    }

    /**
     * Takes down {@code kind}'s starting window once its launch is over without a main window taking its place: failed,
     * or built a window that named its application only after it was added.
     *
     * @param successor the window it stood for, which takes its place on the taskbar; null for none
     */
    public void endStartingWindow(ApplicationKind kind, @Nullable WindowFrame successor) {
        WindowFrame frame = starting.remove(kind.id());
        if (frame == null) return;
        if (successor != null && successor.desktop() == this) replaceStarting(frame, successor);
        else frame.destroy();
    }

    /** A frame this short is steady; this many in a row, the application has stopped holding the thread. */
    private static final float STEADY_FRAME_SECONDS = 0.04f;
    /** The view fades in this long after the mark begins to leave; it ends with the mark's growth. @see WindowAnimator */
    private static final long CROSSFADE_DELAY_NANOS = 100_000_000L;
    private static final int STEADY_FRAMES = 6, MAX_STEADY_WAIT_FRAMES = 300;

    /** {@code successor} where {@code starter} was on the taskbar, then the starter gone. */
    private void replaceStarting(WindowFrame starter, WindowFrame successor) {
        boolean minimised = starter.state() == WindowState.HIDDEN || starter.isMinimizing();
        windowManager.registry().takePlace(successor, starter);
        Taskbar bar = taskbar();
        if (bar != null) bar.takePlace(successor, starter);
        // NO CUT TO THE APPLICATION. Minimised, it waits minimised (the build finished in the background, and a
        // restore shows it ready) and its mark leaves over it on that restore. Shown, the starter stays on top and
        // fades out, its mark growing as it goes, over the application drawn whole beneath it.
        if (minimised) {
            StartingMark.leaveOver(starter, successor);
            successor.hide();
            starter.destroy();
            return;
        }
        // BEGUN ONCE FRAMES RUN SMOOTHLY: the application's build and restore hold the frame thread for hundreds of ms,
        // and a fade begun inside them is spent before one frame shows it
        UIDocument document = starter.document();
        if (document == null || !WindowAnimator.isEnabled()) {
            starter.destroy();
            return;
        }
        // ABOVE EVERY ORDINARY WINDOW, NOW: the application's activation raises it over the starter a frame later, and
        // a raise-after hid the starter for those frames. Raises hand out values below the pinned band.
        starter.setStackOrder(WindowManager.PINNED_BAND - 1);
        // THE APPLICATION IS NOT SEEN UNTIL THE VIEW COMES UP: beneath the glass it showed through as a dark ghost from the
        // swap, a crossfade begun long before the mark had moved. Owned by it, so a starter closed first cannot leave it hidden.
        successor.addClass(WindowFrame.HANDOVER_CLASS);
        int[] state = { 0, 0 };   // smooth frames in a row, frames waited
        long[] leaving = { 0L };   // when the mark began to leave
        document.animation().every(successor, delta -> {
            if (starter.state() == WindowState.DESTROYED || starter.isClosing()) {
                // CLOSING THE STARTER CLOSES THE APPLICATION it was launching, which has not been seen yet: it is
                // destroyed unseen, where closing it would play a close over an invisible window
                if (successor.state() != WindowState.DESTROYED) successor.destroy();
                return false;
            }
            if (leaving[0] == 0L) {
                // AND NOTHING STILL LOADING: the application's restore lands in replies over many frames, and one
                // landing after the mark began to leave stopped it mid-growth
                state[0] = delta <= STEADY_FRAME_SECONDS && !loading() ? state[0] + 1 : 0;
                if (state[0] < STEADY_FRAMES && ++state[1] < MAX_STEADY_WAIT_FRAMES) return true;
                StartingMark.leaveInPlace(starter);
                leaving[0] = System.nanoTime();
            }
            // THE VIEW COMES UP 100 ms INTO THE MARK'S GROWTH, not as it begins: begun with it, the view was settled
            // before the mark had gone
            if (System.nanoTime() - leaving[0] < CROSSFADE_DELAY_NANOS) return true;
            successor.removeClass(WindowFrame.HANDOVER_CLASS);
            successor.playFadeIn();
            starter.playTakeoverFade(starter::destroy);
            return false;
        });
    }

    /**
     * Whether a file request is still out: an application's restore lands in its replies. Jobs are not counted, since
     * one may run for the session.
     */
    private boolean loading() {
        WorkspaceClient at = workspace();
        return at != null && at.health().inFlight() > 0;
    }

    @Nullable
    private WindowFrame startingFor(WindowFrame frame) {
        ApplicationKind kind = frame.isApplicationMain() ? frame.application() : null;
        if (kind == null) return null;
        WindowFrame starter = starting.get(kind.id());
        if (starter == null || starter == frame) return null;
        starting.remove(kind.id());
        return starter.state() == WindowState.DESTROYED || starter.isClosing() ? null : starter;
    }

    /** The starting window's geometry, for an application's main window nothing recorded. */
    private static void takeGeometry(WindowFrame frame, WindowFrame starter) {
        if (starter.isMaximized()) {
            frame.maximize();
            frame.setRestoreRect(starter.restoreLeft(), starter.restoreTop(), starter.restoreWidth(), starter.restoreHeight());
            return;
        }
        frame.resizeTo(starter.recordedWidth(), starter.recordedHeight());
        frame.moveTo(starter.getWantedLeft(), starter.getWantedTop());
    }

    /** The waiting placement an application's main window last had, or null. */
    @Nullable
    private SessionRecord.Placement placementOfApplication(String applicationId) {
        for (SessionRecord.Placement placement : pendingPlacements.values()) {
            if (applicationId.equals(placement.app())) return placement;
        }
        return null;
    }

    /** A closing window's geometry, for the next window under its key. @see WindowFrame#dispose */
    public void rememberPlacement(WindowFrame frame) {
        if (!SessionRecord.isPersistable(frame)) return;
        pendingPlacements.put(frame.key(), SessionRecord.Placement.of(frame).shown());
    }

    /**
     * Replays activation and minimisation once the host has finished opening windows, one frame later: both are
     * statements about the set, and hiding one during {@code addWindow} would fight its open animation.
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
            WindowFrame frame = windowManager.registry().byKey(pendingMru.get(index));
            if (frame != null && frame.state() == WindowState.VISIBLE
                    && !hideAfterRestore.contains(frame)) {
                windowManager.activate(frame, true);
            }
        }
        pendingMru = List.of();
        // AFTER the activation pass, or activating a window that was put away would bring it back.
        for (WindowFrame frame : hideAfterRestore) {
            if (frame.state() == WindowState.VISIBLE) frame.hide();
        }
        hideAfterRestore.clear();
    }

    /** Once per process, claimed atomically since desktops may run on different threads. @see #announceTheSwitcherOnce */
    private static final AtomicBoolean SWITCHER_ANNOUNCED = new AtomicBoolean();

    /**
     * Tells the user how to switch windows, the first time one is put away. The chord is read from the keymap,
     * never spelled, and nothing is said when the command is unbound. Once per process, and suppressible for
     * good through {@code neverShowAgain}.
     */
    private void announceTheSwitcherOnce() {
        if (SWITCHER_ANNOUNCED.get()) return;
        // NOT WORTH SAYING WITH ONE WINDOW. Advertising a switcher on a desktop that has nothing to switch
        // between teaches a chord that will appear broken the first time it is pressed.
        if (windowManager.registry().size() < 2) return;
        KeyChord chord = Keymap.acceleratorFor(this, DesktopCommands.SWITCH_WINDOW);
        if (chord == null || !SWITCHER_ANNOUNCED.compareAndSet(false, true)) return;
        notifications().show(Notification.info("Window minimised")
                .withDetail("Press " + chord + " to switch between windows")
                .withNeverShowAgain("desktop.switcherHint"));
    }

    /** Lets a test drive the first-hide announcement more than once. */
    public static void resetSwitcherAnnouncementForTesting() {
        SWITCHER_ANNOUNCED.set(false);
    }

    // ── The host seam ────────────────────────────────────────────────────────────────────
    //
    // What a LOADER calls, and the half of the compositor a harness never needs: a scene is always
    // the thing on screen, so it never asks whether it is, never suspends, and never paints a
    // pinned window over somebody else's GUI. All of it is the COMPOSITOR's rather than the
    // document's -- the engine may not name a desktop, which is why `Desktop.of` names the document
    // and not the reverse.

    /** @see #suspend() */
    public boolean isSuspended() {
        return suspended;
    }

    /**
     * Takes the compositor off the tree, as a host does when its screen closes. Nothing matches, lays out or
     * paints, and the services drop every node in the subtree. The windows are untouched: states, positions and
     * z-order stay, so {@link #resume} puts everything back.
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

    /**
     * Plays the desktop off screen before its screen closes: the taskbar slides away and every window on it plays its
     * close, a pinned one excepted, since it stays over the game. Then {@code done}, which closes the screen; until
     * {@link #arrive} everything stays gone, so the frames before the close show none of it.
     *
     * <pre>{@code
     * desktop.leave(() -> closeTheScreen());   // Escape pressed
     * // the screen opens again:
     * desktop.resume();
     * desktop.arrive();
     * }</pre>
     *
     * <p>{@code done} runs at once with animations off, and on the document's thread otherwise.</p>
     */
    public void leave(Runnable done) {
        List<WindowFrame> going = new ArrayList<>();
        for (WindowFrame frame : windowManager.registry().windows()) {
            if (frame.state() == WindowState.VISIBLE && !frame.isPinned()) going.add(frame);
        }
        int[] left = {going.size() + 1};
        Runnable one = () -> {
            if (--left[0] == 0) done.run();
        };
        taskbar.leaveScreen(one);
        for (WindowFrame frame : going) frame.animator().playLeave(one);
    }

    /**
     * Plays the desktop onto the screen as it opens: the taskbar comes in and every window on it plays its open. A
     * window already moving, one the HUD put away and is restoring, keeps its own.
     */
    public void arrive() {
        taskbar.comeOnScreen();
        for (WindowFrame frame : windowManager.registry().windows()) {
            if (frame.state() != WindowState.VISIBLE) continue;
            WindowAnimator animator = frame.animator();
            animator.releaseLeave();
            // Pinned: it stayed over the game, and never left.
            if (frame.isPinned() || animator.isPlaying()) continue;
            animator.playOpen();
        }
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

    private final ApplicationRegistry applications = new ApplicationRegistry(this);

    private final TaskbarPins pins = new TaskbarPins();
    /** Over this node's own settings store, so the shell's settings resolve through the settings tree. */
    private final DesktopSettings desktopSettings = new DesktopSettings(settings());
    /** True while {@link #persistTo} reads the pins back, which is not a change to save. */
    private boolean restoringPins;

    {
        // SAVED AS IT HAPPENS: a pin is something the user arranged, and the record is otherwise written only
        // when the desktop leaves the tree, which a crash or a killed process never reaches.
        pins.onDidChange.connect(() -> {
            if (!restoringPins) savePersistedState();
        });
    }

    /** The window layer — the work area's box, and the containing block every frame is placed in. */
    public UIElement windowLayer() {
        return windows;
    }

    /**
     * Where a free-floating overlay is hosted — the band inside the work area, above every window.
     *
     * <p>What {@link UIDocument#workArea()} answers on a desktop, so a {@code Dialog} lands here without
     * naming a compositor. It covers the work area exactly and holds nothing of its own.</p>
     */
    public UIElement overlayLayer() {
        return overlayLayer;
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

    /** The applications pinned to the taskbar, saved with the arrangement. @see TaskbarPins */
    public TaskbarPins pins() {
        return pins;
    }

    /** The desktop's own settings, in {@code desktop-settings.json}. @see DesktopSettings */
    public DesktopSettings desktopSettings() {
        return desktopSettings;
    }

    /**
     * The work area, as an element that tells its windows when it changed size. The frame list is maintained, not
     * derived: it is read in a layout callback that repeats while geometry settles, so it costs one
     * {@code instanceof} per mutation instead of a type test per element per pass. It refuses anything that is
     * not a window, which is what makes the list provably its children; {@link Desktop#addWindow} is the way in.
     */
    private final class WindowLayer extends UIElement {

        private final List<WindowFrame> frames = new ArrayList<>();

        List<WindowFrame> frames() {
            return Collections.unmodifiableList(frames);
        }

        /**
         * Parks the compositor's own decoration (the snap preview) on the layer as an internal child, through a door
         * of its own so the refusal of non-window children stays shut to applications.
         */
        void hostDecoration(UIElement decoration) {
            if (decoration.parent() == this) return;
            hosted = decoration;
            append(decoration);
        }

        /** The one non-window this layer holds. @see #hostDecoration */
        @Nullable
        private UIElement hosted;

        /**
         * The second exemption: the band free dialogs are hosted into. @see Desktop#overlayLayer()
         *
         * <p>A field of its own rather than a widened test, for the reason the one above gives — the
         * layer says which nodes it is making an exception for, and two named ones stay as legible as
         * one. Both are the compositor's own furniture; neither is reachable by an application.</p>
         */
        @Nullable
        private UIElement overlays;

        void hostOverlays(UIElement layer) {
            if (layer.parent() == this) return;
            overlays = layer;
            append(layer);
        }

        @Override
        public UIElement insertAt(int index, UIElement child) {
            if (!(child instanceof WindowFrame) && child != hosted && child != overlays) {
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
            if (removed && child == overlays) overlays = null;
            if (removed && !(child instanceof WindowFrame)) return true;
            if (removed) {
                frames.remove(child);
                // THE STATE FOLLOWS THE TREE. Detaching a frame IS hiding it, whichever route did the
                // detaching -- so a bare removeSelf() cannot leave a window that is out of the tree and
                // still claims to be visible.
                WindowFrame frame = (WindowFrame) child;
                frame.markHidden();
                // The active window cannot be one that left. Through deactivate() so it announces, before the announcement
                // below. It hands over to nobody: leaving the tree is also what minimising looks like, and handing over would
                // drop the caret into a window the user did not ask for. Destroying the active window hands over from
                // WindowFrame.dispose().
                if (windowManager.activeWindow() == child) windowManager.deactivate();
                announceTheSwitcherOnce();
                windowManager.registry().changed();
                // LAST, and after the activation above rather than before it: eviction can destroy a
                // window, and destroying one re-enters this method. Doing it while the active window is
                // still the frame that just left would have activateTopmost() choosing between windows
                // one of which is mid-removal.
                windowManager.registry().evictIfNeeded();
            }
            return removed;
        }

        /**
         * Places what is unplaced and re-clamps what is not, once layout has settled. An {@code afterLayout} hook
         * because it reads measured geometry, which a per-frame hook would read from before the layer existed.
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
                // An unplaced window is placed here: on the pass that first puts a window on an empty desktop the layer is
                // 0x0 and the frame's own callback places nothing, then never fires again. Without this the window draws
                // at the left edge and flies in from there on its first open.
                if (frame.isPlaced()) frame.reclamp();
                else windowManager.placeByCascade(frame);
            }
        }
    }
}
