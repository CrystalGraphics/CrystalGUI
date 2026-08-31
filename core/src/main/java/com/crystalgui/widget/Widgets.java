package com.crystalgui.widget;

import com.crystalgui.core.collection.tree.TreeDataSource;
import com.crystalgui.core.property.ObservableList;
import com.crystalgui.graph.NodeTypeRegistry;
import com.crystalgui.graph.PortDirection;
import com.crystalgui.graph.port.BasicPortType;
import com.crystalgui.ui.dom.NodeContract;
import com.crystalgui.ui.dom.NodeKinds;
import com.crystalgui.ui.dom.UINodeRegistry;
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
import com.crystalgui.widget.control.ProgressBar;
import com.crystalgui.widget.control.Slider;
import com.crystalgui.widget.control.Switch;
import com.crystalgui.widget.control.SymbolIcon;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.widget.dnd.DragGhost;
import com.crystalgui.widget.dnd.InsertionMarker;
import com.crystalgui.widget.form.ColorSelector;
import com.crystalgui.widget.form.SearchField;
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
        UINodeRegistry.register(Button.NAME, Button::new, Button.CONTRACT);
        UINodeRegistry.register(Checkbox.NAME, Checkbox::new, Checkbox.CONTRACT);
        UINodeRegistry.register(Switch.NAME, Switch::new, Switch.CONTRACT);
        UINodeRegistry.register(Slider.NAME, Slider::new, Slider.CONTRACT);
        UINodeRegistry.register(ProgressBar.NAME, ProgressBar::new, ProgressBar.CONTRACT);
        UINodeRegistry.register(TextField.NAME, TextField::new, TextField.CONTRACT);
        // INERT: a symbol icon carries nothing over a wire. Registered all the same, because a kind
        // that is not registered has no tag, and `symbolicon { }` is how a theme reaches it -- the old
        // engine answered the lowercased class name instead, which is the fallback that left 32 tags
        // matching by accident and ToolWindowFrame matching nothing at all.
        UINodeRegistry.register(SymbolIcon.NAME, SymbolIcon::new, NodeContract.INERT);

        // ── text ─────────────────────────────────────────────────────────────
        // The engine's one text leaf AND the widget layer's label -- D15 merged `ui.box.TextNode` into
        // it, so the `text` tag is registered here rather than from a static block in `ui.box`.
        UINodeRegistry.register(UIText.NAME, UIText::new, UIText.CONTRACT);
        // NO CONTRACT: a MarkupDocument is not a StateType and never crosses a wire -- what travels is
        // whatever produced it. Registered all the same, because `markupview { }` is how 35 rules in the
        // sheets reach it and this engine has no lowercased-class-name fallback to match them by.
        UINodeRegistry.register(MarkupView.NAME, MarkupView::new, NodeContract.INERT);

        // ── scroll ───────────────────────────────────────────────────────────
        UINodeRegistry.register(Scroller.NAME, Scroller::new, NodeContract.INERT);
        UINodeRegistry.register(ScrollerView.NAME, ScrollerView::new, NodeContract.INERT);

        // ── overlay ──────────────────────────────────────────────────────────
        UINodeRegistry.register(Popover.NAME, Popover::new, Popover.CONTRACT);
        UINodeRegistry.register(Menu.NAME, Menu::new, Menu.CONTRACT);
        UINodeRegistry.register(MenuItem.NAME, MenuItem::new, MenuItem.CONTRACT);
        UINodeRegistry.register(Dropdown.NAME, Dropdown::new, Dropdown.CONTRACT);
        UINodeRegistry.register(Tooltip.NAME, Tooltip::new, Tooltip.CONTRACT);

        // ── 6.2: the dialogs and the layout composites ─────────────────────────────
        UINodeRegistry.register(Dialog.NAME, Dialog::new, Dialog.CONTRACT);
        UINodeRegistry.register(SplitView.NAME, SplitView::new, SplitView.CONTRACT);
        UINodeRegistry.register(TabView.NAME, TabView::new, TabView.CONTRACT);
        UINodeRegistry.register(Tab.NAME, Tab::new, Tab.CONTRACT);
        // PageStack is shell chrome with a back stack -- localOnly, so it registers a kind for the
        // cascade's sake (`pagestack { }` is how a theme reaches it) and nothing decodes into it.
        UINodeRegistry.register(PageStack.NAME, PageStack::new, NodeContract.INERT);

        // ── 6.3: the collections and the shell's chrome ─────────────────────────────
        //
        // ALL INERT. ListView and TableView are localOnly with a reason WidgetCensus already
        // records -- a row stream has no wire form until M7 -- and the rest are shell chrome. What
        // the registration buys is the CASCADE: 32 shipped rules name `quickpick` and 8 name
        // `treeview`, and without a kind each would report `crystalgui:element` and match none of
        // them. A generic widget takes a raw factory, which is what a decode would produce anyway.
        UINodeRegistry.register(ListView.NAME, () -> new ListView<>(new ObservableList<>()),
                NodeContract.INERT);
        UINodeRegistry.register(TreeView.NAME, () -> new TreeView<>(TreeDataSource.empty()),
                NodeContract.INERT);
        UINodeRegistry.register(TableView.NAME, () -> new TableView<>(new ObservableList<>()),
                NodeContract.INERT);
        // The inspector is INERT for the reason its whole kit is: a descriptor is what travels, not
        // the panel built from one. The kind is for the cascade.
        UINodeRegistry.register(Inspector.NAME, Inspector::new, NodeContract.INERT);

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
        UINodeRegistry.register(CanvasView.NAME, CanvasView::new, NodeContract.INERT);
        UINodeRegistry.register(GraphView.NAME, GraphView::new, NodeContract.INERT);
        UINodeRegistry.register(GraphNode.NAME, () -> new GraphNode(""), NodeContract.INERT);
        UINodeRegistry.register(NodePort.NAME,
                () -> new NodePort(PortDirection.INPUT, new BasicPortType("any"), ""),
                NodeContract.INERT);
        UINodeRegistry.register(NodeCreationMenu.NAME,
                () -> new NodeCreationMenu(new NodeTypeRegistry()), NodeContract.INERT);
        // No sheet names `nodewirelayer` -- the wires take their look from the view that owns them --
        // so this kind is for the RULE rather than the cascade: a concrete node declaring no NAME
        // inherits one and is indistinguishable from a widget that forgot to declare its own.
        UINodeRegistry.register(NodeWireLayer.NAME, NodeWireLayer::new, NodeContract.INERT);

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
        UINodeRegistry.register(CanvasView.NAME, CanvasView::new, NodeContract.INERT);
        UINodeRegistry.register(GraphView.NAME, GraphView::new, NodeContract.INERT);
        UINodeRegistry.register(GraphNode.NAME, () -> new GraphNode(""), NodeContract.INERT);
        UINodeRegistry.register(NodePort.NAME,
                () -> new NodePort(PortDirection.INPUT, new BasicPortType("any"), ""),
                NodeContract.INERT);
        UINodeRegistry.register(NodeCreationMenu.NAME,
                () -> new NodeCreationMenu(new NodeTypeRegistry()), NodeContract.INERT);
        // No sheet names `nodewirelayer` -- the wires take their look from the view that owns them --
        // so this kind is for the RULE rather than the cascade: a concrete node declaring no NAME
        // inherits one and is indistinguishable from a widget that forgot to declare its own.
        UINodeRegistry.register(NodeWireLayer.NAME, NodeWireLayer::new, NodeContract.INERT);

        // ── 6.2: the config kit ─────────────────────────────────────────────────────
        //
        // ALL INERT, and all of them for the same reason: WidgetCensus already marks the kit
        // localOnly -- a descriptor-driven form is built from a ConfigDescriptor, which is what
        // travels, not the controls it produces. What the registration buys is the CASCADE: without
        // a kind a control reports `crystalgui:element`, so `numbercontrol { }` in a theme matches
        // nothing and every `element` rule reaches it instead.
        //
        // NO FACTORY for the controls: each takes a ConfigDescriptor and there is no sensible
        // no-argument form of one, so they are registered for their names alone. Nothing decodes
        // into them, which is exactly what localOnly means.
        UINodeRegistry.register(Configurator.NAME, Configurator::new, NodeContract.INERT);
        UINodeRegistry.register(ConfiguratorGroup.NAME, ConfiguratorGroup::new, NodeContract.INERT);
        UINodeRegistry.register(ConfiguratorPanel.NAME, ConfiguratorPanel::new, NodeContract.INERT);
        UINodeRegistry.register(ArrayControl.NAME, ArrayControl::new, NodeContract.INERT);
        UINodeRegistry.register(AssetControl.NAME, AssetControl::new, NodeContract.INERT);
        UINodeRegistry.register(BooleanControl.NAME, BooleanControl::new, NodeContract.INERT);
        UINodeRegistry.register(ColorControl.NAME, ColorControl::new, NodeContract.INERT);
        UINodeRegistry.register(HeaderControl.NAME, HeaderControl::new, NodeContract.INERT);
        UINodeRegistry.register(InfoControl.NAME, InfoControl::new, NodeContract.INERT);
        UINodeRegistry.register(MaskControl.NAME, MaskControl::new, NodeContract.INERT);
        UINodeRegistry.register(MatrixControl.NAME, MatrixControl::new, NodeContract.INERT);
        UINodeRegistry.register(NumberControl.NAME, NumberControl::new, NodeContract.INERT);
        UINodeRegistry.register(SelectControl.NAME, SelectControl::new, NodeContract.INERT);
        UINodeRegistry.register(SliderControl.NAME, SliderControl::new, NodeContract.INERT);
        UINodeRegistry.register(TextControl.NAME, TextControl::new, NodeContract.INERT);
        UINodeRegistry.register(VectorControl.NAME, VectorControl::new, NodeContract.INERT);

        // ── dnd ──────────────────────────────────────────────────────────────
        UINodeRegistry.register(DragGhost.NAME, DragGhost::new, NodeContract.INERT);
        UINodeRegistry.register(InsertionMarker.NAME, InsertionMarker::new, NodeContract.INERT);

        // ── form ─────────────────────────────────────────────────────────────
        UINodeRegistry.register(SearchField.NAME, SearchField::new, SearchField.CONTRACT);
        UINodeRegistry.register(ColorSelector.NAME, ColorSelector::new, ColorSelector.CONTRACT);
    }
}
