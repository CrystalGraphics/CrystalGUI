package com.crystalgui.editor;

import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.dispose.Disposer;
import com.crystalgui.core.notify.Notifications;
import com.crystalgui.core.notify.StatusBar;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.WorkspaceClient;
import com.crystalgui.graph.shader.ShaderGraphContribution;
import com.crystalgui.core.settings.Settings;
import com.crystalgui.core.settings.SettingsCodec;
import com.crystalgui.core.settings.SettingsLayer;
import com.crystalgui.core.settings.SettingsModel;
import com.crystalgui.fs.ConfigStorage;
import com.crystalgui.serialization.DynamicOps;
import com.crystalgui.ui.elements.workbench.WorkbenchSession;
import com.crystalgui.ui.elements.workbench.WorkbenchSettings;
import com.crystalgui.ui.elements.inspector.Inspector;
import com.crystalgui.ui.elements.inspector.InspectorRegistry;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.chrome.ChromeCommands;
import com.crystalgui.ui.elements.dock.DockCommands;
import com.crystalgui.ui.elements.dock.DockDropZone;
import com.crystalgui.ui.elements.dock.DockGroup;
import com.crystalgui.ui.elements.dock.DockLayout;
import com.crystalgui.ui.elements.dock.DockLeaf;
import com.crystalgui.ui.elements.dock.DockLayoutCodec;
import com.crystalgui.ui.elements.dock.DockPanelDescriptor;
import com.crystalgui.ui.elements.dock.DockPlacement;
import com.crystalgui.ui.elements.dock.DockInput;
import com.crystalgui.ui.elements.dock.DockOpenOptions;
import com.crystalgui.ui.elements.dock.DockPanelRef;
import com.crystalgui.ui.elements.workbench.FileDocument;
import com.crystalgui.ui.elements.workbench.Workbench;
import com.crystalgui.ui.input.FocusPolicy;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.crystalgui.core.data.DataKey;
import com.crystalgui.core.command.CommandRegistry;

/**
 * The editor — the whole application, as one element.
 *
 * <p>A {@link Workbench} is the <em>shell</em>: a dock, a file tree, editors, a Problems panel. This is
 * the <b>product</b> built on it — which panels exist, what the default arrangement is, which commands
 * the application answers to, and where focus starts. A host supplies a {@link WorkspaceClient} and a
 * window; everything else is decided here.</p>
 *
 * <h3>Why this is not a harness scene</h3>
 *
 * <p>Because it was one, and that meant the only assembled editor in the project lived in a debug tool.
 * Every line of it is application behaviour: that {@code Ctrl+S} writes the active file and the layout
 * lives on {@code Ctrl+Shift+S}, that the shader graph opens in the work area, that an application decides
 * where focus starts. None of it demonstrates anything. What a scene legitimately keeps is the
 * <b>fake half</b> — a workspace client backed by something other than a real server — plus whatever
 * diagnostics that scene wants to draw on top.</p>
 *
 * <h3>Keys are commands, not key handling</h3>
 *
 * <p>The scene used to intercept raw key codes. Everything here is a registered {@link
 * com.crystalgui.core.command.Command} instead, which means each one is rebindable, shows up in the
 * palette with its accelerator, and can be greyed when it does not apply — none of which a
 * {@code switch} on a scan code can offer. See {@link CrystalEditorCommands}.</p>
 */
public class CrystalEditor extends UIElement implements Disposable {

    /**
     * The inspector tool window.
     *
     * <p>A general one — see {@link Inspector}. It knows no types; a package makes something inspectable
     * by registering an {@code InspectorSection}, which is why this class no longer names a graph.</p>
     */
    public static final String INSPECTOR_TYPE = "inspector";

    /** How much of the work area the emitted source takes when it is first opened. */
    private static final float SOURCE_SHARE = 0.28f;

    /**
     * Whatever a status line should say — <b>composed here, announced elsewhere</b>.
     *
     * <p>No longer a sink anything writes into. Events go to {@link Notifications} and ambient text to
     * {@link StatusBar}, and this editor subscribes to both and flattens them into the one string a host
     * can bind. That is the split those two exist for: a contribution announces without knowing whether
     * anyone is listening, and where the result is drawn stays this application's decision.</p>
     *
     * <p>Kept as a {@code Signal.Value<String>} because a host wants one line, not a channel per kind —
     * the harness scene binds exactly this.</p>
     */
    public final Signal.Value<String> onStatus = new Signal.Value<>();

    private final Workbench workbench;

    /** Marked internal exactly ONCE, while empty. {@code markAsInternal()} RECURSES, and stamping a
     * populated subtree makes {@code removeChild} silently refuse everything below it. */
    private final UIElement content = new UIElement();

    /**
     * The one inspector — general, and pointed at whatever is selected.
     *
     * <p>Built eagerly: the dock caches a panel factory's result permanently, so returning a placeholder
     * while waiting for something hands back the panel for the rest of the session.</p>
     */
    private final Inspector inspector = new Inspector();
    

    /** The last {@link #saveLayout} result, so {@link #restoreLayout()} has something to restore. */
    @Nullable
    private Object savedLayout;

    private boolean focusGiven;

    /**
     * This editor, for a command that acts on one.
     *
     * <p>What let {@code CrystalEditorCommands} stop capturing an editor and a window, and with them the
     * last reason {@code install(window)} existed.</p>
     */
    public static final DataKey<CrystalEditor> CRYSTAL_EDITOR =
            DataKey.create("crystalEditor", CrystalEditor.class);

    @Override
    public Object getData(DataKey<?> key) {
        if (key == CRYSTAL_EDITOR) return this;
        return super.getData(key);
    }

    /** Names this editor at the window level too — {@code Mod+S} is pressed with nothing focused as often
     * as not. Same reason {@code Workbench} does it; see {@code DataContext}. */
    @Override
    protected void onWindowChanged(@Nullable UIWindow previous, @Nullable UIWindow current) {
        if (previous != null) previous.removeDataProvider(this);
        if (current != null) current.addDataProvider(this);
    }

    /**
     * The application's own verbs — saving, layout, and the command palette.
     *
     * <p>These are the <em>product's</em> offerings rather than a widget's, which is why they sit on the
     * shell element and not on a generic one. They are still per class and context-resolved like every
     * other set; nothing here is registered per window any more.</p>
     */
    @Override
    protected void registerCommands(CommandRegistry registry) {
        CrystalEditorCommands.register();
        ChromeCommands.register();
    }

    public CrystalEditor(WorkspaceClient<?> client) {
        setFocusPolicy(FocusPolicy.NONE);
        workbench = new Workbench(client);
        // BOTH CHANNELS INTO ONE LINE. A notification is an event and wins the line when it arrives; the
        // ambient text is what is left showing between them. Flattening is this application's choice --
        // a host with room for a toast area would connect them separately instead.
        Notifications.onDidNotify.connect(notification -> onStatus.emit(notification.getMessage()));
        StatusBar.onDidChange.connect(text -> { if (!text.isEmpty()) onStatus.emit(text); });
        // The inspector and the generated source follow the front tab. Was a per-frame poll; the dock
        // announces it now. Subscribed here rather than on attach because the dock exists as soon as the
        // workbench does, and this editor owns the workbench -- there is nothing to wait for and nothing
        // that can outlive it.
        workbench.dock().onDidChangeActivePanel.connect(panel -> refreshInspector());
        // AND WHEN A DOCUMENT LANDS. The active PANEL is announced as soon as the dock has built its
        // tree, which can be before the document behind it exists -- activeDocument() reads the open-file
        // store, and a restored tab's content arrives over the network some frames later. Following only
        // the panel therefore leaves the inspector empty at startup until something else moves, which is
        // exactly what "I have to click something first" is.
        workbench.onDidOpenDocument.connect(path -> refreshInspector());
        // AND ON ANY ANNOUNCED CHANGE. The two above say "a different panel is in front" and "a document
        // arrived"; this says "what is being looked at has moved", which is the one a selection produces.
        // Re-resolving the active document here rather than only re-reading the old one is what makes the
        // panel fill at startup, where the first announcement can arrive before the dock has settled on
        // an active tab.
        InspectorRegistry.onDidChangeSubject.connect(this::refreshInspector);
        // A restore waits on listings, which arrive over several frames -- a folder cannot be expanded
        // before the listing revealing it lands. Retried per LISTING rather than per frame: fewer
        // attempts, and every one of them at a moment when the answer may actually have changed.
        workbench.fileTree().source().onDidLoadListing.connect(directory -> {
            if (session != null) session.tick();
        });

        // ONE CALL, NAMING ONE PACKAGE. Which extension opens as a graph, how to build one, what its
        // generated source is and what it tells the inspector are all that package's statements about
        // itself -- see ShaderGraphContribution. This class chooses which contributions to enable, which
        // is the only decision about file types an application should be making.
        ShaderGraphContribution.register(workbench);

        // BESIDE the canvas, not in its strip. A tab in the same group would hide the graph, and the whole
        // point of the emitted source is watching it change as you wire -- a panel you have to switch away
        // from the graph to read is a panel that is never read.
        workbench.registerPanel(DockPanelDescriptor.singleton(INSPECTOR_TYPE, "Inspector")
                        .icon("crystalgui:package").anchor(DockDropZone.SPLIT_RIGHT),
                // The inspector IS the panel content, and it exists from the start. No wrapper -- the
                // one that used to sit between it and the dock existed only to be swapped into -- and no
                // placeholder, which the dock would have cached in its place forever.
                ref -> inspector);
        // The Inspector opens with the workbench; the generated source does not. It is now a document
        // opened on demand by showCompiled(), so putting one in the default layout would mean a tab for a
        // graph nobody has opened yet.
        workbench.open(DockInput.of(new DockPanelRef(INSPECTOR_TYPE)),
                DockPlacement.side(DockDropZone.SPLIT_RIGHT),
                // Not activated: a companion pane should not take the work area's focus at startup.
                DockOpenOptions.INACTIVE.withShare(SOURCE_SHARE));

        content.addClass(CONTENT_CLASS);
        addInternalChild(content);
        content.addChild(workbench);
    }

    /** UNIQUE, never the shared {@code "__content__"} — {@code CanvasView} uses that name for its
     * transformed world plane, so a descendant rule naming it also styles every graph plane below. */
    public static final String CONTENT_CLASS = "__editor-content__";

    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }

    public Workbench workbench() {
        return workbench;
    }

    public Inspector inspector() {
        return inspector;
    }

    /**
     * Rebuilds the inspector from whatever is in front.
     *
     * <h3>A SEED, not the policy</h3>
     *
     * <p>The subject is the <b>focus owner</b>, and the {@link Inspector} resolves that itself — latching
     * it, ignoring focus that lands inside itself, and keeping the last describable one. None of that is
     * an application decision, and while it lived here it was expressed as "the active document's view",
     * which capped the inspectable set to documents: a section describing a file-tree row or a timeline
     * key could register successfully and never once be asked.</p>
     *
     * <p>What remains is genuinely a seed. A restored tab exists before anything has been focused — there
     * is no focus owner to derive a subject from, and nothing will move until the user clicks — so the
     * workbench states what it just opened. Focus supersedes it the moment there is one.</p>
     */
    private void refreshInspector() {
        FileDocument active = workbench.activeDocument();
        if (active != null) inspector.inspect(active.view());
    }



    /**
     * Makes {@code wanted} the host's child, doing nothing when it already is.
     *
     * <h3>Asks about PARENTAGE, not about the child list</h3>
     *
     * <p>This read {@code children.size() == 1 && children.get(0) == wanted}, and that threw
     * {@code "Cannot add the same child twice"} — because {@code clearAllChildren()} <b>silently refuses
     * internal children</b>, so a host whose subtree had been stamped by a {@code markAsInternal()}
     * somewhere above it kept its child through the clear and then rejected the add. The list said one
     * thing and the tree another.</p>
     *
     * <p>Parentage cannot disagree with itself. It is also the question actually being asked: "is the host
     * already showing this?" — and the size check was answering a stricter one that happens to coincide
     * most of the time, which is the worst kind of check.</p>
     */

    // install(UIWindow) is gone, and nothing replaced it.
    //
    // Every command set in the application now arrives with the element that owns it -- DockArea the
    // dock's, GraphView the graph's, TextEditor the editor's, Workbench the explorer's, and this class
    // its own -- each through UIElement.registerCommands, once per class. Their chords are either
    // declared on the commands (application-wide) or bound in bindKeys on the element that scopes them.
    // Constructing the editor is what wires it; there is nothing for a host to remember.

    // ── Persistence ─────────────────────────────────────────────────────────────────────────────

    /**
     * Where preferences and session records go. Null until {@link #useConfig} is called, and everything
     * below is then a no-op — an editor with nowhere to save is a valid one, and is what a test is.
     */
    @Nullable
    private ConfigStorage storage;

    @Nullable
    private WorkbenchSession session;

    /**
     * Gives the editor somewhere to keep the user's preferences and its session records, and loads the
     * preferences immediately.
     *
     * <p>Loading here rather than at first paint because the values decide how things are built:
     * {@code editor.tabSize} is read when a document is created, so arriving late would apply it to every
     * file except the ones already open.</p>
     */
    public CrystalEditor useConfig(ConfigStorage storage) {
        this.storage = storage;
        this.session = new WorkbenchSession(workbench, storage);
        loadPreferences();
        return this;
    }

    @Nullable
    public WorkbenchSession session() {
        return session;
    }

    /** The user layer, read into the ROOT scope so it applies to every panel. @see WorkbenchSettings */
    public void loadPreferences() {
        if (storage == null) return;
        SettingsModel loaded = SettingsCodec.fromJson(storage.read(USER_SETTINGS_FILE));
        settingsHost().replaceLayer(SettingsLayer.USER, loaded.asMap());
        WorkbenchSettings.install(workbench, settingsHost());
        // Written on change rather than only at shutdown. A preferences window that applies immediately
        // and saves only on a clean exit loses everything to a crash -- and the file is a few hundred
        // bytes, so there is nothing to batch. VS Code writes settings.json the same way.
        settingsHost().onChanged.connect(change -> {
            if (change.layer() == SettingsLayer.USER) savePreferences();
        });
    }

    public void savePreferences() {
        if (storage == null || !storage.isWritable()) return;
        storage.write(USER_SETTINGS_FILE,
                SettingsCodec.toJson(settingsHost().layer(SettingsLayer.USER)));
    }

    /**
     * The scope preferences live in: this element, which is the outermost thing every panel resolves
     * through.
     *
     * <p>Not the workbench's own store. Settings resolve <em>outward</em>, so a value written on the
     * workbench would be invisible to anything outside it, and a value written here reaches everything —
     * which is what "a preference" means.</p>
     */
    public Settings settingsHost() {
        return settings();
    }

    public static final String USER_SETTINGS_FILE = "settings.json";

    /** Restores the last session for {@code projectId}, unless the user has turned that off. */
    public boolean restoreSession(String projectId) {
        if (session == null || !workbench.resolve(WorkbenchSettings.RESTORE_SESSION)) return false;
        return session.restore(projectId);
    }

    public void saveSession(String projectId, int screenWidth, int screenHeight) {
        if (session != null) session.save(projectId, screenWidth, screenHeight);
    }

    // ── Layout ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Serialises the pane arrangement.
     *
     * <p>Reads the divider positions back out of the widgets first, or the blob records the weights the
     * layout was <em>built</em> with rather than the ones on screen.</p>
     */
    public <T> T saveLayout(DynamicOps<T> ops, int screenWidth, int screenHeight) {
        workbench.dock().pullWeightsIntoLayout();
        T encoded = DockLayoutCodec.encode(workbench.dock().layout(), ops, screenWidth, screenHeight);
        savedLayout = encoded;
        return encoded;
    }

    /** Restores whatever {@link #saveLayout} last produced. False when there is nothing to restore or the
     * codec refuses the blob — which is a normal outcome, not an error path. */
    @SuppressWarnings("unchecked")
    public <T> boolean restoreLayout(DynamicOps<T> ops) {
        if (savedLayout == null) {
            Notifications.info("nothing saved yet");
            return false;
        }
        DockLayout restored = DockLayoutCodec.decode((T) savedLayout, ops, workbench.panels());
        if (restored == null) {
            Notifications.error("saved layout refused");
            return false;
        }
        workbench.dock().setLayout(restored);
        Notifications.info("layout restored");
        return true;
    }

    // ── Focus ───────────────────────────────────────────────────────────────────────────────────

    /**
     * Hands focus to the dock once there is a group to hand it to. Idempotent; call it per frame.
     *
     * <p>An application decides where focus starts, and an IDE opens with its editor focused. Without this
     * the window opens with focus <b>null</b>, and every command whose {@code enabledWhen} walks up from
     * the focused element reports unavailable — so the palette opens almost entirely dimmed and reads as
     * broken.</p>
     *
     * <p>{@code requestPointerFocus}, never {@code requestFocus}: the latter is PROGRAMMATIC and therefore
     * rings, so the editor would open with a focus outline nobody asked for.</p>
     */
    public void giveInitialFocus() {
        if (focusGiven) return;
        UIWindow window = getAttachedWindow();
        if (window == null) return;
        DockGroup group = workbench.dock().activeGroup();
        if (group == null) return;
        if (window.getInputHandler().getFocusedElement() == null) {
            window.getInputHandler().requestPointerFocus(group);
        }
        focusGiven = true;
    }

    /**
     * Releases every open shader graph's preview renderers. Safe to call more than once.
     *
     * <p>Every one of them, not "the graph": each open file has its own editor and its own
     * {@code CgPreviewRenderer} holding an FBO per node, so freeing only the one in front would leak the
     * rest — which the single scratch graph this replaced could never do.</p>
     */
    @Override
    public void dispose() {
        // Nothing of its own: every graph is registered as a child when it is built, so the tree
        // releases them. The list this replaced was never pruned -- every graph ever opened stayed
        // reachable for the session, and that retention was the only reason its GL pool was freed at
        // exit at all.
    }

}
