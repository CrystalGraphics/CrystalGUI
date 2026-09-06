package com.crystalgui.workbench;

import com.crystalgui.fs.Resource;
import com.crystalgui.workbench.dock.panel.DockPanelDescriptor;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.crystalgui.workbench.extension.WorkbenchExtension;
import com.crystalgui.workbench.extension.WorkbenchExtensions;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgui.app.shadergraph.ShaderGraphContribution;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.example.notes.NotesKind;
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

/**
 * <b>A feature attaches itself to a workbench, and lets go with it.</b>
 *
 * <p>Three ways of doing this had drifted apart — baked into the workbench's constructor, a static
 * {@code register(Workbench)} a host has to remember, and an {@code install(...)} whose return value
 * nobody kept. The third answer to "who takes this down" was usually nobody, and the second produced
 * the defect this test's first case is about: the Notes file type was registered by two harness scenes
 * and by no loader, so a file type shipped in this repository opened in the harness and not in the
 * game.</p>
 */
public class WorkbenchExtensionsTest {

    private static final String PROJECT = "scratch";

    private Workspace workspace;

    @Before
    public void openWorkspace() {
        Protocols.resetForTesting();
        InMemoryFileSystem files = new InMemoryFileSystem().seed(PROJECT + ":Main.java", "class Main { }");
        WorkspaceService service = new WorkspaceService(
                new ProjectRegistry().register(() -> List.of(
                        new WorkspaceProject(PROJECT, "Scratch", Paths.get("/srv/scratch")))),
                files, WorkspacePermission.ALLOW_ALL);
        InMemoryTransport<Object>[] link = InMemoryTransport.pair();
        ProtocolConnection<Object> serverEnd = Protocols.open(link[0], PlainOps.INSTANCE, () -> { }, "host");
        ProtocolConnection<Object> clientEnd = Protocols.open(link[1], PlainOps.INSTANCE, () -> { }, null);
        new WorkspaceBinding<>(service, new WatchHub(service), WorkspaceActor.LOCAL, "host",
                PlainOps.INSTANCE).installOn(serverEnd);
        workspace = Workspace.of(clientEnd);
    }

    @After
    public void closeWorkspace() {
        WorkbenchExtensions.resetForTesting();
        Protocols.resetForTesting();
    }

    /**
     * The acceptance criterion: a file type this repository ships is there on every host, and no host
     * asked for it.
     */
    @Test
    public void aShippedExtensionIsActiveOnAPlainWorkbench() {
        Workbench workbench = new Workbench(workspace);
        try {
            assertNotNull("the Notes kind is registered on a workbench nobody configured",
                    workbench.kinds().byId(NotesKind.ID));
        } finally {
            workbench.dispose();
        }
    }

    /**
     * ...and one from a layer ABOVE the workbench is there too, which a list could never manage.
     *
     * <p>The shader graph lives in {@code app/}, above {@code workbench/}, so the method that used to
     * name what this repository ships could not see it — and the consequence was not tidiness: an
     * <em>application</em> had to contribute it, which made a feature's availability a product's
     * responsibility and put it out of reach of every other product. A {@code ServiceLoader} points the
     * other way, so a mod's extension arrives through exactly the door ours does.</p>
     *
     * <p>Asserted through {@link WorkbenchExtensions#byId}, which is what a manifest's {@code with(...)}
     * resolves against — the question is whether the id is <b>available</b>, not whether anything
     * enabled it.</p>
     */
    @Test
    public void anExtensionFromALayerAboveTheWorkbenchIsFoundToo() {
        assertNotNull("the shader graph lives in app/, so nothing in workbench/ could have listed it",
                WorkbenchExtensions.byId(ShaderGraphContribution.ID));
    }

    /**
     * <b>W6.5's whole claim: an engine with no extensions is not an IDE.</b>
     *
     * <p>{@code new Workbench(workspace)} used to ship Project, Problems and Notifications whether an
     * application asked or not, so a graph-only product got an empty Problems panel and a file tree it
     * had no use for. Nothing could make this assertion before: the registrations were three calls in
     * the constructor.</p>
     *
     * <p><b>And it still opens a file</b>, which is the half that keeps this honest — an engine that
     * registered nothing because it was broken would satisfy the first assertion perfectly.</p>
     */
    @Test
    public void aWorkbenchThatEnablesNothingHasNoPanelsAndStillOpensAFile() {
        Workbench workbench = new Workbench(workspace, List.of());
        try {
            assertEquals("an engine that enables no extensions registered a tool window anyway",
                    List.of(), workbench.panels().descriptors().stream()
                            .filter(DockPanelDescriptor::isSingleton)
                            .map(DockPanelDescriptor::typeId)
                            .sorted()
                            .toList());
            // THE COUNTER-CONTROL, and it has to be something SYNCHRONOUS: opening a file is a round
            // trip, so asserting `openPaths()` here would be asserting that the fixture pumps its
            // transport. The engine's own fallback text kind is the honest test that it is alive --
            // an engine that registered nothing because it was broken would satisfy the list above
            // perfectly, and this is what separates that from "it enabled nothing".
            assertNotNull("an engine with no extensions has no text kind either, so it is not an "
                            + "engine -- the assertion above is measuring a broken constructor",
                    workbench.kinds().forResource(Resource.of(PROJECT, "Main.java")));
        } finally {
            workbench.dispose();
        }
    }

    /** ...and it goes when the workbench does, because activate() hands back what it registered. */
    @Test
    public void whatAnExtensionRegisteredIsWithdrawnWithTheWorkbench() {
        AtomicBoolean released = new AtomicBoolean();
        WorkbenchExtensions.contribute(new WorkbenchExtension() {
            @Override
            public String id() {
                return "test:withdrawn";
            }

            @Override
            public Disposable activate(WorkbenchContext workbench) {
                return () -> released.set(true);
            }
        });

        Workbench workbench = new Workbench(workspace);
        assertFalse("nothing is withdrawn while the workbench is alive", released.get());
        workbench.dispose();
        assertTrue("the handle activate() returned was disposed with the workbench", released.get());
    }

    /** An extension is handed the CONTEXT, which is the whole point of there being one. */
    @Test
    public void anExtensionIsActivatedAgainstTheContext() {
        AtomicReference<WorkbenchContext> seen = new AtomicReference<>();
        WorkbenchExtensions.contribute(new WorkbenchExtension() {
            @Override
            public String id() {
                return "test:context";
            }

            @Override
            public Disposable activate(WorkbenchContext workbench) {
                seen.set(workbench);
                return () -> { };
            }
        });

        Workbench workbench = new Workbench(workspace);
        try {
            assertNotNull("an extension was activated", seen.get());
            assertEquals("...against this workbench", workspace, seen.get().workspace());
            assertNotNull("...and the projects model is reachable from it", seen.get().projects());
        } finally {
            workbench.dispose();
        }
    }

    /**
     * <b>An extension that throws costs its own feature and nothing else.</b>
     *
     * <p>Activation runs while a workbench is being built, so letting one out would take the whole
     * application down over a mod's mistake — the same isolation a dock banner provider gets, and for
     * the reason that one was written: a workbench where nothing opens because something wanted to add
     * a file type is the wrong trade in every direction.</p>
     */
    @Test
    public void anExtensionThatThrowsDoesNotTakeTheWorkbenchDown() {
        WorkbenchExtensions.contribute(new WorkbenchExtension() {
            @Override
            public String id() {
                return "test:broken";
            }

            @Override
            public Disposable activate(WorkbenchContext workbench) {
                throw new IllegalStateException("this extension is broken");
            }
        });
        AtomicBoolean laterOne = new AtomicBoolean();
        WorkbenchExtensions.contribute(new WorkbenchExtension() {
            @Override
            public String id() {
                return "test:after-the-broken-one";
            }

            @Override
            public Disposable activate(WorkbenchContext workbench) {
                laterOne.set(true);
                return () -> { };
            }
        });

        Workbench workbench = new Workbench(workspace);
        try {
            assertTrue("the one after it still ran", laterOne.get());
            assertNotNull("and the workbench is built", workbench.kinds().byId(NotesKind.ID));
        } finally {
            workbench.dispose();
        }
    }

    /** Two extensions cannot claim one id — a packaging mistake, said out loud rather than resolved. */
    @Test
    public void oneIdIsOneExtension() {
        WorkbenchExtensions.contribute(new WorkbenchExtension() {
            @Override
            public String id() {
                return "test:twice";
            }

            @Override
            public Disposable activate(WorkbenchContext workbench) {
                return () -> { };
            }
        });
        int before = WorkbenchExtensions.all().size();
        WorkbenchExtensions.contribute(new WorkbenchExtension() {
            @Override
            public String id() {
                return "test:twice";
            }

            @Override
            public Disposable activate(WorkbenchContext workbench) {
                return () -> { };
            }
        });
        assertEquals("the second one under that id was refused", before, WorkbenchExtensions.all().size());
    }
}
