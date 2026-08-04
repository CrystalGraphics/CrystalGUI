package com.crystalgui.graph.shader;

import com.crystalgui.graph.GraphProperty;
import com.crystalgui.ui.UIElement;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.input.UIDragController;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.ui.elements.TextField;
import com.crystalgui.ui.event.FocusEvent;
import com.crystalgui.ui.event.KeyboardEvent;
import com.crystalgui.ui.elements.UIText;

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
public class PropertyPill extends UIElement {

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

    private final UIElement capsule = new UIElement();
    private final UIElement dot = new UIElement();
    private final UIText name;
    private final UIText type;

    /** The floating copy shown while dragging. One per pill, re-registered per drag. @see #buildGhost */
    private final UIElement ghost = new UIElement();
    private final UIText ghostName = new UIText("");

    private boolean selected;

    /** Built on demand — almost no pill is ever renamed, and a TextField per row is not free. */
    @Nullable
    private TextField editor;

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
        markAsInternal();

        capsule.addClass(CAPSULE_CLASS);
        dot.addClass(DOT_CLASS);
        name = new UIText(property.name());
        name.addClass(NAME_CLASS);
        type = new UIText(BlackboardPanel.displayTypeOf(property));
        type.addClass(TYPE_CLASS);

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

        if (property.exposed()) capsule.addChild(dot);
        capsule.addChild(name);
        addInternalChild(capsule);
        addInternalChild(type);
        buildGhost();
        installPress();
    }

    @Override
    public boolean acceptsPublicChildren() {
        return false;
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
    public UIElement capsule() {
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
        UIWindow window = getAttachedWindow();
        // Never while renaming: the field is a text control, and a drag from inside it is a text
        // selection rather than a move.
        if (window == null || editor != null) return;

        float rawX = event.getPosition().x(), rawY = event.getPosition().y();
        UIDragController drag = window.getInputHandler().getDragController();
        // PER DRAG, which is what the controller expects -- it drops the ghost when the drag ends, so a
        // ghost registered once would appear for the first drag and never again.
        // The name may have changed since the ghost was built.
        ghostName.setText(name.getText());
        drag.setGhost(ghost);
        drag.startDrag(capsule, rawX, rawY, new Payload(propertyId),
                new UIDragController.DragListener() {
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
     * layer, and promotion needs a window to promote from — so it checks {@code getAttachedWindow()} and
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

        UIElement body = new UIElement();
        body.addClass(CAPSULE_CLASS);
        UIElement ghostDot = new UIElement();
        ghostDot.addClass(DOT_CLASS);
        ghostName.addClass(NAME_CLASS);

        // Nothing in a ghost may take the pointer: it sits under the cursor for the whole drag, so a
        // hittable ghost would be the drop target for every frame of it.
        ghost.setHitTest(false);
        body.addChild(ghostDot);
        body.addChild(ghostName);
        ghost.addChild(body);
        addInternalChild(ghost);
    }

    public void beginRename() {
        if (editor != null) return;
        UIWindow window = getAttachedWindow();
        if (window == null) return;

        editor = new TextField();
        editor.addClass(EDITOR_CLASS);
        editor.setText(name.getText());

        // ENTER AND BLUR END THE RENAME, and that is separate from the value listener on purpose.
        //
        // TextField publishes through a Signal.Value, which is EQUALITY-SUPPRESSING -- so committing a
        // name unchanged from the one already there emits nothing at all. Relying on that listener alone
        // meant pressing Enter on the pre-filled name did literally nothing: no write (correct, there is
        // nothing to write) and no close (wrong), so the field sat open and the rename looked broken. It
        // only appeared to work if you typed something different, which is a maddening thing to have to
        // discover.
        //
        // So the value listener writes, and these end the gesture regardless of whether anything changed.
        editor.attachListener(this::applyRename);
        editor.events.getGroup(KeyboardEvent.Down.class).attachListener((element, event) -> {
            if (event.getKeyCode() == CgKeyCodes.KEY_RETURN) {
                applyRename(editor == null ? null : editor.getText());
                endRename();
                event.stopPropagation();
            } else if (event.getKeyCode() == CgKeyCodes.KEY_ESCAPE) {
                // Escape abandons, which is the convention everywhere a rename is inline. Ended without
                // applying, and the press is consumed so it does not also reach whatever else is
                // listening for Escape -- a popover, a modal, the graph.
                endRename();
                event.stopPropagation();
            }
        }, false, true);
        editor.events.getGroup(FocusEvent.Blur.class).attachListener((element, event) -> {
            applyRename(editor == null ? null : editor.getText());
            endRename();
        }, false, true);

        addClass(RENAMING_CLASS);
        addInternalChild(editor);
        invalidateStyleMatch();

        // requestFocus, not requestPointerFocus: this IS keyboard focus and the ring is wanted -- the
        // field appeared in order to be typed into, which is the case :focus-visible exists for.
        window.getInputHandler().requestFocus(editor);
        editor.selectAll();
    }

    /** Whether a rename is in flight — a caller must not rebuild the row under one. */
    public boolean isRenaming() {
        return editor != null;
    }

    /**
     * Reports a new name, if there is one. Does <b>not</b> end the rename — see {@link #beginRename}.
     *
     * <p>Idempotent, because it is reached from three places that can overlap: the value listener, Enter,
     * and the blur Enter itself causes. A name equal to the current one is not a change and is dropped
     * here rather than by the document, so the panel is never handed a rename it would only discard.</p>
     */
    private void applyRename(@Nullable String value) {
        // No editor means the rename is already over, and this is a blur or a late listener arriving
        // during teardown. See endRename.
        if (editor == null) return;
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty() || trimmed.equals(name.getText())) return;
        // The name is updated locally FIRST, so the blur that follows Enter sees the new value and does
        // not report the same rename a second time.
        name.setText(trimmed);
        onRenamed.emit(trimmed);
    }

    /** Takes the editor away and puts the capsule back. Safe to call when no rename is running. */
    public void endRename() {
        if (editor == null) return;
        // CLEARED FIRST, then removed. Detaching a focused field fires a blur, and the blur handler
        // reports a rename -- which rewrites the document, which rebuilds the panel, WHILE refresh() is
        // still walking its pill list. That surfaced as a ConcurrentModificationException from a plain
        // add. Nulling the field before the removal makes both the blur handler and a second endRename
        // no-ops, which is the whole of the guard.
        TextField going = editor;
        editor = null;
        removeInternalChild(going);
        removeClass(RENAMING_CLASS);
        invalidateStyleMatch();
        // AFTER the removal, because that is what cleared the focus -- emitting first would have the
        // host take focus and then lose it again a line later.
        onRenameEnded.emit();
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
