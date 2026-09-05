package com.crystalgui.widget;

import com.crystalgui.core.collection.tree.TreeDataSource;
import com.crystalgui.core.property.ObservableList;
import com.crystalgui.graph.NodeTypeRegistry;
import com.crystalgui.graph.PortDirection;
import com.crystalgui.graph.port.BasicPortType;
import com.crystalgui.ui.dom.NodeContract;
import com.crystalgui.ui.dom.NodeKinds;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.widget.canvas.CanvasView;
import com.crystalgui.widget.collection.list.ListView;
import com.crystalgui.widget.collection.table.TableView;
import com.crystalgui.widget.collection.tree.TreeView;
import com.crystalgui.widget.config.Configurator;
import com.crystalgui.widget.config.ConfiguratorGroup;
import com.crystalgui.widget.config.ConfiguratorPanel;
import com.crystalgui.widget.config.control.ArrayControl;
import com.crystalgui.widget.config.control.AssetControl;
import com.crystalgui.widget.config.control.BooleanControl;
import com.crystalgui.widget.config.control.ColorControl;
import com.crystalgui.widget.config.control.HeaderControl;
import com.crystalgui.widget.config.control.InfoControl;
import com.crystalgui.widget.config.control.MaskControl;
import com.crystalgui.widget.config.control.MatrixControl;
import com.crystalgui.widget.config.control.NumberControl;
import com.crystalgui.widget.config.control.SelectControl;
import com.crystalgui.widget.config.control.SliderControl;
import com.crystalgui.widget.config.control.TextControl;
import com.crystalgui.widget.config.control.VectorControl;
import com.crystalgui.widget.config.inspector.Inspector;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.control.Checkbox;
import com.crystalgui.widget.display.ProgressBar;
import com.crystalgui.widget.display.RadarChart;
import com.crystalgui.widget.control.Slider;
import com.crystalgui.widget.control.Switch;
import com.crystalgui.widget.display.SymbolIcon;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.widget.dnd.DragGhost;
import com.crystalgui.widget.dnd.InsertionMarker;
import com.crystalgui.widget.dnd.Resizer;
import com.crystalgui.widget.texteditor.find.SearchReplaceBar;
import com.crystalgui.widget.texteditor.doc.DocumentationPopup;
import com.crystalgui.widget.texteditor.suggest.CompletionPopup;
import com.crystalgui.widget.texteditor.TextEditor;
import com.crystalgui.widget.composite.ColorSelector;
import com.crystalgui.widget.composite.SearchField;
import com.crystalgui.widget.graph.GraphNode;
import com.crystalgui.widget.graph.GraphView;
import com.crystalgui.widget.graph.NodePort;
import com.crystalgui.widget.graph.NodeWireLayer;
import com.crystalgui.widget.graph.node.NodeCreationMenu;
import com.crystalgui.widget.layout.PageStack;
import com.crystalgui.widget.layout.SplitView;
import com.crystalgui.widget.layout.Tab;
import com.crystalgui.widget.layout.TabView;
import com.crystalgui.widget.overlay.Dialog;
import com.crystalgui.widget.overlay.Dropdown;
import com.crystalgui.widget.overlay.Menu;
import com.crystalgui.widget.overlay.MenuItem;
import com.crystalgui.widget.overlay.Popover;
import com.crystalgui.widget.overlay.Tooltip;
import com.crystalgui.widget.scroll.Scroller;
import com.crystalgui.widget.scroll.ScrollerView;
import com.crystalgui.widget.text.MarkupView;
import com.crystalgui.widget.text.UIText;

/**
 * <b>The widget library's kinds</b> — every {@code widget.*} node a description can decode into.
 *
 * <p>{@link NodeKinds} says why this exists rather than a {@code static {}} block on each widget:
 * a class registering itself is registered only once something has loaded it, so the registry's
 * contents become a function of what a given JVM happened to touch — which is fine for a UI built
 * in-process and wrong for one that arrives over a wire.</p>
 *
 * <p><b>One entry per widget, added in the same commit that ports it.</b> The list is the thing that
 * goes stale — the old engine's equivalent shipped saying "eighteen" while twenty were registered —
 * so {@code NodeKindsCoverageTest} fails on any class declaring a {@code NAME} that nothing here
 * names. That is the same anti-rot shape {@code WidgetContractCoverageTest} and
 * {@code StyleGovernanceTest} already use, and it is what makes a central list safe to keep.</p>
 *
 * <p>Grouped by tier, in the order {@code LayeringTest} enforces, so a reader can see at a glance
 * what a tier holds. The other layers get their own service — {@code chrome}, {@code desktop} and
 * {@code workbench} each declare theirs — because the point of the service is that a LAYER speaks
 * for itself.</p>
 */
public final class Widgets implements NodeKinds {

    /** {@code ServiceLoader} needs a public no-argument constructor. */
    public Widgets() {
    }

    @Override
    public void register() {
        // ── control ──────────────────────────────────────────────────────────
        UIElementRegistry.register(Button.NAME, Button::new, Button.CONTRACT);
        UIElementRegistry.register(Checkbox.NAME, Checkbox::new, Checkbox.CONTRACT);
        UIElementRegistry.register(Switch.NAME, Switch::new, Switch.CONTRACT);
        UIElementRegistry.register(Slider.NAME, Slider::new, Slider.CONTRACT);
        UIElementRegistry.register(ProgressBar.NAME, ProgressBar::new, ProgressBar.CONTRACT);
        UIElementRegistry.register(TextField.NAME, TextField::new, TextField.CONTRACT);
        // INERT: a symbol icon carries nothing over a wire. Registered all the same, because a kind
        // that is not registered has no tag, and `symbolicon { }` is how a theme reaches it -- the old
        // engine answered the lowercased class name instead, which is the fallback that left 32 tags
        // matching by accident and ToolWindowFrame matching nothing at all.
        UIElementRegistry.register(SymbolIcon.NAME, SymbolIcon::new, NodeContract.INERT);
        // INERT: a chart is a readout, so it reports no events and carries no wire state of its own.
        UIElementRegistry.register(RadarChart.NAME, RadarChart::new, NodeContract.INERT);

        // ── text ─────────────────────────────────────────────────────────────
        // The engine's one text leaf AND the widget layer's label -- D15 merged `ui.box.TextNode` into
        // it, so the `text` tag is registered here rather than from a static block in `ui.box`.
        UIElementRegistry.register(UIText.NAME, UIText::new, UIText.CONTRACT);
        // NO CONTRACT: a MarkupDocument is not a StateType and never crosses a wire -- what travels is
        // whatever produced it. Registered all the same, because `markupview { }` is how 35 rules in the
        // sheets reach it and this engine has no lowercased-class-name fallback to match them by.
        UIElementRegistry.register(MarkupView.NAME, MarkupView::new, NodeContract.INERT);

        // ── scroll ───────────────────────────────────────────────────────────
        UIElementRegistry.register(Scroller.NAME, Scroller::new, NodeContract.INERT);
        UIElementRegistry.register(ScrollerView.NAME, ScrollerView::new, NodeContract.INERT);

        // ── overlay ──────────────────────────────────────────────────────────
        UIElementRegistry.register(Popover.NAME, Popover::new, Popover.CONTRACT);
        UIElementRegistry.register(Menu.NAME, Menu::new, Menu.CONTRACT);
        UIElementRegistry.register(MenuItem.NAME, MenuItem::new, MenuItem.CONTRACT);
        UIElementRegistry.register(Dropdown.NAME, Dropdown::new, Dropdown.CONTRACT);
        UIElementRegistry.register(Tooltip.NAME, Tooltip::new, Tooltip.CONTRACT);

        // ── 6.2: the dialogs and the layout composites ─────────────────────────────
        UIElementRegistry.register(Dialog.NAME, Dialog::new, Dialog.CONTRACT);
        UIElementRegistry.register(SplitView.NAME, SplitView::new, SplitView.CONTRACT);
        UIElementRegistry.register(TabView.NAME, TabView::new, TabView.CONTRACT);
        UIElementRegistry.register(Tab.NAME, Tab::new, Tab.CONTRACT);
        // PageStack is shell chrome with a back stack -- localOnly, so it registers a kind for the
        // cascade's sake (`pagestack { }` is how a theme reaches it) and nothing decodes into it.
        UIElementRegistry.register(PageStack.NAME, PageStack::new, NodeContract.INERT);

        // ── 6.3: the collections and the shell's chrome ─────────────────────────────
        //
        // ALL INERT. ListView and TableView are localOnly with a reason WidgetCensus already
        // records -- a row stream has no wire form until M7 -- and the rest are shell chrome. What
        // the registration buys is the CASCADE: 32 shipped rules name `quickpick` and 8 name
        // `treeview`, and without a kind each would report `crystalgui:element` and match none of
        // them. A generic widget takes a raw factory, which is what a decode would produce anyway.
        UIElementRegistry.register(ListView.NAME, () -> new ListView<>(new ObservableList<>()),
                NodeContract.INERT);
        UIElementRegistry.register(TreeView.NAME, () -> new TreeView<>(TreeDataSource.empty()),
                NodeContract.INERT);
        UIElementRegistry.register(TableView.NAME, () -> new TableView<>(new ObservableList<>()),
                NodeContract.INERT);
        // The inspector is INERT for the reason its whole kit is: a descriptor is what travels, not
        // the panel built from one. The kind is for the cascade.
        UIElementRegistry.register(Inspector.NAME, Inspector::new, NodeContract.INERT);

        // ── 6.4: the canvas and the graph ───────────────────────────────────────────
        //
        // ALL INERT, and for the graph the reason is stronger than "no wire form yet": a graph's
        // state IS its GraphDocument, which has its own codec and its own edits, so a description
        // that carried the widgets would be a second, worse copy of the model. What the kinds buy is
        // the cascade -- graph.css names `graphview`, `graphnode`, `nodeport`, `nodecreationmenu`
        // and `canvasview`, and without a kind each would report `crystalgui:element` and match none
        // of them.
        //
        // NodeWireLayer has no entry: its only constructor takes the view it draws for, so there is
        // nothing a registry could build, and no sheet names it -- the wires are styled through the
        // view. A kind for the cascade would be a kind for nobody.
        UIElementRegistry.register(CanvasView.NAME, CanvasView::new, NodeContract.INERT);
        UIElementRegistry.register(GraphView.NAME, GraphView::new, NodeContract.INERT);
        UIElementRegistry.register(GraphNode.NAME, () -> new GraphNode(""), NodeContract.INERT);
        UIElementRegistry.register(NodePort.NAME,
                () -> new NodePort(PortDirection.INPUT, new BasicPortType("any"), ""),
                NodeContract.INERT);
        UIElementRegistry.register(NodeCreationMenu.NAME,
                () -> new NodeCreationMenu(new NodeTypeRegistry()), NodeContract.INERT);
        // No sheet names `nodewirelayer` -- the wires take their look from the view that owns them --
        // so this kind is for the RULE rather than the cascade: a concrete node declaring no NAME
        // inherits one and is indistinguishable from a widget that forgot to declare its own.
        UIElementRegistry.register(NodeWireLayer.NAME, NodeWireLayer::new, NodeContract.INERT);

        // ── 6.4: the canvas and the graph ───────────────────────────────────────────
        //
        // ALL INERT, and for the graph the reason is stronger than "no wire form yet": a graph's
        // state IS its GraphDocument, which has its own codec and its own edits, so a description
        // that carried the widgets would be a second, worse copy of the model. What the kinds buy is
        // the cascade -- graph.css names `graphview`, `graphnode`, `nodeport`, `nodecreationmenu`
        // and `canvasview`, and without a kind each would report `crystalgui:element` and match none
        // of them.
        //
        // NodeWireLayer has no entry: its only constructor takes the view it draws for, so there is
        // nothing a registry could build, and no sheet names it -- the wires are styled through the
        // view. A kind for the cascade would be a kind for nobody.
        UIElementRegistry.register(CanvasView.NAME, CanvasView::new, NodeContract.INERT);
        UIElementRegistry.register(GraphView.NAME, GraphView::new, NodeContract.INERT);
        UIElementRegistry.register(GraphNode.NAME, () -> new GraphNode(""), NodeContract.INERT);
        UIElementRegistry.register(NodePort.NAME,
                () -> new NodePort(PortDirection.INPUT, new BasicPortType("any"), ""),
                NodeContract.INERT);
        UIElementRegistry.register(NodeCreationMenu.NAME,
                () -> new NodeCreationMenu(new NodeTypeRegistry()), NodeContract.INERT);
        // No sheet names `nodewirelayer` -- the wires take their look from the view that owns them --
        // so this kind is for the RULE rather than the cascade: a concrete node declaring no NAME
        // inherits one and is indistinguishable from a widget that forgot to declare its own.
        UIElementRegistry.register(NodeWireLayer.NAME, NodeWireLayer::new, NodeContract.INERT);

        // ── 6.2: the config kit ─────────────────────────────────────────────────────
        //
        // ALL INERT, and all of them for the same reason: WidgetCensus already marks the kit
        // localOnly -- a descriptor-driven form is built from a ConfigDescriptor, which is what
        // travels, not the controls it produces. What the registration buys is the CASCADE: without
        // a kind a control reports `crystalgui:element`, so `numbercontrol { }` in a theme matches
        // nothing and every `element` rule reaches it instead.
        //
        // EACH TAKES A NO-ARGUMENT FORM over a NEUTRAL descriptor -- an unlabelled control of that
        // kind, which is a real thing rather than a placeholder -- so a description CAN decode into
        // one, which is what makes their contracts reachable from a server's own tree.
        UIElementRegistry.register(Configurator.NAME, Configurator::new, NodeContract.INERT);
        UIElementRegistry.register(ConfiguratorGroup.NAME, ConfiguratorGroup::new, NodeContract.INERT);
        UIElementRegistry.register(ConfiguratorPanel.NAME, ConfiguratorPanel::new, NodeContract.INERT);
        UIElementRegistry.register(ArrayControl.NAME, ArrayControl::new, NodeContract.INERT);
        UIElementRegistry.register(AssetControl.NAME, AssetControl::new, NodeContract.INERT);
        UIElementRegistry.register(BooleanControl.NAME, BooleanControl::new, NodeContract.INERT);
        UIElementRegistry.register(ColorControl.NAME, ColorControl::new, NodeContract.INERT);
        UIElementRegistry.register(HeaderControl.NAME, HeaderControl::new, NodeContract.INERT);
        UIElementRegistry.register(InfoControl.NAME, InfoControl::new, NodeContract.INERT);
        UIElementRegistry.register(MaskControl.NAME, MaskControl::new, NodeContract.INERT);
        UIElementRegistry.register(MatrixControl.NAME, MatrixControl::new, NodeContract.INERT);
        UIElementRegistry.register(NumberControl.NAME, NumberControl::new, NodeContract.INERT);
        UIElementRegistry.register(SelectControl.NAME, SelectControl::new, NodeContract.INERT);
        UIElementRegistry.register(SliderControl.NAME, SliderControl::new, NodeContract.INERT);
        UIElementRegistry.register(TextControl.NAME, TextControl::new, NodeContract.INERT);
        UIElementRegistry.register(VectorControl.NAME, VectorControl::new, NodeContract.INERT);

        // ── dnd ──────────────────────────────────────────────────────────────
        UIElementRegistry.register(DragGhost.NAME, DragGhost::new, NodeContract.INERT);
        UIElementRegistry.register(InsertionMarker.NAME, InsertionMarker::new, NodeContract.INERT);
        // A grab handle. Built by `Resizer.install` for a resizable node rather than decoded, so the factory
        // makes an UNBOUND one -- registered all the same, because a concrete node that declares no kind
        // inherits `crystalgui:element` and would match every bare `element` rule there is.
        UIElementRegistry.register(Resizer.NAME, Resizer::new, NodeContract.INERT);
        // AND THE CASCADE DRIVES THEM. `resize` is ambient, like `overflow` making any element a scroll
        // container, so a node grows handles because a sheet says so rather than because it was
        // constructed as a resizable kind. @see Resizer#driveFromStyle
        Resizer.driveFromStyle();

        // ── texteditor ───────────────────────────────────────────────────────
        //
        // The editor and its three popups. Only `texteditor`, `completionpopup` and
        // `documentationpopup` are named by a shipped rule -- `searchreplacebar` is styled entirely by
        // class -- and all four are registered regardless, because a concrete node declaring no kind
        // inherits its supertype's and would match every `scrollerview` or `popover` rule there is.
        UIElementRegistry.register(TextEditor.NAME, TextEditor::new, NodeContract.INERT);
        UIElementRegistry.register(CompletionPopup.NAME, CompletionPopup::new, NodeContract.INERT);
        UIElementRegistry.register(DocumentationPopup.NAME, DocumentationPopup::new, NodeContract.INERT);
        // A find bar is built FOR an editor and never decoded, so the factory makes one over a fresh
        // editor rather than pretending a bar can exist without one.
        UIElementRegistry.register(SearchReplaceBar.NAME,
                () -> new SearchReplaceBar(new TextEditor()), NodeContract.INERT);

        // ── form ─────────────────────────────────────────────────────────────
        UIElementRegistry.register(SearchField.NAME, SearchField::new, SearchField.CONTRACT);
        UIElementRegistry.register(ColorSelector.NAME, ColorSelector::new, ColorSelector.CONTRACT);
    }
}
