package com.crystalgui.graph.shader;

import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.core.undo.CompositeEdit;
import com.crystalgui.core.undo.Edit;
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
import com.crystalgui.ui.event.DragEvent;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.style.StyleGroup;

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

    /** The drop indicator, and the class that reveals it. @see #dropLine */
    public static final String DROP_LINE_CLASS = "__drop-line__";
    public static final String DROP_ACTIVE_CLASS = "__active__";

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

    /** The one menu entry whose wire type does not name it — see {@link #TYPES}. */
    public static final String KIND_COLOR = "Color";

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

    /**
     * Every row the list shows, headers included, in visual order — what a drop is measured against.
     *
     * <p>A separate list from {@link #pills} because a slot between two rows is not the same thing as a
     * slot between two properties once headers are in the way: dropping under a heading files the
     * property <em>into</em> that category, and a collapsed group contributes a row while contributing no
     * pills at all.</p>
     */
    private final List<Row> rows = new ArrayList<>();

    private final List<CategoryHeader> headers = new ArrayList<>();

    /**
     * Folded categories. <b>View state</b>, deliberately — folding is not a change to the graph, the same
     * boundary the editor's own folding draws and the reason {@code Ctrl+Z} does not unfold.
     */
    private final java.util.Set<String> collapsed = new java.util.LinkedHashSet<>();

    /**
     * Categories that exist but hold nothing yet, kept here rather than in the document.
     *
     * <p>A category is a <b>field on a property</b> (6.3.14: "a field, not a tree"), so an <em>empty</em>
     * one has nowhere in the document to live — there is no property carrying its name. Unity's {@code +}
     * menu nonetheless creates a category before it has members, so the heading is held here until
     * something joins it and becomes real.</p>
     *
     * <p><b>The cost, stated rather than hidden:</b> an empty category does not survive a reload. That
     * follows from the model decision and is the honest behaviour for it — the alternative is a second
     * entity in the document whose only job is to name a group with no members, which is exactly the
     * tree the plan chose against.</p>
     */
    private final List<String> pendingCategories = new ArrayList<>();

    /** One row of the list — a pill for a property, or a category heading. @see #rows */
    private record Row(UIElement element, @Nullable String propertyId, String category) {
    }

    /** Where a drop lands: a position in the <b>document</b>, and the category it joins. */
    private record Slot(int index, String category) {
    }

    /**
     * The line showing where a dragged pill would land. Hidden until a drag is over the list.
     *
     * <p>Absolutely positioned, so it does not take a row's worth of space. An in-flow indicator was the
     * obvious first shape and is wrong for a reason worth recording: inserting it <b>moves every pill
     * below it down by its own height</b>, which moves the boundaries the drop index is computed from —
     * so a pointer resting near a boundary alternates between two indices and the line flickers between
     * two gaps. An overlay cannot move what it is measuring.</p>
     */
    private final UIElement dropLine = new UIElement();

    /** Where {@link #dropLine} currently says the drop lands, or -1 while it is hidden. */
    private int dropIndex = -1;

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

    @Nullable
    private Menu categoryMenu;

    /** Which heading {@link #categoryMenu} was opened for. @see #openCategoryMenu */
    @Nullable
    private String menuCategory;

    /** A property whose rename should be re-opened after the next rebuild. @see #refresh */
    @Nullable
    private String renaming;

    private boolean commandsInstalled;

    /** @see #refresh */
    private boolean refreshing;
    private boolean refreshPending;

    /** What the list currently shows. @see #refresh */
    private String shownSignature = "never built";

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
        //
        // EXCEPT on something that takes typing. A press on the rename field bubbles up to here, and
        // focusing the panel from it moved focus OFF the field -- which blurs it, which commits and
        // closes the rename. Clicking into the box you are typing in shut it, which reads as the field
        // refusing to be clicked rather than as a focus fight two elements apart.
        onMouseDown.attachListener((element, event) -> {
            UIElement target = event.getTarget();
            if (target != null && target.consumesTextInput()) return;
            focusSelf();
        }, false, true);

        // Built once and parked, not per drag: it is hidden by the stylesheet rather than by being
        // absent, so there is nothing to attach at the moment a drag starts.
        dropLine.addClass(DROP_LINE_CLASS);
        // Nothing in an indicator may take the pointer -- it sits directly under the cursor for the whole
        // hover, so a hittable one becomes the drop target it is drawn to describe.
        dropLine.setHitTest(false);
        body.addInternalChild(dropLine);
        installReorderDrop();

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
        // NOTHING TO DO when the property list is unchanged, and this is not merely an optimisation.
        //
        // document.onChanged fires for ANY change -- every node added, moved or wired -- and this panel
        // cares about none of them. Rebuilding anyway destroyed the pills, and a pill being dragged onto
        // the canvas IS the drag source: creating the node fired onChanged, the source left the tree
        // mid-drop, UIDragController cancelled and cleared its listener, and endDrag then dereferenced
        // it. A NullPointerException from a successful drop.
        //
        // Comparing what the list would show against what it shows is cheap and, unlike a narrower
        // signal, cannot go stale: anything that changes a pill changes the signature.
        String signature = listSignature();
        if (signature.equals(shownSignature)) {
            applySelectionClasses();
            return;
        }

        refreshing = true;
        try {
            rebuild();
            shownSignature = signature;
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
        for (CategoryHeader header : new ArrayList<>(headers)) {
            header.endRename();
            body.removeInternalChild(header);
        }
        pills.clear();
        headers.clear();
        rows.clear();
        if (emptyMessage != null) {
            body.removeInternalChild(emptyMessage);
            emptyMessage = null;
        }

        // A HEADING WHENEVER THE CATEGORY CHANGES as the list is scanned -- there is no category entity
        // to enumerate, only a field, so the grouping is read off the order rather than imposed on it.
        //
        // A list whose categories are not contiguous therefore shows the same heading twice, and that is
        // deliberate: every gesture here keeps a group together, so the only way to reach that state is a
        // document written elsewhere -- and showing it plainly beats silently reordering someone's list
        // to fit an assumption this panel made.
        String heading = null;
        for (GraphProperty property : document.properties()) {
            if (!property.category().equals(heading)) {
                heading = property.category();
                if (!heading.isEmpty()) addHeader(heading);
            }
            // A folded group contributes its heading and none of its rows. The properties are untouched:
            // folding is view state, so nothing about the document changes when a group closes.
            if (collapsed.contains(property.category())) continue;
            addPill(property);
        }
        // Categories with nothing in them yet, which the document cannot hold. @see #pendingCategories
        for (String pending : pendingCategories) {
            if (!liveCategories().contains(pending)) addHeader(pending);
        }

        if (pills.isEmpty() && headers.isEmpty()) {
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
            else {
                CategoryHeader header = headerFor(renaming);
                if (header != null) header.beginRename();
            }
            renaming = null;
        }
        // The selected property may have been deleted by whatever triggered this.
        if (selectedId != null && document.property(selectedId) == null) select(null);
        else applySelectionClasses();
    }

    /** Every category the document actually holds, which is every distinct non-empty field value. */
    private java.util.Set<String> liveCategories() {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (GraphProperty property : document.properties()) {
            if (property.isCategorised()) out.add(property.category());
        }
        return out;
    }

    private void addHeader(String category) {
        CategoryHeader header = new CategoryHeader(category, collapsed.contains(category));
        header.onPressed.connect(event -> {
            focusSelf();
            if (event.getButtonId() == CgMouseCodes.RIGHT_BUTTON) {
                openCategoryMenu(header.category(), event.getPosition().x(), event.getPosition().y());
            } else if (event.getDetail() >= 2) {
                header.beginRename();
            } else {
                setCategoryCollapsed(header.category(), !header.isCollapsed());
            }
        });
        header.onRenamed.connect(newName -> renameCategory(header.category(), newName));
        header.onRenameEnded.connect(this::focusSelf);
        headers.add(header);
        rows.add(new Row(header, null, category));
        body.addChild(header);
    }

    private void addPill(GraphProperty property) {
        PropertyPill pill = new PropertyPill(property);
            // Through the pill's own signal, NOT a second listener on the same group: the pill has to
            // stopPropagation to keep the press off the panel, and that stops the rest of the group --
            // so a listener attached here afterwards would silently never run. See PropertyPill.onPressed.
            pill.onPressed.connect(event -> {
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
            });
        pill.onRenamed.connect(newName -> renameProperty(property.id(), newName));
        // Focus comes back to the BOARD when a rename ends. Detaching the editor leaves focus null,
        // and commands resolve outward from the focused element -- so without this, Delete, F2 and
        // Mod+D were all dead after an Enter until the user clicked the row again, which reads as
        // the selection not being real.
        pill.onRenameEnded.connect(this::focusSelf);
        pills.add(pill);
        rows.add(new Row(pill, property.id(), property.category()));
        body.addChild(pill);
    }

    /**
     * Everything the list draws, as one string.
     *
     * <p>Every field a pill renders is in here, so a change that would look different forces a rebuild
     * and one that would not does not. Selection is deliberately absent — it is applied to the existing
     * pills rather than rebuilt into new ones.</p>
     */
    private String listSignature() {
        StringBuilder out = new StringBuilder();
        for (GraphProperty property : document.properties()) {
            out.append(property.id()).append('')
                    .append(property.name()).append('')
                    .append(property.typeId()).append('')
                    .append(property.exposed()).append('')
                    // Category and fold state BOTH change what is drawn -- one moves a row under a
                    // different heading, the other takes it off screen entirely. A signature blind to
                    // either would skip the rebuild and leave the list showing the previous grouping.
                    .append(property.category()).append('')
                    .append(collapsed.contains(property.category())).append('')
                    .append(BlackboardPanel.displayTypeOf(property)).append('');
        }
        // An empty category is a row too, and lives nowhere in the document -- without this, creating
        // one changes nothing the signature can see and the heading never appears.
        for (String pending : pendingCategories) {
            out.append("cat").append('').append(pending).append('')
                    .append(collapsed.contains(pending)).append('');
        }
        return out.toString();
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

    // ── Reorder ─────────────────────────────────────────────────────────────

    /**
     * Lets a pill be dropped back on the list to move it, rather than out on the canvas to make a node.
     *
     * <p>The <b>same drag</b> serves both, which is Unity's gesture and the reason this is a drop target
     * rather than a second kind of press: what the user is doing is not decided until they let go, so
     * arming one behaviour at the start would force them to know in advance.</p>
     *
     * <h3>The drop MUST be stopped from bubbling</h3>
     * <p>This panel is an overlay <em>inside</em> the {@code GraphView}, and {@code ShaderGraphEditor}
     * accepts the very same payload on the graph to create a node. {@code DragEvent.Drop} bubbles — so
     * without {@code stopPropagation} a reorder would also drop a node on the canvas underneath, and the
     * board would sprout a node every time a property was moved.</p>
     *
     * <p>The same applies to {@code Over}: leaving it to bubble lets the graph {@code preventDefault} as
     * well, which is harmless on its own but means the canvas is advertising a drop the pointer is not
     * over. Stopping both keeps exactly one target live at a time.</p>
     */
    private void installReorderDrop() {
        body.events.getGroup(DragEvent.Over.class).attachListener((element, event) -> {
            if (!(event.getPayload() instanceof PropertyPill.Payload)) return;
            // REJECTION IS THE DEFAULT: accepting is this call, re-read every frame and never latched.
            event.preventDefault();
            showDropLine(dropIndexAt(event.getPosition().x(), event.getPosition().y()));
            event.stopPropagation();
        }, false, true);

        // Leave does not bubble -- it is chain-dispatched -- so this fires on the body itself as the
        // pointer goes out to the canvas, which is exactly when the line should stop claiming the drop.
        //
        // AND it is the whole of the Escape path too, which is why there is no Cancel listener here.
        // DragEvent.Cancel goes only to the drag SOURCE, so a board watching for it would never hear an
        // abort -- but UIDragController.fireCancelLeave walks a Leave from the stale target all the way
        // to the root first, and this body is on that chain. Escape mid-hover therefore arrives here as
        // an ordinary Leave. Without that the line would simply stay, pointing at a drop that was called
        // off, until the next drag happened to redraw it.
        body.events.getGroup(DragEvent.Leave.class).attachListener(
                (element, event) -> hideDropLine(), false, true);

        body.events.getGroup(DragEvent.Drop.class).attachListener((element, event) -> {
            hideDropLine();
            if (!(event.getPayload() instanceof PropertyPill.Payload dropped)) return;
            event.stopPropagation();
            Slot slot = slotFor(dropIndexAt(event.getPosition().x(), event.getPosition().y()));
            dropProperty(dropped.propertyId(), slot.index(), slot.category());
        }, false, true);
    }

    /**
     * Which slot a drop at this point lands in — {@code 0} above the first pill, {@code pills.size()}
     * below the last.
     *
     * <p>Measured by asking each pill to convert the point into <b>its own</b> space, rather than by
     * comparing against layout coordinates. The list scrolls, and a scrolled child's painted position is
     * its layout position minus the parent's scroll — so the arithmetic version is correct only until
     * someone scrolls, which is precisely when a long list needs reordering. The transform chain already
     * knows, and asking it cannot drift.</p>
     */
    private int dropIndexAt(float screenX, float screenY) {
        for (int i = 0; i < rows.size(); i++) {
            UIElement row = rows.get(i).element();
            // Above this row's midpoint means "before it". Rows entirely above the pointer report a
            // large positive y and fail this; rows entirely below report a negative one and pass, so
            // the FIRST pass is the slot.
            if (row.screenToLocal(screenX, screenY).y
                    < row.getRuntimeCache().getHeight() * 0.5f) return i;
        }
        return rows.size();
    }

    /**
     * Where a drop in slot {@code rowIndex} actually lands — a document position <b>and</b> a category.
     *
     * <p><b>The row above the slot decides both</b>, which is the rule that makes one gesture do two
     * jobs. Under a pill: straight after it, in that pill's category. Under a heading: the top of that
     * group, whether or not the group is folded — so a collapsed category is still a drop target, which
     * it has to be, or filing something into one would mean unfolding it first.</p>
     *
     * <p>Above everything: position zero, uncategorised — the ungrouped region the board opens with.</p>
     */
    private Slot slotFor(int rowIndex) {
        if (rowIndex <= 0 || rows.isEmpty()) return new Slot(0, "");
        Row above = rows.get(Math.min(rowIndex, rows.size()) - 1);
        if (above.propertyId() != null) {
            return new Slot(document.indexOfProperty(above.propertyId()) + 1, above.category());
        }
        return new Slot(firstIndexOfCategory(above.category()), above.category());
    }

    /**
     * Where a category's run begins in the document, or the end of the list for one with no members.
     *
     * <p>An empty category has no position of its own — nothing carries its name — so a property filed
     * into one lands at the end, which is where its heading is drawn. @see #pendingCategories</p>
     */
    private int firstIndexOfCategory(String category) {
        List<GraphProperty> properties = document.properties();
        for (int i = 0; i < properties.size(); i++) {
            if (properties.get(i).category().equals(category)) return i;
        }
        return properties.size();
    }

    /** Draws the indicator in slot {@code index}. Idempotent — safe to call every frame of a hover. */
    private void showDropLine(int index) {
        if (index == dropIndex) return;
        dropIndex = index;
        dropLine.addClass(DROP_ACTIVE_CLASS);
        // Half the list's 4px gap above the pill it lands before, so the line sits IN the gap rather
        // than on a capsule's edge, where it would read as that capsule being outlined.
        //
        // The DIFFERENCE of two cached origins, which is how MainPreviewPanel already reads a child's
        // offset within its container: the cached value is absolute, so subtracting the body's own gives
        // a body-relative y -- the space an absolutely positioned sibling's `top` is measured in. Scroll
        // is deliberately absent from both, and stays correct because the drop line is scrolled by the
        // body exactly as the pills are.
        float origin = body.getRuntimeCache().getY();
        float top;
        if (rows.isEmpty()) {
            top = 0f;
        } else if (index < rows.size()) {
            top = rows.get(index).element().getRuntimeCache().getY() - origin - 2f;
        } else {
            UIElement last = rows.get(rows.size() - 1).element();
            top = last.getRuntimeCache().getY() - origin + last.getRuntimeCache().getHeight() + 2f;
        }
        StyleGroup.inlinePipeline(dropLine.getStyle().getLayoutGroup(), l -> l.top(top));
    }

    private void hideDropLine() {
        dropIndex = -1;
        dropLine.removeClass(DROP_ACTIVE_CLASS);
    }

    /**
     * Moves a property into slot {@code insertAt}, counted against the list <b>as it stands now</b>.
     *
     * <p>{@code GraphDocument.moveProperty} takes the index the property should end up at <em>after</em>
     * being lifted out, which is one less than the slot the user pointed at whenever they pointed below
     * where it already was. Converting here rather than at the model keeps the off-by-one in the one
     * place that knows a pointer was involved — the model's index means what it says.</p>
     */
    public boolean moveProperty(String propertyId, int insertAt) {
        GraphProperty held = document.property(propertyId);
        return held != null && dropProperty(propertyId, insertAt, held.category());
    }

    /**
     * Moves a property to {@code insertAt} and files it under {@code category} — <b>one undo step</b>.
     *
     * <p>One step because it is one gesture. A drag that crosses a heading both moves and re-files, and
     * splitting that into two entries would make the first {@code Ctrl+Z} leave the property in a place
     * the user never put it: the new group at the old position, or the reverse.</p>
     *
     * <p>Both edits are built <b>before</b> either applies. {@code Move.of} captures the property's
     * current index at construction, and re-filing does not reorder anything, so that index is still
     * right when the move runs second — and a {@code CompositeEdit} undoes in reverse, which puts the
     * position back before the category it was read against.</p>
     */
    public boolean dropProperty(String propertyId, int insertAt, String category) {
        GraphProperty held = document.property(propertyId);
        if (held == null) return false;
        int from = document.indexOfProperty(propertyId);
        // A slot below where it already sits is one index less once it is lifted out. @see #moveProperty
        int to = insertAt > from ? insertAt - 1 : insertAt;

        PropertyEdits.Change refile = held.category().equals(category)
                ? null : PropertyEdits.Change.of(document, held.withCategory(category));
        PropertyEdits.Move move = PropertyEdits.Move.of(document, propertyId, to);
        // Both null when the drop changed nothing -- back in its own slot, in its own group, which is the
        // common miss and must not push an undo step for an operation that did nothing.
        if (refile == null && move == null) return false;

        Edit edit;
        if (refile == null) edit = move;
        else if (move == null) edit = refile;
        else edit = CompositeEdit.of("move property " + held.name(), refile, move);
        if (undo != null) undo.execute(edit); else edit.apply();
        return true;
    }

    // ── Categories ──────────────────────────────────────────────────────────

    /** Every category with a heading on screen, in the order they are drawn. */
    public List<String> categories() {
        List<String> out = new ArrayList<>();
        for (CategoryHeader header : headers) out.add(header.category());
        return out;
    }

    @Nullable
    public CategoryHeader headerFor(String category) {
        for (CategoryHeader header : headers) {
            if (header.category().equals(category)) return header;
        }
        return null;
    }

    public boolean isCategoryCollapsed(String category) {
        return collapsed.contains(category);
    }

    /**
     * Folds or unfolds a category.
     *
     * <p><b>Not undoable</b>, and that is the same boundary the whole engine draws: folding is how you
     * are looking at the document, not a change to it. {@code Ctrl+Z} unfolding instead of undoing is
     * exactly the failure the document/view rule exists to prevent, and it is where VS Code and IntelliJ
     * both put it.</p>
     */
    public void setCategoryCollapsed(String category, boolean value) {
        boolean changed = value ? collapsed.add(category) : collapsed.remove(category);
        if (changed) refresh();
    }

    /**
     * Declares a new, empty category and opens a rename on it.
     *
     * <p>Unity's {@code +} menu creates a category before it has members, so this does too — held in
     * {@link #pendingCategories} until a property joins it, because a category is a field and an empty
     * one has nothing in the document to be written on.</p>
     */
    public String addCategory() {
        String name = uniqueCategoryName("New Category");
        pendingCategories.add(name);
        refresh();
        CategoryHeader header = headerFor(name);
        // Straight into a rename, the same gesture adding a property uses: the generated name is a
        // placeholder, so "add, type, Enter" is one motion rather than an add followed by a hunt.
        if (header != null) header.beginRename();
        return name;
    }

    private String uniqueCategoryName(String desired) {
        java.util.Set<String> taken = liveCategories();
        taken.addAll(pendingCategories);
        if (!taken.contains(desired)) return desired;
        for (int n = 1; ; n++) {
            String candidate = desired + " " + n;
            if (!taken.contains(candidate)) return candidate;
        }
    }

    /**
     * Renames a category — which means rewriting the field on <b>every</b> property carrying it.
     *
     * <p>One undo step for the lot, because the user performed one rename. Without the grouping, undoing
     * would walk the properties back one at a time through a half-renamed category that never existed as
     * a state anyone chose.</p>
     */
    public boolean renameCategory(String from, String to) {
        String trimmed = to == null ? "" : to.trim();
        if (trimmed.isEmpty() || trimmed.equals(from)) return false;
        // Merging into an existing category is allowed and is what the name says happened -- refusing it
        // would leave the header showing a name the document does not have.
        List<Edit> edits = new ArrayList<>();
        for (GraphProperty property : document.properties()) {
            if (!property.category().equals(from)) continue;
            PropertyEdits.Change change = PropertyEdits.Change.of(document,
                    property.withCategory(trimmed));
            if (change != null) edits.add(change);
        }
        // The pending entry moves with it, so renaming a category that is still empty keeps its heading
        // rather than dropping it and drawing a second one under the old name.
        int pending = pendingCategories.indexOf(from);
        if (pending >= 0) {
            pendingCategories.set(pending, trimmed);
            if (edits.isEmpty()) refresh();
        }
        // Fold state follows the name too, or a folded category springs open when renamed.
        if (collapsed.remove(from)) collapsed.add(trimmed);
        if (edits.isEmpty()) return pending >= 0;

        Edit edit = edits.size() == 1 ? edits.get(0)
                : CompositeEdit.of("rename category " + from, edits.toArray(new Edit[0]));
        if (undo != null) undo.execute(edit); else edit.apply();
        return true;
    }

    /**
     * Removes a category, leaving its properties uncategorised.
     *
     * <p><b>It cannot take them with it.</b> A category owns nothing — it is a string each property
     * carries — so deleting one clears a field and the rows move up into the ungrouped region. That is
     * the whole payoff of "a field, not a tree": there is no "deleting a category deletes its contents"
     * rule to get wrong, because there is no containment to begin with.</p>
     */
    public boolean removeCategory(String category) {
        List<Edit> edits = new ArrayList<>();
        for (GraphProperty property : document.properties()) {
            if (!property.category().equals(category)) continue;
            PropertyEdits.Change change = PropertyEdits.Change.of(document, property.withCategory(""));
            if (change != null) edits.add(change);
        }
        boolean wasPending = pendingCategories.remove(category);
        collapsed.remove(category);
        if (edits.isEmpty()) {
            if (wasPending) refresh();
            return wasPending;
        }
        Edit edit = edits.size() == 1 ? edits.get(0)
                : CompositeEdit.of("remove category " + category, edits.toArray(new Edit[0]));
        if (undo != null) undo.execute(edit); else edit.apply();
        return true;
    }

    /** The heading's context menu: rename or remove. Built once and reopened, like every menu here. */
    private void openCategoryMenu(String category, float screenX, float screenY) {
        UIWindow window = getAttachedWindow();
        if (window == null) return;
        if (categoryMenu == null) {
            categoryMenu = new Menu();
            categoryMenu.addItem(RENAME_LABEL);
            categoryMenu.addItem(DELETE_LABEL);
            categoryMenu.onItemActivated.connect(item -> {
                String target = menuCategory;
                if (target == null) return;
                if (RENAME_LABEL.equals(item.getText())) {
                    CategoryHeader header = headerFor(target);
                    if (header != null) header.beginRename();
                } else if (DELETE_LABEL.equals(item.getText())) {
                    removeCategory(target);
                }
            });
            addInternalChild(categoryMenu);
        }
        // WHICH heading was pressed, held rather than captured: the menu is built once and reused, so a
        // closure over the first category it opened for would act on that one forever.
        menuCategory = category;
        // ROOT space, not physical pixels -- the same requirement openRowMenu records.
        var at = AnchoredPlacement.pointerToRoot(window, screenX, screenY);
        categoryMenu.showAt(at.x(), at.y(), null);
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
            typeMenu.onItemActivated.connect(item -> {
                if (CATEGORY_LABEL.equals(item.getText())) addCategory();
                else addProperty(item.getText());
            });
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
