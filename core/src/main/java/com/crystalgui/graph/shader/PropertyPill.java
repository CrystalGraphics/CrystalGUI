package com.crystalgui.graph.shader;

import com.crystalgui.graph.GraphProperty;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.UIText;

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

    private final String propertyId;

    private final UIElement capsule = new UIElement();
    private final UIElement dot = new UIElement();
    private final UIText name;
    private final UIText type;

    private boolean selected;

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

        // Every part is scenery: the ROW takes the press, so a click anywhere along it selects and a drag
        // from anywhere along it drags. Letting the capsule eat the press would make the gap beside a
        // short name dead, which reads as the row only sometimes working.
        dot.setHitTest(false);
        name.setHitTest(false);
        type.setHitTest(false);
        capsule.setHitTest(false);

        if (property.exposed()) capsule.addChild(dot);
        capsule.addChild(name);
        addInternalChild(capsule);
        addInternalChild(type);
    }

    @Override
    public boolean acceptsPublicChildren() {
        return false;
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
