package com.crystalgui.app.shadergraph.blackboard;

import com.crystalgui.ui.service.Drag;
import com.crystalgui.graph.GraphProperty;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.input.UIDragController;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.ui.event.FocusEvent;
import com.crystalgui.ui.event.KeyboardEvent;
import com.crystalgui.widget.text.UIText;

import javax.annotation.Nullable;

/**
 * One row of the Blackboard: a capsule holding the property's name, with its type dim and right-aligned.
 *
 * <p>Reference: {@code docs/research/unity-blackboard/02-blackboard-categories-1.png}.</p>
 *
 * <h3>A chip, not a table row</h3>
 * <p>The capsule is sized to its text and sits inside a wider row, which is what makes the board read as
 * a list of <em>things</em> rather than a table of values — and it is what gives the drag a grabbable
 * shape, since dragging a pill onto the canvas is how a property becomes a node.</p>
 *
 * <h3>The dot means exposed, and it is the only place that state is visible</h3>
 * <p>Unity puts a small filled dot inside the capsule for an exposed property and omits it otherwise. It
 * is worth copying exactly: a property that is declared but hidden from the material inspector is
 * otherwise indistinguishable from one that is not, and the difference is invisible in the graph.</p>
 */
public class PropertyPill extends UINode {

    public static final String PILL_CLASS = "__property-pill__";
    public static final String CAPSULE_CLASS = "__capsule__";
    public static final String DOT_CLASS = "__dot__";
    public static final String NAME_CLASS = "__name__";
    public static final String TYPE_CLASS = "__type__";

    /** On the row while it is the Blackboard's selection. */
    public static final String SELECTED_CLASS = "__selected__";

    /** On the row while its name is being typed, so the capsule can step out of the way. */
    public static final String RENAMING_CLASS = "__renaming__";
    public static final String EDITOR_CLASS = "__rename__";

    /** On the floating copy that follows the cursor during a drag. */
    public static final String GHOST_CLASS = "__ghost__";

    private final String propertyId;

    private final UINode capsule = new UINode();
    private final UINode dot = new UINode();
    private final UIText name;
    private final UIText type;

    /** The floating copy shown while dragging. One per pill, re-registered per drag. @see #buildGhost */
    private final UINode ghost = new UINode();
    private final UIText ghostName = new UIText("");

    private boolean selected;

    /** The rename gesture, shared with {@link CategoryHeader} — see {@link InlineRename} for why. */
    private final InlineRename rename;

    /** Fires with the committed name. The pill does not write the document; its host does. */
    public final Signal.Value<String> onRenamed = new Signal.Value<>();

    /**
     * Fires once a rename is over, however it ended — committed, abandoned or torn down.
     *
     * <p>Exists so the host can take focus back. Detaching the editor drops the window's focus to
     * <b>nothing</b>, and every command resolves outward from the focused element — so after an Enter the
     * board looked selected and its whole key set was dead until the user clicked it again. The pill
     * cannot fix that itself: which element should hold focus is the host's business, not a row's.</p>
     */
    public final Signal.Action onRenameEnded = new Signal.Action();

    /**
     * A press on the capsule, forwarded so the host can select, focus or open a menu.
     *
     * <p><b>One listener owns the capsule's press</b>, and that is not tidiness. The host's own listener
     * called {@code stopPropagation()} to keep the press off the panel behind it, and
     * {@code EventListenerGroup} stops emitting to the REST OF THE GROUP once that is set — so a second
     * listener attached afterwards, for the drag, never ran at all. The gesture simply did nothing, with
     * nothing to see anywhere. So the press arrives here once and is shared through this signal.</p>
     */
    public final Signal.Value<MouseEvent.Down> onPressed = new Signal.Value<>();

    public PropertyPill(GraphProperty property) {
        this.propertyId = property.id();
        addClass(PILL_CLASS);
        capsule.addClass(CAPSULE_CLASS);
        dot.addClass(DOT_CLASS);
        name = new UIText(property.name());
        name.addClass(NAME_CLASS);
        type = new UIText(BlackboardPanel.displayTypeOf(property));
        type.addClass(TYPE_CLASS);
        // SELF-SIZING BY DECLARATION, not by the auto-detect, and this label is exactly the case that
        // method exists for: it is the only column here that GROWS, so on the first layout pass it is
        // handed whatever slack the row has and concludes it does not size itself -- correct-looking, and
        // permanent, since the decision is taken once by design.
        //
        // The cost only appears once the row is sized to its content (the Blackboard body is
        // `align-items: start`, so a long name can scroll rather than spill): a label contributing zero
        // width leaves the row exactly as wide as the capsule, and `Vector 2` renders as `Ve…` jammed
        // against the panel edge, outside the scrollable width instead of inside it.

        // THE CAPSULE IS THE TARGET, not the row.
        //
        // The row spans the panel's full width, so making it hittable meant clicking anywhere on that
        // line -- including the empty gap out to the right border -- selected the property. The chip is
        // what looks clickable, so the chip is what should be.
        //
        // setHitTest(false) applies to the whole SUBTREE, like CSS pointer-events: none, so this cannot
        // be done by switching the row off and the capsule on. The row stays hittable and simply carries
        // no listener: a press on it falls through to the panel, which focuses itself, which is the right
        // outcome for a click on empty space.
        dot.setHitTest(false);
        name.setHitTest(false);
        type.setHitTest(false);

        if (property.exposed()) capsule.append(dot);
        capsule.append(name);
        append(capsule);
        append(type);
        buildGhost();
        installPress();

        rename = new InlineRename(this, EDITOR_CLASS, RENAMING_CLASS, property.name(), name::setText,
                this::invalidateStyleMatch);
        // Forwarded rather than exposed: onRenamed and onRenameEnded are this row's published surface and
        // its host is already connected to them. Handing out the helper's signals instead would make the
        // pill's API depend on how the gesture happens to be implemented.
        rename.onCommitted.connect(onRenamed::emit);
        rename.onEnded.connect(onRenameEnded::emit);
    }

    /**
     * What a drag from a pill carries.
     *
     * <p>A dedicated type rather than the bare id string: a payload is matched by {@code instanceof} at
     * the drop target, and a raw {@code String} would be accepted by anything else that happened to drag
     * text. One record makes "is this a property?" unambiguous.</p>
     */
    public record Payload(String propertyId) {
    }

    public String propertyId() {
        return propertyId;
    }

    /** The capsule, which is what a drag ghost should picture. */
    public UINode capsule() {
        return capsule;
    }

    public String displayName() {
        return name.getText();
    }

    public boolean isSelected() {
        return selected;
    }

    /**
     * Swaps the capsule for a text field holding the current name, selected, ready to be typed over.
     *
     * <p>Unity opens one on a fresh property and on a double-click, and pre-selects the text so the first
     * keystroke replaces it — which is what makes "add, type, Enter" a single gesture rather than an add
     * followed by a hunt for the rename.</p>
     *
     * <p>The pill does <b>not</b> write the document. It reports the committed name and lets its host
     * decide, which keeps one writer for a property exactly as {@code NodeFieldBinder} does for a field —
     * the alternative is a widget that can edit a document it was never given.</p>
     */
    /**
     * Makes this pill draggable onto the canvas, where it becomes a node reading the property.
     *
     * <p>The gesture is Unity's: there is no "create property node" in the create menu, because the node
     * <em>is</em> a reference to a board entry and dragging it out is how you say which one.</p>
     *
     * <p>Started with the default threshold, so a press that does not travel is still a click — the pill
     * has to stay selectable, and a drag that armed immediately would make selecting one impossible.</p>
     */
    private void installPress() {
        capsule.onMouseDown.attachListener((element, event) -> {
            // Reported FIRST, so the host has selected and focused before a drag can begin -- dragging
            // an unselected pill should still act on that pill.
            onPressed.emit(event);
            beginDrag(event);
            // Kept off the panel behind us, which would otherwise clear the selection we just made. Safe
            // to set here because this is the only listener on this group -- see onPressed.
            event.stopPropagation();
        }, false, true);
    }

    /**
     * Starts a drag carrying this property, so it can be dropped on the canvas as a node.
     *
     * <p>The gesture is Unity's: there is no "create property node" in the create menu, because the node
     * <em>is</em> a reference to a board entry and dragging it out is how you say which one.</p>
     *
     * <p>Uses the default threshold, so a press that does not travel is still a click — the pill has to
     * stay selectable, and a drag that armed immediately would make selecting one impossible.</p>
     */
    private void beginDrag(MouseEvent.Down event) {
        if (event.getButtonId() != CgMouseCodes.LEFT_BUTTON) return;
        UIDocument window = document();
        // Never while renaming: the field is a text control, and a drag from inside it is a text
        // selection rather than a move.
        if (window == null || rename.isRunning()) return;

        float rawX = event.getPosition().x(), rawY = event.getPosition().y();
        // PER DRAG, which is what the controller expects -- it drops the ghost when the drag ends, so a
        // ghost registered once would appear for the first drag and never again.
        // The name may have changed since the ghost was built.
        ghostName.setText(name.getText());
        Drag.startWithPayload(capsule, rawX, rawY, new Payload(propertyId),
                new Drag.Listener() {
                    @Override
                    public void onDragUpdate(float mouseX, float mouseY, float startX, float startY,
                                             float deltaX, float deltaY) {
                        // Nothing to move: the pill stays put, the ghost follows the cursor and the DROP
                        // does the work. The controller still needs a listener -- that is the gesture.
                    }
                });
    }

    /**
     * Builds the floating copy that follows the cursor. Called once, at construction.
     *
     * <p><b>It has to be IN THE TREE.</b> {@code UIDragController.showGhost} promotes it to the top
     * layer, and promotion needs a window to promote from — so it checks {@code document()} and
     * silently does nothing for an unparented element. A ghost built fresh per drag and handed straight
     * to {@code setGhost} was therefore never shown at all, with no error to explain it. Same lesson the
     * row menu already learned: a Popover must be in the tree before it can be promoted.</p>
     *
     * <p>So one ghost, parented here and hidden by the stylesheet until a drag promotes it —
     * {@code showGhost} writes {@code display} at IMPORTANT origin, which is what lets a resting
     * {@code display: none} in CSS be overridden for the duration.</p>
     */
    private void buildGhost() {
        ghost.addClass(PILL_CLASS);
        ghost.addClass(GHOST_CLASS);

        UINode body = new UINode();
        body.addClass(CAPSULE_CLASS);
        UINode ghostDot = new UINode();
        ghostDot.addClass(DOT_CLASS);
        ghostName.addClass(NAME_CLASS);

        // Nothing in a ghost may take the pointer: it sits under the cursor for the whole drag, so a
        // hittable ghost would be the drop target for every frame of it.
        ghost.setHitTest(false);
        body.append(ghostDot);
        body.append(ghostName);
        ghost.append(body);
        append(ghost);
    }

    public void beginRename() {
        rename.begin();
    }

    /** Whether a rename is in flight — a caller must not rebuild the row under one. */
    public boolean isRenaming() {
        return rename.isRunning();
    }

    /** Takes the editor away and puts the capsule back. Safe to call when no rename is running. */
    public void endRename() {
        rename.end();
    }

    public PropertyPill setSelected(boolean value) {
        if (selected == value) return this;
        selected = value;
        if (value) addClass(SELECTED_CLASS); else removeClass(SELECTED_CLASS);
        // The class is what the sheet styles; :checked would need a pseudo-class on an element that has
        // no natural checked meaning, and this row is not a control.
        invalidateStyleMatch();
        return this;
    }
}
