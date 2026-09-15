package com.crystalgui.widget;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import dev.vfyjxf.taffy.style.TaffyPosition;

import com.crystalgui.core.collection.tree.TreeDataSource;
import com.crystalgui.core.collection.tree.TreeRow;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.ObservableList;
import com.crystalgui.style.PseudoClasses;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.text.lang.SymbolKind;
import com.crystalgui.text.markup.MarkupParser;
import com.crystalgui.text.syntax.Language;
import com.crystalgui.text.syntax.SyntaxToken;
import com.crystalgui.ui.dom.Preview;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.canvas.CanvasView;
import com.crystalgui.widget.collection.list.ListRenderer;
import com.crystalgui.widget.collection.list.ListView;
import com.crystalgui.widget.collection.table.TableColumn;
import com.crystalgui.widget.collection.table.TableView;
import com.crystalgui.widget.collection.tree.TreeRenderer;
import com.crystalgui.widget.collection.tree.TreeView;
import com.crystalgui.widget.composite.ColorSelector;
import com.crystalgui.widget.composite.RadarChart;
import com.crystalgui.widget.composite.SearchField;
import com.crystalgui.widget.config.ConfigControl;
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
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.control.Checkbox;
import com.crystalgui.widget.control.Slider;
import com.crystalgui.widget.control.Switch;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.widget.display.ProgressBar;
import com.crystalgui.widget.display.SymbolIcon;
import com.crystalgui.widget.layout.PageStack;
import com.crystalgui.widget.layout.SplitView;
import com.crystalgui.widget.layout.TabView;
import com.crystalgui.widget.overlay.Dialog;
import com.crystalgui.widget.overlay.Dropdown;
import com.crystalgui.widget.overlay.Menu;
import com.crystalgui.widget.overlay.Popover;
import com.crystalgui.widget.overlay.Tooltip;
import com.crystalgui.widget.scroll.ScrollerView;
import com.crystalgui.widget.text.MarkupView;
import com.crystalgui.widget.text.SyntaxHighlighting;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.widget.texteditor.TextEditor;

/**
 * What each shipped widget's Library card shows — the kind dressed to be recognised: a slider at half, a field
 * with a label, a tab view with its tabs, a menu drawn open.
 *
 * <pre>{@code
 * UIElementRegistry.register(Slider.NAME, Slider::new, Slider.CONTRACT,
 *         KindInfo.named("Slider").preview(WidgetSamples.slider()));
 * }</pre>
 *
 * <p>Design-time only: a sample is never placed, and nothing here runs until a Library asks. Widths are the
 * logical width each is laid out at before the card fits it; a sheet decides everything else, and the few
 * blocks drawn only for a sample are styled by {@code .__sample-*__} rules in {@code ua/samples.css}.</p>
 */
public final class WidgetSamples {

    /** A plain block inside a sample: a pane's content, a node on a canvas, a child of an element. */
    public static final String BLOCK_CLASS = "__sample-block__";

    /** A canvas drawn for a sample: its grid, which is what tells a plane from a panel. */
    public static final String CANVAS_CLASS = "__sample-canvas__";

    /** A heading inside a sample — a popover's title. */
    public static final String TITLE_CLASS = "__sample-title__";

    /** A bare text field drawn for a sample: the outline its contexts each give it, since the field has none. */
    public static final String FIELD_CLASS = "__sample-field__";

    /** A form drawn for a sample: a label column narrow enough that a label and its value read as one row. */
    public static final String FORM_CLASS = "__sample-form__";

    /** A tree row's fold arrow, drawn from the row's expanded or collapsed class. */
    public static final String TWISTY_CLASS = "__sample-twisty__";

    /** A page behind the front one in a page stack's sample. */
    public static final String BACK_PAGE_CLASS = "__sample-back-page__";

    /** A popover drawn for a sample: room round its content, as a settings popover has. */
    public static final String POPOVER_CLASS = "__sample-popover__";

    /** The point a sample popover's box aims at the button that opened it. */
    public static final String NOTCH_CLASS = "__sample-notch__";

    /** The turned square inside a notch, whose lower half the popover covers. */
    public static final String NOTCH_TIP_CLASS = "__sample-notch-tip__";

    /** A bar standing in for a line of text inside a sample's page. */
    public static final String LINE_CLASS = "__sample-line__";

    /** How wide a form is laid out: a label column and a field, as in an inspector. */
    private static final float FORM_WIDTH = 110f;

    /** The radar's wedges, one hue per axis — data, as a chart's colours are. */
    private static final int[] RADAR_HUES = {0xFF4C9AFF, 0xFF6CC070, 0xFFE0A040, 0xFFD06070, 0xFFA070E0};

    private WidgetSamples() {
    }

    // ── Controls ─────────────────────────────────────────────────────────

    public static Preview button() {
        return Preview.sample(() -> new Button("Button"));
    }

    public static Preview checkbox() {
        return Preview.sample(() -> new Checkbox("Checkbox").setChecked(true));
    }

    public static Preview switchOn() {
        return Preview.sample(() -> new Switch().setChecked(true));
    }

    /** Short enough to sit in the card at its own thickness, and at half so the fill shows. */
    public static Preview slider() {
        return Preview.sample(() -> new Slider().setValue(0.5f)).width(54f);
    }

    /** As it looks being typed into: the placeholder and a caret. Unfocused, an empty field draws nothing at all. */
    public static Preview textField() {
        return Preview.sample(() -> new TextField().setPlaceholder("Type here").presentFocused().addClass(FIELD_CLASS))
                .width(72f);
    }

    /** Wide enough to read as a field and not a button. */
    public static Preview searchField() {
        return Preview.sample(() -> {
            SearchField search = new SearchField().setPlaceholder("Search");
            search.field().presentFocused();
            return search;
        }).width(72f);
    }

    public static Preview dropdown() {
        return Preview.sample(() -> {
            Dropdown dropdown = new Dropdown();
            dropdown.addOptions("Linear", "Ease", "Step");
            return dropdown.select(1);
        });
    }

    public static Preview colorSelector() {
        // WHOLE, at its own width: the wheel, the sliders and the values together are what a colour selector is.
        return Preview.sample(ColorSelector::new).width(160f).whole();
    }

    // ── Display ──────────────────────────────────────────────────────────

    public static Preview progressBar() {
        return Preview.sample(() -> {
            ProgressBar bar = new ProgressBar();
            bar.setFraction(0.6f);
            return bar;
        }).width(54f);
    }

    public static Preview symbolIcon() {
        return Preview.sample(() -> new SymbolIcon().show(SymbolKind.CLASS, Set.of()));
    }

    public static Preview radarChart() {
        return Preview.sample(() -> {
            // THE SHAPE, NOT THE WORDS: axis labels at card size are specks, and the web of wedges is what names it.
            RadarChart chart = new RadarChart()
                    .setAxisLabels(List.of("", "", "", "", ""))
                    .setValues(0.85, 0.55, 0.95, 0.4, 0.7)
                    .setAxisColors(RADAR_HUES);
            chart.layout(l -> l.width(46f).height(46f));
            return chart;
        });
    }

    // ── Text ─────────────────────────────────────────────────────────────

    /** A document, not a label: a heading, a list with emphasis and a code block — what plain text cannot draw. */
    public static Preview markupView() {
        return Preview.sample(() -> new MarkupView(MarkupParser.parse(
                "<h1>Guide</h1><ul><li><b>Bold</b> and <i>italic</i></li><li>A <a href=\"#\">link</a></li></ul>"
                        + "<pre>let x = 1;</pre>"))).width(100f).whole();
    }

    /** Coloured code with its gutter, read-only so the card never takes a caret. */
    public static Preview textEditor() {
        return Preview.sample(() -> {
            String code = "class Hero {\n  int hp;\n  void heal() {}\n}";
            // COLOURED ONCE, by the shared snippet tokenizer: a document's own holds a native parse tree that only
            // its document releases, and a card has no document. Read-only, so the tokens never go stale.
            List<SyntaxToken> tokens = SyntaxHighlighting.tokenize(code, Language.JAVA);
            TextEditor editor = new TextEditor(code)
                    .setLanguage(Language.JAVA)
                    .setTokenizer((document, from, to) -> tokens)
                    .setReadOnly(true)
                    // Nothing below the code to scroll to, so no bar.
                    .setScrollBeyondLastLine(false);
            editor.layout(l -> l.height(58f));
            return editor;
        }).width(150f).whole();
    }

    // ── Layout ───────────────────────────────────────────────────────────

    /** One empty box: a container, with no children to suggest a layout it does not have. */
    public static Preview element() {
        return Preview.sample(() -> block(44f, 30f));
    }

    public static Preview scrollView() {
        return Preview.sample(() -> {
            ScrollerView scroller = new ScrollerView();
            scroller.layout(l -> l.height(40f));
            for (String line : List.of("First line", "Second line", "Third line", "Fourth line", "Fifth line")) {
                scroller.append(new UIText(line));
            }
            return scroller;
        }).width(70f);
    }

    public static Preview splitView() {
        return Preview.sample(() -> {
            SplitView split = new SplitView();
            split.layout(l -> l.height(44f));
            while (split.paneCount() < 2) split.addPane();
            split.paneContent(0, fill(block(0f, 0f)));
            split.paneContent(1, fill(block(0f, 0f)));
            return split;
        }).width(90f);
    }

    /** The whole view: its strip over a framed page of content, which is what separates it from a tab. */
    public static Preview tabView() {
        return Preview.sample(() -> {
            TabView tabs = new TabView();
            tabs.layout(l -> l.height(52f));
            for (String label : List.of("Scene", "Game", "Log")) {
                tabs.addTab(label).content().append(fill(block(0f, 0f).append(page(3))));
            }
            return tabs.selectIndex(0);
        }).width(96f);
    }

    /** The strip alone: tabs with their close buttons, the first one active — no page under it. */
    public static Preview tab() {
        return Preview.sample(() -> {
            TabView tabs = new TabView();
            for (String label : List.of("Tab", "Other")) tabs.addTab(label).setClosable(true);
            tabs.panes().layout(l -> l.display(TaffyDisplay.NONE));
            return tabs.selectIndex(0);
        }).width(90f);
    }

    /** One page shown in front of the pages it swaps between, offset behind it like a deck. */
    public static Preview pageStack() {
        return Preview.sample(() -> {
            UIElement deck = new UIElement();
            deck.layout(l -> l.width(66f).height(48f));
            for (int i = 2; i >= 1; i--) {
                float offset = i * 5f;
                UIElement back = block(0f, 0f).addClass(BACK_PAGE_CLASS);
                back.layout(l -> l.positionType(TaffyPosition.ABSOLUTE).left(offset).top(0f).width(54f).height(48f - offset));
                deck.append(back);
            }
            PageStack<String> pages = new PageStack<>();
            pages.layout(l -> l.positionType(TaffyPosition.ABSOLUTE).left(0f).top(0f).width(54f).height(48f));
            pages.setPageFactory(key -> {
                UIElement page = block(0f, 0f);
                page.append(new UIText(key).addClass(TITLE_CLASS));
                page.append(page(2));
                return fill(page);
            });
            pages.show("Page 1");
            deck.append(pages);
            return deck;
        });
    }

    public static Preview canvasView() {
        return Preview.sample(() -> {
            CanvasView canvas = new CanvasView();
            canvas.addClass(CANVAS_CLASS);
            canvas.layout(l -> l.height(44f));
            canvas.addNode(block(22f, 14f), 8f, 8f);
            canvas.addNode(block(22f, 14f), 44f, 22f);
            return canvas;
        }).width(90f);
    }

    // ── Collections ──────────────────────────────────────────────────────

    public static Preview listView() {
        return Preview.sample(() -> {
            ObservableList<String> rows = new ObservableList<>();
            for (String row : List.of("Apples", "Bread", "Cheese")) rows.add(row);
            ListView<String> list = new ListView<>(rows);
            list.setRenderer(textRows());
            // EXACTLY ITS ROWS: a sliced fourth row and a scrollbar read as a broken list, not a long one.
            list.setItemHeight(14f);
            list.layout(l -> l.height(42f));
            return list.select(1);
        }).width(70f);
    }

    public static Preview treeView() {
        return Preview.sample(() -> {
            Map<String, List<String>> children = Map.of(
                    "assets", List.of("textures", "sounds"),
                    "textures", List.of("stone.png"));
            TreeView<String> tree = new TreeView<>(new TreeDataSource<>() {
                @Override
                public List<String> roots() {
                    return List.of("assets", "src");
                }

                @Override
                public List<String> children(String parent) {
                    return children.getOrDefault(parent, List.of());
                }

                @Override
                public boolean hasChildren(String item) {
                    return children.containsKey(item);
                }
            });
            tree.setRenderer(new TreeRenderer<>() {
                @Override
                public UIElement createTemplate() {
                    UIElement row = new UIElement();
                    row.layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER));
                    row.append(new UIElement().addClass(TWISTY_CLASS));
                    row.append(new UIText(""));
                    return row;
                }

                @Override
                public void bind(String item, TreeRow<String> row, int index, UIElement template) {
                    ((UIText) template.children().get(1)).setText(item);
                }
            });
            tree.setExpandedItems(List.of("assets", "textures"));
            tree.setItemHeight(14f);
            tree.layout(l -> l.height(70f));
            return tree;
        }).width(80f);
    }

    public static Preview tableView() {
        return Preview.sample(() -> {
            ObservableList<String[]> rows = new ObservableList<>();
            rows.add(new String[] {"Sword", "1"});
            rows.add(new String[] {"Shield", "2"});
            rows.add(new String[] {"Potion", "5"});
            TableView<String[]> table = new TableView<>(rows);
            table.addColumn(TableColumn.<String[]>of("Item", row -> row[0]).width(44f));
            table.addColumn(TableColumn.<String[]>of("Qty", row -> row[1]).width(28f));
            table.layout(l -> l.height(52f));
            return table;
        }).width(76f);
    }

    // ── Overlays, drawn open where they stand ────────────────────────────

    /** Opened under the button that shows it, holding a control: a panel of settings for one thing on screen. */
    public static Preview popover() {
        return Preview.sample(() -> {
            UIElement anchored = new UIElement();
            anchored.layout(l -> l.alignItems(AlignItems.CENTER));
            anchored.append(new Button("Style"));
            // BEFORE THE POPOVER, so the popover paints over its lower half and only the point shows.
            anchored.append(new UIElement().addClass(NOTCH_CLASS).append(new UIElement().addClass(NOTCH_TIP_CLASS)));
            Popover popover = new Popover();
            popover.addClass(POPOVER_CLASS);
            popover.append(new UIText("Opacity").addClass(TITLE_CLASS));
            UIElement row = new UIElement();
            row.layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER).gapAll(4f));
            Slider opacity = new Slider().setValue(0.7f);
            opacity.layout(l -> l.width(50f));
            row.append(opacity);
            row.append(new UIText("70%"));
            popover.append(row);
            anchored.append(popover.presentInline());
            return anchored;
        }).whole();
    }

    public static Preview menu() {
        return Preview.sample(() -> {
            Menu menu = new Menu();
            menu.addItem("Cut");
            menu.addItem("Copy");
            menu.addItem("Paste");
            return menu.presentInline();
        });
    }

    /** A row is shown in its menu, highlighted. */
    public static Preview menuItem() {
        return Preview.sample(() -> {
            Menu menu = new Menu();
            menu.addItem("Menu Item").forceState(PseudoClasses.FOCUS, true);
            menu.addItem("Another");
            return menu.presentInline();
        });
    }

    public static Preview tooltip() {
        return Preview.sample(() -> new Tooltip("Tooltip").setShortcut("Ctrl+T").presentInline());
    }

    public static Preview dialog() {
        return Preview.sample(() -> {
            Dialog dialog = new Dialog("Dialog");
            dialog.getContent().append(new UIText("Are you sure?"));
            return dialog.presentInline();
        }).width(100f);
    }

    // ── Forms: a field in its row where the label is part of the picture, the control alone where not ──

    public static Preview configurator() {
        return Preview.sample(() -> {
            ConfigDescriptor name = ConfigDescriptor.text("name", "Name");
            return new Configurator(Configurator.Arrangement.COMPACT, name, new TextControl(name, "Player"));
        });
    }

    public static Preview configuratorGroup() {
        return Preview.sample(() -> {
            ConfiguratorGroup group = new ConfiguratorGroup("Transform");
            group.addClass(FORM_CLASS);
            ConfigDescriptor x = ConfigDescriptor.number("x", "X");
            ConfigDescriptor y = ConfigDescriptor.number("y", "Y");
            group.content().append(new Configurator(x, new NumberControl(x, 0)));
            group.content().append(new Configurator(y, new NumberControl(y, 12)));
            return group;
        }).width(FORM_WIDTH).whole();
    }

    public static Preview configuratorPanel() {
        return Preview.sample(() -> {
            ConfiguratorPanel panel = new ConfiguratorPanel();
            panel.addClass(FORM_CLASS);
            // NO BARS: at card size a scrollbar is a stripe across the rows, and nothing here scrolls.
            panel.setScrollbarsVisible(false);
            panel.layout(l -> l.height(62f));
            panel.add(ConfigDescriptor.header("Material"), null);
            panel.add(ConfigDescriptor.color("tint", "Tint"), 0xFF4C9AFF);
            panel.add(ConfigDescriptor.bool("lit", "Lit"), true);
            return panel;
        }).width(FORM_WIDTH).whole();
    }

    public static Preview anchorField() {
        ConfigDescriptor anchor = ConfigDescriptor.anchor("pivot", "Pivot");
        // THE GRID ALONE: it is the whole of what the field looks like, and a label beside it leaves nine specks.
        return Preview.sample(() -> new AnchorControl(anchor, new double[] {0.5, 0.5}));
    }

    public static Preview arrayField() {
        ConfigDescriptor tags = ConfigDescriptor.of("tags", "Tags", ConfigDescriptor.Kind.ARRAY)
                .element(ConfigDescriptor.text("tag", ""));
        return Preview.sample(() -> new ArrayControl(tags, List.of("hostile", "undead"))).width(FORM_WIDTH);
    }

    public static Preview assetField() {
        ConfigDescriptor texture = ConfigDescriptor.asset("texture", "Texture");
        return field(texture, () -> new AssetControl(texture, "stone"));
    }

    public static Preview booleanField() {
        ConfigDescriptor enabled = ConfigDescriptor.bool("enabled", "Enabled");
        return field(enabled, () -> new BooleanControl(enabled, true));
    }

    public static Preview colorField() {
        ConfigDescriptor tint = ConfigDescriptor.color("tint", "Tint");
        // THE SWATCH ALONE, at a width: a compact cell sizes a swatch to nothing.
        return Preview.sample(() -> new ColorControl(tint, 0xFF4C9AFF)).width(48f);
    }

    public static Preview formHeader() {
        // IN ITS ROW, where the band is drawn: a header alone is only its words.
        return Preview.sample(() -> {
            ConfigDescriptor transform = ConfigDescriptor.header("Transform");
            return new Configurator(transform, new HeaderControl(transform));
        }).width(FORM_WIDTH);
    }

    public static Preview formInfo() {
        ConfigDescriptor vertices = ConfigDescriptor.info("vertices", "Vertices");
        return field(vertices, () -> new InfoControl(vertices, "1,204"));
    }

    public static Preview formNote() {
        return Preview.sample(() -> new NoteControl(ConfigDescriptor.note("note"), "Changes apply when saved."))
                .width(FORM_WIDTH);
    }

    public static Preview classChipsField() {
        ConfigDescriptor classes = ConfigDescriptor.of("classes", "Classes", ConfigDescriptor.Kind.ARRAY);
        return field(classes, () -> new ClassChips(classes, List.of("title", "wide")));
    }

    public static Preview maskField() {
        ConfigDescriptor layers = ConfigDescriptor.mask("layers", "Layers", List.of("UI", "World"));
        return field(layers, () -> new MaskControl(layers, Set.of("UI")));
    }

    public static Preview matrixField() {
        ConfigDescriptor scale = ConfigDescriptor.matrix("scale", "Scale", 2);
        // THE GRID ALONE: a matrix has no room for a label beside it on a card.
        return Preview.sample(() -> new MatrixControl(scale, new double[] {1, 0, 0, 1})).width(46f);
    }

    public static Preview numberField() {
        ConfigDescriptor speed = ConfigDescriptor.number("speed", "Speed");
        return field(speed, () -> new NumberControl(speed, 1.5));
    }

    public static Preview selectField() {
        ConfigDescriptor mode = ConfigDescriptor.select("mode", "Mode", List.of("Linear", "Ease", "Step"));
        return field(mode, () -> new SelectControl(mode, "Ease"));
    }

    public static Preview sliderField() {
        ConfigDescriptor volume = ConfigDescriptor.number("volume", "Volume").range(0f, 1f);
        // THE TRACK AND ITS VALUE ALONE: a compact cell sizes the track to nothing, and a row's label shrinks it to specks.
        return Preview.sample(() -> new SliderControl(volume, 0.5)).width(80f);
    }

    public static Preview textControl() {
        ConfigDescriptor name = ConfigDescriptor.text("name", "Name");
        return field(name, () -> new TextControl(name, "Player"));
    }

    public static Preview vectorField() {
        ConfigDescriptor position = ConfigDescriptor.vector("position", "Pos", 3);
        // THE COMPONENTS ALONE, at a width that gives each its letter: compact stacks the letters over each other.
        return Preview.sample(() -> new VectorControl(position, new double[] {0, 1, 0})).width(96f);
    }

    // ── Parts ────────────────────────────────────────────────────────────

    /**
     * A field with its label in front, at its own width — the toolbar cell's arrangement. An inspector row's label
     * column is wider than a card, and would leave the value clipped at its edge.
     */
    private static Preview field(ConfigDescriptor descriptor, Supplier<ConfigControl> control) {
        return Preview.sample(() -> new Configurator(Configurator.Arrangement.COMPACT, descriptor, control.get()));
    }

    private static UIElement block(float width, float height) {
        UIElement block = new UIElement().addClass(BLOCK_CLASS);
        if (width > 0f) block.layout(l -> l.width(width).height(height));
        return block;
    }

    /** Bars of decreasing length standing in for a page's text. */
    private static UIElement page(int lines) {
        UIElement page = new UIElement();
        page.layout(l -> l.gapAll(3f).paddingAll(4f));
        for (int i = 0; i < lines; i++) {
            float width = 40f - i * 9f;
            UIElement line = new UIElement().addClass(LINE_CLASS);
            line.layout(l -> l.width(width).height(3f));
            page.append(line);
        }
        return page;
    }

    private static UIElement fill(UIElement element) {
        StyleGroup.inlinePipeline(element.getStyle().getLayoutGroup(),
                l -> l.widthPercent(100f).heightPercent(100f));
        return element;
    }

    private static ListRenderer<String> textRows() {
        return new ListRenderer<>() {
            @Override
            public UIElement createTemplate() {
                return new UIText("");
            }

            @Override
            public void bind(String item, int index, UIElement template) {
                ((UIText) template).setText(item);
            }
        };
    }
}
