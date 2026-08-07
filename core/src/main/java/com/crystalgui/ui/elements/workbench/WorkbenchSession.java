package com.crystalgui.ui.elements.workbench;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.ConfigStorage;
import com.crystalgui.serialization.JsonOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.elements.dock.DockLeaf;
import com.crystalgui.ui.elements.dock.DockPanelDescriptor;
import com.crystalgui.ui.elements.dock.DockPanelRef;
import com.crystalgui.ui.elements.dock.DockPath;
import com.crystalgui.ui.elements.dock.DockLayout;
import com.crystalgui.ui.elements.dock.DockLayoutCodec;

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
 *       lands ({@link Workbench#onDocumentLoaded}), never when the panel is built. A caret restored into a
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
     */
    public static final int VERSION = 3;

    private static final String KEY_VERSION = "version";
    private static final String KEY_DOCK = "dock";
    private static final String KEY_TOOL_WINDOWS = "toolWindows";
    private static final String KEY_ACTIVE = "active";
    private static final String KEY_EXPANDED = "expanded";
    private static final String KEY_FILES = "files";
    private static final String KEY_PATH = "path";
    private static final String KEY_VIEW = "view";

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
        workbench.onDocumentLoaded.connect(this::applyViewState);
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

        CgPath active = workbench.activeFilePath();
        if (active != null) out.putString(KEY_ACTIVE, active.toString());

        List<CgPath> expanded = workbench.fileTree().treeView().expandedItems();
        out.putList(KEY_EXPANDED, expanded, (entry, path) -> entry.putString(KEY_PATH, path.toString()));

        List<CgPath> withViewState = new ArrayList<>();
        for (CgPath path : workbench.openPaths()) {
            if (workbench.documentFor(path) instanceof DocumentViewState) withViewState.add(path);
        }
        out.putList(KEY_FILES, withViewState, (entry, path) -> {
            entry.putString(KEY_PATH, path.toString());
            StateMap<JsonElement> view = new StateMap<>(JsonOps.INSTANCE);
            ((DocumentViewState) workbench.documentFor(path)).writeViewState(view);
            entry.putRaw(KEY_VIEW, view.encode());
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
    private void captureOpenToolWindows() {
        for (DockLeaf leaf : workbench.dock().layout().leaves()) {
            for (DockPanelRef panel : leaf.panels()) {
                DockPanelDescriptor descriptor = workbench.panels().descriptor(panel.typeId());
                if (descriptor == null || !descriptor.isSingleton()) continue;

                List<DockPanelRef> neighbours = new ArrayList<>(leaf.panels());
                neighbours.remove(panel);
                DockPath parent = leaf.parent() == null
                        ? null : workbench.dock().layout().pathOf(leaf.parent());
                int index = leaf.parent() == null ? -1 : leaf.parent().indexOf(leaf);

                ToolWindowState state = workbench.toolWindows()
                        .getOrCreate(panel.typeId(), descriptor.anchor())
                        .withVisible(true)
                        .withWeight(leaf.size())
                        .withGroupedWith(neighbours)
                        .withActive(panel.equals(leaf.activePanel()))
                        .withPlacement(parent, index);
                workbench.toolWindows().put(state);
            }
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
            in = new StateMap<>(JsonOps.INSTANCE, JsonParser.parseString(json));
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

        workbench.dock().setLayout(layout);

        // AFTER the layout is installed, so a placement naming a path describes the tree that is now
        // there. Absent is not a failure: a record written before tool-window placements existed still has
        // a usable dock, and every panel simply falls back to its descriptor's anchor the first time it is
        // hidden -- which is exactly the first-run behaviour.
        workbench.toolWindows().clear();
        for (ToolWindowState state
                : ToolWindowLayout.decodeFrom(in, KEY_TOOL_WINDOWS).ordered()) {
            workbench.toolWindows().put(state);
        }

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
