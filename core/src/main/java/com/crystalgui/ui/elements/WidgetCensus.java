package com.crystalgui.ui.elements;

import com.crystalgui.ui.contract.WidgetContracts;
import com.crystalgui.ui.elements.canvas.CanvasView;
import com.crystalgui.ui.elements.chrome.Breadcrumbs;
import com.crystalgui.ui.elements.chrome.MenuBarView;
import com.crystalgui.ui.elements.chrome.NavigatorView;
import com.crystalgui.ui.elements.chrome.NotificationBalloons;
import com.crystalgui.ui.elements.chrome.NotificationsView;
import com.crystalgui.ui.elements.chrome.PageStack;
import com.crystalgui.ui.elements.chrome.ProblemsPanel;
import com.crystalgui.ui.elements.chrome.ProcessesPopover;
import com.crystalgui.ui.elements.chrome.ProgressStatusItem;
import com.crystalgui.ui.elements.chrome.QuickPick;
import com.crystalgui.ui.elements.chrome.StatusBarView;
import com.crystalgui.ui.elements.config.Configurator;
import com.crystalgui.ui.elements.config.ConfiguratorGroup;
import com.crystalgui.ui.elements.config.ConfiguratorPanel;
import com.crystalgui.ui.elements.config.control.ArrayControl;
import com.crystalgui.ui.elements.config.control.HeaderControl;
import com.crystalgui.ui.elements.config.control.InfoControl;
import com.crystalgui.ui.elements.desktop.Desktop;
import com.crystalgui.ui.elements.desktop.Taskbar;
import com.crystalgui.ui.elements.desktop.WindowFrame;
import com.crystalgui.ui.elements.desktop.WindowIcon;
import com.crystalgui.ui.elements.desktop.WindowPreview;
import com.crystalgui.ui.elements.desktop.WindowSwitcher;
import com.crystalgui.ui.elements.desktop.WindowThumbnail;
import com.crystalgui.ui.elements.dock.DockArea;
import com.crystalgui.ui.elements.dock.DockBannerBar;
import com.crystalgui.ui.elements.dock.DockGroup;
import com.crystalgui.ui.elements.dock.DockWindow;
import com.crystalgui.ui.elements.editor.CompletionPopup;
import com.crystalgui.ui.elements.editor.DocumentationPopup;
import com.crystalgui.ui.elements.editor.SearchReplaceBar;
import com.crystalgui.ui.elements.editor.TextEditor;
import com.crystalgui.ui.elements.graph.GraphNode;
import com.crystalgui.ui.elements.graph.GraphView;
import com.crystalgui.ui.elements.graph.NodeCreationMenu;
import com.crystalgui.ui.elements.graph.NodePort;
import com.crystalgui.ui.elements.graph.NodeWireLayer;
import com.crystalgui.ui.elements.inspector.Inspector;
import com.crystalgui.ui.elements.list.ListView;
import com.crystalgui.ui.elements.table.TableView;
import com.crystalgui.ui.elements.tree.TreeView;
import com.crystalgui.ui.elements.workbench.DiffView;
import com.crystalgui.ui.elements.workbench.MergeView;
import com.crystalgui.ui.elements.workbench.ProjectFileTree;
import com.crystalgui.ui.elements.workbench.RegionDropOverlay;
import com.crystalgui.ui.elements.workbench.RegionHost;
import com.crystalgui.ui.elements.workbench.StripeView;
import com.crystalgui.ui.elements.workbench.ToolWindowFrame;
import com.crystalgui.ui.elements.workbench.ViewContainer;
import com.crystalgui.ui.elements.workbench.Workbench;

/**
 * <b>Every widget that deliberately does not travel, and why.</b> {@code plan_ui_rewrite.md} M1.
 *
 * <p>A widget class is either contracted or listed here. There is no third state, and
 * {@code WidgetContractCoverageTest} enumerates the widget packages and fails on a class that is
 * neither — which is the same anti-rot shape {@code AGENTS.md} prescribes for the CSS property
 * registry, applied to the question it was invented for. Adding a widget fails that test until
 * somebody writes down which side of the line it is on, <b>which is the only moment the question is
 * cheap to answer.</b></p>
 *
 * <h3>Every reason here is one of four, and "nobody got to it" is not among them</h3>
 *
 * <ol>
 *   <li><b>View state.</b> Scroll offset, pan, zoom, a drag ghost's position. The same document/view
 *       boundary that keeps scroll out of the undo stack keeps it off the wire — a server pushing it
 *       would be fighting the person using the UI.</li>
 *   <li><b>Derived.</b> Built from something else that <em>does</em> travel: an Inspector is built from
 *       a descriptor, and its controls are contracted individually. Describing the container as well
 *       would be a second, disagreeing copy of the same fact.</li>
 *   <li><b>The IDE shell.</b> The editor, the graph, the workbench, the compositor. Their state is a
 *       document the workspace protocol already carries, or a layout the session record already
 *       carries.</li>
 *   <li><b>Blocked on a mechanism that has a name.</b> The collection views need rows as a
 *       <em>stream</em>, which is M7 and needs the mirror. Stated with the milestone, so it is a
 *       schedule rather than an omission.</li>
 * </ol>
 *
 * <p><b>Abstract classes are not listed.</b> They cannot be instantiated, so nothing can describe one;
 * the coverage test skips them, and a concrete subclass answers for itself.</p>
 */
public final class WidgetCensus {

    private WidgetCensus() {
    }

    private static boolean registered;

    /** Idempotent, so a test may call it without caring whether something else already did. */
    public static synchronized void register() {
        if (registered) return;
        registered = true;

        // ── 1. View state ────────────────────────────────────────────────────
        WidgetContracts.localOnly(Scroller.class,
                "View state. A scroll offset is where you are looking, not what you are looking at -- "
                        + "the same boundary that keeps it out of the undo stack keeps it off the wire.");
        WidgetContracts.localOnly(ScrollerView.class,
                "View state, as Scroller. The bars are a presentation of an offset that does not travel.");
        WidgetContracts.localOnly(CanvasView.class,
                "View state: pan and zoom. What is ON the canvas travels as its children.");
        WidgetContracts.localOnly(DragGhost.class,
                "Transient. It exists for the duration of one drag and is positioned by the input layer; "
                        + "there is no moment at which a peer could usefully be told about it.");
        WidgetContracts.localOnly(InsertionMarker.class,
                "Transient, as DragGhost -- a gap held open while a drag is over a list.");

        // ── 2. Derived from something that does travel ───────────────────────
        WidgetContracts.localOnly(Configurator.class,
                "Derived. Built from a ConfigDescriptor; the CONTROLS it builds are contracted "
                        + "individually, and describing the container as well would be a second copy of "
                        + "the same fact that could disagree with the first.");
        WidgetContracts.localOnly(ConfiguratorGroup.class, "Derived, as Configurator -- a section of one.");
        WidgetContracts.localOnly(ConfiguratorPanel.class, "Derived, as Configurator -- the scroller around one.");
        WidgetContracts.localOnly(Inspector.class,
                "Derived. An Inspector is a Configurator over whatever is selected; its controls carry "
                        + "the state.");
        WidgetContracts.localOnly(HeaderControl.class,
                "Derived: a section heading read from the descriptor. It has no value -- it is the one "
                        + "kind of ConfigControl that is not a ValueControl.");
        WidgetContracts.localOnly(InfoControl.class,
                "Derived: explanatory text read from the descriptor, as HeaderControl.");
        WidgetContracts.localOnly(SymbolIcon.class,
                "Derived. The glyph is chosen from a symbol's kind and modifiers, which is language "
                        + "data the analysis already carries.");
        WidgetContracts.localOnly(WindowIcon.class, "Derived: drawn from a window's own icon and title.");
        WidgetContracts.localOnly(WindowThumbnail.class,
                "Derived, and a PICTURE -- it mirrors a live window's subtree, or draws a snapshot of "
                        + "one. There is nothing here that is not somewhere else.");
        WidgetContracts.localOnly(WindowPreview.class, "Derived: the panel a WindowThumbnail sits in.");

        // ── 3. The IDE shell ─────────────────────────────────────────────────
        WidgetContracts.localOnly(TextEditor.class,
                "The editor. Its state is a DOCUMENT -- text, carets, folds -- and the workspace "
                        + "protocol already carries documents. Describing it as a widget would put the "
                        + "same file on the wire twice, in two formats, with no rule for which wins.");
        WidgetContracts.localOnly(SearchReplaceBar.class,
                "Part of the editor, and its state is a QUERY plus a cursor through the matches -- both "
                        + "derived from a document that does not travel as a widget either.");
        WidgetContracts.localOnly(CompletionPopup.class,
                "Part of the editor, and driven by the language engine rather than by a server.");
        WidgetContracts.localOnly(DocumentationPopup.class, "Part of the editor, as CompletionPopup.");
        WidgetContracts.localOnly(MarkupView.class,
                "Holds a parsed MarkupDocument, which has no wire form. What would travel is the SOURCE "
                        + "markup, and that is a document rather than a widget's state.");

        WidgetContracts.localOnly(GraphView.class,
                "The shader graph. Its state is a graph document with its own serialization, and the "
                        + "same two-formats objection applies as for the editor.");
        WidgetContracts.localOnly(GraphNode.class, "Part of the graph document.");
        WidgetContracts.localOnly(NodePort.class, "Part of the graph document.");
        WidgetContracts.localOnly(NodeWireLayer.class, "Part of the graph document -- it draws the edges.");
        WidgetContracts.localOnly(NodeCreationMenu.class, "Part of the graph, and built from the node registry.");

        WidgetContracts.localOnly(Workbench.class,
                "The IDE shell. Its layout is a WorkbenchSession record, which is persisted and restored "
                        + "by its own mechanism.");
        WidgetContracts.localOnly(ViewContainer.class, "Workbench shell -- a titled panel host.");
        WidgetContracts.localOnly(RegionHost.class, "Workbench shell -- one half of a region.");
        WidgetContracts.localOnly(RegionDropOverlay.class, "Workbench shell -- a drop target shown during a drag.");
        WidgetContracts.localOnly(StripeView.class, "Workbench shell -- the tool-window rail.");
        WidgetContracts.localOnly(ToolWindowFrame.class,
                "Workbench shell. A tool window's placement lives on its ToolWindowState record, which "
                        + "the session persists.");
        WidgetContracts.localOnly(ProjectFileTree.class,
                "Workbench shell, and its contents are the workspace filesystem -- already a protocol.");
        WidgetContracts.localOnly(DiffView.class, "Workbench shell -- a view over two documents.");
        WidgetContracts.localOnly(MergeView.class, "Workbench shell -- a view over three.");

        WidgetContracts.localOnly(DockArea.class,
                "Dock layout, persisted by DockLayout in the workbench session record.");
        WidgetContracts.localOnly(DockGroup.class, "Dock layout, as DockArea.");
        WidgetContracts.localOnly(DockWindow.class, "Dock layout: a torn-out editor window.");
        WidgetContracts.localOnly(DockBannerBar.class,
                "Dock chrome: a notice strip a panel raises about itself, so its content is the panel's "
                        + "and its presence is a local decision.");

        WidgetContracts.localOnly(Desktop.class,
                "The compositor. A window's identity and lifetime are ServerWindow's, one layer up -- "
                        + "which is the layer a mod actually uses.");
        WidgetContracts.localOnly(WindowFrame.class,
                "Compositor chrome. What a networked window IS lives in net.window; describing the frame "
                        + "would be a second answer to the same question.");
        WidgetContracts.localOnly(Taskbar.class, "Compositor chrome: the WindowRegistry, rendered.");
        WidgetContracts.localOnly(WindowSwitcher.class, "Compositor chrome: Mod+Tab, over the same registry.");

        WidgetContracts.localOnly(MenuBarView.class,
                "Shell chrome, and built from the CommandRegistry -- the menu model is the thing that "
                        + "would travel, not the bar.");
        WidgetContracts.localOnly(StatusBarView.class, "Shell chrome, built from contributed parts.");
        WidgetContracts.localOnly(Breadcrumbs.class, "Shell chrome, derived from the active file's path.");
        WidgetContracts.localOnly(NavigatorView.class, "Shell chrome -- the settings navigator.");
        WidgetContracts.localOnly(PageStack.class, "Shell chrome -- a page host with a back stack.");
        WidgetContracts.localOnly(QuickPick.class, "Shell chrome -- the palette's picker.");
        WidgetContracts.localOnly(ProblemsPanel.class, "Shell chrome, built from the DiagnosticSet.");
        WidgetContracts.localOnly(ProcessesPopover.class, "Shell chrome, built from the running-process list.");
        WidgetContracts.localOnly(ProgressStatusItem.class, "Shell chrome, built from a progress channel.");
        WidgetContracts.localOnly(NotificationsView.class, "Shell chrome, built from the Notifications store.");
        WidgetContracts.localOnly(NotificationBalloons.class, "Shell chrome, as NotificationsView.");

        // ── 4. Blocked on a mechanism that has a name ────────────────────────
        WidgetContracts.localOnly(ListView.class,
                "Blocked on M7. A list's contract is its ROWS, and rows have to be a stream -- the "
                        + "server sends a count and a template, the client asks for rows{from,to} as it "
                        + "scrolls. That needs the mirror (M2) underneath it. A contract carrying only "
                        + "the selection would describe a list whose contents never arrive, which is "
                        + "worse than saying nothing.");
        WidgetContracts.localOnly(TableView.class, "Blocked on M7, as ListView -- rows are a stream.");
        WidgetContracts.localOnly(TreeView.class,
                "Blocked on M7, as ListView. A tree additionally needs its expansion state, which is "
                        + "view state for a local tree and document state for a served one -- a "
                        + "distinction the row stream has to settle first.");
        WidgetContracts.localOnly(ArrayControl.class,
                "Blocked on M7. Its value is a List<Object> whose element type is whatever the "
                        + "descriptor says, so it has no wire form until collections do.");
    }
}
