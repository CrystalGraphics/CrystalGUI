package com.crystalgui.workbench;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.annotation.Nullable;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.net.protocol.UiMethods;
import com.crystalgui.net.window.ClientWindowContext;
import com.crystalgui.net.window.ClientWindows;
import com.crystalgui.net.window.Presentation;
import com.crystalgui.net.window.WindowMount;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.workbench.dock.layout.DockPanelRef;
import com.crystalgui.workbench.dock.panel.DockInput;
import com.crystalgui.workbench.dock.panel.DockPanelDescriptor;
import com.crystalgui.workbench.region.DockRegion;
import com.crystalgui.workbench.region.RegionSide;

/**
 * <b>A server's window as a citizen of the workbench</b> — an editor tab or a tool window, restored by
 * key on the next connection.
 *
 * <pre>{@code
 * ClientWindows.of(connection).setMount(workbench.windowMount(desktopMount));
 * }</pre>
 *
 * <p>Installed as the client's one {@link WindowMount}. It reads each window's
 * {@link ClientWindowContext#presentation()} and routes: a tab into the dock, a tool window onto a
 * rail, and everything else — including anything it does not recognise — to the mount it wraps, which
 * is the desktop's. A host with no workbench installs that one directly and every window opens there,
 * which is the hint working rather than failing.</p>
 *
 * <h3>The tab is the dock's, and everything the dock does applies</h3>
 *
 * <p>A networked panel becomes an ordinary {@link DockPanelRef}, so it splits, drags between groups,
 * tears out into a {@code DockWindow} and is written into the session like every other tab. None of
 * that touches the session on the wire: a torn-out tab moves an <em>element</em> between frames, and
 * the window it belongs to is a thing on a connection.</p>
 *
 * <h3>Restore asks the server; it does not rebuild the tree</h3>
 *
 * <p>A described tree is the server's and cannot be reconstructed from a layout file — the layout
 * holds an identity, never content. So a restored tab shows a placeholder and asks, through
 * {@code ui/requestOpen}, for the window back by key; the tree arrives through the ordinary mount path
 * and replaces it. A refusal drops the tab, because a tab that can never be filled is worse than one
 * that is gone: the machine was broken, the block was mined, the player walked away.</p>
 *
 * <p>Which is why the {@linkplain #manifest() manifest} is persisted beside the layout. A ref names a
 * type id, and on the next launch nothing has opened that type yet — so without a record of what its
 * descriptor looked like, the dock decodes a ref it cannot build and drops it.</p>
 */
public final class NetworkedPanels implements WindowMount {

    /** The ref state key holding the window's own key — what a restore asks the server for. */
    public static final String WINDOW_KEY = "windowKey";

    private final Workbench workbench;

    /** Where a {@link Presentation#WINDOW} goes, and where anything unroutable falls back to. */
    @Nullable
    private final WindowMount desktop;

    /** What each networked type looks like as a panel. Persisted, so a restore can rebuild it. */
    private final Map<String, Entry> known = new LinkedHashMap<>();

    /** The live window behind each open panel, by {@link #panelKey}. */
    private final Map<String, ClientWindowContext> live = new LinkedHashMap<>();

    /** How a networked type presents itself. Everything here is string-shaped, so it persists. */
    public record Entry(String typeId, String title, String presentation) {
    }

    public NetworkedPanels(Workbench workbench, @Nullable WindowMount desktop) {
        this.workbench = Objects.requireNonNull(workbench, "workbench");
        this.desktop = desktop;
    }

    // ── Routing ─────────────────────────────────────────────────────────────────────────────────

    @Override
    public MountedWindow mount(ClientWindowContext context) {
        Presentation presentation = context.presentation();
        switch (presentation.kind()) {
            case EDITOR_TAB:
                return mountAsTab(context, presentation);
            case TOOL_WINDOW:
                return mountAsToolWindow(context, presentation);
            default:
                return mountOnDesktop(context);
        }
    }

    private MountedWindow mountOnDesktop(ClientWindowContext context) {
        if (desktop == null) {
            throw new IllegalStateException("no desktop to mount <" + context.type() + "> onto");
        }
        return desktop.mount(context);
    }

    private MountedWindow mountAsTab(ClientWindowContext context, Presentation presentation) {
        remember(context, presentation);
        String panel = panelKey(context.type(), context.key());
        live.put(panel, context);
        DockPanelRef ref = refFor(context.type(), context.key(), titleOf(context));
        // OPENED THROUGH THE DOCK, never by reaching into a group: a restore has already put this ref in
        // the layout, and open() finds it there and activates it rather than making a second tab.
        workbench.open(DockInput.of(ref));
        workbench.dock().requestRebuild();
        return new Mounted(panel, ref, context, false);
    }

    private MountedWindow mountAsToolWindow(ClientWindowContext context, Presentation presentation) {
        remember(context, presentation);
        // A tool window is a SINGLETON of its type, so its ref carries no key and neither does its entry
        // here: two windows of one type would be one panel, and the rail has one button. Keyed by the
        // window's own key instead, the container built for the rail would look for a panel under a name
        // nothing had stored, find nothing, and ask the server for a window that was already on screen.
        String panel = panelKey(context.type(), null);
        live.put(panel, context);
        DockPanelRef ref = new DockPanelRef(context.type());
        workbench.showPanel(context.type());
        return new Mounted(panel, ref, context, true);
    }

    /**
     * Registers this type's descriptor if it is new, and records what it looks like.
     *
     * <p>Lazily, on first sight, because nothing knows a mod's networked types until one opens — the
     * whole point of a description that names its own class. The record is what makes a restore
     * possible before that has happened again.</p>
     */
    private void remember(ClientWindowContext context, Presentation presentation) {
        Entry entry = new Entry(context.type(), titleOf(context), presentation.encode());
        if (known.containsKey(entry.typeId())) return;
        known.put(entry.typeId(), entry);
        registerDescriptor(entry);
    }

    private void registerDescriptor(Entry entry) {
        Presentation where = Presentation.parse(entry.presentation());
        DockPanelDescriptor descriptor;
        if (where.kind() == Presentation.Kind.TOOL_WINDOW) {
            descriptor = DockPanelDescriptor.singleton(entry.typeId(), entry.title())
                    .region(regionOf(where.region()))
                    .side(RegionSide.PRIMARY);
        } else {
            descriptor = DockPanelDescriptor.document(entry.typeId(), entry.title());
        }
        workbench.panels().register(descriptor, this::contentFor);
    }

    /** A region name off the wire. Anything unrecognised lands on the bottom strip. */
    private static DockRegion regionOf(@Nullable String named) {
        if (named == null) return DockRegion.PANEL;
        switch (named.toLowerCase(Locale.ROOT)) {
            case "sidebar":
                return DockRegion.SIDEBAR;
            case "auxiliary":
                return DockRegion.AUXILIARY;
            case "editor":
                return DockRegion.EDITOR;
            default:
                return DockRegion.PANEL;
        }
    }

    /**
     * What a panel of this ref shows: the live tree, or a placeholder plus a request for it.
     *
     * <p>The dock calls this whenever it builds — on the first open, on a split, on a drag, and on a
     * restore. Only the last of those has nothing live behind it.</p>
     */
    private UIElement contentFor(DockPanelRef ref) {
        String typeId = ref.typeId();
        String windowKey = ref.state(WINDOW_KEY, "");
        ClientWindowContext context = live.get(panelKey(typeId, windowKey.isEmpty() ? null : windowKey));
        if (context != null) return context.root();
        askServerFor(ref, typeId, windowKey);
        return loading();
    }

    /**
     * Asks the server for a window a restored ref names.
     *
     * <p>The key travels as the argument, which the server's own resolver re-derives from — a claim,
     * exactly as every other {@code requestOpen} argument is. It is not a handle to a window: a client
     * naming a key does not get the window, it gets whatever that server decides the key means now.</p>
     */
    private void askServerFor(DockPanelRef ref, String typeId, String windowKey) {
        if (asking.contains(typeId + "\0" + windowKey)) return;
        asking.add(typeId + "\0" + windowKey);
        StateMap<Object> args = new StateMap<>(PlainOps.INSTANCE);
        if (!windowKey.isEmpty()) args.putString(UiMethods.KEY, windowKey);
        ClientWindows.requestOpen(typeId, args, granted -> {
            asking.remove(typeId + "\0" + windowKey);
            if (granted) return;
            // A TAB THAT CAN NEVER BE FILLED is worse than one that is gone. The machine was broken, the
            // block was mined, the player is somewhere else -- and a placeholder with no explanation is
            // the shape that gets reported as the editor being broken.
            CrystalGuiCore.LOGGER.info("The server refused to reopen <{}>; dropping its tab", typeId);
            dropPanel(ref);
        });
    }

    /** Requests in flight, so a rebuild between the ask and the answer does not ask twice. */
    private final Set<String> asking = new LinkedHashSet<>();

    private void dropPanel(DockPanelRef ref) {
        // NOT WHILE SOMETHING IS LIVE BEHIND IT. A refusal answers the ASK, and by the time it lands the
        // window may have arrived some other way -- the server opening it on its own, a second tab of the
        // same type. Dropping then takes away a panel that is on screen and working.
        String windowKey = ref.state(WINDOW_KEY, "");
        if (live.containsKey(panelKey(ref.typeId(), windowKey.isEmpty() ? null : windowKey))) return;
        if (workbench.dock().layout().closePanel(ref)) workbench.dock().requestRebuild();
        workbench.hidePanel(ref.typeId());
    }

    /** What a tab shows while its window is on its way. */
    private static UIElement loading() {
        UIElement placeholder = new UIElement();
        placeholder.addClass("__networked-loading__");
        return placeholder;
    }

    private static String titleOf(ClientWindowContext context) {
        String named = context.title();
        if (!named.isEmpty()) return named;
        return context.type().isEmpty() ? "Panel" : context.type();
    }

    /** The ref a networked editor tab is, and the one a restore rebuilds. */
    public static DockPanelRef refFor(String typeId, @Nullable String windowKey, String title) {
        DockPanelRef ref = new DockPanelRef(typeId).withState(DockPanelRef.TITLE, title);
        return windowKey == null || windowKey.isEmpty() ? ref : ref.withState(WINDOW_KEY, windowKey);
    }

    /** A window's identity here: its type, and its key when it has one. */
    private static String panelKey(String typeId, @Nullable String windowKey) {
        return windowKey == null || windowKey.isEmpty() ? typeId : typeId + "#" + windowKey;
    }

    // ── The manifest ────────────────────────────────────────────────────────────────────────────

    /**
     * What has to be re-registered before a saved layout is decoded.
     *
     * <p>Every networked type this workbench has seen, in the order it saw them. Persisted by
     * {@link WorkbenchSession}; a ref whose type is not in it is dropped at read, because the dock has
     * no descriptor to build it from and inventing one would put an unlabelled, unplaceable tab on
     * screen.</p>
     */
    public List<Entry> manifest() {
        return new ArrayList<>(known.values());
    }

    /** Re-registers a saved manifest. Called before the layout is decoded, never after. */
    public void restoreManifest(List<Entry> entries) {
        for (Entry entry : entries) {
            if (entry == null || known.containsKey(entry.typeId())) continue;
            known.put(entry.typeId(), entry);
            registerDescriptor(entry);
        }
    }

    /** Whether a window of this type is on screen. For tests and for a host asking. */
    public boolean isLive(String typeId, @Nullable String windowKey) {
        return live.containsKey(panelKey(typeId, windowKey));
    }

    // ── One panel ───────────────────────────────────────────────────────────────────────────────

    /** A networked panel on the workbench, and which side is ending it. */
    private final class Mounted implements MountedWindow {

        private final String panel;
        private final DockPanelRef ref;
        private final ClientWindowContext context;
        private final boolean toolWindow;
        private boolean ended;

        Mounted(String panel, DockPanelRef ref, ClientWindowContext context, boolean toolWindow) {
            this.panel = panel;
            this.ref = ref;
            this.context = context;
            this.toolWindow = toolWindow;
        }

        @Override
        public void closedByServer(String reason) {
            if (ended) return;
            ended = true;
            live.remove(panel);
            if (toolWindow) workbench.hidePanel(ref.typeId());
            else dropPanel(ref);
        }

        @Override
        public void focus() {
            if (ended) return;
            if (toolWindow) workbench.showPanel(ref.typeId());
            else workbench.open(DockInput.of(ref));
        }

        @Override
        public void contentReplaced(UIElement newRoot) {
            if (ended) return;
            // The factory reads `live`, which already holds the fresh tree -- so what is needed is for it
            // to be ASKED again. The dock rebuilds on the next frame; a region caches its occupant, so a
            // tool window is taken down and put back, which is one frame of a panel that is not there and
            // is the honest cost of a tree that was replaced wholesale.
            if (toolWindow) {
                workbench.hidePanel(ref.typeId());
                workbench.showPanel(ref.typeId());
            } else {
                workbench.dock().requestRebuild();
            }
        }
    }
}
