package com.crystalgui.graph.shader;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.graph.GraphDocument;
import com.crystalgui.graph.GraphProperty;
import com.crystalgui.graph.PropertyEdits;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.Menu;
import com.crystalgui.ui.elements.ScrollerView;
import com.crystalgui.ui.elements.UIText;
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
    public static final String ADD_CLASS = "__add__";
    public static final String BODY_CLASS = "__body__";

    /** What the {@code +} menu's first entry says. A category is created like a property, not around one. */
    public static final String CATEGORY_LABEL = "Category";

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
    private final UIText title = new UIText("");
    private final UIText subtitle = new UIText("Shader Graphs");
    private final UIElement add = new UIElement();
    private final ScrollerView body = new ScrollerView();

    private final List<PropertyPill> pills = new ArrayList<>();

    private final CanvasOverlayMove move;

    /** The placeholder, held so a rebuild can take it away again. @see #refresh */
    @Nullable
    private UIText emptyMessage;

    @Nullable
    private String selectedId;

    @Nullable
    private Menu typeMenu;

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
        head.addChild(title);
        head.addChild(subtitle);
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
        // Removed BY REFERENCE, never with clearAllChildren(): a PropertyPill calls markAsInternal() on
        // itself -- it is an assembled widget whose parts nothing should walk into -- and
        // clearAllChildren deliberately skips internal children. So the clear removed nothing and every
        // refresh stacked another copy of the list. Exactly the bug ConfiguratorPanel.clearRows already
        // records, hit a second time by a second panel, which is why this one removes what it added.
        for (PropertyPill pill : pills) body.removeInternalChild(pill);
        pills.clear();
        if (emptyMessage != null) {
            body.removeChild(emptyMessage);
            emptyMessage = null;
        }

        for (GraphProperty property : document.properties()) {
            PropertyPill pill = new PropertyPill(property);
            pill.onMouseDown.attachListener((element, event) -> {
                select(property.id());
                event.stopPropagation();
            }, false, true);
            pills.add(pill);
            body.addChild(pill);
        }
        if (pills.isEmpty()) {
            emptyMessage = new UIText(EMPTY_MESSAGE);
            emptyMessage.addClass("__empty__");
            emptyMessage.setHitTest(false);
            body.addChild(emptyMessage);
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
