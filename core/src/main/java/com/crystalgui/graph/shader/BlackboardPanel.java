package com.crystalgui.graph.shader;

import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.graph.GraphDocument;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.graph.GraphIds;
import com.crystalgui.graph.GraphProperty;
import com.crystalgui.graph.PropertyEdits;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.Menu;
import com.crystalgui.ui.elements.ScrollerView;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.AnchoredPlacement;
import com.crystalgui.ui.elements.canvas.CanvasOverlayMove;
import com.crystalgui.ui.input.FocusPolicy;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Blackboard — every property the graph declares, as a floating panel over the canvas.
 *
 * <p>Reference: {@code docs/research/unity-blackboard/}, chiefly {@code 10-blackboard-panel.png} for the
 * frame, {@code 02-blackboard-categories-1.png} for the rows and {@code 11-add-property-menu.png} for
 * the type list.</p>
 *
 * <h3>A floating panel, not a dock pane — and the second one</h3>
 * <p>Same seam the Main Preview uses: {@code GraphView.addOverlay} puts it in the <em>viewport</em>
 * rather than on the transformed plane, so it stays put while the graph pans underneath. The move and
 * clamp behaviour is {@link CanvasOverlayMove}, extracted from the Main Preview when this arrived —
 * three separate bugs are recorded in it, and re-deriving them here was the alternative.</p>
 *
 * <p>Unity guarantees the board <em>cannot be dragged off the graph and lost</em>, which the clamp is.</p>
 *
 * <h3>The title is the document's name, not "Blackboard"</h3>
 * <p>Straight from the reference: the panel is named after the thing it belongs to, with the asset path
 * as a dim subtitle. It reads as part of the document rather than as a tool inspecting it.</p>
 *
 * <h3>Selection is the panel's, and the inspector borrows it</h3>
 * <p>A property is <b>not in the graph</b>, so teaching {@code GraphSelection} about one would be
 * widening a graph selection to hold something that is not a graph element. Instead this owns
 * {@link #onPropertySelected} and the inspector listens; each source clears the other. Two sources, one
 * subject, and neither has to know what the other can hold.</p>
 */
public class BlackboardPanel extends UIElement {

    public static final String PANEL_CLASS = "__blackboard__";
    public static final String HEAD_CLASS = "__head__";
    public static final String TITLE_CLASS = "__title__";
    public static final String SUBTITLE_CLASS = "__subtitle__";
    /** The column holding the title over the subtitle. @see BlackboardPanel */
    public static final String TITLES_CLASS = "__titles__";
    public static final String ADD_CLASS = "__add__";
    public static final String BODY_CLASS = "__body__";

    /** What the {@code +} menu's first entry says. A category is created like a property, not around one. */
    public static final String CATEGORY_LABEL = "Category";

    /** The row context menu, in Unity's order and grouping. */
    public static final String RENAME_LABEL = "Rename";
    public static final String DELETE_LABEL = "Delete";
    public static final String DUPLICATE_LABEL = "Duplicate";

    /**
     * This panel's commands.
     *
     * <h3>Scoped to the panel, which is what keeps them off the graph's toes</h3>
     * <p>The Blackboard is an overlay <b>inside</b> the {@code GraphView}, and {@code GraphCommands}
     * already binds {@code Delete} there — so a board without its own binding would delete the selected
     * <em>nodes</em> while the user was looking at a selected property. Binding on this panel puts these
     * further in, and {@code KeymapResolver} walks focus outward taking the <b>innermost</b> match: the
     * same mechanism that lets a text field's {@code Mod+A} beat the window's.</p>
     *
     * <p>Falling through is the other half, and why enablement is not optional. A disabled command does
     * not fire and the resolver carries on outward, so {@code Delete} with no property selected reaches
     * the graph's own delete — which is what the user meant, since nothing here was selected to mean
     * anything else.</p>
     */
    public static final String DELETE_COMMAND = "blackboard.deleteProperty";
    public static final String DUPLICATE_COMMAND = "blackboard.duplicateProperty";
    public static final String RENAME_COMMAND = "blackboard.renameProperty";

    /** Pixels per wheel notch. One pill's height, so a notch moves the list by one row. */
    private static final float WHEEL_STEP_PX = 14f;

    /** Shown in place of the list while nothing is declared. */
    public static final String EMPTY_MESSAGE = "No properties yet";

    /** Records which menu entry created a property, so a pill can say {@code Color} and not {@code vec4}. */
    public static final String KIND_OPTION = "kind";

    /**
     * The type menu, in Unity's order.
     *
     * <p>Ten of Unity's sixteen — see 6.3.14 for why each of the other six is out. The label is what the
     * user picks; the value is the <b>wire type</b>, which is what a port carries and what
     * {@code CgShaderType} can parse.</p>
     *
     * <h3>Color's wire type is {@code vec4}, and that is Unity's model too</h3>
     * <p>There is no {@code color} in {@code CgShaderType} — it exists only as a token in a
     * {@code Properties} block, where it earns a material inspector a colour picker. A Color property's
     * <em>data</em> type is a four-component vector, exactly as Unity documents it, and "Color" is the
     * property <b>kind</b> rather than the type. So the kind is recorded in {@link #KIND_OPTION} and the
     * pill reads it back — without which {@code labelFor("vec4")} could not tell the two menu entries
     * apart, and every Color property would show as {@code Vector 4}.</p>
     *
     * <p>Declaring it as {@code color} in the emitted block is a follow-up: {@code CgMasterNode.property}
     * takes a {@code CgShaderType}, which cannot spell a token that is not one.</p>
     */
    public static final Map<String, String> TYPES = buildTypes();

    private static Map<String, String> buildTypes() {
        Map<String, String> types = new LinkedHashMap<>();
        types.put("Float", "float");
        types.put("Vector 2", "vec2");
        types.put("Vector 3", "vec3");
        types.put("Vector 4", "vec4");
        types.put("Color", "vec4");
        types.put("Boolean", "boolean");
        types.put("Texture 2D", "sampler2D");
        types.put("Texture 2D Array", "sampler2DArray");
        types.put("Texture 3D", "sampler3D");
        types.put("Cubemap", "samplerCube");
        return java.util.Collections.unmodifiableMap(types);
    }

    /** A sensible starting value per type, so a new property is born valid. */
    public static String defaultValueFor(String typeId) {
        switch (typeId) {
            case "float": return "0";
            case "vec2": return "(0,0)";
            case "vec3": return "(0,0,0,0)";     // declared vec4 — see CgShaderType.propertyDeclarationType
            case "vec4": return "(0,0,0,0)";

            case "boolean": return "false";
            default: return "\"white\"";          // every sampler kind takes a quoted fallback name
        }
    }

    /** {@code (property id)} for whatever is selected here, or null when the selection was cleared. */
    public final Signal.Value<String> onPropertySelected = new Signal.Value<>();

    private final GraphDocument document;

    @Nullable
    private final UndoStack undo;

    private final UIElement head = new UIElement();
    private final UIElement titles = new UIElement();
    private final UIText title = new UIText("");
    private final UIText subtitle = new UIText("Shader Graphs");
    private final UIElement add = new UIElement();
    /**
     * The list. A PLAIN box, not a {@link ScrollerView}, and that is forced rather than chosen.
     *
     * <p>{@code ScrollerView} writes its bars' {@code display} from Java at IMPORTANT origin and says so
     * in its own javadoc — <em>"not expressible in CSS"</em> — so no stylesheet rule can hide them. A
     * rule that tried was a guaranteed no-op, which is exactly what shipped once.</p>
     *
     * <p>The cost is that a bare {@code overflow} box scrolls by API and ignores the wheel, so the wheel
     * is wired by hand below. That is five lines against a widget whose entire purpose is the bars we do
     * not want.</p>
     */
    private final UIElement body = new UIElement();

    private final List<PropertyPill> pills = new ArrayList<>();

    private final CanvasOverlayMove move;

    /** The placeholder, held so a rebuild can take it away again. @see #refresh */
    @Nullable
    private UIText emptyMessage;

    @Nullable
    private String selectedId;

    @Nullable
    private Menu typeMenu;

    @Nullable
    private Menu rowMenu;

    /** A property whose rename should be re-opened after the next rebuild. @see #refresh */
    @Nullable
    private String renaming;

    private boolean commandsInstalled;

    /** @see #refresh */
    private boolean refreshing;
    private boolean refreshPending;

    public BlackboardPanel(GraphDocument document, String documentName, @Nullable UndoStack undo) {
        this.document = document;
        this.undo = undo;

        addClass(PANEL_CLASS);
        // Focusable so Delete can be a command scoped to this panel rather than a global key that would
        // fire while the user was deleting a NODE. Commands resolve outward from the focused element.
        setFocusPolicy(FocusPolicy.CLICK);
        markAsInternal();

        // Head is a CONTAINER with the text as children -- a UIText draws from its own box top, so making
        // the text be the strip leaves nothing for align-items to centre. Same structure, same reason, as
        // the Main Preview's header.
        head.addClass(HEAD_CLASS);
        title.addClass(TITLE_CLASS);
        subtitle.addClass(SUBTITLE_CLASS);
        add.addClass(ADD_CLASS);
        // The two labels are scenery: the strip around them is the move handle, so a press must reach
        // the head. The + is not -- it has its own job and takes its own press.
        title.setHitTest(false);
        subtitle.setHitTest(false);
        // Title OVER subtitle, so the head is a row whose first item is a COLUMN. Unity stacks them and
        // the two lines read as one identity -- side by side they read as two unrelated labels, which is
        // what the first version looked like.
        titles.addClass(TITLES_CLASS);
        titles.setHitTest(false);
        titles.addChild(title);
        titles.addChild(subtitle);
        head.addChild(titles);
        head.addChild(add);

        body.addClass(BODY_CLASS);
        addInternalChild(head);
        addInternalChild(body);

        setDocumentName(documentName);
        move = CanvasOverlayMove.install(this, head, this::resizeContainingBlock);
        add.onMouseDown.attachListener((element, event) -> {
            openTypeMenu();
            event.stopPropagation();
        }, false, true);

        // A bare overflow box has no wheel handling of its own -- see the `body` field. A POSITIVE notch
        // means the wheel rolled DOWN, which ScrollerView is the only other statement of; taking the sign
        // at face value is how CanvasView shipped zooming the wrong way.
        body.onMouseScroll.attachListener((element, event) -> {
            float before = body.getScrollTop();
            body.setScrollTop(before + event.getScroll() * WHEEL_STEP_PX);
            if (body.getScrollTop() != before) event.stopPropagation();
        }, false, true);

        // A press anywhere on the panel focuses it, not only a press on a pill -- otherwise clicking the
        // empty area below the list silently drops the board out of key scope.
        onMouseDown.attachListener((element, event) -> focusSelf(), false, true);

        document.onChanged.connect(this::refresh);
        refresh();
    }

    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }

    /** The graph's file name, shown as the panel's title. @see BlackboardPanel */
    public BlackboardPanel setDocumentName(String name) {
        title.setText(name == null || name.isEmpty() ? "Shader Graph" : name);
        return this;
    }

    /** The dim second line — the asset path in Unity's reference. */
    public BlackboardPanel setSubtitle(String text) {
        subtitle.setText(text == null ? "" : text);
        return this;
    }

    /**
     * Takes keyboard focus, so this panel's key bindings are in scope.
     *
     * <p>Commands resolve outward from the focused element, and {@code KeymapResolver} takes the
     * innermost match — which is what keeps the board's {@code Delete} from reaching the graph's while a
     * property is selected. Nothing is in scope until something here holds focus.</p>
     */
    private void focusSelf() {
        UIWindow window = getAttachedWindow();
        if (window != null) window.getInputHandler().requestPointerFocus(this);
    }

    /** Re-clamps after the canvas resized. Call per frame; cheap and idempotent. */
    public void reclamp() {
        move.reclampIfPlaced(resizeOriginLeft(), resizeOriginTop());
    }

    // ── The list ────────────────────────────────────────────────────────────

    /**
     * Rebuilds the pills from the document.
     *
     * <p>A full rebuild rather than a diff, because the list is short and the alternative is a second
     * model of what is on screen that can disagree with the first. The selection is re-applied by id, so
     * a rebuild does not lose it — which matters, since every edit to the selected property triggers one.</p>
     */
    public void refresh() {
        // RE-ENTRANCY GUARD. Rebuilding detaches pills, detaching a focused rename field fires a blur,
        // and a blur can report a rename -- which rewrites the document, which calls this again from
        // inside itself. The inner pass would clear the list the outer one is still walking, which
        // surfaced as a ConcurrentModificationException from something as ordinary as adding a property.
        //
        // Coalescing rather than ignoring: the inner request is remembered and served once the outer
        // pass finishes, so a change that arrives mid-rebuild is not silently dropped.
        if (refreshing) {
            refreshPending = true;
            return;
        }
        refreshing = true;
        try {
            rebuild();
        } finally {
            refreshing = false;
        }
        if (refreshPending) {
            refreshPending = false;
            refresh();
        }
    }

    private void rebuild() {
        // EVERYTHING here is added and removed through the INTERNAL pair, and that is load-bearing twice
        // over.
        //
        // First: a PropertyPill marks itself internal, and clearAllChildren() deliberately skips internal
        // children -- so a bulk clear removed nothing and every refresh stacked another copy of the list.
        // ConfiguratorPanel.clearRows records the same bug from the inspector.
        //
        // Second, and the one that survived that fix: GraphView.addOverlay marks this panel internal
        // AFTER it is constructed, and markAsInternal() RECURSES -- so it stamps whatever refresh() had
        // already put in the body. That element then becomes unremovable through removeChild, which
        // refuses internal children by contract. The symptom was one stubborn placeholder with a fresh
        // one stacked under it on the next refresh, visible only in the assembled editor: a panel built
        // in isolation is never stamped, so it looked perfect in every isolated test.
        //
        // Using the internal pair for both directions makes this immune to being stamped from outside,
        // which is the only thing that can be, since a widget cannot stop its host promoting it.
        // ENDED BEFORE DETACHED, and that order is the whole of a nasty crash.
        //
        // UIElement.onRemoved drops the window's input references -- which BLURS a focused field -- and
        // then iterates its own children. A blur handler that removes the editor mutates that list in
        // between, so the forEach on the next line walks a modified list and throws
        // ConcurrentModificationException. Pressing F2 and hitting Enter was enough to hit it.
        //
        // Taking the editor away first means the blur fired during teardown finds nothing to do: both
        // applyRename and endRename are no-ops once the field is cleared. The copy is belt and braces --
        // a rename reported from here would re-enter this method.
        for (PropertyPill pill : new ArrayList<>(pills)) {
            pill.endRename();
            body.removeInternalChild(pill);
        }
        pills.clear();
        if (emptyMessage != null) {
            body.removeInternalChild(emptyMessage);
            emptyMessage = null;
        }

        for (GraphProperty property : document.properties()) {
            PropertyPill pill = new PropertyPill(property);
            pill.capsule().onMouseDown.attachListener((element, event) -> {
                // FOCUS THE PANEL, explicitly. Click-focus targets the exact element hit, never the
                // nearest focusable ancestor -- so pressing a pill focuses the pill, which has no focus
                // policy, and nothing takes focus at all. Every command here resolves outward from the
                // focused element, so without this the whole key set is inert while the board looks
                // alive. GraphNode calls requestFocus itself for exactly this reason.
                //
                // requestPointerFocus, never requestFocus: the latter rings :focus-visible, and a focus
                // ring appearing because you clicked is the noise that pseudo-class exists to remove.
                focusSelf();
                select(property.id());
                // A right-press selects AND opens the menu, which is what every list does: acting on
                // whatever was under the pointer is the whole point of a context menu, and a menu that
                // required a left-click first would make every operation two gestures.
                if (event.getButtonId() == CgMouseCodes.RIGHT_BUTTON) {
                    openRowMenu(event.getPosition().x(), event.getPosition().y());
                } else if (event.getDetail() >= 2) {
                    // Double-click renames, which is what Unity's Blackboard documents.
                    pill.beginRename();
                }
                event.stopPropagation();
            }, false, true);
            pill.onRenamed.connect(newName -> renameProperty(property.id(), newName));
            // Focus comes back to the BOARD when a rename ends. Detaching the editor leaves focus null,
            // and commands resolve outward from the focused element -- so without this, Delete, F2 and
            // Mod+D were all dead after an Enter until the user clicked the row again, which reads as
            // the selection not being real.
            pill.onRenameEnded.connect(this::focusSelf);
            pills.add(pill);
            body.addChild(pill);
        }
        if (pills.isEmpty()) {
            emptyMessage = new UIText(EMPTY_MESSAGE);
            emptyMessage.addClass("__empty__");
            emptyMessage.setHitTest(false);
            // addInternalChild/removeInternalChild, never the public pair -- see the note on refresh().
            body.addInternalChild(emptyMessage);
        }
        if (renaming != null) {
            PropertyPill pill = pillFor(renaming);
            // A rename survives the rebuild its own document change caused. Without this, committing a
            // name destroys the editor mid-commit and typing the FIRST character of a live-updating
            // field would end the rename.
            if (pill != null) pill.beginRename();
            renaming = null;
        }
        // The selected property may have been deleted by whatever triggered this.
        if (selectedId != null && document.property(selectedId) == null) select(null);
        else applySelectionClasses();
    }

    /** The pills, in list order — for a host that wants to drag one. */
    public List<PropertyPill> pills() {
        return List.copyOf(pills);
    }

    @Nullable
    public PropertyPill pillFor(String propertyId) {
        for (PropertyPill pill : pills) {
            if (pill.propertyId().equals(propertyId)) return pill;
        }
        return null;
    }

    // ── Selection ───────────────────────────────────────────────────────────

    @Nullable
    public String selectedPropertyId() {
        return selectedId;
    }

    @Nullable
    public GraphProperty selectedProperty() {
        return selectedId == null ? null : document.property(selectedId);
    }

    /** Selects a property, or clears with null. Emits either way, so a listener can show or hide. */
    public void select(@Nullable String propertyId) {
        if (java.util.Objects.equals(selectedId, propertyId)) return;
        selectedId = propertyId;
        applySelectionClasses();
        onPropertySelected.emit(propertyId);
    }

    private void applySelectionClasses() {
        for (PropertyPill pill : pills) pill.setSelected(pill.propertyId().equals(selectedId));
    }

    // ── Adding and removing ─────────────────────────────────────────────────

    /**
     * The {@code +} menu: {@code Category}, a separator, then the types.
     *
     * <p>Built once and reopened, like the Main Preview's mesh menu — a menu rebuilt per press cannot
     * hold state and costs a tree rebuild on a gesture that should feel instant.</p>
     */
    public void openTypeMenu() {
        UIWindow window = getAttachedWindow();
        if (window == null) return;
        if (typeMenu == null) {
            typeMenu = new Menu();
            // Category first and alone above a separator, exactly as Unity has it -- a category is
            // CREATED here rather than being a container you fill afterwards.
            typeMenu.addItem(CATEGORY_LABEL);
            typeMenu.addSeparator();
            for (String label : TYPES.keySet()) typeMenu.addItem(label);
            // One listener dispatching on the label, which is the idiom the Main Preview's mesh menu
            // already uses -- a closure per item captures state that a rebuilt menu would invalidate.
            typeMenu.onItemActivated.connect(item -> addProperty(item.getText()));
            // In the tree for the same reason as the row menu below, even though showFor could attach it
            // through its anchor -- relying on that would make the two menus behave differently for no
            // reason a reader could see.
            addInternalChild(typeMenu);
        }
        typeMenu.showFor(add, add);
    }

    /**
     * Declares a new property from a <b>menu label</b>, selects it, and returns it.
     *
     * <p>The label rather than the wire type, and that is not incidental: {@code Color} and
     * {@code Vector 4} are both {@code vec4}, so a type id cannot say which entry was picked and a
     * property added as Color would have been recorded — and shown — as a Vector 4. The menu has the
     * answer; taking anything else throws it away.</p>
     *
     * <p>Selected on creation because the next thing anyone does is name it, and the form that renames it
     * is driven by the selection. An unknown label — {@code Category}, which has nothing to create yet —
     * returns null. See 6.3.14 on categories being a field rather than an entity.</p>
     */
    @Nullable
    public GraphProperty addProperty(@Nullable String menuLabel) {
        String typeId = menuLabel == null ? null : TYPES.get(menuLabel);
        if (typeId == null) return null;
        GraphProperty property = GraphProperty.of(document.uniquePropertyName(menuLabel), typeId,
                defaultValueFor(typeId))
                .withOption(KIND_OPTION, menuLabel);

        PropertyEdits.Add edit = PropertyEdits.Add.of(document, property);
        if (undo != null) undo.execute(edit); else edit.apply();
        select(property.id());

        // Straight into a rename, which is what Unity does: the generated name is a placeholder, so
        // "add, type, Enter" is one gesture rather than an add followed by a hunt for the rename. The
        // pill only exists after the document change rebuilt the list, so this reads it back rather than
        // holding the one it made.
        PropertyPill pill = pillFor(property.id());
        if (pill != null) pill.beginRename();
        return property;
    }

    /** Removes whatever is selected. Returns whether anything was. */
    public boolean removeSelected() {
        if (selectedId == null) return false;
        PropertyEdits.Remove edit = PropertyEdits.Remove.of(document, selectedId);
        if (edit == null) return false;
        if (undo != null) undo.execute(edit); else edit.apply();
        select(null);
        return true;
    }

    // ── Commands ────────────────────────────────────────────────────────────

    /**
     * Registers this panel's commands and binds their defaults on it. Idempotent; safe to call per frame.
     *
     * <p>Explicit, like every command set in this engine — nothing injects its own defaults, because a
     * registry that quietly acquired commands nobody registered surprises anything that enumerates it,
     * and the palette is exactly such a thing.</p>
     *
     * @return true once a window was found and the keys are live
     */
    public boolean installCommands() {
        UIWindow window = getAttachedWindow();
        if (window == null) return false;
        if (commandsInstalled) return true;
        commandsInstalled = true;

        CommandRegistry registry = window.getCommands();
        if (!registry.contains(DELETE_COMMAND)) {
            registry.register(Command.of(DELETE_COMMAND, "Delete Property")
                    .run(context -> withBoard(context, BlackboardPanel::removeSelected))
                    .enabledWhen(BlackboardPanel::isActionable));
            registry.register(Command.of(DUPLICATE_COMMAND, "Duplicate Property")
                    .run(context -> withBoard(context, BlackboardPanel::duplicateSelected))
                    .enabledWhen(BlackboardPanel::isActionable));
            registry.register(Command.of(RENAME_COMMAND, "Rename Property")
                    .run(context -> withBoard(context, BlackboardPanel::renameSelected))
                    .enabledWhen(BlackboardPanel::isActionable));
        }

        // Delete AND Backspace, for the reason GraphCommands records: the platform convention differs
        // between a full keyboard and a laptop one. F2 is the rename key wherever a list has one.
        keymap().bind("Delete", DELETE_COMMAND);
        keymap().bind("Backspace", DELETE_COMMAND);
        keymap().bind("Mod+D", DUPLICATE_COMMAND);
        keymap().bind("F2", RENAME_COMMAND);
        return true;
    }

    /** Opens a rename on whatever is selected. The command's body, and the row menu's. */
    public void renameSelected() {
        PropertyPill pill = selectedId == null ? null : pillFor(selectedId);
        if (pill != null) pill.beginRename();
    }

    /**
     * The nearest enclosing board, from whatever holds focus.
     *
     * <p>Resolved from the context rather than captured, so two boards in one window each act on
     * themselves — the commands are registered once by id and shared, and a captured {@code this} would
     * make the second board drive the first.</p>
     */
    @Nullable
    private static BlackboardPanel boardFor(CommandContext context) {
        for (UIElement element = context.source(); element != null; element = element.getParent()) {
            if (element instanceof BlackboardPanel board) return board;
        }
        return null;
    }

    /**
     * Whether a command applies at all.
     *
     * <p>A rename in flight counts as <b>not</b> applicable, and that is the important half: while a
     * text field is up, {@code Delete} and {@code Backspace} belong to the text being typed. Without it
     * the first backspace of a correction would delete the property being renamed — recoverable through
     * undo, but the field vanishes at the same moment and nothing explains what happened.</p>
     */
    private static boolean isActionable(CommandContext context) {
        BlackboardPanel board = boardFor(context);
        if (board == null || board.selectedId == null) return false;
        PropertyPill pill = board.pillFor(board.selectedId);
        return pill == null || !pill.isRenaming();
    }

    private static void withBoard(CommandContext context, java.util.function.Consumer<BlackboardPanel> action) {
        BlackboardPanel board = boardFor(context);
        if (board != null) action.accept(board);
    }

    // ── The row menu ────────────────────────────────────────────────────────

    /**
     * Rename / Delete / Duplicate for the selected property.
     *
     * <p>Built once and reopened, like the {@code +} menu — and like it, one listener dispatching on the
     * label rather than a closure per item, since a closure would capture the property the menu was
     * FIRST opened on and quietly act on it forever after.</p>
     */
    public void openRowMenu(float screenX, float screenY) {
        UIWindow window = getAttachedWindow();
        if (window == null || selectedId == null) return;
        if (rowMenu == null) {
            rowMenu = new Menu();
            rowMenu.addItem(RENAME_LABEL);
            rowMenu.addItem(DELETE_LABEL);
            rowMenu.addSeparator();
            // Duplicate is grouped apart because it CREATES rather than acts on what is there -- Unity
            // separates it for the same reason, and the accelerator is shown because a menu is where a
            // shortcut is learned.
            // The accelerator is READ FROM THE KEYMAP rather than typed here. A hard-coded label is a
            // promise the menu cannot keep: rebind the key and the menu goes on advertising the old one,
            // which is worse than showing nothing. Null when unbound, and setAccelerator takes that.
            var chord = com.crystalgui.ui.input.keymap.Keymap.acceleratorFor(this, DUPLICATE_COMMAND);
            rowMenu.addItem(DUPLICATE_LABEL).setAccelerator(chord == null ? null : chord.toString());
            rowMenu.onItemActivated.connect(item -> applyRowMenu(item.getText()));
            // Must be IN the tree to be promoted to the top layer -- a Menu is a Popover, and an
            // unparented one has nothing to promote from. showFor can attach itself through its anchor;
            // showAt has no anchor to find a host from, so it throws. Internal, because this is a
            // composite. The Main Preview's mesh menu records the same requirement.
            addInternalChild(rowMenu);
        }
        // ROOT space, not physical pixels: the menu is promoted to the top layer, whose containing block
        // is the root, so a raw pointer position lands wherever that number falls in root coordinates --
        // which put an earlier menu in the corner of the whole window.
        var at = AnchoredPlacement.pointerToRoot(window, screenX, screenY);
        rowMenu.showAt(at.x(), at.y(), null);
    }

    private void applyRowMenu(String label) {
        if (RENAME_LABEL.equals(label)) {
            renameSelected();
            return;
        }
        if (DELETE_LABEL.equals(label)) {
            removeSelected();
            return;
        }
        if (DUPLICATE_LABEL.equals(label)) duplicateSelected();
    }

    /**
     * Renames a property, undoably.
     *
     * <p>The REFERENCE is deliberately left alone — see {@link GraphProperty#withName}. Once a reference
     * exists, a material points at it, so rewriting it on every rename would break the binding silently.</p>
     */
    public void renameProperty(String propertyId, String newName) {
        GraphProperty current = document.property(propertyId);
        if (current == null || newName == null || newName.isBlank()) return;
        if (newName.equals(current.name())) return;

        PropertyEdits.Change edit = PropertyEdits.Change.of(document, current.withName(newName));
        if (edit == null || !edit.changesAnything()) return;
        if (undo != null) undo.execute(edit); else edit.apply();
    }

    /**
     * Copies the selected property and inserts the copy directly after it.
     *
     * <p>A NEW id, because an id is identity — sharing one would make the copy and the original the same
     * property to every node referencing either. The name gets the same uniquing a fresh property does,
     * and the reference is derived from that new name rather than copied, since two uniforms cannot share
     * a name in the generated shader.</p>
     */
    @Nullable
    public GraphProperty duplicateSelected() {
        GraphProperty source = selectedProperty();
        if (source == null) return null;

        String name = document.uniquePropertyName(source.name());
        GraphProperty copy = new GraphProperty(GraphIds.generate(), name, GraphProperty.referenceFor(name),
                source.typeId(), source.defaultValue(), source.exposed(), source.category(),
                source.options());

        int after = document.indexOfProperty(source.id()) + 1;
        PropertyEdits.Add edit = new PropertyEdits.Add(document, copy, after);
        if (undo != null) undo.execute(edit); else edit.apply();
        select(copy.id());
        return copy;
    }

    /**
     * What a pill's right-hand column says.
     *
     * <p>The recorded {@link #KIND_OPTION} when there is one, because two menu entries can share a wire
     * type — {@code Color} and {@code Vector 4} are both {@code vec4}, so the type alone cannot say which
     * was chosen. Falls back to the first entry with that type for a property written by something that
     * never set the option.</p>
     */
    public static String displayTypeOf(GraphProperty property) {
        String kind = property.option(KIND_OPTION);
        return kind != null ? kind : labelFor(property.typeId());
    }

    /** The first menu label using {@code typeId} — ambiguous by construction; prefer {@link #displayTypeOf}. */
    public static String labelFor(String typeId) {
        for (Map.Entry<String, String> entry : TYPES.entrySet()) {
            if (entry.getValue().equals(typeId)) return entry.getKey();
        }
        return typeId;
    }
}
