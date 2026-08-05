package com.crystalgui.ui.elements.workbench;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.ConfigStorage;
import com.crystalgui.serialization.JsonOps;
import com.crystalgui.serialization.StateMap;
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

    /** Bump when the shape changes meaning. An unknown version is discarded, never guessed at. */
    public static final int VERSION = 1;

    private static final String KEY_VERSION = "version";
    private static final String KEY_DOCK = "dock";
    private static final String KEY_ACTIVE = "active";
    private static final String KEY_EXPANDED = "expanded";
    private static final String KEY_FILES = "files";
    private static final String KEY_PATH = "path";
    private static final String KEY_VIEW = "view";

    /** How many frames a pending expansion is retried before it is written off as unreachable. */
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
     * Re-attempts what could not be restored yet. Call once a frame; cheap and self-terminating.
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
