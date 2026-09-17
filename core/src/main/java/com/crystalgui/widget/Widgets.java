package com.crystalgui.widget;

import com.crystalgui.core.collection.tree.TreeDataSource;
import com.crystalgui.core.property.ObservableList;
import com.crystalgui.graph.NodeTypeRegistry;
import com.crystalgui.graph.PortDirection;
import com.crystalgui.graph.port.BasicPortType;
import com.crystalgui.ui.dom.GlyphRole;
import com.crystalgui.ui.dom.KindInfo;
import com.crystalgui.ui.dom.NodeContract;
import com.crystalgui.ui.dom.NodeKinds;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.widget.canvas.CanvasView;
import com.crystalgui.widget.collection.list.ListView;
import com.crystalgui.widget.collection.table.TableView;
import com.crystalgui.widget.collection.tree.TreeView;
import com.crystalgui.widget.config.Configurator;
import com.crystalgui.widget.config.ConfiguratorGroup;
import com.crystalgui.widget.config.ConfiguratorPanel;
import com.crystalgui.widget.config.control.AnchorControl;
import com.crystalgui.widget.config.control.ArrayControl;
import com.crystalgui.widget.config.control.AssetControl;
import com.crystalgui.widget.config.control.BooleanControl;
import com.crystalgui.widget.config.control.ClassChips;
import com.crystalgui.widget.config.control.ColorControl;
import com.crystalgui.widget.config.control.HeaderControl;
import com.crystalgui.widget.config.control.InfoControl;
import com.crystalgui.widget.config.control.MaskControl;
import com.crystalgui.widget.config.control.MatrixControl;
import com.crystalgui.widget.config.control.NoteControl;
import com.crystalgui.widget.config.control.NumberControl;
import com.crystalgui.widget.config.control.SelectControl;
import com.crystalgui.widget.config.control.SliderControl;
import com.crystalgui.widget.config.control.TextControl;
import com.crystalgui.widget.config.control.VectorControl;
import com.crystalgui.widget.config.inspector.Inspector;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.control.Checkbox;
import com.crystalgui.widget.display.ProgressBar;
import com.crystalgui.widget.composite.RadarChart;
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
import com.crystalgui.widget.composite.CreateMenu;
import com.crystalgui.widget.composite.SearchTree;
import com.crystalgui.widget.surface.SurfaceEditor;
import com.crystalgui.widget.surface.insert.InsertMenu;
import com.crystalgui.widget.layout.ContextToolbar;
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

    // THE LIBRARY'S CATEGORIES for the shipped widgets. An addon files under its own, or its namespace.
    private static final String CONTROLS = "Controls";
    private static final String DISPLAY = "Display";
    private static final String TEXT = "Text";
    private static final String LAYOUT = "Layout";
    private static final String OVERLAYS = "Overlays";
    private static final String COLLECTIONS = "Collections";
    private static final String FORMS = "Forms";

    /** {@code ServiceLoader} needs a public no-argument constructor. */
    public Widgets() {
    }

    /** A placed tab view: two tabs, so there is somewhere to put the first page and a second to switch to. */
    private static TabView twoTabs() {
        TabView tabs = new TabView();
        tabs.addTab("Tab 1");
        tabs.addTab("Tab 2");
        return tabs.selectIndex(0);
    }

    @Override
    public void register() {
        // THE PLAIN ELEMENT'S PRESENTATION. `ui.dom` registers the kind itself, so a decode before bootstrap finds
        // it; how a picker files and draws one is this layer's to say.
        UIElementRegistry.describe(UIElement.NAME,
                KindInfo.named("Element").inCategory(LAYOUT)
                        .synonyms("div", "container", "box", "group", "row", "column")
                        .describedAs("A plain container that lays out its children.")
                        .preview(WidgetSamples.element()));

        // ── control ──────────────────────────────────────────────────────────
        UIElementRegistry.register(Button.NAME, Button::new, Button.CONTRACT,
                KindInfo.named("Button").glyph(GlyphRole.CONTROL).inCategory(CONTROLS)
                        .synonyms("press", "click", "action").describedAs("A labelled push button.")
                        .starter(() -> new Button("Button")).preview(WidgetSamples.button()));
        UIElementRegistry.register(Checkbox.NAME, Checkbox::new, Checkbox.CONTRACT,
                KindInfo.named("Checkbox").glyph(GlyphRole.CONTROL).inCategory(CONTROLS)
                        .synonyms("tick", "check", "toggle").describedAs("An on/off box with a label.")
                        .starter(() -> new Checkbox("Checkbox")).preview(WidgetSamples.checkbox()));
        UIElementRegistry.register(Switch.NAME, Switch::new, Switch.CONTRACT,
                KindInfo.named("Switch").glyph(GlyphRole.CONTROL).inCategory(CONTROLS)
                        .synonyms("toggle", "on", "off").describedAs("An on/off switch with a sliding knob.")
                        .preview(WidgetSamples.switchOn()));
        UIElementRegistry.register(Slider.NAME, Slider::new, Slider.CONTRACT,
                KindInfo.named("Slider").glyph(GlyphRole.CONTROL).inCategory(CONTROLS)
                        .synonyms("range", "value").describedAs("Picks a number by dragging a thumb along a track.")
                        .preview(WidgetSamples.slider()));
        UIElementRegistry.register(ProgressBar.NAME, ProgressBar::new, ProgressBar.CONTRACT,
                KindInfo.named("Progress Bar").glyph(GlyphRole.CONTROL).inCategory(DISPLAY)
                        .synonyms("loading", "meter", "percent").describedAs("Shows how far something has got.")
                        .preview(WidgetSamples.progressBar()));
        UIElementRegistry.register(TextField.NAME, TextField::new, TextField.CONTRACT,
                KindInfo.named("Text Field").glyph(GlyphRole.CONTROL).inCategory(CONTROLS)
                        .synonyms("input", "entry", "edit").describedAs("One line of editable text.")
                        .preview(WidgetSamples.textField()));
        // INERT: a symbol icon carries nothing over a wire. Registered all the same, because a kind
        // that is not registered has no tag, and `symbolicon { }` is how a theme reaches it -- the old
        // engine answered the lowercased class name instead, which is the fallback that left 32 tags
        // matching by accident and ToolWindowFrame matching nothing at all.
        UIElementRegistry.register(SymbolIcon.NAME, SymbolIcon::new, NodeContract.INERT,
                KindInfo.named("Symbol Icon").glyph(GlyphRole.TEXT).inCategory(DISPLAY)
                        .synonyms("icon", "image", "glyph").describedAs("A small icon drawn in the text color.")
                        .preview(WidgetSamples.symbolIcon()));

        // ── text ─────────────────────────────────────────────────────────────
        // The engine's one text leaf AND the widget layer's label -- D15 merged `ui.box.TextNode` into
        // it, so the `text` tag is registered here rather than from a static block in `ui.box`.
        UIElementRegistry.register(UIText.NAME, UIText::new, UIText.CONTRACT,
                KindInfo.named("Text").glyph(GlyphRole.TEXT).inCategory(TEXT)
                        .synonyms("label", "string", "caption").describedAs("A run of text that wraps to its width.")
                        .starter(() -> new UIText("Text")));
        // NO CONTRACT: a MarkupDocument is not a StateType and never crosses a wire -- what travels is
        // whatever produced it. Registered all the same, because `markupview { }` is how 35 rules in the
        // sheets reach it and this engine has no lowercased-class-name fallback to match them by.
        UIElementRegistry.register(MarkupView.NAME, MarkupView::new, NodeContract.INERT,
                KindInfo.named("Markup View").glyph(GlyphRole.TEXT).inCategory(TEXT)
                        .synonyms("markdown", "rich text", "document").describedAs("Formatted text with headings, lists and code.")
                        .preview(WidgetSamples.markupView()));

        // ── scroll ───────────────────────────────────────────────────────────
        UIElementRegistry.register(Scroller.NAME, Scroller::new, NodeContract.INERT,
                KindInfo.named("Scrollbar").glyph(GlyphRole.LAYOUT).hide());
        UIElementRegistry.register(ScrollerView.NAME, ScrollerView::new, NodeContract.INERT,
                KindInfo.named("Scroll View").glyph(GlyphRole.LAYOUT).inCategory(LAYOUT)
                        .synonyms("scroll", "overflow", "viewport").describedAs("Scrolls content larger than itself.")
                        .preview(WidgetSamples.scrollView()));

        // ── overlay ──────────────────────────────────────────────────────────
        UIElementRegistry.register(Popover.NAME, Popover::new, Popover.CONTRACT,
                KindInfo.named("Popover").glyph(GlyphRole.OVERLAY).inCategory(OVERLAYS)
                        .synonyms("popup", "flyout").describedAs("Floats above the page, anchored to what opened it.")
                        .preview(WidgetSamples.popover()));
        UIElementRegistry.register(Menu.NAME, Menu::new, Menu.CONTRACT,
                KindInfo.named("Menu").glyph(GlyphRole.OVERLAY).inCategory(OVERLAYS)
                        .synonyms("context menu", "list").describedAs("A popup list of menu items.")
                        .preview(WidgetSamples.menu()));
        UIElementRegistry.register(MenuItem.NAME, MenuItem::new, MenuItem.CONTRACT,
                KindInfo.named("Menu Item").glyph(GlyphRole.OVERLAY).inCategory(OVERLAYS)
                        .synonyms("entry", "option").describedAs("One row of a menu.")
                        .starter(() -> new MenuItem("Menu Item")).preview(WidgetSamples.menuItem()));
        UIElementRegistry.register(Dropdown.NAME, Dropdown::new, Dropdown.CONTRACT,
                KindInfo.named("Dropdown").glyph(GlyphRole.CONTROL).inCategory(CONTROLS)
                        .synonyms("select", "combo", "choice", "picker").describedAs("Picks one option from a list.")
                        .starter(() -> new Dropdown("Select")).preview(WidgetSamples.dropdown()));
        UIElementRegistry.register(Tooltip.NAME, Tooltip::new, Tooltip.CONTRACT,
                KindInfo.named("Tooltip").glyph(GlyphRole.OVERLAY).inCategory(OVERLAYS)
                        .synonyms("hint", "hover").describedAs("Explains what it is attached to on hover.")
                        .starter(() -> new Tooltip("Tooltip")).preview(WidgetSamples.tooltip()));

        // ── 6.2: the dialogs and the layout composites ─────────────────────────────
        UIElementRegistry.register(Dialog.NAME, Dialog::new, Dialog.CONTRACT,
                KindInfo.named("Dialog").glyph(GlyphRole.OVERLAY).inCategory(OVERLAYS)
                        .synonyms("modal", "window", "alert").describedAs("A titled window over the page.")
                        .starter(() -> new Dialog("Dialog")).preview(WidgetSamples.dialog()));
        UIElementRegistry.register(SplitView.NAME, SplitView::new, SplitView.CONTRACT,
                KindInfo.named("Split View").glyph(GlyphRole.LAYOUT).inCategory(LAYOUT)
                        .synonyms("divider", "pane", "splitter").describedAs("Two panes with a draggable divider.")
                        .preview(WidgetSamples.splitView()));
        UIElementRegistry.register(TabView.NAME, TabView::new, TabView.CONTRACT,
                KindInfo.named("Tab View").glyph(GlyphRole.LAYOUT).inCategory(LAYOUT)
                        .synonyms("tabs", "pages").describedAs("Pages chosen by a row of tabs.")
                        .starter(Widgets::twoTabs)
                        .preview(WidgetSamples.tabView()));
        UIElementRegistry.register(Tab.NAME, Tab::new, Tab.CONTRACT,
                KindInfo.named("Tab").glyph(GlyphRole.LAYOUT).inCategory(LAYOUT)
                        .synonyms("page").describedAs("One page of a tab view.")
                        .starter(() -> new Tab("Tab")).preview(WidgetSamples.tab()));
        // PageStack is shell chrome with a back stack -- localOnly, so it registers a kind for the
        // cascade's sake (`pagestack { }` is how a theme reaches it) and nothing decodes into it.
        UIElementRegistry.register(PageStack.NAME, PageStack::new, NodeContract.INERT,
                KindInfo.named("Page Stack").glyph(GlyphRole.LAYOUT).inCategory(LAYOUT)
                        .synonyms("navigation", "back", "pages").describedAs("Pages shown one at a time, with a back stack.")
                        .preview(WidgetSamples.pageStack()));
        // The row a tool's options take over. Chrome, like PageStack, and registered for the same reason.
        UIElementRegistry.register(ContextToolbar.NAME, ContextToolbar::new, NodeContract.INERT, KindInfo.hidden());

        // ── 6.3: the collections and the shell's chrome ─────────────────────────────
        //
        // ALL INERT. ListView and TableView are localOnly with a reason WidgetCensus already
        // records -- a row stream has no wire form until M7 -- and the rest are shell chrome. What
        // the registration buys is the CASCADE: 32 shipped rules name `quickpick` and 8 name
        // `treeview`, and without a kind each would report `crystalgui:element` and match none of
        // them. A generic widget takes a raw factory, which is what a decode would produce anyway.
        UIElementRegistry.register(ListView.NAME, () -> new ListView<>(new ObservableList<>()),
                NodeContract.INERT, KindInfo.named("List View").glyph(GlyphRole.COLLECTION).inCategory(COLLECTIONS)
                        .synonyms("list", "rows", "items").describedAs("A scrolling list of rows from a model.")
                        .preview(WidgetSamples.listView()));
        UIElementRegistry.register(TreeView.NAME, () -> new TreeView<>(TreeDataSource.empty()),
                NodeContract.INERT, KindInfo.named("Tree View").glyph(GlyphRole.COLLECTION).inCategory(COLLECTIONS)
                        .synonyms("tree", "hierarchy", "outline").describedAs("Rows that fold into a hierarchy.")
                        .preview(WidgetSamples.treeView()));
        UIElementRegistry.register(TableView.NAME, () -> new TableView<>(new ObservableList<>()),
                NodeContract.INERT, KindInfo.named("Table View").glyph(GlyphRole.COLLECTION).inCategory(COLLECTIONS)
                        .synonyms("table", "grid", "columns").describedAs("Rows with sortable columns.")
                        .preview(WidgetSamples.tableView()));
        // The inspector is INERT for the reason its whole kit is: a descriptor is what travels, not
        // the panel built from one. The kind is for the cascade.
        UIElementRegistry.register(Inspector.NAME, Inspector::new, NodeContract.INERT,
                KindInfo.named("Inspector").glyphOf(Configurator.NAME).hide());

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
        UIElementRegistry.register(CanvasView.NAME, CanvasView::new, NodeContract.INERT,
                KindInfo.named("Canvas View").inCategory(LAYOUT)
                        .synonyms("zoom", "pan", "infinite").describedAs("A plane that pans and zooms what is placed on it.")
                        .preview(WidgetSamples.canvasView()));
        // THE GRAPH IS HIDDEN from pickers: its state is its GraphDocument, which a document cannot hold.
        UIElementRegistry.register(GraphView.NAME, GraphView::new, NodeContract.INERT, KindInfo.hidden());
        UIElementRegistry.register(GraphNode.NAME, () -> new GraphNode(""), NodeContract.INERT, KindInfo.hidden());
        UIElementRegistry.register(NodePort.NAME,
                () -> new NodePort(PortDirection.INPUT, new BasicPortType("any"), ""),
                NodeContract.INERT, KindInfo.hidden());
        UIElementRegistry.register(NodeCreationMenu.NAME,
                () -> new NodeCreationMenu(new NodeTypeRegistry()), NodeContract.INERT, KindInfo.hidden());
        // No sheet names `nodewirelayer` -- the wires take their look from the view that owns them --
        // so this kind is for the RULE rather than the cascade: a concrete node declaring no NAME
        // inherits one and is indistinguishable from a widget that forgot to declare its own.
        UIElementRegistry.register(NodeWireLayer.NAME, NodeWireLayer::new, NodeContract.INERT, KindInfo.hidden());

        // THE SURFACE, and it registers here rather than in a service of its own: a LAYER speaks for
        // itself, and `widget.surface` is a tier inside `widget`, not a layer beside it. INERT for the
        // graph's reason -- a surface's state is its consumer's document -- and the kind is what lets a
        // sheet name `surface` at all.
        UIElementRegistry.registerTag(SurfaceEditor.NAME, NodeContract.INERT);
        // THE SEARCH MENU and the engine's Add menu over it. INERT: what a menu lists is its consumer's
        // library, which is not something a description could carry. InsertMenu is cascade-only -- it is
        // built with the surface it belongs to, so a registry has no way to make one.
        UIElementRegistry.register(CreateMenu.NAME, () -> new CreateMenu<>("Create"), NodeContract.INERT, KindInfo.hidden());
        UIElementRegistry.register(SearchTree.NAME, SearchTree::new, NodeContract.INERT, KindInfo.hidden());
        UIElementRegistry.registerTag(InsertMenu.NAME, NodeContract.INERT);

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
        UIElementRegistry.register(Configurator.NAME, Configurator::new, NodeContract.INERT,
                KindInfo.named("Configurator").glyph(GlyphRole.COLLECTION).inCategory(FORMS)
                        .synonyms("form", "settings", "properties").describedAs("A form of labelled fields.")
                        .preview(WidgetSamples.configurator()));
        UIElementRegistry.register(ConfiguratorGroup.NAME, ConfiguratorGroup::new, NodeContract.INERT,
                KindInfo.named("Configurator Group").glyphOf(Configurator.NAME).inCategory(FORMS)
                        .synonyms("section", "fold").describedAs("A folding section of a form.")
                        .preview(WidgetSamples.configuratorGroup()));
        UIElementRegistry.register(ConfiguratorPanel.NAME, ConfiguratorPanel::new, NodeContract.INERT,
                KindInfo.named("Configurator Panel").glyphOf(Configurator.NAME).inCategory(FORMS)
                        .synonyms("form", "panel").describedAs("A scrolling form panel.")
                        .preview(WidgetSamples.configuratorPanel()));
        // A FIELD SHARES ITS CONTROL'S MARK where it is that control in a form -- a boolean field is a
        // checkbox -- and the words still name the field.
        UIElementRegistry.register(AnchorControl.NAME, AnchorControl::new, NodeContract.INERT,
                KindInfo.named("Anchor Field").glyph(GlyphRole.CONTROL).inCategory(FORMS).describedAs("Picks one of nine anchor points.").preview(WidgetSamples.anchorField()));
        UIElementRegistry.register(ArrayControl.NAME, ArrayControl::new, NodeContract.INERT,
                KindInfo.named("Array Field").glyph(GlyphRole.CONTROL).inCategory(FORMS)
                        .synonyms("list").describedAs("An editable list of values.")
                        .preview(WidgetSamples.arrayField()));
        UIElementRegistry.register(AssetControl.NAME, AssetControl::new, NodeContract.INERT,
                KindInfo.named("Asset Field").glyph(GlyphRole.CONTROL).inCategory(FORMS)
                        .synonyms("resource", "file").describedAs("Picks a resource.")
                        .preview(WidgetSamples.assetField()));
        UIElementRegistry.register(BooleanControl.NAME, BooleanControl::new, NodeContract.INERT,
                KindInfo.named("Boolean Field").glyphOf(Checkbox.NAME).inCategory(FORMS)
                        .synonyms("checkbox", "bool").describedAs("A labelled on/off field.")
                        .preview(WidgetSamples.booleanField()));
        UIElementRegistry.register(ClassChips.NAME, ClassChips::new, NodeContract.INERT,
                KindInfo.named("Class Chips").glyph(GlyphRole.CONTROL).inCategory(FORMS)
                        .synonyms("tags", "classes", "tokens").describedAs("A list of names as chips, with a prompt that adds more.")
                        .preview(WidgetSamples.classChipsField()));
        UIElementRegistry.register(ColorControl.NAME, ColorControl::new, NodeContract.INERT,
                KindInfo.named("Color Field").glyphOf(ColorSelector.NAME).inCategory(FORMS)
                        .synonyms("colour", "swatch").describedAs("A labelled color field.")
                        .preview(WidgetSamples.colorField()));
        UIElementRegistry.register(HeaderControl.NAME, HeaderControl::new, NodeContract.INERT,
                KindInfo.named("Form Header").glyph(GlyphRole.TEXT).inCategory(FORMS).describedAs("A heading between form fields.").preview(WidgetSamples.formHeader()));
        UIElementRegistry.register(InfoControl.NAME, InfoControl::new, NodeContract.INERT,
                KindInfo.named("Form Info").glyph(GlyphRole.TEXT).inCategory(FORMS).describedAs("A read-only value in a form.").preview(WidgetSamples.formInfo()));
        UIElementRegistry.register(MaskControl.NAME, MaskControl::new, NodeContract.INERT,
                KindInfo.named("Mask Field").glyph(GlyphRole.CONTROL).inCategory(FORMS)
                        .synonyms("flags", "bits").describedAs("Toggles a set of flags.")
                        .preview(WidgetSamples.maskField()));
        UIElementRegistry.register(MatrixControl.NAME, MatrixControl::new, NodeContract.INERT,
                KindInfo.named("Matrix Field").glyph(GlyphRole.CONTROL).inCategory(FORMS).describedAs("Edits a matrix of numbers.").preview(WidgetSamples.matrixField()));
        UIElementRegistry.register(NoteControl.NAME, NoteControl::new, NodeContract.INERT,
                KindInfo.named("Form Note").glyph(GlyphRole.TEXT).inCategory(FORMS).describedAs("A paragraph of help in a form.").preview(WidgetSamples.formNote()));
        UIElementRegistry.register(NumberControl.NAME, NumberControl::new, NodeContract.INERT,
                KindInfo.named("Number Field").glyph(GlyphRole.CONTROL).inCategory(FORMS)
                        .synonyms("int", "float", "spinner").describedAs("A labelled number, dragged or typed.")
                        .preview(WidgetSamples.numberField()));
        UIElementRegistry.register(SelectControl.NAME, SelectControl::new, NodeContract.INERT,
                KindInfo.named("Select Field").glyphOf(Dropdown.NAME).inCategory(FORMS)
                        .synonyms("dropdown", "enum", "choice").describedAs("A labelled choice from a list.")
                        .preview(WidgetSamples.selectField()));
        UIElementRegistry.register(SliderControl.NAME, SliderControl::new, NodeContract.INERT,
                KindInfo.named("Slider Field").glyphOf(Slider.NAME).inCategory(FORMS)
                        .synonyms("range").describedAs("A labelled slider.").preview(WidgetSamples.sliderField()));
        UIElementRegistry.register(TextControl.NAME, TextControl::new, NodeContract.INERT,
                KindInfo.named("Text Field").glyphOf(TextField.NAME).inCategory(FORMS)
                        .synonyms("string", "input").describedAs("A labelled line of text.")
                        .preview(WidgetSamples.textControl()));
        UIElementRegistry.register(VectorControl.NAME, VectorControl::new, NodeContract.INERT,
                KindInfo.named("Vector Field").glyph(GlyphRole.CONTROL).inCategory(FORMS)
                        .synonyms("xyz", "position").describedAs("Edits two to four numbers side by side.")
                        .preview(WidgetSamples.vectorField()));

        // ── dnd ──────────────────────────────────────────────────────────────
        UIElementRegistry.register(DragGhost.NAME, DragGhost::new, NodeContract.INERT, KindInfo.hidden());
        UIElementRegistry.register(InsertionMarker.NAME, InsertionMarker::new, NodeContract.INERT, KindInfo.hidden());
        // A grab handle. Built by `Resizer.install` for a resizable node rather than decoded, so the factory
        // makes an UNBOUND one -- registered all the same, because a concrete node that declares no kind
        // inherits `crystalgui:element` and would match every bare `element` rule there is.
        UIElementRegistry.register(Resizer.NAME, Resizer::new, NodeContract.INERT, KindInfo.hidden());
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
        UIElementRegistry.register(TextEditor.NAME, TextEditor::new, NodeContract.INERT,
                KindInfo.named("Text Editor").inCategory(TEXT)
                        .synonyms("code", "source", "multiline").describedAs("A code editor with syntax coloring.")
                        .preview(WidgetSamples.textEditor()));
        UIElementRegistry.register(CompletionPopup.NAME, CompletionPopup::new, NodeContract.INERT, KindInfo.hidden());
        UIElementRegistry.register(DocumentationPopup.NAME, DocumentationPopup::new, NodeContract.INERT, KindInfo.hidden());
        // A find bar is built FOR an editor and never decoded, so the factory makes one over a fresh
        // editor rather than pretending a bar can exist without one.
        UIElementRegistry.register(SearchReplaceBar.NAME,
                () -> new SearchReplaceBar(new TextEditor()), NodeContract.INERT, KindInfo.hidden());

        // ── form ─────────────────────────────────────────────────────────────
        UIElementRegistry.register(SearchField.NAME, SearchField::new, SearchField.CONTRACT,
                KindInfo.named("Search Field").glyph(GlyphRole.CONTROL).inCategory(CONTROLS)
                        .synonyms("filter", "find", "query").describedAs("A text field with a magnifier and a clear button.")
                        .preview(WidgetSamples.searchField()));
        UIElementRegistry.register(ColorSelector.NAME, ColorSelector::new, ColorSelector.CONTRACT,
                KindInfo.named("Color Selector").glyph(GlyphRole.COLLECTION).inCategory(CONTROLS)
                        .synonyms("colour", "picker", "hue").describedAs("Picks a color from a field, hue and alpha.")
                        .preview(WidgetSamples.colorSelector()));
        UIElementRegistry.register(RadarChart.NAME, RadarChart::new, RadarChart.CONTRACT,
                KindInfo.named("Radar Chart").glyph(GlyphRole.COLLECTION).inCategory(DISPLAY)
                        .synonyms("spider", "chart", "stats").describedAs("Values on axes around a centre.")
                        .preview(WidgetSamples.radarChart()));
    }
}
