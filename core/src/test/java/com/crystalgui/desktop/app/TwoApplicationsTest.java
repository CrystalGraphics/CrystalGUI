package com.crystalgui.desktop.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.file.Paths;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgui.app.shadergraph.ShaderGraphContribution;
import com.crystalgui.style.theme.UiThemeManager;
import com.crystalgui.core.dispose.Disposer;
import com.crystalgui.core.notify.Notification;
import com.crystalgui.core.notify.Notifications;
import com.crystalgui.core.notify.StatusBarAlignment;
import com.crystalgui.core.notify.StatusBarEntry;
import com.crystalgui.core.storage.ConfigStorage;
import com.crystalgui.core.storage.InMemoryConfigStorage;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.motion.WindowAnimator;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.document.DocumentKind;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.client.Workspace;
import com.crystalgui.fs.project.ProjectRegistry;
import com.crystalgui.fs.project.WorkspaceProject;
import com.crystalgui.fs.provider.InMemoryFileSystem;
import com.crystalgui.fs.server.WatchHub;
import com.crystalgui.fs.server.WorkspaceActor;
import com.crystalgui.fs.server.WorkspaceBinding;
import com.crystalgui.fs.server.WorkspacePermission;
import com.crystalgui.fs.server.WorkspaceService;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.Protocols;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.text.lang.ProjectSourcesRegistry;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.widget.config.inspector.InspectorRegistry;
import com.crystalgui.workbench.WorkbenchApplication;

/**
 * <b>Two applications on one desktop</b> — W7's acceptance, stated as the thing the tree could not do.
 *
 * <p>Before this there was no such concept: an application was a class a host constructed and held in a
 * static, so "which applications are running" had no answer and there was room in the field for exactly
 * one. The claim W7 makes is that <b>a second product is a second manifest naming a different list of
 * extension ids</b> — not a second shell, not a second dock, and nothing a host has to remember.</p>
 *
 * <h3>What each assertion is guarding</h3>
 *
 * <ul>
 *   <li><b>Separate status bars</b> — D4. One line of text cannot serve two products, and the static it
 *       used to be was a shortcut from when there was one window.</li>
 *   <li><b>One notification centre</b> — D3. Every OS has exactly one and it is inside no application,
 *       which is why {@code Notifications} is the one thing here that stays process-wide.</li>
 *   <li><b>Different feature sets</b> — the whole point of the id list. The counter-control matters more
 *       than the positive: an implementation that activated everything for everybody would satisfy
 *       "the editor has an inspector" perfectly.</li>
 *   <li><b>Grouped in the taskbar</b> — D5, {@code AppUserModelID}. Windows of one product stay
 *       together whatever order they were opened in.</li>
 * </ul>
 */
public class TwoApplicationsTest {

    private static final String PROJECT = "scratch";

    /** A second product over the same engine: the same window, a shorter list. */
    private static final ApplicationKind GRAPHS_ONLY = ApplicationKind.of("test:graphs", "Graphs")
            .icon("crystalgui:package")
            .opens(DocumentKind.FilePatterns.extension("shadergraph"))
            .launch(context -> WorkbenchApplication.of(context)
                    .with("crystalgui:shadergraph")
                    .title("Graphs")
                    .key("graphs:main")
                    .start());

    /** A third with no features at all — the counter-control for "did the list do anything". */
    private static final ApplicationKind BARE = ApplicationKind.of("test:bare", "Bare")
            .launch(context -> WorkbenchApplication.of(context).title("Bare").start());

    private InMemoryTransport<Object>[] link;
    private ProtocolConnection<Object> serverEnd;
    private ProtocolConnection<Object> clientEnd;
    private Workspace workspace;
    private UIDocument surface;
    private Desktop desktop;
    private ConfigStorage storage;
    private boolean animationsWere;

    @Before
    public void openDesktop() {
        Protocols.resetForTesting();
        InMemoryFileSystem files = new InMemoryFileSystem().seed(PROJECT + ":Main.java", "class Main { }");
        WorkspaceService service = new WorkspaceService(
                new ProjectRegistry().register(() -> List.of(
                        new WorkspaceProject(PROJECT, "Scratch", Paths.get("/srv/scratch")))),
                files, WorkspacePermission.ALLOW_ALL);
        link = InMemoryTransport.pair();
        serverEnd = Protocols.open(link[0], PlainOps.INSTANCE, () -> { }, "host");
        clientEnd = Protocols.open(link[1], PlainOps.INSTANCE, () -> { }, null);
        new WorkspaceBinding<>(service, new WatchHub(service), WorkspaceActor.LOCAL, "host",
                PlainOps.INSTANCE).installOn(serverEnd);
        workspace = Workspace.of(clientEnd);

        // ANIMATIONS OFF, and it is load-bearing rather than tidiness: with them on, a destroyed
        // window's taskbar entry collapses over several frames and the strip goes on holding the frame
        // until it finishes -- and a test runs no frames, so the entry, its window and the application
        // inside it would stay in the heap for ever. "Animations off" turns off the WAITING too, which
        // is what makes every teardown here synchronous. @see WindowAnimator
        animationsWere = WindowAnimator.isEnabled();
        WindowAnimator.setEnabled(false);

        surface = new UIDocument();
        desktop = Desktop.of(surface);
        storage = new InMemoryConfigStorage();
    }

    @After
    public void closeDesktop() {
        for (Application running : desktop.applications().running()) running.dispose();
        // AND THE THEME, because installing one MUTATES SHIPPED SHEETS. `WorkbenchSettings.apply`
        // substitutes the active theme's variables into `StyleSheet.DEFAULT` itself -- a process-wide
        // object -- so an application left running leaves every later test in this worker computing
        // colours from a themed user-agent sheet. `ConfigKitTest` is where that lands: two of its
        // colour assertions fail in the suite and pass in isolation, which is the shape this file's own
        // javadoc predicted before an application ever attached to a document.
        UiThemeManager.getInstance().resetForTesting();
        Notifications.resetForTesting();
        InspectorRegistry.resetForTesting();
        ProjectSourcesRegistry.resetForTesting();
        Disposer.resetForTesting();
        Protocols.resetForTesting();
        WindowAnimator.setEnabled(animationsWere);
    }

    private WorkbenchApplication launch(ApplicationKind kind) {
        desktop.applications().install(kind);
        Application launched = desktop.applications().launch(kind, workspace, storage);
        assertNotNull("'" + kind.id() + "' refused to launch", launched);
        return (WorkbenchApplication) launched;
    }

    @Test
    public void twoApplicationsRunOnOneDesktopWithSeparateStatusBarsAndOneNotificationCentre() {
        WorkbenchApplication graphs = launch(GRAPHS_ONLY);
        WorkbenchApplication bare = launch(BARE);

        assertEquals("both are running", 2, desktop.applications().running().size());
        assertNotSame("two windows, not one", graphs.mainWindow(), bare.mainWindow());

        // D4. One line of text cannot serve two products.
        assertNotSame("the two share a status bar, so whatever either says overwrites the other",
                graphs.workbench().statusBar(), bare.workbench().statusBar());
        int bareEntries = bare.workbench().statusBar().size();
        graphs.workbench().statusBar().addEntry(
                StatusBarEntry.of("probe", "from the graphs"), "probe", StatusBarAlignment.LEFT);
        assertTrue("the entry did not land on the bar it was written to",
                graphs.workbench().statusBar().text().contains("from the graphs"));
        assertEquals("the other application's bar took the entry too, so one line of text is serving "
                        + "two products", bareEntries, bare.workbench().statusBar().size());

        // D3. One centre, and it is the desktop's -- which is why it is the one thing here that stays
        // process-wide: every OS has exactly one and it is inside no application.
        int before = Notifications.history().size();
        Notifications.show(Notification.info("something happened"));
        assertEquals("a notification landed in more than one place, or in none",
                before + 1, Notifications.history().size());
    }

    /**
     * The list of ids is what a manifest is for.
     *
     * <p>The counter-control is the second half: an engine that activated everything contributed,
     * whatever the manifest said, satisfies "the graph product has the graph kind" perfectly.</p>
     */
    @Test
    public void eachApplicationGetsOnlyTheExtensionsItsManifestNamed() {
        WorkbenchApplication graphs = launch(GRAPHS_ONLY);
        WorkbenchApplication bare = launch(BARE);

        Resource graph = Resource.of(PROJECT, "a.shadergraph");
        // BY ID, never "is there a kind at all": a workbench registers a fallback text kind, so
        // `forResource` answers for every extension nobody claimed and an assertion on null would pass
        // against a build where the manifest's list did nothing whatever.
        assertEquals("the graph product named crystalgui:shadergraph and did not get it",
                ShaderGraphContribution.GRAPH_TYPE, graphs.workbench().kinds().forResource(graph).id());
        assertNotEquals("an application that named no extensions was given one anyway, so the list is "
                        + "decoration and every product ships every feature",
                ShaderGraphContribution.GRAPH_TYPE, bare.workbench().kinds().forResource(graph).id());
    }

    /** D19/LaunchServices: answerable from the manifests, with nothing running. */
    @Test
    public void openWithIsAnsweredFromTheManifestBeforeAnythingIsLaunched() {
        desktop.applications().install(GRAPHS_ONLY);
        assertSame("a manifest that declares the extension did not answer for it", GRAPHS_ONLY,
                desktop.applications().handlerFor(Resource.of(PROJECT, "a.shadergraph")));
        assertNull("something claimed a file no manifest mentions",
                desktop.applications().handlerFor(Resource.of(PROJECT, "a.zip")));
        assertTrue("installing launched something", desktop.applications().running().isEmpty());
    }

    /**
     * D5: the taskbar groups by application, and by FIRST appearance.
     *
     * <p>Interleaved on purpose — two products opening a window each, alternately. Grouping that
     * happened to work on {@code A, A, B} would be indistinguishable from no grouping at all.</p>
     */
    @Test
    public void theTaskbarKeepsOneApplicationsWindowsTogether() {
        WorkbenchApplication graphs = launch(GRAPHS_ONLY);
        WorkbenchApplication bare = launch(BARE);
        WindowFrame secondGraphWindow = desktop.addWindow(new WindowFrame("Graph 2"));
        secondGraphWindow.setApplication(GRAPHS_ONLY);

        List<WindowFrame> strip = desktop.registry().taskbarOrder();
        assertEquals(List.of(graphs.mainWindow(), secondGraphWindow, bare.mainWindow()), strip);
    }

    /**
     * D17: a hidden main window is the application running in the background.
     *
     * <p>Evicting one would <em>quit</em> a product nobody asked to quit — silently, because seven other
     * windows happened to be hidden. Quitting is {@link Application#dispose()}: recorded, never inferred
     * from a cap.</p>
     */
    @Test
    public void anApplicationsMainWindowIsNotEvicted() {
        WorkbenchApplication graphs = launch(GRAPHS_ONLY);
        graphs.mainWindow().hide();

        desktop.registry().setHiddenCap(0);
        desktop.registry().evictIfNeeded();

        assertTrue("the application's main window was evicted, which quit a product silently",
                desktop.registry().windows().contains(graphs.mainWindow()));
        assertEquals("...and the application went with it", 1, desktop.applications().running().size());
    }

    /** Single-instance: a second launch activates the one that is running, and takes its argument. */
    @Test
    public void aSecondLaunchOfASingleInstanceApplicationActivatesTheFirst() {
        ApplicationKind single = ApplicationKind.of("test:single", "Single")
                .singleInstance()
                .launch(context -> WorkbenchApplication.of(context).title("Single").start());
        WorkbenchApplication first = launch(single);
        Application again = desktop.applications().launch(single, workspace, storage);

        assertSame("a second window opened over the same documents and the same session record",
                first, again);
        assertEquals(1, desktop.applications().running().size());
    }
}
