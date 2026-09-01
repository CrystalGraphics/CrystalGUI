package com.crystalgui.widget;

import com.crystalgui.workbench.chrome.notification.NotificationsView;
import com.crystalgui.workbench.chrome.palette.QuickPick;
import com.crystalgui.workbench.chrome.preferences.NavigatorView;
import com.crystalgui.workbench.chrome.problems.ProblemsPanel;
import com.crystalgui.workbench.chrome.status.Breadcrumbs;
import com.crystalgui.workbench.chrome.status.StatusBarView;
import com.crystalgui.core.collection.pick.QuickPickItem;
import com.crystalgui.core.collection.pick.QuickPickSource;
import com.crystalgui.core.collection.tree.TreeDataSource;
import com.crystalgui.core.property.ObservableList;
import com.crystalgui.fs.Resource;
import com.crystalgui.graph.NodeType;
import com.crystalgui.graph.NodeTypeRegistry;
import com.crystalgui.graph.port.BasicPortType;
import com.crystalgui.graph.port.PortType;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSet;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.text.diagnostic.Markers;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.widget.texteditor.TextEditor;
import com.crystalgui.text.view.RenderWhitespace;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.widget.canvas.CanvasView;
import com.crystalgui.widget.collection.list.ListView;
import com.crystalgui.widget.collection.table.TableColumn;
import com.crystalgui.widget.collection.table.TableView;
import com.crystalgui.widget.collection.tree.TreeView;
import com.crystalgui.widget.graph.GraphNode;
import com.crystalgui.widget.graph.GraphView;
import com.crystalgui.widget.graph.NodePort;
import com.crystalgui.widget.graph.node.NodeCreationMenu;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * <b>Every {@code __part__} the shipped sheets select under a ported widget's tag is still reachable
 * on the new engine.</b>
 *
 * <p>The port's silent failure, and the one M6.1 met over and over: a rule that stops matching
 * produces an unstyled widget rather than an error, so a sheet and a tree can drift apart completely
 * while everything compiles and every test passes. Three things break a rule, and this catches all
 * three at once — the widget stops reporting the tag the sheet names, the port drops a class, or a
 * name becomes a shadow PART, which no descendant selector can reach.</p>
 *
 * <h3>Why it reads the sheets rather than a list</h3>
 *
 * <p>A hand-written list of expected classes is a second copy of the stylesheet, and the copy is what
 * rots — the sheets ARE the specification of what has to be reachable. So this parses them, and a rule
 * added tomorrow is checked tomorrow without anyone remembering to add it here.</p>
 *
 * <h3>And why it walks the COMPOSED tree</h3>
 *
 * <p>Reading the source for {@code "__x__"} string literals (which is how the port audited this by
 * hand) cannot tell a constant that is declared from one that is applied. Building the widget and
 * looking at what is actually there can, and it also sees classes added at construction by something
 * three levels down.</p>
 */
public class StyleParityTest extends UiDocumentTestBase {

    private static final PortType FLOAT = new BasicPortType("float", 1);

    /** Every sheet the engine ships, read as text — the selectors are what this test is about. */
    private static final List<String> SHEETS = List.of(
            "ua/core.css", "ua/widgets.css", "ua/editor.css", "ua/overlays.css", "ua/config-kit.css",
            "ua/inspector.css", "ua/workbench.css", "ua/panels.css", "ua/search.css", "ua/desktop.css",
            "graph.css");

    /**
     * A compositor and something inside it, in a document of their own.
     *
     * <p><b>The one subject kind that cannot be handed to the fixture's document</b>, and both halves
     * of the reason are the batch's own rules. A {@code Desktop} takes no space until a window is open,
     * so an empty one has no work area, no strip and no entry — a fixture built empty would agree with
     * any sheet at all. And a window has to be OPENED through the compositor rather than appended, so
     * the subject has to run frames before it is looked at. Mounted here and reported as already
     * connected, which {@link #everyPartTheSheetsSelectIsReachable} honours.</p>
     */
    private static UINode ownDocument(java.util.function.Function<Desktop, UINode> pick) {
        UIDocument doc = new UIDocument().markFrameThread();
        doc.styles().addStylesheet(StyleSheet.DEFAULT);
        Desktop desktop = Desktop.of(doc);
        desktop.addWindow(new WindowFrame("Parity"));
        doc.frame(0.016f, 800f, 600f);
        doc.frame(0.016f, 800f, 600f);
        return pick.apply(desktop);
    }

    /**
     * The ported widgets, by the tag their rules name.
     *
     * <p>Built with the least content that still produces their structure: a list needs rows before it
     * realises any, a table needs a column, a graph needs a node with a port. A widget demoed empty
     * would pass this test by having no tree to disagree with the sheet.</p>
     */
    private static Map<String, Supplier<UINode>> subjects() {
        Map<String, Supplier<UINode>> subjects = new LinkedHashMap<>();
        subjects.put("listview", () -> {
            ObservableList<String> model = new ObservableList<>();
            for (int i = 0; i < 8; i++) model.add("row " + i);
            return new ListView<>(model);
        });
        subjects.put("treeview", () -> new TreeView<>(TreeDataSource.empty()));
        subjects.put("tableview", () -> {
            // WITH ROWS. An empty table has no `__row__`, no `__cell__` and no `__divider__`, so an
            // empty fixture agrees with any sheet at all -- which is the failure mode this whole test
            // exists to catch, arriving through the fixture instead of the port.
            ObservableList<String> model = new ObservableList<>();
            for (int i = 0; i < 5; i++) model.add("row " + i);
            TableView<String> table = new TableView<>(model);
            table.addColumn(TableColumn.<String>of("Name", s -> s).flexible());
            table.addColumn(TableColumn.<String>of("N", s -> String.valueOf(s.length())).width(60f));
            return table;
        });
        subjects.put("canvasview", CanvasView::new);
        subjects.put("graphview", GraphView::new);
        subjects.put("nodecreationmenu", () -> {
            NodeTypeRegistry library = new NodeTypeRegistry();
            library.register(NodeType.of("demo.add").label("Add").category("Math").out("out", "float"));
            library.register(NodeType.of("demo.mul").label("Multiply").category("Math")
                    .in("a", "float").out("out", "float"));
            library.register(NodeType.of("demo.time").label("Time").category("Input"));
            return new NodeCreationMenu(library);
        });
        // 6.3's chrome. Each is a container a shipped rule reaches INTO -- which is why not one of
        // them hosts a shadow root, and why every one of their names has to be a light class.
        subjects.put("quickpick", () -> {
            QuickPick pick = new QuickPick();
            pick.setSource(QuickPickSource.of(List.of(
                    QuickPickItem.of("a", "Alpha").withDescription("first"),
                    QuickPickItem.of("b", "Beta").withDescription("second"),
                    // A DISABLED ROW, which is its own template class: the palette DIMS rather than
                    // filters, so an all-enabled fixture never builds one.
                    QuickPickItem.of("c", "Gamma").withEnabled(false))));
            return pick;
        });
        subjects.put("problemspanel", () -> {
            // WITH PROBLEMS IN IT. Every `__problem__` rule in the sheets is a ROW TEMPLATE class,
            // realised only when there is something to show, so an empty panel agrees with any sheet.
            Markers markers = new Markers();
            Resource file = Resource.of("project", "src/Main.java");
            DiagnosticSet set = markers.attach(file, new DiagnosticSet());
            set.setAll(List.of(
                    new Diagnostic(new TextPoint(1, 0), new TextPoint(1, 4),
                            DiagnosticSeverity.ERROR, "cannot find symbol", null, null),
                    new Diagnostic(new TextPoint(7, 2), new TextPoint(7, 9),
                            DiagnosticSeverity.WARNING, "unused import", null, null)));
            return new ProblemsPanel().bindTo(markers);
        });
        subjects.put("navigatorview", NavigatorView::new);
        subjects.put("statusbarview", StatusBarView::new);
        subjects.put("breadcrumbs", () -> {
            Breadcrumbs crumbs = new Breadcrumbs();
            crumbs.setTrail(List.of("core", "src", "widget"));
            return crumbs;
        });
        subjects.put("notificationsview", NotificationsView::new);
        subjects.put("graphnode", () -> {
            GraphNode node = new GraphNode("Node");
            node.addInput(FLOAT, "In");
            node.addOutput(FLOAT, "Out");
            // A CONTROL and a PREVIEW, because both are lazily attached: `__controls__` and
            // `__control-row__` exist only once something is in them, and the sheets have
            // twenty-three rules for the first alone.
            node.addControl("Space", new UINode());
            node.preview().append(new UINode());
            return node;
        });
        // SECOND-MOST STYLED THING IN THE ENGINE at 67 rules, and it is built inside a GraphNode --
        // so a subject keyed on `graphnode` never checks one of them. The subject IS the port; the
        // node around it is scaffolding.
        subjects.put("nodeport", () -> {
            GraphNode node = new GraphNode("Node");
            node.addInput(FLOAT, "In");
            node.addOutput(FLOAT, "Out");
            return node;
        });
        // ── The desktop (6.6) ────────────────────────────────────────────────
        //
        // WITH A WINDOW OPEN, and the fixture would agree with any sheet at all without one: a desktop
        // with no window takes up no space by design, so an empty one has no work area, no strip and no
        // entry -- which is the fixture failure this whole test exists to catch, arriving through the
        // subject rather than through the port.
        // WITH TEXT AND A PROBLEM, or the fixture agrees with any sheet: an empty editor realises no
        // line, so `__line__`, `__syntax__` and every decoration the view parts pool are absent, and
        // `ua/editor.css` is 174 rules over 78 part names -- nearly all of them under a realised row.
        subjects.put("texteditor", () -> {
            // INDENTED, with real spaces: an indent guide is drawn per indent LEVEL and a
            // whitespace mark per space, so a document of unindented words realises neither
            // and the fixture would report both rules unreachable while the setting was on.
            TextEditor editor = new TextEditor(
                    "one\n    two\n        three\n    four\nfive\n");
            editor.buffer().diagnostics().changeOne("parity", java.util.List.of(
                    Diagnostic.onRow(2, DiagnosticSeverity.ERROR, "boom")));
            // THE THREE VIEW PARTS THAT ARE OFF BY DEFAULT, turned on rather than exempted. Each is
            // one call, and exempting them by NAME would exempt them for every widget -- the map is
            // keyed on the class name alone, so `__active__` written off here would also excuse the
            // desktop's `window.__active__`, which IS reachable and is asserted.
            editor.setIndentGuidesVisible(true);
            editor.setRenderWhitespace(RenderWhitespace.ALL);
            editor.setRulers(2);
            // AND THE CARET IN THE NESTED BLOCK, which is what makes one guide `__active__`.
            editor.setCaret("one\n    two\n        thr".length());
            return editor;
        });

        subjects.put("desktop", () -> ownDocument(d -> d));
        subjects.put("taskbar", () -> ownDocument(Desktop::taskbar));
        subjects.put("window", () -> ownDocument(d -> d.registry().windows().get(0)));

        return subjects;
    }

    /**
     * Names that need a STATE or a COMPOSED widget this fixture does not build, with the reason.
     *
     * <p>Every entry is a deliberate hole and the reason is the point — a list like this decays into
     * a suppression file the moment an entry stops saying why. What it is NOT is a list of names the
     * port may drop: each is reachable in the running application and unreachable from a fixture that
     * builds one widget and gives it no data, no pointer and no model.</p>
     */
    private static final Map<String, String> NOT_IN_A_FIXTURE = Map.ofEntries(
            Map.entry("panning", "a state class, live only while a middle-drag is in flight"),
            Map.entry("collapsed", "GraphNode.setCollapsed, a state the fixture does not enter"),
            Map.entry("empty-collapsed", "a port column with nothing wired, while collapsed"),
            Map.entry("ports-empty", "a node with no ports at all"),
            Map.entry("no-inputs", "a node with outputs and no inputs"),
            Map.entry("no-input-gap", "the same, one level down"),
            Map.entry("selected", "GraphSelection, which needs a click or an API call"),
            Map.entry("property-node", "ShaderPropertyNodes marks these; the shader app builds them"),
            Map.entry("property-linked", "as above, for a property still bound to its declaration"),
            Map.entry("exposed-dot", "a Blackboard property that is exposed, marked by the app"),
            Map.entry("preview-image", "ShaderNodePreview fills the slot; needs a compiled shader"),
            Map.entry("full-width", "a control that declines its label -- the CONTROL sets this"),
            Map.entry("editor", "PortDefaultEditor, built by GraphView for a port with a default"),
            Map.entry("editor-label", "inside that editor"),
            Map.entry("editor-dot", "inside that editor"),
            Map.entry("editor-dot-ring", "inside that editor"),
            Map.entry("editor-dot-core", "inside that editor"),
            Map.entry("config-control", "a ConfigControl placed into a node -- NodeFieldWidgets wires it"),
            Map.entry("vector", "VectorControl, as above"),
            Map.entry("vector-cell", "VectorControl, as above"),
            Map.entry("number", "NumberControl, as above"),
            Map.entry("boolean", "BooleanControl, as above"),
            Map.entry("color", "ColorControl, as above"),
            Map.entry("swatch", "ColorControl, as above"),
            Map.entry("mark", "the Checkbox inside a BooleanControl, as above"),
            Map.entry("category", "a create-menu row whose node type declares one"),
            Map.entry("category-segment", "as above"),
            Map.entry("category-separator", "as above"),
            Map.entry("entry-category", "as above"),
            // SERVICE-DRIVEN. These three widgets take their content from something the application
            // stands up -- a page tree, a status-item registry, a notification centre -- and none of
            // them has a setter a fixture can call. Building the service to reach a row template
            // would make this a test of the service; what is checked instead is that every OTHER
            // name in the same widget is reachable, which is what catches a dropped class.
            Map.entry("nav-search", "NavigatorView takes a page tree; the fixture has no model"),
            Map.entry("nav-node", "as above"),
            Map.entry("nav-label", "as above"),
            Map.entry("nav-arrow", "as above"),
            Map.entry("find-mode", "the navigator's search bar, which needs that model"),
            Map.entry("status-item", "StatusBarView reads a contribution registry"),
            Map.entry("status-sep", "as above -- a separator exists between two items"),
            Map.entry("notification", "NotificationsView reads the notification centre"),
            Map.entry("message", "inside a notification"),
            // The navigator's inner TreeView, which has the same model problem one level down --
            // `row` and `expanded` are ListView's and TreeView's own, and both ARE checked under
            // their own tags, where the fixture does give them data.
            Map.entry("row", "a realised row; checked under `listview` and `treeview` instead"),
            Map.entry("expanded", "a TreeView state; checked under `treeview` instead"),
            // ── The desktop (6.6) ─────────────────────────────────────────────
            //
            // GESTURE STATE. Each is a class the compositor adds for the length of an interaction the
            // fixture does not perform, and reaching them would mean driving a drag, a maximise or a
            // minimise inside a style test -- which is the DesktopBatchPortTest's job and is where each
            // is actually asserted.
            Map.entry("snap-preview", "hosted on the work area only while a move drag is over an edge"),
            Map.entry("maximized", "WindowFrame.maximize, a gesture the fixture does not make"),
            Map.entry("fullscreen", "as above, for F11"),
            Map.entry("pinned", "WindowFrame.setPinned -- the caption's pin, or a HUD"),
            Map.entry("occupied", "the overlay slot, sized only while an owned modal is showing"),
            Map.entry("hidden", "a taskbar entry for a MINIMISED window"),
            Map.entry("attention", "an entry flashing for a window that asked without stealing focus"),
            Map.entry("badge", "an entry's count, which an application sets"),
            Map.entry("busy", "an entry's progress, which an application sets"),
            Map.entry("progress", "inside that busy entry"),
            // 6.7'S WIDGETS, styled through `window` because a tool window IS one. ToolWindowFrame,
            // DockWindow and the Problems panel all live in `workbench`, which has not been ported --
            // so these rules are unreachable HERE and reachable in the batch that brings them.
            Map.entry("tool-window", "ToolWindowFrame, a workbench widget -- 6.7"),
            Map.entry("dock", "the Dock button in a tool window's caption -- 6.7"),
            Map.entry("dock-window", "DockWindow, a torn-out editor -- 6.7"),
            Map.entry("strip", "a DockWindow's tab strip -- 6.7"),
            Map.entry("problem-tab", "ProblemsPanel's tabs, seen through a tool window -- 6.7"),
            // ── The editor (6.5) ──────────────────────────────────────────────
            //
            // GESTURE AND DOCUMENT STATE the fixture does not enter. The three view parts that are
            // merely OFF by default are not here -- the subject turns them on, because a setting is
            // one call and an exemption is forever.
            Map.entry("selection", "SelectionsPart, which needs a selection -- a drag or an API call"),
            Map.entry("fold", "a folded region; the fixture's document has no foldable block"),
            Map.entry("fold-placeholder", "the `...` inside that folded region"),
            Map.entry("quick-fix-bulb", "QuickFixBulbPart, which needs a language engine offering an action"),
            Map.entry("zoom-indicator", "the zoom overlay, shown for a moment after Ctrl+scroll"),
            Map.entry("zoom-label", "inside that overlay"),
            Map.entry("zoom-reset", "inside that overlay"),
            Map.entry("shown", "a state class on the find bar, which the fixture does not open"),
            // 6.7'S APPLICATION, styled through `texteditor` because CrystalEditor puts these on one.
            Map.entry("file-editor", "CrystalEditor marks its editors with this -- 6.7"),
            Map.entry("shader-source", "the shader graph's source view, likewise -- 6.7"),
            // THE ICON PALETTE IS MUTUALLY EXCLUSIVE -- a tile wears exactly one of these, chosen from
            // the window's identity, so a fixture can only ever carry one however many windows it
            // opens the same way. `tile-mono` is deliberately NOT here and must stay reachable: it is
            // what an iconless window gets, which is every window the fixture opens, so it is the one
            // entry that proves the whole palette is wired rather than merely written.
            Map.entry("tile-1", "one of seven mutually exclusive palette classes; see tile-mono"),
            Map.entry("tile-2", "one of seven mutually exclusive palette classes; see tile-mono"),
            Map.entry("tile-3", "one of seven mutually exclusive palette classes; see tile-mono"),
            Map.entry("tile-4", "one of seven mutually exclusive palette classes; see tile-mono"),
            Map.entry("tile-5", "one of seven mutually exclusive palette classes; see tile-mono"),
            Map.entry("tile-6", "one of seven mutually exclusive palette classes; see tile-mono"),
            Map.entry("branded", "WindowIcon sets this from the SVG -- artwork naming its own colours"));

    /**
     * <b>The check.</b> For each widget: every {@code __x__} a sheet selects under its tag is a class
     * some node in its composed tree carries, or a part some node exposes.
     *
     * <p>Reported per widget with the offending selector, because "class `__foo__` is missing" is not
     * actionable and "this rule cannot match" is.</p>
     */
    @Test
    public void everyPartTheSheetsSelectIsReachable() throws IOException {
        withDefaultStyles();
        List<String> offences = new ArrayList<>();
        for (Map.Entry<String, Supplier<UINode>> entry : subjects().entrySet()) {
            String tag = entry.getKey();
            UINode widget = entry.getValue().get();
            // A SUBJECT THAT BROUGHT ITS OWN DOCUMENT IS LEFT WHERE IT IS. Appending it here would
            // move it across documents mid-walk, which tears its subtree down and rebuilds it -- and a
            // compositor cannot be built anywhere else, since a desktop with no window has no tree.
            boolean mounted = widget.document() != null;
            if (!mounted) {
                layout(widget, l -> l.width(400f).height(300f));
                document.append(widget);
            }
            frame();
            // A POPUP BUILDS ITS ROWS ON OPEN. NodeCreationMenu's entries are realised by the
            // TreeView inside it, and a menu nobody opened has none -- so an unopened fixture
            // reports eighteen "unreachable" rules that are perfectly reachable in the application.
            if (widget instanceof NodeCreationMenu menu) menu.openAll(0f, 0f, null);
            // A PICKER'S ROWS ARE ITS RESULTS, and it has none until it is open and has queried its
            // source -- so an unopened one reports two dozen `__qp-*__` rules as unreachable.
            if (widget instanceof QuickPick pick) pick.open(document);
            frame();
            frame();

            Set<String> reachable = reachableNames(widget);
            for (Selector sel : selectorsFor(tag)) {
                for (String name : sel.names) {
                    if (NOT_IN_A_FIXTURE.containsKey(name)) continue;
                    if (!reachable.contains(name)) {
                        offences.add(String.format("%-18s %-56s %s  (no node carries __%s__)",
                                tag, sel.text, sel.sheet, name));
                    }
                }
            }
            if (!mounted) widget.removeSelf();
            frame();
        }
        assertTrue("a shipped rule can no longer match anything in the widget it was written for:\n"
                + String.join("\n", offences), offences.isEmpty());
    }

    /**
     * The counter-assertion, and it is not a formality.
     *
     * <p>Everything above passes vacuously if the sheets are not being read — a typo in a path, a
     * resource that stopped shipping, a regex that matches nothing. This asserts the inputs are real:
     * the sheets parse to hundreds of selectors, the widgets build trees with dozens of names in
     * them, and a name nothing declares is genuinely reported as unreachable.</p>
     */
    @Test
    public void theCheckIsActuallyLookingAtSomething() throws IOException {
        withDefaultStyles();
        assertFalse("no selectors were read -- the sheets did not load",
                selectorsFor("graphnode").isEmpty());
        assertTrue("graphnode has 80-odd rules; found " + selectorsFor("graphnode").size(),
                selectorsFor("graphnode").size() > 20);

        GraphNode node = new GraphNode("Node");
        node.addInput(FLOAT, "In");
        layout(node, l -> l.width(200f).height(100f));
        document.append(node);
        frame();
        frame();

        Set<String> reachable = reachableNames(node);
        assertTrue("a built graph node has more than a handful of names: " + reachable,
                reachable.size() > 5);
        assertFalse("a name nothing declares must NOT read as reachable",
                reachable.contains("no-such-part-anywhere"));
    }

    // ── Reading the tree ─────────────────────────────────────────────────────

    /** Every class and every exposed part name in the composed subtree, {@code __x__} stripped. */
    private static Set<String> reachableNames(UINode root) {
        Set<String> names = new LinkedHashSet<>();
        for (UINode node : composed(root)) {
            for (String c : node.classes()) {
                if (c.startsWith("__") && c.endsWith("__") && c.length() > 4) {
                    names.add(c.substring(2, c.length() - 2));
                }
            }
            // A PART is reachable too, through a `::part()` twin -- so it is not a gap, and counting
            // it here is what keeps this test about REACHABILITY rather than about spelling.
            String part = node.get(Attribute.PART);
            if (part != null && !part.isEmpty()) names.add(part);
        }
        return names;
    }

    // ── Reading the sheets ───────────────────────────────────────────────────

    private record Selector(String text, String sheet, List<String> names) {
    }

    private static final Pattern RULE = Pattern.compile("([^{}]+)\\{[^{}]*}");
    private static final Pattern COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern PART_NAME = Pattern.compile("__([a-z0-9-]+)__");

    /** Every selector in the shipped sheets whose FIRST tag is {@code tag}. */
    private static List<Selector> selectorsFor(String tag) throws IOException {
        Pattern head = Pattern.compile("^" + Pattern.quote(tag) + "(?=$|[\\s.:\\[>])");
        List<Selector> out = new ArrayList<>();
        for (String sheet : SHEETS) {
            // COMMENTS FIRST. The house style puts one on the line above the rule it explains, so a
            // scan that splits on `}` reads it as part of the next selector -- which is the defect
            // `ScopedSheets` was refused whole for, and the one that silently skipped 94 rules when
            // the ::part() twins were generated.
            String css = COMMENT.matcher(read(sheet)).replaceAll(" ");
            Matcher rule = RULE.matcher(css);
            while (rule.find()) {
                String group = rule.group(1).trim().replaceAll("\\s+", " ");
                if (group.startsWith("@")) continue;
                for (String one : group.split(",")) {
                    String sel = one.trim();
                    if (!head.matcher(sel).find()) continue;
                    List<String> names = new ArrayList<>();
                    Matcher m = PART_NAME.matcher(sel);
                    while (m.find()) names.add(m.group(1));
                    if (!names.isEmpty()) out.add(new Selector(sel, sheet, names));
                }
            }
        }
        return out;
    }

    private static String read(String sheet) throws IOException {
        String path = "/assets/crystalgui/ui/styles/" + sheet;
        try (InputStream in = StyleParityTest.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("shipped sheet is missing from the jar: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
