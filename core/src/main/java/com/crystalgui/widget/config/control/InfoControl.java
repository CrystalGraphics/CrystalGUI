package com.crystalgui.widget.config.control;

import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.widget.config.ValueControl;
import com.crystalgui.widget.text.UIText;

import javax.annotation.Nullable;

/**
 * A fact, not a field — read-only text in the control column.
 *
 * <pre>{@code
 * form.row(ConfigDescriptor.info("id", "Node id"), node.id());                        // written once
 * form.prop(ConfigDescriptor.info("size", "size"), Property.derived(() -> sizeOf(box)));   // kept current
 * }</pre>
 *
 * <h3>Why this is a kind rather than a disabled {@link TextControl}</h3>
 * <p>Because a disabled text control is neither read-only nor read-only-<em>looking</em>. It draws the
 * full input chrome — a sunken box with a caret target — which says "type here". An inspector showing a
 * node's id, its category and its resolved port types as editable boxes invites the user to change facts
 * that are not theirs to change.</p>
 *
 * <p>Still writable <b>programmatically</b>: read-only means the user cannot type into it, not that a panel
 * cannot refresh it.</p>
 *
 * <h3>Not focusable, and not merely un-editable</h3>
 * <p>Tab must not stop on it. A caret cannot appear in it, so a tab stop there is a dead one.</p>
 */
public class InfoControl extends ValueControl<String> {

    public static final Name NAME = Name.of("infocontrol");

    public static final String INFO_CLASS = "__info__";

    private final UIText value;

    /** The no-argument constructor the registry's factory needs, over a NEUTRAL descriptor. */
    public InfoControl() {
        this(ConfigDescriptor.text("", ""), null);
    }

    public InfoControl(ConfigDescriptor descriptor, @Nullable String initial) {
        super(NAME, descriptor, initial == null ? "" : initial);
        addClass(INFO_CLASS);
        value = new UIText(getValue());
        value.addClass("__value__");
        // Scenery, like a Configurator's label: nothing here is interactive, and a text run that ate the
        // pointer would make the row's whole right-hand side dead to a click that was aimed past it.
        value.setHitTest(false);
        append(value);
    }

    /** The text element, for a host that wants to style or measure it. */
    public UIText text() {
        return value;
    }

    @Override
    protected void writeToWidgets(@Nullable String incoming) {
        value.setText(incoming == null ? "" : incoming);
    }
}
