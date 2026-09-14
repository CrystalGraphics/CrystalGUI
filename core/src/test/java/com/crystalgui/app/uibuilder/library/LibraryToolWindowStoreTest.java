package com.crystalgui.app.uibuilder.library;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.crystalgui.app.crystaleditor.CrystalEditor;
import com.crystalgui.app.uibuilder.UiBuilderContribution;
import com.crystalgui.core.storage.InMemoryConfigStorage;
import com.crystalgui.core.storage.StorageLayout;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.fs.client.Workspace;
import com.crystalgui.fs.project.ProjectRegistry;
import com.crystalgui.fs.project.WorkspaceProject;
import com.crystalgui.fs.provider.InMemoryFileSystem;
import com.crystalgui.fs.server.WatchHub;
import com.crystalgui.fs.server.WorkspaceActor;
import com.crystalgui.fs.server.WorkspaceBinding;
import com.crystalgui.fs.server.WorkspaceService;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.Protocols;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.widget.control.Button;
import com.crystalgui.workbench.Workbench;

/**
 * <b>A user's groups land in the UI builder extension's own store</b> —
 * {@code crystalgui/workspace-config/extensions/crystalgui.uibuilder/library.json} — in the order an application
 * builds a workbench: the extensions activate inside the constructor, and the stores are supplied after it.
 */
public class LibraryToolWindowStoreTest extends UiDocumentTestBase {

    private Workbench workbench;
    private ProtocolConnection<Object> serverEnd;
    private ProtocolConnection<Object> clientEnd;

    @Before
    public void openWorkbench() {
        Protocols.resetForTesting();
        UIElementRegistry.bootstrap();
        WorkspaceService service = new WorkspaceService(
                new ProjectRegistry().register(() -> List.of(
                        new WorkspaceProject("scratch", "Scratch", Paths.get("/srv/scratch")))),
                new InMemoryFileSystem(), (actor, project, path, operation) -> true);
        InMemoryTransport<Object>[] link = InMemoryTransport.pair();
        serverEnd = Protocols.open(link[0], PlainOps.INSTANCE, () -> { }, "host");
        clientEnd = Protocols.open(link[1], PlainOps.INSTANCE, () -> { }, null);
        new WorkspaceBinding<>(service, new WatchHub(service), WorkspaceActor.LOCAL, "host",
                PlainOps.INSTANCE).installOn(serverEnd);
        workbench = new Workbench(Workspace.of(clientEnd), CrystalEditor.EXTENSIONS);
    }

    @After
    public void closeWorkbench() {
        workbench.dispose();
        Protocols.resetForTesting();
    }

    @Rule
    public final TemporaryFolder installation = new TemporaryFolder();

    @Test
    public void aGroupMadeInTheLibraryIsWrittenToTheExtensionsStoreSuppliedAfterTheWorkbench() throws Exception {
        Desktop desktop = Desktop.of(document);
        desktop.useStorage(installation.getRoot().toPath());
        // AFTER construction, as WorkbenchApplication does it.
        workbench.useConfig(new InMemoryConfigStorage());
        workbench.useExtensionStores(desktop::extensionStore);
        UIElement root = new UIElement().layout(l -> l.width(1200).height(800));
        root.append(workbench);
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        workbench.showPanel(UiBuilderContribution.LIBRARY_PANEL);
        frame();

        LibraryToolWindow library = find(workbench);
        assertNotNull("the Library tool window is not in the workbench", library);
        UserLibrary mine = library.panel().userLibrary();
        mine.createGroup("Mine");
        mine.addToGroup("Mine", Button.NAME);

        Path file = StorageLayout.configIn(installation.getRoot().toPath())
                .resolve(StorageLayout.EXTENSIONS).resolve("crystalgui.uibuilder").resolve(UserLibrary.FILE);
        assertTrue("the group was not written to " + file, Files.isRegularFile(file));
        assertTrue(Files.readString(file).contains("crystalgui:button"));
    }

    private static LibraryToolWindow find(UIElement from) {
        if (from instanceof LibraryToolWindow found) return found;
        for (UIElement child : from.children()) {
            LibraryToolWindow found = find(child);
            if (found != null) return found;
        }
        return null;
    }
}
