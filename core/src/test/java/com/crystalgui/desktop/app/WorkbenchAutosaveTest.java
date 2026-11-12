package com.crystalgui.desktop.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgraphics.net.CgInMemoryTransport;
import com.crystalgraphics.net.protocol.CgProtocolConnection;
import com.crystalgraphics.net.protocol.CgProtocols;
import com.crystalgraphics.serialization.CgPlainOps;
import com.crystalgraphics.storage.config.CgConfigStorage;
import com.crystalgraphics.storage.config.CgInMemoryConfigStorage;
import com.crystalgui.core.dispose.Disposer;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.motion.WindowAnimator;
import com.crystalgui.fs.client.WorkspaceClient;
import com.crystalgui.fs.project.ProjectRegistry;
import com.crystalgui.fs.project.WorkspaceProject;
import com.crystalgui.fs.provider.InMemoryFileSystem;
import com.crystalgui.fs.server.WatchHub;
import com.crystalgui.fs.server.WorkspaceActor;
import com.crystalgui.fs.server.WorkspaceBinding;
import com.crystalgui.fs.server.WorkspacePermission;
import com.crystalgui.fs.server.WorkspaceService;
import com.crystalgui.style.theme.UiThemeManager;
import com.crystalgui.text.lang.ProjectSourcesRegistry;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.widget.config.inspector.InspectorRegistry;
import com.crystalgui.workbench.app.WorkbenchApplication;

/** A workbench writes its session after its arrangement changes, not only when it quits. */
public class WorkbenchAutosaveTest {

    private static final ApplicationKind KIND = ApplicationKind.of("test:autosave", "Autosave")
            .launch(context -> WorkbenchApplication.of(context).title("Autosave").start());

    private CgInMemoryTransport<Object>[] link;
    private CgProtocolConnection<Object> serverEnd, clientEnd;
    private Path home;
    private UIDocument surface;
    private Desktop desktop;
    private boolean animationsWere;
    private WorkbenchApplication app;

    @Before
    public void launch() throws IOException {
        CgProtocols.resetForTesting();
        InMemoryFileSystem files = new InMemoryFileSystem().seed("scratch:Main.java", "class Main { }");
        WorkspaceService service = new WorkspaceService(
                new ProjectRegistry().register(() -> List.of(
                        new WorkspaceProject("scratch", "Scratch", Paths.get("/srv/scratch")))),
                files, WorkspacePermission.ALLOW_ALL);
        link = CgInMemoryTransport.pair();
        serverEnd = CgProtocols.open(link[0], CgPlainOps.INSTANCE, () -> { }, "host");
        clientEnd = CgProtocols.open(link[1], CgPlainOps.INSTANCE, () -> { }, null);
        new WorkspaceBinding<>(service, new WatchHub(service), WorkspaceActor.LOCAL, "host", CgPlainOps.INSTANCE)
                .installOn(serverEnd);
        WorkspaceClient workspace = WorkspaceClient.of(clientEnd);

        animationsWere = WindowAnimator.isEnabled();
        WindowAnimator.setEnabled(false);
        home = Files.createTempDirectory("autosave");
        surface = new UIDocument();
        desktop = Desktop.of(surface).useStorage(home);
        desktop.applications().install(KIND);
        CgConfigStorage unused = new CgInMemoryConfigStorage();
        app = (WorkbenchApplication) desktop.applications().launch(KIND, workspace, unused);
        assertNotNull(app);
        frames(10);
    }

    @After
    public void close() {
        for (Application running : desktop.applications().running()) running.dispose();
        UiThemeManager.getInstance().resetForTesting();
        InspectorRegistry.resetForTesting();
        ProjectSourcesRegistry.resetForTesting();
        Disposer.resetForTesting();
        CgProtocols.resetForTesting();
        WindowAnimator.setEnabled(animationsWere);
    }

    private void frames(int count) {
        for (int i = 0; i < count; i++) {
            surface.frame(0.25f, 1200, 800);
            link[0].deliver();
            link[1].deliver();
            serverEnd.tick();
            clientEnd.tick();
        }
    }

    /** The session records written so far, by file. */
    private List<Path> records() throws IOException {
        try (Stream<Path> all = Files.walk(home)) {
            return all.filter(p -> p.getFileName().toString().startsWith("session.")).toList();
        }
    }

    @Test
    public void aChangeToTheArrangementIsWrittenWithoutQuitting() throws IOException {
        frames(8);
        for (Path record : records()) Files.delete(record);
        app.workbench().dock().onDidChangeLayout.emit();
        assertTrue("written before the delay: " + records(), records().isEmpty());
        frames(8);
        assertFalse("the session was not written after the arrangement changed", records().isEmpty());
    }
}
