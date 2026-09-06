package com.crystalgui.workbench.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.data.DataKey;
import com.crystalgui.core.data.DataProvider;
import com.crystalgui.core.notify.NotificationEvent;
import com.crystalgui.core.notify.Notifications;
import com.crystalgui.core.settings.Settings;
import com.crystalgui.core.settings.SettingsCodec;
import com.crystalgui.core.settings.SettingsLayer;
import com.crystalgui.core.settings.SettingsModel;
import com.crystalgui.core.notify.Notification;
import com.crystalgui.document.Document;
import com.crystalgui.core.signal.ConnectionGroup;
import com.crystalgui.workbench.editor.EditorService;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.core.storage.ConfigStorage;
import com.crystalgui.core.window.WindowPolicy;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.app.Application;
import com.crystalgui.desktop.app.ApplicationKind;
import com.crystalgui.desktop.app.LaunchContext;
import com.crystalgui.desktop.window.WindowChrome;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.client.Workspace;
import com.crystalgui.serialization.DynamicOps;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.data.UiDataKeys;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.workbench.Workbench;
import com.crystalgui.workbench.WorkbenchSession;
import com.crystalgui.workbench.WorkbenchSettings;
import com.crystalgui.workbench.chrome.menu.ChromeCommands;
import com.crystalgui.workbench.dock.DockGroup;
import com.crystalgui.workbench.dock.layout.DockLayout;
import com.crystalgui.workbench.dock.layout.DockLayoutCodec;

/**
 * <b>The runtime every workbench-shaped application shares</b> - a {@link Workbench}, the window it
 * lives in, its preferences, its session, and the extensions its manifest named.
 *
 * <p>Build one from a manifest's launch factory. What is left in a product after this is a name, an
 * icon, a list of extension ids and a window title, which is the point: <b>a second application is a
 * different list, not a second class.</b></p>
 *
 * <pre>{@code
 * .launch(ctx -> WorkbenchApplication.of(ctx)
 *         .with("crystalgui:explorer", "crystalgui:problems", "crystalgui:scripting")
 *         .title("Crystal Editor")
 *         .key("editor:main")
 *         .policy(WindowPolicy.HIDE_ON_CLOSE)
 *         .start())
 * }</pre>
 *
 * <p>The chain ends in {@code start()} on purpose: the workbench and its window are built once the
 * declaration is complete, so nothing is constructed before the extension list is known and nothing is
 * mutated after the window is on screen.</p>
 *
 * <h3>Closing is not quitting</h3>
 *
 * <p>Under {@link WindowPolicy#HIDE_ON_CLOSE} the window goes away and the application is still running:
 * every document, the dock arrangement and the undo history are intact, and the taskbar entry is how it
 * comes back. {@link #dispose()} is the other verb. It is also why a main window is exempt from
 * hidden-window eviction - a cap on hidden windows must never quit a product nobody asked to quit.</p>
 *
 * <h3>Restoring waits to be told, and nothing polls</h3>
 *
 * <p>A session describes a workbench over a <em>workspace</em>, so it cannot be read until there is one
 * to key it by: the server's greeting says which workspace this is and the project listing says what is
 * on it. Both arrive as signals and the restore hangs off them, so a host does not have to know when to
 * ask - and a workspace that never connects simply leaves the workbench empty rather than hanging.</p>
 */
public class WorkbenchApplication extends UIElement
        implements Application, WindowChrome, DataProvider {

    /** {@code ua/workbench.css} names the tag. Was {@code crystaleditor}, when there was one product. */
    public static final Name NAME = Name.of("application");

    /** UNIQUE, never the shared {@code "__content__"} — {@code CanvasView} uses that name for its
     * transformed world plane, so a descendant rule naming it also styles every graph plane below. */
    public static final String CONTENT_CLASS = "__editor-content__";

    public static final String USER_SETTINGS_FILE = "settings.json";

    /**
     * The running application a command acts on.
     *
     * <p>Resolved outward from the focused element, which is what lets Save File and Save Layout be
     * registered <b>once</b> and still act on the right one of two applications open side by side. The
     * captured version could not have done it at all.</p>
     */
    public static final DataKey<WorkbenchApplication> APPLICATION =
            DataKey.create("workbenchApplication", WorkbenchApplication.class);

    /**
     * Whatever a status line should say — <b>composed here, announced elsewhere</b>.
     *
     * <p>Notification events and the workbench's own ambient text flattened into the one string a host
     * can bind. That flattening is an application's choice: a host with room for a toast area would
     * connect the two separately instead.</p>
     */
    public final Signal.Value<String> onStatus = new Signal.Value<>();

    private final ApplicationKind kind;
    private final Desktop desktop;
    private final Workspace workspace;

    /** The parent of every workspace's store; scoped by identity in {@link #restoreWhenReady}. */
    private final ConfigStorage workspaces;
    private final ConfigStorage storage;
    private final Workbench workbench;
    private final WindowFrame window;
    private final WorkbenchSession session;

    /** Marked internal exactly ONCE, while empty. {@code markAsInternal()} RECURSES. */
    private final UIElement content = new UIElement();

    private final ConnectionGroup lifetime = new ConnectionGroup();

    /** The last {@link #saveLayout} result, so {@link #restoreLayout} has something to restore. */
    @Nullable
    private Object savedLayout;

    private boolean focusGiven;
    private boolean projectsAsked;

    /** Which record this application is holding, once the workspace has said who it is. */
    @Nullable
    private String sessionKey;

    private boolean disposed;

    /**
     * The surface this is on — <b>remembered, because {@code disconnected()} cannot ask</b>.
     *
     * <p>A detach queues the callback and nulls the node's document <em>before</em> the queue is
     * drained ({@code UINode}: {@code doc.queue(this::disconnected); document = null;}), so
     * {@code document()} inside {@code disconnected} answers null every time, for every node. Reading it
     * there is the shape this class inherited, and it meant the two things a departure is FOR — writing
     * the session and withdrawing the document-level data provider — both silently did nothing: the
     * editor's arrangement was never written when its screen closed, and every application ever attached
     * stayed reachable from {@code UIDocument.scopeProviders} for the life of the process.</p>
     *
     * <p>Nothing failed either way, which is why it survived: a session that is never written looks
     * exactly like one that was written and had nothing new in it.</p>
     */
    @Nullable
    private UIDocument attachedTo;

    // ── Building ────────────────────────────────────────────────────────────────────────────────

    /** Starts one from a launch. @see Builder */
    public static Builder of(LaunchContext context) {
        return new Builder(context);
    }

    /**
     * What a manifest declares about its own window and its own feature set.
     *
     * <p>Fluent, and terminated by {@link #start()} — one word more than the plan's sketch, and it
     * buys the thing that matters: the workbench, the extensions and the window are built <b>once</b>,
     * from a complete declaration, rather than a half-configured application being mutated after it is
     * already on screen.</p>
     */
    public static final class Builder {

        private final LaunchContext context;
        private final List<String> extensions = new ArrayList<>();
        @Nullable
        private String title;
        @Nullable
        private String key;
        @Nullable
        private String icon;
        private WindowPolicy policy = WindowPolicy.HIDE_ON_CLOSE;

        private Builder(LaunchContext context) {
            this.context = context;
        }

        /**
         * Which extensions this application enables, by id.
         *
         * <p>An id nothing contributed is a <b>logged absence</b>, never an error — the three-tier
         * degradation the language stack already follows, and what lets {@code crystalgui:scripting} be
         * listed on a host with no engine band. An extension that is contributed and not listed is
         * simply not activated.</p>
         */
        public Builder with(String... extensionIds) {
            return with(Arrays.asList(extensionIds));
        }

        public Builder with(List<String> extensionIds) {
            if (extensionIds != null) extensions.addAll(extensionIds);
            return this;
        }

        public Builder title(String windowTitle) {
            this.title = windowTitle;
            return this;
        }

        /** A stable name for the window, so its geometry survives a restart. @see WindowFrame#key() */
        public Builder key(String windowKey) {
            this.key = windowKey;
            return this;
        }

        /** Defaults to the manifest's. */
        public Builder icon(String namespacedIcon) {
            this.icon = namespacedIcon;
            return this;
        }

        public Builder policy(WindowPolicy windowPolicy) {
            this.policy = windowPolicy;
            return this;
        }

        /** Builds the workbench, activates the extensions, and opens the window. */
        public WorkbenchApplication start() {
            return new WorkbenchApplication(this);
        }
    }

    protected WorkbenchApplication(Builder builder) {
        super(NAME);
        LaunchContext context = builder.context;
        this.kind = context.kind();
        this.desktop = context.desktop();
        this.workspace = context.workspace();
        this.storage = context.storage();
        this.workspaces = context.workspaces();
        setFocusPolicy(FocusPolicy.NONE);

        this.workbench = new Workbench(workspace, builder.extensions);
        // BOTH CHANNELS INTO ONE LINE. A notification is an event and wins the line when it arrives; the
        // ambient text is what is left showing between them.
        lifetime.add(Notifications.onDidChange.connect(event -> {
            if (event.kind() == NotificationEvent.Kind.ADDED && event.notification() != null) {
                onStatus.emit(event.notification().getMessage());
            }
        }));
        // READ ON DEMAND, never carried by the signal: composing the line walks every entry, and the
        // caret readout writes on every selection change.
        lifetime.add(workbench.statusBar().onDidChange.connect(() -> {
            String text = workbench.statusBar().text();
            if (!text.isEmpty()) onStatus.emit(text);
        }));

        content.addClass(CONTENT_CLASS);
        append(content);
        content.append(workbench);

        // THE STORE, BEFORE THE PREFERENCES ARE READ. Scoped to this application by the registry, so two
        // products on one desktop do not write each other's settings.json (D20).
        workbench.useConfig(storage);
        // AND THE CACHE ROOT IN THE SAME BREATH, because extensions activate while the workbench is
        // being built and ask for their cache directory as they do -- a root supplied after this line
        // is a root nobody ever sees. @see Workbench#useCache
        workbench.useCache(context.cache());
        // THE WORKSPACE'S OWN STORE IS NOT SET HERE. A backup, a history and a session belong to one
        // WORKSPACE, and which workspace this is arrives with the server's greeting -- see
        // restoreWhenReady, which is where all three are given their store.
        this.session = new WorkbenchSession(workbench);
        loadPreferences();

        this.window = desktop.addWindow(new WindowFrame(
                builder.title == null ? kind.displayName() : builder.title));
        window.setApplication(kind).markApplicationMain();
        window.setPolicy(builder.policy);
        if (builder.key != null) window.setKey(builder.key);
        String iconName = builder.icon != null ? builder.icon : kind.icon();
        if (iconName != null) window.setIcon(iconName);
        // setContent, not content().append -- it is what ADOPTS the workbench's menu bar into the
        // caption, so the window has one header rather than two stacked on each other.
        window.setContent(this);

        // THE RESTORE HANGS OFF THE WIRE, not off a host's latch. Both signals fire again on a
        // reconnect, which is what makes rejoining a DIFFERENT server read that server's record --
        // the static "have I asked yet" flag this replaces was per PROCESS, so the project list was
        // asked for at most once per game session however many worlds were joined afterwards.
        lifetime.add(workspace.onDidGreet.connect(hello -> askForProjects()));
        lifetime.add(workbench.projects().onDidChangeProjects().connect(this::restoreWhenReady));
        // A RESTORE WAITS ON LISTINGS, which arrive over several frames -- a folder cannot be expanded
        // before the listing revealing it lands. Retried per LISTING rather than per frame: fewer
        // attempts, and every one at a moment when the answer may actually have changed.
        lifetime.add(workbench.projects().onDidLoadListing().connect(directory -> session.tick()));
        // ONLY IF THE SERVER HAS ALREADY SPOKEN. A workspace is shared and outlives every application on
        // it, so one launched onto a wire that greeted long ago will never see `onDidGreet` again and has
        // to ask now; one launched before the greeting must NOT, and that is not merely tidiness --
        // `WorkspaceTreeSource` records that a listing asked too early "is thrown away with no error at
        // all", and the outstanding call holds its continuation, so an application disposed before the
        // reply landed stayed reachable through the client's in-flight table.
        if (workspace.hasGreeted()) askForProjects();

        for (Resource resource : context.open()) open(resource);
    }

    // ── Application ─────────────────────────────────────────────────────────────────────────────

    @Override
    public ApplicationKind kind() {
        return kind;
    }

    @Override
    public WindowFrame mainWindow() {
        return window;
    }

    @Override
    public boolean open(Resource resource) {
        if (resource == null) return false;
        workbench.openResource(resource, null);
        activate();
        return true;
    }

    @Override
    public void activate() {
        // THREE STATES AND THEY ARE NOT THE SAME: hidden (minimised, or closed under HIDE_ON_CLOSE)
        // needs showing, visible-but-behind needs raising, in-front needs only the keyboard. `show`
        // covers the first two and `activate` the last, so asking for both is correct rather than lazy.
        window.show(true);
        desktop.activate(window);
    }

    public Workbench workbench() {
        return workbench;
    }

    public Workspace workspace() {
        return workspace;
    }

    public WorkbenchSession session() {
        return session;
    }

    // ── The tree ────────────────────────────────────────────────────────────────────────────────

    @Override
    public Object getData(DataKey<?> key) {
        if (key == APPLICATION) return this;
        // THE STORE PREFERENCES ARE WRITTEN TO, which is this element's and not the window root's.
        if (key == UiDataKeys.SETTINGS_HOST) return settingsHost();
        return null;
    }

    /** Names this application at the window level too — {@code Mod+S} is pressed with nothing focused
     * as often as not. Same reason {@code Workbench} does it; see {@code DataContext}. */
    @Override
    protected void connected() {
        super.connected();
        UIDocument document = document();
        if (document == null) return;
        attachedTo = document;
        document.addDataProvider(this);
        // AND THE APPEARANCE AXES, REPLAYED NOW THAT THERE IS A DOCUMENT TO INSTALL THEM ON.
        // `WorkbenchSettings.apply` reaches the style engine through `workbench.document()`, and it is
        // called from `loadPreferences` while this application is still being built -- long before it is
        // added to anything. Asked once too early, replayed on arrival; idempotent by contract.
        WorkbenchSettings.apply(workbench);
        // FOCUS ONCE THERE IS SOMETHING TO GIVE IT TO. An application decides where focus starts, and
        // with none the palette opens almost entirely dimmed -- every `enabledWhen` walks up from the
        // focused element and reports unavailable. Owned by this element, so it stops when it leaves.
        document.animation().every(this, delta -> {
            giveInitialFocus();
            return !focusGiven;
        });
    }

    /**
     * <p>OFF SCREEN IS THE MOMENT TO WRITE, and the application is what knows it — not the host. A
     * screen closing detaches the compositor, which detaches everything on it, so this fires without
     * any platform having to remember.</p>
     */
    @Override
    protected void disconnected() {
        UIDocument leaving = attachedTo;
        attachedTo = null;
        // NOT WHILE QUITTING: dispose() writes the state first, deliberately, because by the time this
        // callback runs the surface has already been detached and there is nothing left to measure. A
        // second write here would be the same record with a zero-sized viewport in it.
        if (leaving != null && !disposed) {
            saveState(leaving);
        }
        if (leaving != null) leaving.removeDataProvider(this);
        super.disconnected();
    }

    /**
     * The verbs every workbench application offers — saving, and the layout.
     *
     * <p>Once per class, and they resolve their subject from the data context, so two applications on
     * one desktop each save their own.</p>
     */
    @Override
    protected void registerCommands(CommandRegistry registry) {
        WorkbenchApplicationCommands.register();
        ChromeCommands.register();
    }

    /**
     * The main menu bar — offered to whatever window this application is put in.
     *
     * <p>Client-side decorations: an application inside a {@code WindowFrame} would otherwise have two
     * headers stacked on each other, the window's caption and its own menu row. The bar is
     * <b>moved</b> into the caption rather than duplicated or hidden.</p>
     */
    @Override
    @Nullable
    public UIElement captionChrome() {
        return workbench.menuBar();
    }

    // ── Focus ───────────────────────────────────────────────────────────────────────────────────

    /**
     * Hands focus to the dock once there is a group to hand it to. Idempotent.
     *
     * <p>{@code requestPointerFocus}, never {@code requestFocus}: the latter is PROGRAMMATIC and
     * therefore rings, so the application would open with a focus outline nobody asked for.</p>
     */
    public void giveInitialFocus() {
        if (focusGiven) return;
        UIDocument surface = document();
        if (surface == null) return;
        DockGroup group = workbench.dock().activeGroup();
        if (group == null) return;
        if (surface.focus().focused() == null) {
            surface.focus().requestPointerFocus(group);
        }
        focusGiven = true;
    }

    // ── Persistence ─────────────────────────────────────────────────────────────────────────────

    /** The user layer, read into the ROOT scope so it applies to every panel. @see WorkbenchSettings */
    public void loadPreferences() {
        SettingsModel loaded = SettingsCodec.fromJson(storage.read(USER_SETTINGS_FILE));
        settingsHost().replaceLayer(SettingsLayer.USER, loaded.asMap());
        WorkbenchSettings.install(workbench, settingsHost());
        // Written on change rather than only at shutdown. A preferences window that applies immediately
        // and saves only on a clean exit loses everything to a crash, and the file is a few hundred
        // bytes. VS Code writes settings.json the same way.
        lifetime.add(settingsHost().onChanged.connect(change -> {
            if (change.layer() == SettingsLayer.USER) savePreferences();
        }));
    }

    public void savePreferences() {
        if (!storage.isWritable()) return;
        storage.write(USER_SETTINGS_FILE, SettingsCodec.toJson(settingsHost().layer(SettingsLayer.USER)));
    }

    /**
     * The scope preferences live in: this element, which is the outermost thing every panel resolves
     * through.
     *
     * <p>Not the workbench's own store. Settings resolve <em>outward</em>, so a value written on the
     * workbench would be invisible to anything outside it.</p>
     */
    public Settings settingsHost() {
        return settings();
    }

    /** Writes everything this application is responsible for keeping — its session and its preferences. */
    public void saveState() {
        saveState(document());
    }

    private void saveState(@Nullable UIDocument surface) {
        if (sessionKey == null || surface == null) return;
        Box box = surface.box();
        if (box == null) return;
        session.save(sessionKey, (int) box.width(), (int) box.height());
        savePreferences();
    }

    private void askForProjects() {
        if (projectsAsked || disposed) return;
        projectsAsked = true;
        // "NEVER ASKED", "asked and dropped", "refused" and "answered with nothing" all look like an
        // empty panel, so a refusal un-latches and the next greeting asks again.
        workbench.projects().loadProjects(this::restoreWhenReady, () -> projectsAsked = false);
    }

    /**
     * Restores the arrangement once the workspace can be named, and once only.
     *
     * <p>Keyed by <b>(application id, workspace identity)</b> — §4.9. Not by a project: a session record
     * describes a <em>workbench</em>, whose tabs may come from any project, so one record per project
     * over one dock would restore N layouts onto one screen. It was merely <em>named</em> after a
     * project because every client held a constant.</p>
     */
    private void restoreWhenReady() {
        if (sessionKey != null || disposed) return;
        List<CgPath> roots = workbench.projects().roots();
        if (roots.isEmpty()) return;
        // THE WORKSPACE CAN BE NAMED, WHICH IS WHAT EVERYTHING BELOW WAS WAITING FOR. Its store is a
        // directory of its own, so a client that has joined ten servers keeps ten sets of unsaved work
        // rather than one shared pile -- and the record inside it is named after the APPLICATION,
        // because the directory has already said which workspace this is.
        ConfigStorage mine = workspaces.scoped(identityFor(workspace, roots));
        sessionKey = kind.id();
        // BACKUPS AND HISTORY TOO, and not before now: see DesktopHost#frame, which used to set a store
        // here that every workspace shared.
        workspace.setStorage(mine);
        session.useStorage(mine);
        // REMEMBERED EVEN WHEN THE RESTORE IS DECLINED. Turning session restore off means "do not put
        // the last arrangement back", never "stop recording this one".
        if (workbench.resolve(WorkbenchSettings.RESTORE_SESSION) && !session.restore(sessionKey)) {
            // D23: one record written under the old per-project name, read once. Without it every user
            // loses one arrangement on the day the key changes, for ten lines.
            session.restore(legacyKeyFor(roots));
        }
        // UNSAVED WORK IS NOT PART OF "the last arrangement", and is not gated on wanting it back.
        // "Do not reopen my tabs" is a preference about a layout; "throw away what I never saved" is
        // not the same sentence, and reading one as the other loses somebody's work silently.
        // SUBSCRIBED BEFORE THE RESTORE, or the first document is put back before anything is
        // listening -- the restore is asynchronous per file but the subscription is not.
        lifetime.add(workbench.editors().onDidRestoreUnsavedWork.connect(this::announceRestored));
        workbench.editors().restoreUnsavedWork();
    }

    /**
     * Says that a file came back modified, and offers the way out.
     *
     * <p>Without this a restore is silent: the file opens with a marker the author did not put there,
     * and "my editor thinks this file is modified and it isn't" is indistinguishable from a bug in the
     * dirty state — which is exactly how it was reported. VS Code can be silent about it because you
     * left the file dirty yourself and remember doing so.</p>
     *
     * <p><b>Discard is a real action, not a dismissal.</b> Telling somebody their file holds changes
     * they do not recognise, and leaving them to work out that the way back is to close the tab without
     * saving, is worse than saying nothing: it names a problem and hides the remedy. This reverts to
     * what is on disk and drops the backup, so the next launch is clean.</p>
     */
    private void announceRestored(EditorService.Restored restored) {
        Resource resource = restored.resource();
        if (restored.fileAlsoChanged()) {
            // A WARNING, and it names the consequence rather than the mechanism. Saving is not blocked
            // and must not be -- the work is the author's to keep -- but it will be refused and turned
            // into a merge, and learning that at the moment of saving is learning it too late.
            Notifications.show(Notification.warning("Restored unsaved changes")
                    .withDetail(resource.name() + " has changes from a previous session, and the file "
                            + "itself has changed since. Saving will ask you to merge.")
                    .withAction("Discard", () -> discardRestored(resource)));
            return;
        }
        Notifications.show(Notification.info("Restored unsaved changes")
                .withDetail(resource.name() + " has changes from a previous session that were never "
                        + "saved. Save it to keep them.")
                .withAction("Discard", () -> discardRestored(resource)));
    }

    /** Back to the file on disk, and the backup with it. @see #announceRestored */
    private void discardRestored(Resource resource) {
        Document document = workbench.documents().get(resource);
        // BOTH, and in this order. Reverting alone leaves the backup on disk, so the same work is
        // offered again on the next launch and the notification comes back with it.
        if (document != null) workbench.documents().revert(document);
        workbench.documents().discardBackup(resource);
    }

    /**
     * Which workspace this is — the name of the directory its state lives in.
     *
     * <p>The {@code workspaceId} the server greets with; a server that has never heard of the field
     * falls back to a hash of the sorted project ids it listed, which is VS Code's multi-root workspace
     * id computed the same way for the same reason.</p>
     */
    static String identityFor(Workspace workspace, List<CgPath> roots) {
        String identity = workspace.server().workspaceId();
        return identity.isEmpty() ? hashOfProjects(roots) : identity;
    }

    /** What the record was called when a client named it after the one project it held a constant for. */
    private static String legacyKeyFor(List<CgPath> roots) {
        return roots.get(0).project();
    }

    private static String hashOfProjects(List<CgPath> roots) {
        List<String> ids = new ArrayList<>();
        for (CgPath root : roots) {
            if (!ids.contains(root.project())) ids.add(root.project());
        }
        ids.sort(null);
        // FNV-1a over the sorted ids: stable across runs, which `String.hashCode` on a joined list also
        // is -- but a signed int printed with a minus sign makes an ugly file name, and this is what the
        // identity is FOR.
        long hash = 0xcbf29ce484222325L;
        for (String id : ids) {
            for (int i = 0; i < id.length(); i++) {
                hash = (hash ^ id.charAt(i)) * 0x100000001b3L;
            }
            hash = (hash ^ '/') * 0x100000001b3L;
        }
        return Long.toHexString(hash).toLowerCase(Locale.ROOT);
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

    /** Restores whatever {@link #saveLayout} last produced. False when there is nothing to restore or
     * the codec refuses the blob — a normal outcome, not an error path. */
    @SuppressWarnings("unchecked")
    public <T> boolean restoreLayout(DynamicOps<T> ops) {
        if (savedLayout == null) return false;
        DockLayout restored = DockLayoutCodec.decode((T) savedLayout, ops, workbench.panels());
        if (restored == null) return false;
        workbench.dock().setLayout(restored);
        return true;
    }

    // ── Quitting ────────────────────────────────────────────────────────────────────────────────

    /**
     * Quits it: the state written, the window destroyed, the workbench taken down, and the desktop told.
     *
     * <p>Not what closing the window does — see the class note. Idempotent, because a host taking a
     * desktop down destroys every window and one of them is this one.</p>
     */
    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        saveState();
        lifetime.disconnectAll();
        desktop.applications().forget(this);
        // The window's own destroy detaches this element, which is what fires `disconnected` -- so the
        // state above is written BEFORE it, deliberately: by then there is no document left to measure.
        window.destroy();
        workbench.dispose();
    }
}
