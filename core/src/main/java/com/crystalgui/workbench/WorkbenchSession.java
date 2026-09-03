package com.crystalgui.workbench;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.document.DocumentViewState;
import com.crystalgui.document.FileDocument;
import com.crystalgui.fs.CgPath;
import com.crystalgui.core.storage.ConfigStorage;
import com.crystalgui.serialization.JsonOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.workbench.dock.layout.DockLeaf;
import com.crystalgui.workbench.dock.DockWindow;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.workbench.dock.panel.DockPanelDescriptor;
import com.crystalgui.workbench.dock.panel.DockPanelKind;
import com.crystalgui.workbench.dock.layout.DockPanelRef;
import com.crystalgui.workbench.region.DockRegion;
import com.crystalgui.ui.dom.SessionState;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.workbench.dock.layout.DockLayout;
import com.crystalgui.workbench.dock.layout.DockLayoutCodec;

import com.crystalgui.workbench.toolwindow.ToolWindowLayout;
import com.crystalgui.workbench.toolwindow.ToolWindowState;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * Closing a project and reopening it where you left it — {@code .idea/workspace.xml}'s job.
 *
 * <pre>
 * { version, dock: {…}, active: "proj:a.txt", expanded: ["proj:src"], files: [{path, view:{…}}] }
 * </pre>
 *
 * <h3>Outside the project, keyed by project id</h3>
 *
 * <p>Session state is <b>private and does not travel</b>: nobody wants somebody else's tab arrangement
 * when they open a shared project, which is why {@code workspace.xml} sits in IntelliJ's default
 * {@code .gitignore} and why VS Code keeps this in its own storage rather than in {@code .vscode/}. It is
 * also why it cannot live in the project here at all — a project may be {@code READONLY}, and you still
 * want to reopen it where you left off.</p>
 *
 * <p>Keyed by <b>project id</b>, which {@code CgPath} deliberately keeps independent of the directory —
 * <i>"moving a project's folder must not invalidate every reference to it"</i>. So moving a project keeps
 * its session, which VS Code cannot manage: it keys workspace storage by folder path and loses your open
 * editors when a folder moves.</p>
 *
 * <p>Shared <em>settings</em> take the opposite route and live in the project, because those must travel
 * when it is copied or committed. Two requirements, two homes; see {@code SettingsLayer}.</p>
 *
 * <h3>JSON, concretely, unlike everything else here</h3>
 *
 * <p>{@code DockLayoutCodec} is generic over {@code DynamicOps} because a layout may end up in a document
 * or on a wire. A session record only ever goes to one place — a config file on this machine — and making
 * it generic would push a type parameter through the workbench so that a second format could be used by
 * nobody. The nested layout is still produced by the generic codec and embedded whole.</p>
 *
 * <h3>Two restores that cannot be done at once, and that is the interesting part</h3>
 *
 * <p>Both halves of a restore race something asynchronous, so both are <b>parked and retried</b> rather
 * than applied once and hoped for:</p>
 *
 * <ul>
 *   <li><b>View state</b> — a caret, a scroll offset, a fold set — is applied when the file's content
 *       lands ({@link Workbench#onDidOpenDocument}), never when the panel is built. A caret restored into a
 *       document that is still empty clamps to zero, and the symptom is a caret that looks like it was
 *       never saved.</li>
 *   <li><b>Tree expansion</b> waits for listings. A folder cannot be expanded before its parent's listing
 *       reveals it exists, so {@link #tick()} re-attempts what is left each frame and gives up only once a
 *       whole pass makes no progress and nothing is still in flight.</li>
 * </ul>
 *
 * <p>Restoring the dock does <em>not</em> need reopening the files bolted on: a leaf's
 * {@code DockPanelRef} already carries the path, and the workbench's panel factory reads a file it has not
 * read yet. Storing an open-file list beside the layout would be a second copy of the same fact, and the
 * two would disagree the first time a tab was closed while a restore was pending.</p>
 */
public final class WorkbenchSession {

    /**
     * Bump when the shape changes meaning. An unknown version is discarded, never guessed at.
     *
     * <p><b>2</b> added {@link #KEY_TOOL_WINDOWS}.</p>
     *
     * <p><b>3</b> is not a shape change at all — it is a <em>meaning</em> change, and that is the harder
     * case to spot. {@code CrystalEditor}'s {@code shadersource} panel went from a singleton view that
     * followed the front graph to a document keyed by a graph's path, so a record written at 2 holds a
     * pathless {@code shadersource} entry that decodes perfectly and means nothing: a tab titled
     * {@code compiled_graph.shader} with no graph behind it. Nothing about the record is malformed, which
     * is exactly why the version has to say so.</p>
     *
     * <p>An old record is discarded rather than migrated, which is right here and would be wrong for
     * settings: the cost is one arrangement that the very next save rewrites, against panels landing
     * somewhere nobody asked for.</p>
     *
     * <h3>4 — the Parts model, and why it is also a discard</h3>
     *
     * <p><b>Decided in advance rather than discovered</b>, because this is the version where the question
     * is genuinely arguable and skipping it would mean answering it by accident. See {@code plan.md} §23
     * F4.</p>
     *
     * <p>Parts adds four persisted facts — region visibility, region size, container membership, and view
     * order within a container — and takes tool windows <em>out of the dock tree</em>. So a record written
     * at 3 stores a tool window as a position inside a layout tree, and a reader at 4 needs the region it
     * belongs to.</p>
     *
     * <p><b>A migration is refused, and not for effort.</b> It cannot be faithful: a tree position does not
     * carry a region, so translating one would mean inferring "this leaf was against the left wall,
     * therefore sidebar" — and that inference is wrong for every tool window the user had nested
     * mid-tree, which is the arrangement the four-tier restoration heuristic exists to support. Guessing
     * is precisely what this version field exists to refuse; a migration here would be the guess wearing a
     * function name.</p>
     *
     * <p>The cost is one lost arrangement per user, once, which the next save rewrites. Stated here so it
     * reads as a decision rather than as a regression when somebody's layout comes back default.</p>
     *
     * <h3>5 — the Inspector left the dock, which is a MEANING change</h3>
     *
     * <p>The same shape as 3, and missed for the same reason. A record written at 4 — after regions landed
     * but before the Inspector moved into one — holds an {@code inspector} leaf in the dock tree. It
     * decodes perfectly and is wrong: the panel factory hands back the <em>same element</em> the auxiliary
     * region is showing, so whichever host re-parents last wins and the other is left with an empty box.
     * On screen that was a working inspector wedged between the splits and an empty one on the wall.</p>
     *
     * <p>{@code stripToolWindows} makes it unreachable regardless of version, which is the durable half —
     * a layout is also something one user can hand to another. The bump is what clears the records that
     * already exist.</p>
     *
     * <h3>6 — the anchor became a region and a side</h3>
     *
     * <p>A genuine shape change, and the first one since 2. {@code ToolWindowState} stored a
     * {@code DockDropZone} anchor and <em>derived</em> its region from it; it now stores
     * {@link DockRegion} and {@link com.crystalgui.ui.elements.dock.RegionSide} outright, which is what
     * lets a tool window say which <b>half</b> of a region it is in — IntelliJ's {@code isSplit}, and the
     * one field the two rails derive their contents from.</p>
     *
     * <p>Discarded rather than migrated, and this time it genuinely could have been migrated: an anchor
     * maps onto a region cleanly, which is what {@code DockRegion.ofWall} did. The reason not to is that
     * a record written at 5 has <b>no side at all</b>, so a migration would have to invent one — and a
     * silently invented {@code PRIMARY} for a panel the user had put in the bottom-right is a layout that
     * comes back subtly wrong rather than obviously default. One lost arrangement, once, is cheaper.</p>
     */
    public static final int VERSION = 6;

    private static final String KEY_VERSION = "version";
    private static final String KEY_DOCK = "dock";
    private static final String KEY_TOOL_WINDOWS = "toolWindows";
    private static final String KEY_ACTIVE = "active";
    private static final String KEY_EXPANDED = "expanded";
    private static final String KEY_FILES = "files";
    /**
     * Widget state that outlives the run -- {@link SessionState}, keyed by element id.
     *
     * <p><b>No version bump.</b> The key is purely additive: a record written before it simply has no
     * {@code widgets} array, and every read below tolerates that. Bumping would DISCARD every existing
     * arrangement -- see the version block above -- which is far too much to pay for one optional field.</p>
     */
    private static final String KEY_WIDGETS = "widgets";
    private static final String KEY_ID = "id";
    private static final String KEY_PATH = "path";
    private static final String KEY_VIEW = "view";

    /**
     * Torn-out editor windows — the dock trees that are <b>not</b> under {@link #KEY_DOCK} (W9, persisted
     * at W12).
     *
     * <h3>They were persisted by nothing at all, and did not even come back docked</h3>
     *
     * <p>Two independent reasons, either of which alone would have done it. {@code DockLayout.tearOut}
     * <em>removes</em> the leaf from the layout, and {@code KEY_DOCK} is that layout — so the panel was
     * in no project record. And {@code DockArea.tearOutToWindow} builds a {@code DockWindow} with no
     * {@code WindowFrame.key()}, which {@code Desktop.applyPersistedGeometry} skips — so it was in no
     * desktop record either.</p>
     *
     * <p>What made it read as an editor bug rather than a persistence gap is that the <em>file</em>
     * survived: {@code openPaths()} reads {@code OpenDocuments}, which is document-level, so the caret
     * and scroll were saved perfectly with no tab left to land in.</p>
     *
     * <h3>Here rather than in the desktop record, and that is not arbitrary</h3>
     *
     * <p>The desktop's record is per <b>host</b> and holds geometry against a key. A torn-out window is
     * per <b>project</b> — it holds that project's documents — and what has to survive is not only where
     * it was but <em>what was in it</em>, which is a dock tree. The same argument that keeps a tool
     * window's placement here rather than there.</p>
     *
     * <p><b>No version bump</b>, for the reason {@link #KEY_WIDGETS} gives: the key is purely additive.
     * A record written before it has no {@code windows} array, which decodes to "no torn-out windows" —
     * and that is exactly what was true of those sessions, since nothing was recording them.</p>
     */
    private static final String KEY_WINDOWS = "windows";
    private static final String KEY_TITLE = "title";
    private static final String KEY_LEFT = "left";
    private static final String KEY_TOP = "top";
    private static final String KEY_WIDTH = "width";
    private static final String KEY_HEIGHT = "height";

    /**
     * How many <b>listings</b> a pending expansion is retried across before it is written off.
     *
     * <p>Was frames, when {@link #tick()} ran from a per-frame ticker. It is now driven by
     * {@code WorkspaceTreeSource.onDidLoadListing}, so each attempt happens at a moment when the answer
     * may actually have changed rather than sixty times a second regardless.</p>
     *
     * <p>The budget therefore only counts down while the workspace is genuinely still answering. A folder
     * that will <em>never</em> list — because it was deleted since the session was recorded — no longer
     * decrements anything and simply lingers in the pending set. That is harmless in a way it was not
     * before: the whole reason for a give-up was that the retry ran a set difference every frame for the
     * rest of the session, and nothing runs per frame any more.</p>
     */
    private static final int EXPANSION_ATTEMPTS = 600;

    private final Workbench workbench;
    private final ConfigStorage storage;

    /** View state read from a record, waiting for its file's content to arrive. */
    private final Map<CgPath, JsonElement> pendingViewState = new LinkedHashMap<>();

    /** Folders a record says were open, waiting for the listing that reveals they exist. */
    private final Set<CgPath> pendingExpansion = new LinkedHashSet<>();

    private int attemptsLeft;

    @Nullable
    private CgPath pendingActive;

    public WorkbenchSession(Workbench workbench, ConfigStorage storage) {
        this.workbench = workbench;
        this.storage = storage;
        workbench.onDidOpenDocument.connect(this::applyViewState);
        // The second half of the torn-out-window restore. A record read before the workbench had a
        // UIDocument parked its windows; this is the moment there is somewhere to open them.
        // @see #reopenTornOutWindows
        workbench.onDidJoinWindow.connect(this::reopenTornOutWindows);
        // AT CONSTRUCTION, not on a successful restore. The store is what reads a widget back as it leaves
        // the tree, so a first run -- which has no record to restore and would never have installed one --
        // would lose everything closed before the first save. Idempotent, and re-asserted at both entry
        // points below for a workbench attached after this ran.
        installWidgetState();
    }

    /**
     * Remembered widget state, installed on the window so {@code registerElement} can hand it out.
     *
     * <p>Installed lazily rather than in the constructor: a workbench is built before it is attached, so
     * there is no window yet at that point.</p>
     */
    private final SessionState<JsonElement> widgetState = new SessionState<>(JsonOps.INSTANCE);

    private void installWidgetState() {
        UIDocument window = workbench.document();
        if (window != null) window.setSessionState(widgetState);
    }

    /** {@code session.harness.scratch.json} — flat, so {@link ConfigStorage#list} can find them to prune. */
    public static String fileNameFor(String projectId) {
        StringBuilder safe = new StringBuilder("session.");
        for (char c : projectId.toCharArray()) {
            // Anything that could steer a write out of the config directory becomes an underscore. A
            // project id is validated on construction, but this composes a FILENAME from it, and that is
            // a different question from whether it is a legal id.
            safe.append(Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_' ? c : '_');
        }
        return safe.append(".json").toString().toLowerCase(Locale.ROOT);
    }

    // ── Saving ──────────────────────────────────────────────────────────────────────────────────

    /** Writes the session record for {@code projectId}. */
    public void save(String projectId, float viewportWidth, float viewportHeight) {
        if (!storage.isWritable()) return;
        storage.write(fileNameFor(projectId), toJson(viewportWidth, viewportHeight));
    }

    /** The record as text, without writing it — what a test asserts on. */
    public String toJson(float viewportWidth, float viewportHeight) {
        installWidgetState();
        StateMap<JsonElement> out = new StateMap<>(JsonOps.INSTANCE);
        out.putInt(KEY_VERSION, VERSION);

        // Read the divider positions back out of the widgets first, or the record keeps the weights the
        // layout was BUILT with rather than the ones on screen.
        workbench.dock().pullWeightsIntoLayout();
        out.putRaw(KEY_DOCK, DockLayoutCodec.encode(workbench.dock().layout(), JsonOps.INSTANCE,
                viewportWidth, viewportHeight));

        // BESIDE the dock, never inside it -- see ToolWindowLayout. The dock records what is on screen; a
        // closed tool window has left it entirely, and this is the only place its placement survives. It is
        // captured here rather than continuously because hidePanel already writes a placement the moment a
        // panel closes; what is left is the panels that are still OPEN, whose current geometry is only
        // knowable now.
        captureOpenToolWindows();
        workbench.toolWindows().encodeInto(out, KEY_TOOL_WINDOWS);

        // BESIDE the dock for the same reason, one step further out: a torn-out window's tree is not in
        // the main layout at all -- tearOut removed it. @see #KEY_WINDOWS
        out.putList(KEY_WINDOWS, tornOutWindows(), WorkbenchSession::writeDockWindow);

        // Every opted-in widget in the tree, over the top of what came in. Reading the LIVE elements is
        // what makes a dragged divider survive; keeping the entries nobody built is what stops a session
        // that never opened a panel from writing that panel's state away.
        widgetState.capture(workbench);
        List<String> ids = new ArrayList<>(widgetState.entries().keySet());
        out.putList(KEY_WIDGETS, ids, (entry, id) -> {
            entry.putString(KEY_ID, id);
            entry.putRaw(KEY_VIEW, widgetState.entries().get(id));
        });

        CgPath active = workbench.activeFilePath();
        if (active != null) out.putString(KEY_ACTIVE, active.toString());

        List<CgPath> expanded = workbench.fileTree().treeView().expandedItems();
        out.putList(KEY_EXPANDED, expanded, (entry, path) -> entry.putString(KEY_PATH, path.toString()));

        // ASKED OF THE DOCUMENTS THAT EXIST, and only those: `documentFor` CREATES on demand, so walking
        // anything wider here would build every editor in the session just to save it -- which is the
        // cost lazy tabs exist to avoid, paid at the one moment nobody is watching for it.
        Map<CgPath, JsonElement> files = new LinkedHashMap<>();
        for (CgPath path : workbench.openPaths()) {
            if (!(workbench.documentFor(path) instanceof DocumentViewState stateful)) continue;
            StateMap<JsonElement> view = new StateMap<>(JsonOps.INSTANCE);
            stateful.writeViewState(view);
            files.put(path, view.encode());
        }
        // AND THEN WHAT WE ARE STILL HOLDING FOR A TAB NOBODY OPENED.
        //
        // A restored tab is a title until it is activated, so a session with five files open comes back
        // with one live document and four that have never been built -- and `openPaths` reports the
        // documents, correctly, because that is what it is asked by everything else. Saving from that
        // alone silently drops the other four files' carets and scroll positions on the first save after
        // any restart, and it is invisible: the tabs all come back, so nothing looks lost until you
        // notice a file you had not touched now opens at the top.
        //
        // `pendingViewState` is exactly the right answer and already exists -- it holds what a record
        // said, keyed by path, and an entry is REMOVED the moment its document arrives and consumes it.
        // So whatever is still in there is precisely the set that was restored and never looked at.
        // putIfAbsent rather than put: a document that has since been built has the newer word.
        for (Map.Entry<CgPath, JsonElement> untouched : pendingViewState.entrySet()) {
            files.putIfAbsent(untouched.getKey(), untouched.getValue());
        }
        out.putList(KEY_FILES, new ArrayList<>(files.keySet()), (entry, path) -> {
            entry.putString(KEY_PATH, path.toString());
            entry.putRaw(KEY_VIEW, files.get(path));
        });

        return new GsonBuilder().setPrettyPrinting().create().toJson(out.encode()) + "\n";
    }

    /**
     * Records where every <em>currently open</em> tool window is, so the record describes the screen.
     *
     * <h3>Why saving needs this and hiding does not</h3>
     *
     * <p>{@link Workbench#hidePanel} captures a placement at the moment a panel closes, which is the only
     * moment its position is still readable. A panel that is <b>open</b> when the session is saved has
     * never been through that path, so its stored placement is whatever it had when it was last hidden —
     * potentially several drags ago, or nothing at all. Walking the open ones at save time is what makes a
     * restored session match the screen rather than the history.</p>
     *
     * <p>Runs after {@code pullWeightsIntoLayout}, so the weights recorded are the ones the dividers are
     * actually at rather than the ones the layout was built with.</p>
     */
    // ── Torn-out windows ────────────────────────────────────────────────────────────────────────

    /**
     * Every torn-out editor window belonging to <b>this</b> workbench, in the desktop's open order.
     *
     * <p>Matched by <b>panel-registry identity</b>, not by walking the tree from here: a
     * {@code DockWindow} is a top-level desktop citizen and is not under the workbench at all, which is
     * the whole point of it. What ties it back is that its dock builds content from this workbench's
     * registry — so anything it holds is this project's, and anything holding another registry is
     * somebody else's window that happens to share a desktop.</p>
     */
    private List<DockWindow> tornOutWindows() {
        UIDocument window = workbench.document();
        // desktopIfPresent, never desktop(): the latter BUILDS one, and a save must not create a
        // compositor in a window that never had a window open in it.
        Desktop desktop = Desktop.ifPresent(window);
        if (desktop == null) return List.of();
        List<DockWindow> out = new ArrayList<>();
        for (WindowFrame frame : desktop.registry().windows()) {
            if (frame instanceof DockWindow dock && dock.area().registry() == workbench.panels()) {
                out.add(dock);
            }
        }
        return out;
    }

    /**
     * One torn-out window: where it was, and the whole dock tree inside it.
     *
     * <p>Geometry follows {@code DesktopSession}'s rules exactly, because they are properties of a
     * {@code WindowFrame} rather than of that record: position is the <b>intent</b> pair
     * ({@code getWantedLeft}, never the clamped {@code getX()}, or every launch pulls the window further
     * in) and size is {@code recordedWidth}, which answers from the last measured box so a hidden window
     * does not record a zero.</p>
     *
     * <p>The title is stored rather than recomputed from the layout. It is whatever panel the window was
     * torn out with and does not track what is in it afterwards, so there is nothing to derive it from —
     * picking "the first panel" would rename the window on every restore.</p>
     */
    private static void writeDockWindow(StateMap<JsonElement> out, DockWindow frame) {
        out.putString(KEY_TITLE, frame.getTitle());
        out.putFloat(KEY_LEFT, frame.getWantedLeft());
        out.putFloat(KEY_TOP, frame.getWantedTop());
        out.putFloat(KEY_WIDTH, frame.recordedWidth());
        out.putFloat(KEY_HEIGHT, frame.recordedHeight());
        // The window's OWN box as the viewport, not the workbench's: this tree lives inside this window,
        // so that is the box its weights were resolved against.
        out.putRaw(KEY_DOCK, DockLayoutCodec.encode(frame.area().layout(), JsonOps.INSTANCE,
                frame.recordedWidth(), frame.recordedHeight()));
    }

    /** A torn-out window as read back, before anything has been opened for it. */
    private record TornOutWindow(String title, float left, float top, float width, float height,
                                 DockLayout layout) {

        /** @see com.crystalgui.ui.elements.desktop.DesktopSession.Placement#isUsable() */
        boolean isUsable() {
            return width > 0f && height > 0f;
        }
    }

    /** Windows the record named that have not been opened yet. @see #reopenTornOutWindows */
    private final List<TornOutWindow> pendingWindows = new ArrayList<>();

    private void readTornOutWindows(StateMap<JsonElement> in) {
        pendingWindows.clear();
        for (TornOutWindow parsed : in.getList(KEY_WINDOWS, this::readTornOutWindow)) {
            if (parsed != null && parsed.isUsable()) pendingWindows.add(parsed);
        }
    }

    @Nullable
    private TornOutWindow readTornOutWindow(StateMap<JsonElement> entry) {
        JsonElement dock = entry.getRaw(KEY_DOCK);
        if (dock == null) return null;
        DockLayout layout = DockLayoutCodec.decode(dock, JsonOps.INSTANCE, workbench.panels());
        if (layout == null) return null;
        // A TORN-OUT WINDOW HOLDS DOCUMENTS, so the same strip the main dock gets: a record written while
        // a tool window could still be nested in a tree would otherwise restore one into a window whose
        // registry hands back the very element the region is showing.
        stripToolWindows(layout);
        if (layout.leaves().stream().allMatch(DockLeaf::isEmpty)) return null;
        return new TornOutWindow(
                entry.getString(KEY_TITLE, ""),
                entry.getFloat(KEY_LEFT, 0f), entry.getFloat(KEY_TOP, 0f),
                entry.getFloat(KEY_WIDTH, 0f), entry.getFloat(KEY_HEIGHT, 0f),
                layout);
    }

    /**
     * Opens the torn-out windows the record named, once there is a {@code UIDocument} to open them into.
     *
     * <p><b>Deferred, because a restore legitimately runs before the tree has a window.</b> A host may
     * restore on its first frame — the harness does, above its own {@code uiWindow.init} — and
     * {@code openWindow} needs a desktop. The docked half needs no window and succeeds, so a failure
     * here is <em>ordered</em> rather than total, which is exactly what made the tool-window version of
     * this look like a partial bug. Driven by {@link Workbench#onDidJoinWindow}, the same one-frame
     * deferral the tool windows ride.</p>
     *
     * <p>Idempotent: the list is drained, so a second window-join opens nothing.</p>
     */
    public void reopenTornOutWindows() {
        if (pendingWindows.isEmpty()) return;
        UIDocument window = workbench.document();
        if (window == null) return;
        List<TornOutWindow> opening = new ArrayList<>(pendingWindows);
        pendingWindows.clear();
        for (TornOutWindow record : opening) {
            DockWindow frame = new DockWindow(workbench.panels(), record.layout(), record.title());
            // BEFORE the open, so it appears at the size and place it is meant to be rather than flying
            // in at a default and jumping -- Desktop.addWindow's own note, from the other side.
            frame.resizeTo(record.width(), record.height());
            frame.moveTo(record.left(), record.top());
            Desktop.of(window).addWindow(frame);
        }
    }

    /** Removes any panel the dock should never hold — anything whose kind is not a document. */
    private void stripToolWindows(DockLayout layout) {
        for (DockLeaf leaf : new ArrayList<>(layout.leaves())) {
            for (DockPanelRef panel : new ArrayList<>(leaf.panels())) {
                DockPanelDescriptor descriptor = workbench.panels().descriptor(panel.typeId());
                if (descriptor != null && descriptor.kind() != DockPanelKind.DOCUMENT) {
                    layout.closePanel(panel);
                }
            }
        }
    }

    private void captureOpenToolWindows() {
        // EVERY placement, not just the ones on screen, and visibility DERIVED from the hosts rather than
        // trusted from the record. Walking only what is showing left two problems in the file:
        //
        //   * a tool window displaced from a half kept `visible: true`, so several claimed the same slot
        //     and the restore showed them in turn, each undoing the last;
        //   * a region's share was written per tool window at whatever moment each was last touched, so
        //     one region carried several different weights and the restore applied them in list order --
        //     the size that came back was simply the last entry's.
        //
        // A region's size is a property of the REGION. Writing the current one onto every member makes the
        // copies agree, which is what stops the restore order from mattering.
        for (ToolWindowState state : new ArrayList<>(workbench.toolWindows().all())) {
            DockRegion region = state.region();
            if (region == DockRegion.EDITOR) continue;
            // ASKED OF THE MANAGER, never derived here. "Is this on screen" already has one correct
            // answer and this used to be a second, worse one: `host.showing(state.side())` can only see
            // a DOCKED panel, so a tool window that was FLOATING or WINDOWED -- living in a frame rather
            // than in a region half -- recorded as closed every single time and never came back. Its
            // placement did survive, which is what made the bug read as "restore is broken" rather than
            // as one field: reopening the panel by hand put it back in exactly the right place.
            //
            // isPanelOpen's own javadoc warns against precisely this expression, for the split-region
            // reason it was written for. The session then reintroduced it.
            boolean showing = workbench.toolWindowManager().isPanelOpen(state.typeId());
            workbench.toolWindows().put(state
                    .withVisible(showing)
                    .withWeight(workbench.regions().weightOf(region))
                    .withSideWeight(workbench.regions().sideWeightOf(region)));
        }
    }

    // ── Restoring ───────────────────────────────────────────────────────────────────────────────

    /**
     * Restores the session for {@code projectId}.
     *
     * @return false when there is nothing stored or the record cannot be trusted — a normal outcome on
     *         first run, and the caller already needs a default layout for that case
     */
    public boolean restore(String projectId) {
        String json = storage.read(fileNameFor(projectId));
        return json != null && fromJson(json);
    }

    /** Restores from text. Returns false when the record is unusable, having changed nothing. */
    public boolean fromJson(String json) {
        StateMap<JsonElement> in;
        try {
            in = new StateMap<>(JsonOps.INSTANCE, new JsonParser().parse(json));
        } catch (RuntimeException malformed) {
            CrystalGuiCore.LOGGER.warn("Session record could not be read; opening with the defaults",
                    malformed);
            return false;
        }

        int version = in.getInt(KEY_VERSION, -1);
        if (version != VERSION) {
            // DockLayoutCodec's rule, and it is right here for the same reason and wrong for settings for
            // the opposite one: a session restored from a format that changed meaning puts panels in
            // places nobody asked for, while a discarded settings file silently resets every preference.
            CrystalGuiCore.LOGGER.info("Session record is version {} but this build reads {}; "
                    + "opening with the defaults", version, VERSION);
            return false;
        }

        JsonElement dock = in.getRaw(KEY_DOCK);
        if (dock == null) return false;
        DockLayout layout = DockLayoutCodec.decode(dock, JsonOps.INSTANCE, workbench.panels());
        if (layout == null) return false;

        // Parked BEFORE the layout is installed: installing it builds the panels, which starts the reads
        // whose completion is what applies the view state.
        pendingViewState.clear();
        for (Map.Entry<CgPath, JsonElement> entry : viewStatesIn(in).entrySet()) {
            pendingViewState.put(entry.getKey(), entry.getValue());
        }

        // TOOL WINDOWS ARE NOT DOCUMENTS, and a saved layout may still name one.
        //
        // A record written between the regions landing and the Inspector moving out of the dock holds an
        // `inspector` leaf, which restores into the tree while showPanel also puts it in the auxiliary
        // region -- and the registry hands back the SAME element, so whichever host re-parents last wins
        // and the other is left with an empty box. That is exactly what it looked like: a working
        // inspector wedged between the splits and an empty one on the wall.
        //
        // The version bump below clears the records that already exist; this makes it unreachable, which
        // matters because a layout is also a thing a user can hand to another user.
        stripToolWindows(layout);
        workbench.dock().setLayout(layout);

        // PARKED, not opened: a restore can legitimately run before the tree has a UIDocument to open into.
        // @see #reopenTornOutWindows
        readTornOutWindows(in);
        reopenTornOutWindows();

        // AFTER the layout is installed, so a placement naming a path describes the tree that is now
        // there. Absent is not a failure: a record written before tool-window placements existed still has
        // a usable dock, and every panel simply falls back to its descriptor's anchor the first time it is
        // hidden -- which is exactly the first-run behaviour.
        // BEFORE applyVisibility, which is what builds the containers -- a widget takes its state as it
        // joins the window, so the store has to be loaded and installed before anything is built. Nothing
        // else has to be timed: a widget built minutes later is served by the same path.
        restoreWidgetState(in);

        workbench.toolWindows().clear();
        for (ToolWindowState state
                : ToolWindowLayout.decodeFrom(in, KEY_TOOL_WINDOWS).ordered()) {
            workbench.toolWindows().put(state);
            // The region's share comes back with the placement, because a region's size is not derivable
            // from anything else once its occupant is hidden -- the same reason placement itself moved out
            // of the tree.
            //
            // FOR HIDDEN ONES TOO, and that is the whole point of storing it. Restricting this to visible
            // regions left a closed one's remembered width unknown to the model -- and applyVisibility
            // then calls hidePanel, which records the width it can currently see. That is the DEFAULT at
            // that moment, so reopening a region you had resized and closed always came back at 20%.
            workbench.regions().setWeight(state.region(), state.weight());
            workbench.regions().setSideWeight(state.region(), state.sideWeight());
        }
        // AND PUT THEM BACK. One lookup per entry: the whole of what replaced replaying drops into a tree
        // and hoping the branches they named still existed.
        workbench.toolWindowManager().applyVisibility();

        pendingExpansion.clear();
        for (String raw : in.getList(KEY_EXPANDED, map -> map.getString(KEY_PATH, ""))) {
            if (!raw.isEmpty()) pendingExpansion.add(parseOrNull(raw));
        }
        pendingExpansion.remove(null);
        attemptsLeft = EXPANSION_ATTEMPTS;

        String active = in.getString(KEY_ACTIVE, "");
        pendingActive = active.isEmpty() ? null : parseOrNull(active);
        return true;
    }

    /**
     * Loads the remembered widget states and installs the store on the window.
     *
     * <p>No application happens here and none can: the widgets are handed their state by
     * {@code UIDocument.registerElement} as they join the tree, which for a tool window is the first time
     * it is opened and for a widget inside one may be later still. That indirection IS the feature --
     * see {@link SessionState}, which explains why anything applied once at startup misses most of what
     * it is for.</p>
     */
    private void restoreWidgetState(StateMap<JsonElement> in) {
        Map<String, JsonElement> entries = new LinkedHashMap<>();
        JsonElement widgets = in.getRaw(KEY_WIDGETS);
        if (widgets != null && widgets.isJsonArray()) {
            for (JsonElement element : widgets.getAsJsonArray()) {
                StateMap<JsonElement> entry = new StateMap<>(JsonOps.INSTANCE, element);
                String id = entry.getString(KEY_ID, "");
                JsonElement view = entry.getRaw(KEY_VIEW);
                if (!id.isEmpty() && view != null) entries.put(id, view);
            }
        }
        entries.forEach(widgetState::put);
        installWidgetState();
    }

    /**
     * The per-file view states, read straight off the array.
     *
     * <p>Not through {@code getList}: that hands its mapper a {@link StateMap} and keeps only what the
     * mapper returns, and what is wanted here is the nested element <em>itself</em> — an opaque payload
     * belonging to whichever document kind wrote it, which this class must not interpret.</p>
     */
    private Map<CgPath, JsonElement> viewStatesIn(StateMap<JsonElement> in) {
        Map<CgPath, JsonElement> found = new LinkedHashMap<>();
        JsonElement files = in.getRaw(KEY_FILES);
        if (files == null || !files.isJsonArray()) return found;
        for (JsonElement element : files.getAsJsonArray()) {
            StateMap<JsonElement> entry = new StateMap<>(JsonOps.INSTANCE, element);
            CgPath path = parseOrNull(entry.getString(KEY_PATH, ""));
            JsonElement view = entry.getRaw(KEY_VIEW);
            if (path != null && view != null) found.put(path, view);
        }
        return found;
    }

    /**
     * Applies a parked view state now that the content is in.
     *
     * <p>Consumed rather than kept: re-applying on a later read would drag the caret back to where the
     * session record left it every time the file is reloaded from disk, undoing wherever the reader had
     * since moved to.</p>
     */
    private void applyViewState(CgPath path) {
        JsonElement view = pendingViewState.remove(path);
        if (view == null) return;
        FileDocument document = workbench.documentFor(path);
        if (!(document instanceof DocumentViewState stateful)) return;
        try {
            stateful.readViewState(new StateMap<>(JsonOps.INSTANCE, view));
        } catch (RuntimeException refused) {
            CrystalGuiCore.LOGGER.warn("Could not restore where {} was left; opening it at the top",
                    path, refused);
        }
    }

    /**
     * Re-attempts what could not be restored yet.
     *
     * <p>Call it when a listing arrives — {@code WorkspaceTreeSource.onDidLoadListing} — not once a
     * frame. It stays cheap and self-terminating either way, and is a no-op once nothing is pending.</p>
     *
     * @return whether anything is still pending
     */
    public boolean tick() {
        if (pendingExpansion.isEmpty() && pendingActive == null) return false;
        if (attemptsLeft-- <= 0) {
            // Written off rather than retried forever: a folder in the record may simply have been deleted
            // since, and an unbounded retry would run a set difference every frame for the whole session.
            pendingExpansion.clear();
            pendingActive = null;
            return false;
        }

        List<CgPath> remaining = new ArrayList<>(pendingExpansion);
        for (CgPath folder : remaining) {
            if (!workbench.fileTree().source().hasChildren(folder)) continue;
            workbench.fileTree().treeView().setExpanded(folder, true);
            pendingExpansion.remove(folder);
        }

        if (pendingExpansion.isEmpty() && pendingActive != null) {
            CgPath active = pendingActive;
            pendingActive = null;
            // Last, so the reveal it triggers lands on a tree that has finished opening itself.
            workbench.openFile(active);
        }
        return !pendingExpansion.isEmpty() || pendingActive != null;
    }

    /** True while a restore is still waiting on listings or reads. */
    public boolean isRestoring() {
        return !pendingExpansion.isEmpty() || !pendingViewState.isEmpty() || pendingActive != null;
    }

    @Nullable
    private static CgPath parseOrNull(String raw) {
        try {
            return raw.isEmpty() ? null : CgPath.parse(raw);
        } catch (RuntimeException notAPath) {
            // A record naming a project that no longer exists is ordinary, not a fault: the entry is
            // dropped and everything else in the session is kept, exactly as an unknown panel type is.
            return null;
        }
    }
}
