package com.crystalgui.harness.scene;

import com.crystalgui.core.notify.StatusBar;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.workbench.chrome.menu.MenuBarView;
import com.crystalgui.workbench.chrome.palette.QuickPick;
import com.crystalgui.workbench.chrome.status.StatusBarView;
import com.crystalgui.core.collection.list.SelectionMode;
import com.crystalgui.core.collection.pick.QuickPickItem;
import com.crystalgui.core.collection.pick.QuickPickSource;
import com.crystalgui.core.collection.tree.TreeDataSource;
import com.crystalgui.core.collection.tree.TreeRow;
import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.command.MenuId;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.ObservableList;
import com.crystalgui.graph.port.BasicPortType;
import com.crystalgui.graph.port.PortType;
import com.crystalgui.render.UiRecorder;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.text.lang.SymbolKind;
import com.crystalgui.text.lang.SymbolModifier;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.widget.canvas.CanvasView;
import com.crystalgui.widget.collection.list.ListRenderer;
import com.crystalgui.widget.collection.list.ListView;
import com.crystalgui.widget.collection.table.TableColumn;
import com.crystalgui.widget.collection.table.TableView;
import com.crystalgui.widget.collection.tree.TreeRenderer;
import com.crystalgui.widget.collection.tree.TreeView;
import com.crystalgui.widget.config.ConfiguratorGroup;
import com.crystalgui.widget.config.ConfiguratorPanel;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.control.Checkbox;
import com.crystalgui.widget.control.CheckboxGroup;
import com.crystalgui.widget.display.ProgressBar;
import com.crystalgui.widget.control.Slider;
import com.crystalgui.widget.control.Switch;
import com.crystalgui.widget.display.SymbolIcon;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.widget.composite.ColorSelector;
import com.crystalgui.widget.composite.SearchField;
import com.crystalgui.widget.graph.GraphNode;
import com.crystalgui.widget.graph.GraphView;
import com.crystalgui.widget.graph.NodePort;
import com.crystalgui.widget.layout.PageStack;
import com.crystalgui.widget.layout.SplitView;
import com.crystalgui.widget.layout.Tab;
import com.crystalgui.widget.layout.TabView;
import com.crystalgui.widget.overlay.Dialog;
import com.crystalgui.widget.overlay.DialogManager;
import com.crystalgui.widget.overlay.Dropdown;
import com.crystalgui.widget.overlay.InputDialog;
import com.crystalgui.widget.overlay.Menu;
import com.crystalgui.widget.overlay.MenuItem;
import com.crystalgui.widget.overlay.Popover;
import com.crystalgui.widget.overlay.Tooltip;
import com.crystalgui.widget.scroll.ScrollerView;
import com.crystalgui.widget.text.UIText;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import org.joml.Matrix4f;

/**
 * <b>Every widget the M6 port has moved, on the NEW engine, in one scrolling column.</b>
 *
 * <p>{@code --mode=cgui-new-gallery}. The counterpart to {@code cgui-gallery}, which is the same idea
 * over {@code UIWindow}/{@code UIElement} — this one builds a {@link UIDocument} and paints through
 * the box tree, so what it shows is the ported widget rendering itself under the ported cascade.</p>
 *
 * <h3>What it is for, and what it can only be told by eye</h3>
 *
 * <p>The port has tests for the things a test can see: a kind is registered, a part carries its name,
 * a subclass answers its own tag, a state change reports once. What none of them can see is whether a
 * widget <em>draws</em>. A part that stayed a class still exists, still lays out, and still paints —
 * as an unstyled box. A {@code ::part()} twin that was added to the wrong sheet, or with the host's
 * pseudo-class in the wrong place, produces a rule that matches nothing and reports nothing. That is
 * the whole failure mode {@code SheetPortTest} exists to bound and cannot actually detect, and it is
 * what one look at this scene answers.</p>
 *
 * <h3>It scrolls, and that is a widget under test too</h3>
 *
 * <p>The column is a {@link ScrollerView}, so the scene exercises the one composite in the batch that
 * takes a caller's content — through a {@link com.crystalgui.ui.dom.UISlot}, which is what replaced
 * {@code acceptsPublicChildren}. If the slot were missing, every section below would be in the tree
 * and on screen nowhere, which is exactly the shape that reads as "the scene did not build".</p>
 *
 * <p>Interactive: mouse and keyboard go to {@link UIDocument}'s input service, so hover, press, focus,
 * dragging a slider and typing in a field are all real here. A scene that only painted would miss
 * every state rule in the sheets, which is most of them.</p>
 */
public class CgUiNewEngineGalleryScene
        implements InteractiveSceneLifecycle, CgSystemInput.Keyboard, CgSystemInput.Mouse {

    /**
     * The same {@code uiScale} the other CrystalGUI scenes run at.
     *
     * <p>Given to the BOX TREE ({@code setRootTransform}) and never applied to the paint pose by hand.
     * That is the engine's own rule with one word changed: the root transform is the single definition
     * of what {@code uiScale} means, so layout composes every box's world matrix through it, the
     * painter takes its pose from that matrix, and hit-testing inverts the same one. Scale the pose
     * instead and the two disagree by exactly the scale factor — the picture is right and every click
     * lands at half the distance, which is what "the mouse input Y is shifted down" was.</p>
     *
     * <p>Deliberately not in the ortho projection either: {@code CgTextRenderer} picks its glyph
     * raster size off the pose scale, so a projection-side scale rasterizes glyphs at logical size and
     * magnifies them — blurry text, and only the text.</p>
     */
    private static final float SCALE = 2f;

    private UIDocument document;
    private ProgressBar determinate;
    private float elapsed;

    @Override
    public void init(HarnessContext ctx) {
        document = new UIDocument().markFrameThread();
        // THE USER-AGENT SHEET, which is not installed for you -- a scene that asserts on default.css
        // behaviour without this exercises no CSS at all and looks like a styling regression.
        document.styles().addStylesheet(StyleSheet.DEFAULT);
        // THE GRAPH THEME, which the UA sheet deliberately does not contain. The split is the same
        // one `ore.css` sits on: `ua/config-kit.css` gives a graphnode its GEOMETRY -- width, min and
        // max width, the title bar's row and height, the port columns -- and `crystalgui:graph` gives
        // it the Unity look, every background and border and the per-type port palette a wire reads
        // its colour from. Measured: the UA half is 93% geometry declarations, the theme 39% colour
        // and the rest radii and outlines.
        //
        // Without this the nodes lay out perfectly and paint nothing -- no background, no border, no
        // port colour -- which reads as a broken port rather than as an unthemed widget. The old
        // gallery loads it on the line below its own DEFAULT and hardcodes not one graph rule in its
        // scene sheet; this scene simply never did.
        document.styles().addStylesheet(StyleSheetRegistry.of("crystalgui:graph"));
        document.styles().addStylesheet(StyleSheet.parse(SCENE_CSS));
        document.boxes().setRootTransform(new Matrix4f().scale(SCALE, SCALE, 1f));
        document.append(buildRoot());
    }

    /**
     * The scene's own layout only — every widget's appearance comes from the UA sheet.
     *
     * <p>Nothing here names a widget: if a button is the wrong size or a slider draws no track, that
     * is the port to fix and not this string. A scene sheet that styled the subjects would be a scene
     * that passes whatever the widgets do.</p>
     */
    private static final String SCENE_CSS = """
            #page {
                /* Stated rather than relied on. It USED to be load-bearing: BoxStyle wrote CSS's
                   initials for anything unset, so an unset direction was `row` where the old bridge
                   defaulted to `column`, and this scene laid out sideways with every section on one
                   line and zero-high -- which is what "nothing is drawing" turned out to be. D5.8 was
                   reversed at 6.1 and both engines answer `column` now; the declaration stays because
                   a layout this scene depends on should not be inherited from a default. */
                flex-direction: column;
                width: 100%;
                height: 100%;
                background-color: #1E1E1E;
                padding: 12;
            }
            #column {
                width: 100%;
                flex-direction: column;
                gap: 14;
                /* Room for the vertical bar, which is absolutely positioned and overlays the content. */
                padding-right: 14;
            }
            .section {
                width: 100%;
                flex-direction: column;
                align-items: flex-start;
                gap: 6;
                padding: 10;
                background-color: #252526;
                border-radius: 4;
            }
            .heading {
                color: #4FC1FF;
                font-size: 13;
            }
            /* The twisty is the RENDERER's, so its look is the scene's too. Ported from the old
               gallery: a vector chevron rotated by the __expanded__ class TreeView applies, and
               removed outright on a leaf. The rules are on the twisty's OWN box rather than on the
               row, because a row carries the depth indent as padding-left and a rule there would
               out-specify it and flatten the tree. */
            .tv-row { flex-direction: row; align-items: center; gap: 4; height: 22; }
            .tv-twisty {
                width: 11px; height: 11px; flex-shrink: 0; padding-left: 3px;
                color: #98C379; overlay: shape("chevron-right");
            }
            .tv-row:hover .tv-twisty { color: #FFFFFF; }
            .tv-row.__expanded__ > .tv-twisty { transform: rotate(90deg); }
            .tv-row.__leaf__ > .tv-twisty { overlay: none; }
            .row {
                width: 100%;
                flex-direction: row;
                gap: 10;
                align-items: center;
            }
            /* THE THREE THAT NEED A BOX TO LIVE IN. A SplitView divides what it is given and a TabView's
               panes fill what is left, so both measure to nothing inside a content-sized column -- and
               a dialog stage is a positioning context for absolutely placed windows, which has no
               content to be sized by at all. Fixed heights here, not in the widgets. */
            .stage {
                width: 100%;
                height: 240;
                background-color: #1B1B1B;
                border-radius: 4;
            }
            .split-demo { width: 100%; height: 180; }
            .tabs-demo  { width: 100%; height: 200; }
            /* A panel is sized by its rows; the cap is so a long kit does not own the whole page. */
            .config-demo {
                width: 100%;
                max-height: 420;
            }
            .config-log {
                width: 100%;
                flex-direction: column;
                gap: 2;
                padding: 8;
                background-color: #1B1B1B;
                border-radius: 4;
            }
            .pane-body {
                width: 100%;
                height: 0;
                flex-grow: 1;
                flex-direction: column;
                align-items: center;
                justify-content: center;
                background-color: #2D2D30;
            }
            """;

    private UIElement buildRoot() {
        UIElement page = new UIElement().setId("page");

        ScrollerView scroller = new ScrollerView();
        StyleGroup.inlinePipeline(scroller.getStyle().getLayoutGroup(),
                l -> l.widthPercent(100f).height(0f).flexGrow(1f));
        page.append(scroller);

        UIElement column = new UIElement().setId("column");
        scroller.append(column);

        Button disabled = new Button("Disabled");
        disabled.setEnabled(false);
        column.append(section("Button", row(
                new Button("Ordinary"), disabled,
                new Button("With icon").setPreIcon(new UIElement()))));

        Checkbox one = new Checkbox("First");
        Checkbox two = new Checkbox("Second");
        CheckboxGroup group = new CheckboxGroup().allowEmpty(false);
        one.setGroup(group);
        two.setGroup(group);
        one.setChecked(true);
        column.append(section("Checkbox + CheckboxGroup (exclusive, allowEmpty false)",
                row(new Checkbox("Standalone"), one, two)));

        column.append(section("Switch", row(new Switch(), new Switch().setChecked(true))));

        Slider slider = new Slider();
        slider.setRange(0f, 100f);
        slider.setValue(35f);
        StyleGroup.inlinePipeline(slider.getStyle().getLayoutGroup(), l -> l.width(220f));
        column.append(section("Slider (drag it)", row(slider)));

        determinate = new ProgressBar();
        determinate.setFraction(0.4f);
        StyleGroup.inlinePipeline(determinate.getStyle().getLayoutGroup(), l -> l.width(180f).height(6f));
        ProgressBar indeterminate = new ProgressBar();
        indeterminate.setFraction(-1f);
        StyleGroup.inlinePipeline(indeterminate.getStyle().getLayoutGroup(), l -> l.width(180f).height(6f));
        column.append(section("ProgressBar — determinate (animates) and indeterminate (sweeps)",
                row(determinate, indeterminate)));

        TextField field = new TextField();
        field.setText("Type in me");
        StyleGroup.inlinePipeline(field.getStyle().getLayoutGroup(), l -> l.width(220f));
        column.append(section("TextField", row(field)));

        SearchField search = new SearchField();
        StyleGroup.inlinePipeline(search.getStyle().getLayoutGroup(), l -> l.width(240f));
        column.append(section("SearchField (icon, field, clear)", row(search)));

        Dropdown dropdown = new Dropdown("Pick one");
        dropdown.addOption("Alpha");
        dropdown.addOption("Beta");
        dropdown.addOption("Gamma");
        column.append(section("Dropdown (click to open its Menu)", row(dropdown)));

        Button menuAnchor = new Button("Open a menu");
        Menu menu = new Menu();
        menu.addItem(new MenuItem("Cut"));
        menu.addItem(new MenuItem("Copy"));
        menu.addItem(new MenuItem("Paste"));
        page.append(menu);
        menuAnchor.attachListener(() -> menu.showFor(menuAnchor, menuAnchor));
        column.append(section("Menu + MenuItem", row(menuAnchor)));

        Button tipped = new Button("Hover me");
        Tooltip.attach(tipped, "A tooltip, placed after layout");
        column.append(section("Tooltip", row(tipped)));

        Button popAnchor = new Button("Toggle a popover");
        Popover popover = new Popover();
        UIText popText = new UIText("A bare Popover: light children, no shadow root.");
        popover.append(popText);
        page.append(popover);
        popAnchor.attachListener(() -> {
            if (popover.isOpen()) popover.hide();
            else popover.showFor(popAnchor, popAnchor);
        });
        column.append(section("Popover", row(popAnchor)));

        SymbolIcon cls = new SymbolIcon();
        cls.show(SymbolKind.CLASS, Set.of());
        SymbolIcon iface = new SymbolIcon();
        iface.show(SymbolKind.INTERFACE, Set.of());
        SymbolIcon marked = new SymbolIcon();
        marked.show(SymbolKind.METHOD,
                Set.of(SymbolModifier.STATIC,
                        SymbolModifier.FINAL));
        column.append(section("SymbolIcon — class, interface, and a method with static+final marks",
                row(cls, iface, marked)));

        ColorSelector colours = new ColorSelector();
        column.append(section("ColorSelector — the deepest composite in the batch", row(colours)));

        // ── 6.2: the dialogs and the layout composites ─────────────────────
        column.append(section("SplitView — drag either divider; the right pane holds a nested vertical one",
                splitView()));
        column.append(section("TabView + Tab — click a tab, close one with its X, and the strip scrolls",
                tabView()));
        column.append(section("PageStack — one page at a time, built on first show",
                pageStack()));
        column.append(section("Dialog + DialogManager — drag a title bar, click to raise, X closes",
                dialogs()));
        column.append(section("InputDialog — a prompt and a confirm, both centred once measured",
                inputDialogs()));

        column.append(section(
                "The config kit — thirteen controls, two groups, and a log of what changed",
                configKit()));

        // ── 6.3: the collections and the shell's chrome ─────────────────────
        column.append(section(
                "ListView — ten thousand rows, a dozen realised; drag and shift-click to select",
                listView()));
        column.append(section("TreeView — click a twisty; src starts expanded", treeView()));
        column.append(section("TableView — click a header to sort, drag a divider to resize",
                tableView()));
        column.append(section("MenuBarView — Alt+F, arrows across the bar, one row dimmed",
                menuBar()));
        column.append(section("StatusBarView + Breadcrumbs", statusBar()));
        column.append(section("QuickPick — the palette, promoted over everything", palette()));

        // ── 6.4: the canvas and the graph ───────────────────────────────
        column.append(section(
                "GraphView — middle-drag to pan, wheel to zoom, drag a port onto another to wire",
                graphView()));
        column.append(section("CanvasView — the plane underneath it, with culling", canvasView()));

        return page;
    }

    /**
     * Two dividers and a nested split, which is where a divider drag goes wrong first.
     *
     * <p>Nested because the outer split's travel is measured against its own content box minus its
     * dividers, and a split inside a pane is the shape that gets that arithmetic wrong — the inner
     * one's travel must come from the pane it was given, not from the window.</p>
     */
    private UIElement splitView() {
        SplitView split = new SplitView();
        split.addClass("split-demo");
        // 0..100, not 0..1 -- matching LDLib2's 5..95 defaults, which is what the widget documents.
        split.setPercentage(40f).setLimits(15f, 85f);
        split.first().append(paneBody("first pane"));

        SplitView nested = new SplitView();
        nested.setOrientation(SplitView.Orientation.VERTICAL);
        nested.first().append(paneBody("nested top"));
        nested.second().append(paneBody("nested bottom"));
        split.second().append(nested);
        return split;
    }

    /**
     * Enough tabs to overflow the strip, so the rail scrolls and the strip bar appears.
     *
     * <p>The bar is the thing to watch: it is derived from measured sizes and refreshed from a
     * post-layout hook, which replaced the two overrides the old engine needed ({@code setScroll} and
     * {@code onLayoutChanged}). If it never appears, that hook is not running.</p>
     */
    private UIElement tabView() {
        TabView tabs = new TabView();
        tabs.addClass("tabs-demo");
        tabs.setTabSide(TabView.TabSide.TOP);
        for (String name : new String[] {"one", "two", "three", "four", "five", "six", "seven"}) {
            Tab tab = tabs.addTab(name);
            tab.setClosable(true);
            tab.onCloseRequested.connect(() -> tabs.removeTab(tab));
            tab.content().append(paneBody("pane " + name));
        }
        return tabs;
    }

    /** One page at a time, each built by the factory the first time it is asked for. */
    private UIElement pageStack() {
        PageStack<String> stack = new PageStack<>();
        stack.addClass("stage");
        stack.setPageFactory(key -> paneBody("page " + key));
        stack.setPlaceholder(paneBody("nothing shown"));

        UIElement controls = row();
        for (String key : new String[] {"alpha", "beta", "gamma"}) {
            Button open = new Button(key);
            open.attachListener(() -> stack.show(key));
            controls.append(open);
        }
        Button none = new Button("none");
        none.attachListener(() -> stack.show(null));
        controls.append(none);

        UIElement box = new UIElement();
        StyleGroup.inlinePipeline(box.getStyle().getLayoutGroup(),
                l -> l.widthPercent(100f).flexDirection(FlexDirection.COLUMN).gapAll(8f));
        box.append(controls);
        box.append(stack);
        return box;
    }

    /**
     * Three managed dialogs on a stage of their own.
     *
     * <p>The stage is an ordinary node: {@link DialogManager} places and stacks what it is given, and
     * a dialog moves by INLINE insets rather than a transform. <b>They do not resize</b> — D6 chose a
     * resize mode over an edge band and 6.0 did not build one, so the three {@code UIResizer} hooks
     * were deleted with the port, and this is what that looks like on screen.</p>
     */
    private UIElement dialogs() {
        UIElement stage = new UIElement().addClass("stage");
        DialogManager manager = new DialogManager(stage);

        Dialog first = manager.manage(new Dialog("panel one"));
        first.getContent().append(hint("drag my title bar"));

        Dialog second = manager.manage(new Dialog("panel two"));
        second.getContent().append(new Button("a button"));

        Dialog third = manager.manage(new Dialog("panel three"));
        third.getContent().append(hint("click me to raise"));

        UIElement controls = row();
        Button openAll = new Button("open all");
        openAll.attachListener(manager::showAll);
        controls.append(openAll);
        Button closeAll = new Button("close all");
        closeAll.attachListener(manager::closeAll);
        controls.append(closeAll);
        Button spawn = new Button("new window");
        spawn.attachListener(() -> manager.manage(
                new Dialog("panel " + (manager.getDialogs().size() + 1))).show());
        controls.append(spawn);

        UIElement box = new UIElement();
        StyleGroup.inlinePipeline(box.getStyle().getLayoutGroup(),
                l -> l.widthPercent(100f).flexDirection(FlexDirection.COLUMN).gapAll(8f));
        box.append(controls);
        box.append(stage);
        return box;
    }

    /**
     * The two static prompts, and the one thing worth watching is WHERE they appear.
     *
     * <p>A prompt is out of flow, so its size is unknown until it has been laid out: it opens
     * transparent (a {@code __placing__} class the sheet owns) and centres itself from a post-layout
     * hook, then drops the class. A prompt that flashes at the top-left before settling means that
     * hook ran before layout; one that never appears at all means the class was never dropped.</p>
     */
    private UIElement inputDialogs() {
        UIElement controls = row();
        Button ask = new Button("ask for a name");
        ask.attachListener(() -> InputDialog.ask(ask, "New file", "name", "untitled.txt", name -> { }));
        controls.append(ask);
        Button confirm = new Button("confirm a deletion");
        confirm.attachListener(() -> InputDialog.confirm(
                confirm, "Delete", "Delete untitled.txt?", () -> { }));
        controls.append(confirm);
        return controls;
    }

    /** A filled pane body, so an empty split or tab reads as empty rather than as broken. */
    private UIElement paneBody(String label) {
        UIElement body = new UIElement().addClass("pane-body");
        body.append(hint(label));
        return body;
    }

    /** A label that never eats a press — a pane's text must not shadow the pane. */
    private UIText hint(String text) {
        UIText label = new UIText(text);
        label.setHitTest(false);
        return label;
    }

    /**
     * The config kit: every control it ships, one panel, with a live log of what changed.
     *
     * <p><b>All thirteen at once, deliberately.</b> Each is a different shape of the same idea — a
     * label on the left and an editor on the right — so the only useful check is whether they line up
     * with each other. A control demoed on its own can be wrong in a way that reads as correct.</p>
     *
     * <p>The two groups exist so a foldout and an indent are visible <em>against</em> ungrouped rows;
     * depth only reads as depth next to something that is not indented. The nested group is collapsed
     * to start, which is the state the arrow is easiest to get wrong in.</p>
     */
    private UIElement configKit() {
        ConfiguratorPanel panel = new ConfiguratorPanel();
        panel.addClass("config-demo");

        // A plain header: a full-width band with no arrow and nothing to collapse, unlike the group
        // below it. Two different things that look alike until you try to fold one.
        panel.add(ConfigDescriptor.header("Node Settings"), null);
        panel.add(ConfigDescriptor.text("name", "Name").tooltip("Free text"), "Untitled");
        panel.add(ConfigDescriptor.number("scale", "Scale"), 1.0);
        panel.add(ConfigDescriptor.number("opacity", "Opacity").range(0f, 1f), 0.5);
        panel.add(ConfigDescriptor.number("count", "Count").integral(true), 3);
        panel.add(ConfigDescriptor.bool("exposed", "Exposed"), true);
        panel.add(ConfigDescriptor.select("space", "Space",
                List.of("Object", "View", "World", "Tangent", "Absolute World")), "World");
        panel.add(ConfigDescriptor.vector("offset", "Offset", 3), new double[] {0, 1, 0});
        panel.add(ConfigDescriptor.vector("uv", "UV", 2), new double[] {0, 0});

        ConfiguratorGroup advanced = new ConfiguratorGroup("Advanced");
        panel.append(advanced);
        panel.addTo(advanced.content(), ConfigDescriptor.number("bias", "Bias"), 0.0);
        panel.addTo(advanced.content(), ConfigDescriptor.bool("clamp", "Clamp"), false);
        ConfiguratorGroup nested = new ConfiguratorGroup("Nested", true);
        advanced.content().append(nested);
        panel.addTo(nested.content(), ConfigDescriptor.text("note", "Note"), "two levels deep");

        panel.add(ConfigDescriptor.of("entries", "Entries", ConfigDescriptor.Kind.ARRAY)
                .element(ConfigDescriptor.text("entries.e", "")), List.of("alpha", "beta"));

        ConfiguratorGroup rich = new ConfiguratorGroup("Colour, mask, matrix, asset");
        panel.append(rich);
        panel.addTo(rich.content(), ConfigDescriptor.color("tint", "Tint"), 0xFF3C8CFF);
        panel.addTo(rich.content(), ConfigDescriptor.mask("layers", "Layers",
                List.of("Default", "Water", "UI", "PostProcessing")), Set.of("Default", "Water"));
        panel.addTo(rich.content(), ConfigDescriptor.matrix("transform", "Transform", 4), null);
        panel.addTo(rich.content(), ConfigDescriptor.asset("shader", "Shader"), "Shaders/Lit.shader");

        // NEWEST FIRST AND CAPPED. A log that grows downward pushes itself off the page, and the only
        // line worth seeing is the one that just happened -- which is the whole point of showing the
        // panel's `changed` signal rather than trusting the controls to look right.
        UIElement log = new UIElement().addClass("config-log");
        List<String> lines = new ArrayList<>();
        panel.changed.connect((id, value) -> {
            lines.add(0, id + " = " + describe(value));
            while (lines.size() > 6) lines.remove(lines.size() - 1);
            log.removeAll();
            for (String line : lines) log.append(hint(line));
        });
        log.append(hint("scrub a number, type a name, open a colour — changes land here"));

        UIElement box = new UIElement();
        StyleGroup.inlinePipeline(box.getStyle().getLayoutGroup(),
                l -> l.widthPercent(100f).flexDirection(FlexDirection.COLUMN).gapAll(8f));
        box.append(panel);
        box.append(log);
        return box;
    }

    /** An array is not its {@code toString}, and neither is a matrix. */
    private static String describe(@Nullable Object value) {
        if (value instanceof double[] numbers) return Arrays.toString(numbers);
        return String.valueOf(value);
    }

    // ── 6.3: the collections and the shell's chrome ────────────────────────

    /**
     * A virtualised list of ten thousand rows.
     *
     * <p><b>Ten thousand deliberately.</b> A list of twenty tells you nothing a column of labels
     * would not, and virtualisation is the one thing about this widget that can be silently absent —
     * a list that realised everything looks identical until the frame time says otherwise. The
     * counter under it reads what is actually realised, so the answer is on screen rather than in a
     * profiler.</p>
     */
    private UIElement listView() {
        ObservableList<String> model = new ObservableList<>();
        for (int i = 0; i < 10_000; i++) model.add("Row " + i);
        ListView<String> list = new ListView<>(model);
        list.setRenderer(new ListRenderer<>() {
            @Override
            public UIElement createTemplate() {
                UIElement row = new UIElement();
                row.append(new UIText(""));
                return row;
            }

            @Override
            public void bind(String item, int index, UIElement template) {
                ((UIText) template.children().get(0)).setText(item);
            }
        }).setItemHeight(22f).setSelectionMode(SelectionMode.MULTIPLE);
        StyleGroup.inlinePipeline(list.getStyle().getLayoutGroup(),
                l -> l.widthPercent(100f).height(180f));

        UIText counter = hint("");
        document.animation().every(list, delta -> {
            long realised = list.children().stream()
                    .filter(c -> c.hasClass(ListView.ROW_CLASS)).count();
            counter.setText(realised + " of 10,000 rows realised · "
                    + list.getSelectedIndices().size() + " selected");
            return true;
        });

        UIElement box = new UIElement();
        StyleGroup.inlinePipeline(box.getStyle().getLayoutGroup(),
                l -> l.widthPercent(100f).flexDirection(FlexDirection.COLUMN).gapAll(6f));
        box.append(list);
        box.append(counter);
        return box;
    }

    /** A three-level tree, so an indent reads as an indent and a twisty has somewhere to go. */
    private UIElement treeView() {
        TreeDataSource<String> source = new TreeDataSource<>() {
            @Override
            public List<String> roots() {
                return List.of("src", "assets", "docs");
            }

            @Override
            public List<String> children(String parent) {
                long depth = parent.chars().filter(c -> c == '/').count();
                if (depth >= 2) return List.of();
                return List.of(parent + "/one", parent + "/two", parent + "/three");
            }

            @Override
            public boolean hasChildren(String item) {
                return !children(item).isEmpty();
            }
        };
        TreeView<String> tree = new TreeView<>(source);
        // A TWISTY, BUILT BY THE RENDERER -- which is where it belongs on both engines: TreeView
        // applies the indent and the __expanded__/__collapsed__/__leaf__ classes and nothing else, so
        // a renderer that makes only a label produces a tree that cannot be opened with the mouse.
        // A real chevron rather than a glyph: the bundled Minecraft font has no triangle character.
        tree.setRenderer(new TreeRenderer<>() {
            @Override
            public UIElement createTemplate() {
                UIElement row = new UIElement().addClass("tv-row");
                row.setFocusPolicy(FocusPolicy.CLICK);
                UIElement twisty = new UIElement().addClass("tv-twisty");
                // ONCE per pooled element, reading the row's CURRENT index at click time -- it cannot
                // capture one, because this element is a different row every time it is recycled.
                twisty.onMouseDown.attachListener((el, event) -> {
                    int at = tree.indexOfRowElement(el.parentElement());
                    if (at >= 0) tree.toggleExpandedAt(at);
                    event.stopPropagation();
                }, false, false);
                row.append(twisty);
                UIText label = new UIText("");
                label.setHitTest(false);
                row.append(label);
                return row;
            }

            @Override
            public void bind(String item, TreeRow<String> row, int index, UIElement template) {
                ((UIText) template.children().get(1))
                        .setText(row.depth() == 0 ? item : item.substring(item.lastIndexOf('/') + 1));
            }
        });
        tree.setExpanded("src", true);
        StyleGroup.inlinePipeline(tree.getStyle().getLayoutGroup(),
                l -> l.widthPercent(100f).height(200f));
        return tree;
    }

    /**
     * Three columns, one flexible and two fixed, two of them sortable.
     *
     * <p>A fixed column beside a flexible one is the pairing that catches the arithmetic: a table
     * whose weights are applied to the whole width rather than to the free space looks right until
     * exactly one column is fixed.</p>
     */
    private UIElement tableView() {
        ObservableList<String> model = new ObservableList<>();
        for (String name : new String[] {"gui_quad.shader", "gui_backdrop_filter.shader", "gui_blur.shader",
                                         "gui_gradient.shader", "gui_curve.shader"}) {
            model.add(name);
        }
        TableView<String> table = new TableView<>(model);
        table.addColumn(TableColumn.<String>of("Name", s -> s).flexible().sortable());
        table.addColumn(TableColumn.<String>of("Chars", s -> String.valueOf(s.length()))
                .width(70f).sortable());
        table.addColumn(TableColumn.<String>of("Kind",
                s -> s.contains("glass") || s.contains("blur") ? "effect" : "draw").width(90f));
        StyleGroup.inlinePipeline(table.getStyle().getLayoutGroup(),
                l -> l.widthPercent(100f).height(180f));
        return table;
    }

    /**
     * A menu bar over a real {@link CommandRegistry}, with one command deliberately disabled.
     *
     * <p>The disabled row is the point: the registry carries {@code enabled} and never filters, so a
     * bar that DROPPED it would look tidier and be wrong — a menu whose rows are never in the same
     * place twice is the failure that rule exists to prevent.</p>
     */
    private UIElement menuBar() {
        CommandRegistry registry = new CommandRegistry();
        MenuId file = MenuId.of("gallery.file");
        MenuId edit = MenuId.of("gallery.edit");
        UIText log = hint("pick something from the bar");
        registry.register(Command.of("gallery.new", "New")
                .menu(file, "io", 0).run(() -> log.setText("File > New")));
        registry.register(Command.of("gallery.open", "Open...")
                .menu(file, "io", 1).run(() -> log.setText("File > Open")));
        registry.register(Command.of("gallery.save", "Save (disabled on purpose)")
                .menu(file, "io", 2).enabledWhen(context -> false).run(() -> {
                }));
        registry.register(Command.of("gallery.cut", "Cut")
                .menu(edit, "clipboard", 0).run(() -> log.setText("Edit > Cut")));
        registry.register(Command.of("gallery.copy", "Copy")
                .menu(edit, "clipboard", 1).run(() -> log.setText("Edit > Copy")));

        MenuBarView bar = new MenuBarView(registry).addMenu(file, "File").addMenu(edit, "Edit");
        StyleGroup.inlinePipeline(bar.getStyle().getLayoutGroup(), l -> l.widthPercent(100f));

        UIElement box = new UIElement();
        StyleGroup.inlinePipeline(box.getStyle().getLayoutGroup(),
                l -> l.widthPercent(100f).flexDirection(FlexDirection.COLUMN).gapAll(6f));
        box.append(bar);
        box.append(log);
        return box;
    }

    /**
     * The status bar, with its breadcrumb trail in it.
     *
     * <p>Both at once because the trail lives in the bar — showing it alone would demo a widget in a
     * context nothing uses it in, and its sizing comes from the bar around it.</p>
     */
    private UIElement statusBar() {
        StatusBarView status = new StatusBarView(new StatusBar());
        StyleGroup.inlinePipeline(status.getStyle().getLayoutGroup(), l -> l.widthPercent(100f));
        status.breadcrumbs().setTrail(List.of("core", "src", "com", "crystalgui", "widget"));
        return status;
    }

    /**
     * The picker, opened over the whole document — it is a promoted overlay, never a child.
     *
     * <p>Which is the thing worth seeing: it is built here as a sibling of nothing, positioned
     * against the surface rather than against whatever opened it, and dismissed by Escape or a press
     * outside. A picker demoed inline would be a list with a search box on top of it.</p>
     */
    private UIElement palette() {
        Button open = new Button("Open a QuickPick");
        open.attachListener(() -> {
            QuickPick pick = new QuickPick();
            pick.setPlaceholder("Type to filter...");
            pick.setSource(QuickPickSource.of(List.of(
                    QuickPickItem.of("quad", "gui_quad.shader")
                            .withDescription("the default material"),
                    QuickPickItem.of("glass", "gui_backdrop_filter.shader")
                            .withDescription("liquid glass"),
                    QuickPickItem.of("blur", "gui_blur.shader")
                            .withDescription("one axis of the Gaussian"),
                    QuickPickItem.of("gradient", "gui_gradient.shader")
                            .withDescription("eight stops, one draw"))));
            pick.open(document);
        });
        return row(open);
    }

    // ── 6.4: the canvas and the graph ──────────────────────────────────────

    /**
     * A node graph: three nodes, two wires, a live pan and zoom.
     *
     * <p><b>Wired on purpose, and with one input deliberately taken twice.</b> A graph of unconnected
     * nodes demonstrates the node widget and nothing about the view — the wires are what read a port's
     * colour out of the cascade, what cull with the plane, and what the marquee has to avoid selecting.
     * The status line under it reports the pan and zoom continuously, which is the only way to see
     * that a drag tracks the pointer rather than accelerating away from it.</p>
     *
     * <p>The third node sits far enough right to be off screen at rest, so culling has something to
     * cull: a plane whose every node is visible demonstrates a viewport, not a canvas.</p>
     */
    private UIElement graphView() {
        GraphView graph = new GraphView();
        StyleGroup.inlinePipeline(graph.getStyle().getLayoutGroup(),
                l -> l.widthPercent(100f).height(320f));

        PortType number = new BasicPortType("float", 1);
        PortType colour = new BasicPortType("vec4", 4);

        GraphNode a = new GraphNode("Time");
        graph.addNode(a, 20f, 30f);
        NodePort aOut = a.addOutput(number, "Out");

        GraphNode b = new GraphNode("Multiply");
        graph.addNode(b, 220f, 20f);
        NodePort bIn = b.addInput(number, "A");
        NodePort bIn2 = b.addInput(number, "B");
        NodePort bOut = b.addOutput(number, "Out");

        GraphNode c = new GraphNode("Fragment");
        graph.addNode(c, 430f, 60f);
        c.addInput(colour, "Base Color");
        NodePort cIn = c.addInput(number, "Alpha");

        GraphNode far = new GraphNode("Off screen");
        graph.addNode(far, 1400f, 40f);
        far.addOutput(number, "Out");

        graph.connect(aOut, bIn);
        graph.connect(bOut, cIn);

        UIText status = hint("");
        document.animation().every(graph, delta -> {
            status.setText(String.format("pan %.0f, %.0f · zoom %.2f · %d wires — "
                            + "middle-drag to pan, wheel to zoom, drag a port to rewire",
                    graph.getPanX(), graph.getPanY(), graph.getZoom(),
                    graph.getConnections().size()));
            return true;
        });

        UIElement box = new UIElement();
        StyleGroup.inlinePipeline(box.getStyle().getLayoutGroup(),
                l -> l.widthPercent(100f).flexDirection(FlexDirection.COLUMN).gapAll(6f));
        box.append(graph);
        box.append(status);
        // THE SECOND INPUT IS LEFT FREE so the replace rule is reachable by hand: drag `Time`'s output
        // onto `Multiply`'s A, which is already taken, and the existing wire has to leave.
        box.append(hint("`Multiply.A` is already wired — dropping another wire on it must REPLACE, "
                + "not refuse; `B` is free for comparison"));
        return box;
    }

    /**
     * The bare canvas the graph is built on: pan, zoom, and culling with the count on screen.
     *
     * <p>Shown beside the graph rather than instead of it because the two answer different questions.
     * A {@code GraphView} demonstrates wires and ports; a {@code CanvasView} demonstrates that the
     * plane is a viewport — which is only visible when there is more content than fits and a number
     * saying how much of it is currently culled.</p>
     */
    private UIElement canvasView() {
        CanvasView canvas = new CanvasView();
        StyleGroup.inlinePipeline(canvas.getStyle().getLayoutGroup(),
                l -> l.widthPercent(100f).height(200f));

        List<UIElement> tiles = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            UIElement tile = new UIElement().addClass("canvas-tile");
            tile.append(hint("#" + i));
            StyleGroup.inlinePipeline(tile.getStyle().getLayoutGroup(),
                    l -> l.width(70f).height(44f));
            canvas.addNode(tile, (i % 10) * 90f, (i / 10) * 60f);
            tiles.add(tile);
        }

        UIText status = hint("");
        document.animation().every(canvas, delta -> {
            int visible = 0;
            for (UIElement tile : tiles) {
                Box box = tile.box();
                if (box != null && box.width() > 0f) visible++;
            }
            status.setText(String.format("zoom %.2f · %d of %d tiles laid out — "
                            + "culled tiles keep their box and lose their opacity, never the reverse",
                    canvas.getZoom(), visible, tiles.size()));
            return true;
        });

        UIElement box = new UIElement();
        StyleGroup.inlinePipeline(box.getStyle().getLayoutGroup(),
                l -> l.widthPercent(100f).flexDirection(FlexDirection.COLUMN).gapAll(6f));
        box.append(canvas);
        box.append(status);
        return box;
    }

    private UIElement section(String heading, UIElement body) {
        UIElement section = new UIElement().addClass("section");
        UIText title = new UIText(heading);
        title.addClass("heading");
        section.append(title);
        section.append(body);
        return section;
    }

    private UIElement row(UIElement... children) {
        UIElement row = new UIElement().addClass("row");
        StyleGroup.defaultPipeline(row.getStyle().getLayoutGroup(),
                l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER));
        for (UIElement child : children) {
            if (child != null) row.append(child);
        }
        return row;
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        int w = ctx.getScreenWidth();
        int h = ctx.getScreenHeight();
        float delta = frame.getDeltaTime();

        // The determinate bar walks, so a single glance says whether a value write reaches the fill
        // -- a frozen bar at its first value is what the old ProgressBar's non-idempotent setter
        // looked like from the other side.
        elapsed += delta;
        determinate.setFraction((elapsed % 4f) / 4f);

        document.frame(delta, w / SCALE, h / SCALE);

        UiRecorder context = document.recorder();
        context.beginFrame(w, h);
        document.paint(context);
        context.endFrame();

        // A startup capture, so this scene contributes to the pixel-regression set. Without it the
        // scene runs and writes no artifact, and a "scenes diff to zero" check silently covers
        // nothing here.
        if (frame.getFrameNumber() == 5) ctx.getArtifactService().requestCapture("startup");
    }

    @Override
    public void dispose() {
        document = null;
        determinate = null;
    }

    @Override
    public boolean isRunning() {
        return true;
    }

    @Override
    public boolean uses3DCamera() {
        return false;
    }

    @Override
    public boolean shouldShutdownOnComplete() {
        return false;
    }

    @Override
    public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
        return document.input().consumeKeyboardEvent(event);
    }

    @Override
    public boolean consumeMouseEvent(CgSystemInput.Mouse.Event event) {
        return document.input().consumeMouseEvent(event);
    }
}
