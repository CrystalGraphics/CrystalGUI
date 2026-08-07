package com.crystalgui.ui.elements.workbench;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.ConfigStorage;
import com.crystalgui.serialization.JsonOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.elements.dock.DockLeaf;
import com.crystalgui.ui.elements.dock.DockPanelDescriptor;
import com.crystalgui.ui.elements.dock.DockPanelKind;
import com.crystalgui.ui.elements.dock.DockPanelRef;
import com.crystalgui.ui.elements.dock.DockRegion;
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
        workbench.onDidOpenDocument.connect(this::applyViewState);
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
        // TOOL WINDOWS ARE NOT IN THE DOCK TREE. This used to walk every leaf looking for singletons and
        // record the strip-mates and the structural path for the four-tier restoration heuristic to
        // replay. A region cannot be collapsed away, so what is left to capture is whether it is showing
        // and how wide its region is -- which is the whole of what a lookup needs.
        for (DockRegion region : DockRegion.values()) {
            if (region == DockRegion.EDITOR) continue;
            RegionHost host = workbench.regions().host(region);
            if (host == null || host.showing() == null) continue;
            // Only what is SHOWING is captured here -- a cleared host has forgotten what it held. A
            // hidden region's entry is written by hidePanel instead, which is the moment its width is
            // still known.
            workbench.toolWindows().put(workbench.toolWindows()
                    .getOrCreate(host.showing(), region)
                    .withVisible(true)
                    .withWeight(workbench.regions().weightOf(region)));
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

        // AFTER the layout is installed, so a placement naming a path describes the tree that is now
        // there. Absent is not a failure: a record written before tool-window placements existed still has
        // a usable dock, and every panel simply falls back to its descriptor's anchor the first time it is
        // hidden -- which is exactly the first-run behaviour.
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
