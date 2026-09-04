package com.crystalgui.workbench;

import java.util.List;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.document.Document;
import com.crystalgui.document.DocumentKind;
import com.crystalgui.document.DocumentKinds;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.client.Workspace;
import com.crystalgui.fs.client.WorkspaceDocuments;
import com.crystalgui.fs.client.WorkspaceProjects;
import com.crystalgui.text.diagnostic.Markers;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.texteditor.TextEditor;
import com.crystalgui.workbench.decoration.FileDecorations;
import com.crystalgui.workbench.editor.EditorService;
import com.crystalgui.workbench.dock.DockArea;
import com.crystalgui.workbench.dock.layout.DockPanelRef;
import com.crystalgui.workbench.dock.panel.DockPanelDescriptor;
import com.crystalgui.workbench.dock.panel.DockPanelRegistry;
import com.crystalgui.workbench.toolwindow.ToolWindowKind;
import com.crystalgui.workbench.toolwindow.ToolWindowManager;

/**
 * <b>What an extension is written against</b> — the engine's surface, without the engine's class.
 *
 * <p>{@code Workbench} implements it, and everything outside {@code com.crystalgui.workbench} names
 * this instead: the applications in {@code app/}, the language stack's Run shell, a mod's own panel.
 * {@code LayeringTest} enforces it, which is the whole reason this is an interface rather than the
 * class being handed over — an engine that can be named can be reached into, and eleven of these
 * methods are all {@code language/} has ever needed.</p>
 *
 * <h3>Measured, not designed</h3>
 *
 * <p>The surface is the union of what the outside actually calls today: seventeen methods from
 * {@code app/}, eleven from {@code language/}, eight from the harness and three from the 1.7.10 loader.
 * Nothing was added on the grounds that an extension might want it. What is deliberately <em>absent</em>
 * is as informative: no {@code getStyleEngine}, no direct dock-tree surgery, no way to ask which host
 * this is.</p>
 *
 * <h3>Names are today's, on purpose</h3>
 *
 * <p>It is {@code toolWindowManager()} rather than the narrower {@code toolWindows()} facade the
 * plan calls for, and there is no {@code statusBar()} because a status bar is still a process-wide
 * static. Both are known and both are somebody else's step; declaring the target shape now would mean
 * two spellings of one thing for as long as it took to get there, which is the drift this interface
 * exists to prevent.</p>
 */
public interface WorkbenchContext {

    // ── The workspace under it ──────────────────────────────────────────────────────────────────

    /** The workspace this workbench is a view of. */
    Workspace workspace();

    /** The projects and their listings. @see WorkspaceProjects */
    WorkspaceProjects projects();

    /** The open documents, one per resource. */
    WorkspaceDocuments documents();

    /** Every kind of document this workbench can open. @see #contribute */
    DocumentKinds kinds();

    /** The tabs over those documents — the one open lane. */
    EditorService editors();

    /** Problems, by owner. */
    Markers markers();

    /** What the explorer draws beside a file — colour, badge, tooltip. */
    FileDecorations decorations();

    // ── The chrome ──────────────────────────────────────────────────────────────────────────────

    /** The dock: what is on screen and where. */
    DockArea dock();

    /** typeId → what it is and how to build one. */
    DockPanelRegistry<UIElement> panels();

    /** The tool windows and their regions. */
    ToolWindowManager toolWindowManager();

    /** The window this workbench is in, or null before it is attached to one. */
    @Nullable
    UIDocument document();

    // ── What is active ──────────────────────────────────────────────────────────────────────────

    /** The file in front, or null when what is in front is not a file. */
    @Nullable
    CgPath activeFilePath();

    /** The text editor in front, or null when what is in front is not one. */
    @Nullable
    TextEditor activeEditor();

    /** The text editor showing {@code resource}, if one is open. */
    @Nullable
    TextEditor editorFor(@Nullable Resource resource);

    /** The text editor showing {@code path}, if one is open. */
    @Nullable
    TextEditor editorFor(CgPath path);

    /** The document open for {@code path}, if any. */
    @Nullable
    Document documentFor(CgPath path);

    /** Every file open in a tab. */
    List<CgPath> openPaths();

    // ── Opening and saving ──────────────────────────────────────────────────────────────────────

    void openFile(CgPath path);

    void openFile(CgPath path, @Nullable Runnable onOpened);

    void openResource(Resource resource);

    void openResource(Resource resource, @Nullable Runnable onOpened);

    /** @return whether there was something to save */
    boolean saveActiveFile();

    // ── Contributing ────────────────────────────────────────────────────────────────────────────

    /** Adds a file type. @see DocumentKind */
    WorkbenchContext contribute(DocumentKind kind);

    /** Adds a file type, plus the extensions that open into it. */
    WorkbenchContext contribute(DocumentKind kind, String... extensions);

    /**
     * Adds a tool window from one declaration. @see ToolWindowKind
     *
     * <p>Here rather than on {@code toolWindowManager()} — which is where the plan put it — because a
     * kind derives a command and an accelerator as well as a panel, and the manager can register
     * neither: it holds the regions and the panel registry, not the workbench's keymap.</p>
     *
     * @return a handle that withdraws what can be withdrawn — the command, its key and the badge
     *         subscription. The panel type itself stays, because the registry it is in dies with this
     *         workbench and a half-removed panel type is worse than a kept one
     */
    Disposable registerToolWindow(ToolWindowKind kind);

    /** Adds a panel type and how to build one. */
    WorkbenchContext registerPanel(DockPanelDescriptor descriptor,
                                   Function<DockPanelRef, UIElement> factory);

    /** @return whether the panel was opened */
    boolean showPanel(String typeId);

    /** @return whether the panel is on screen */
    boolean isPanelOpen(String typeId);

    /** Brings a panel forward, opening it if it is closed. @return whether it is now showing */
    boolean revealPanel(String typeId);
}
