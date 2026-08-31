package com.crystalgui.ui.elements.config.control;

import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.elements.config.ConfigControl;
import com.crystalgui.core.config.ConfigDescriptor;

import javax.annotation.Nullable;

/**
 * A fact, not a field — read-only text in the control column.
 *
 * <h3>Why this is a kind rather than a disabled {@link TextControl}</h3>
 * <p>Because a disabled text control is neither read-only nor read-only-<em>looking</em>. It draws the
 * full input chrome — a sunken box with a caret target — which says "type here", and
 * {@code setEnabled(false)} on the wrapper does not reach the {@link com.crystalgui.ui.elements.TextField}
 * inside it, so the row stayed genuinely editable. An inspector showing a node's id, its category and its
 * resolved port types as editable boxes invites the user to change facts that are not theirs to change,
 * and then silently discards what they typed.</p>
 *
 * <p>{@code HEADER} already established that a non-value may wear the {@link ConfigControl} shape so it
 * travels through the same registry, row and kit-height machinery as everything else. This is the second
 * one, and it takes a label like an ordinary row rather than self-labelling, because a fact has a name
 * and a value where a header has only a name.</p>
 *
 * <h3>Not focusable, and not merely un-editable</h3>
 * <p>Tab must not stop on it. A caret cannot appear in it, so a tab stop there is a dead one — the user
 * presses Tab, the focus ring lands on something inert, and the next press has to be made blind.</p>
 */
public class InfoControl extends ConfigControl {

    public static final String INFO_CLASS = "__info__";

    private final UIText value;

    public InfoControl(ConfigDescriptor descriptor, @Nullable String initial) {
        super(descriptor);
        addClass(INFO_CLASS);
        markAsInternal();

        value = new UIText(initial == null ? "" : initial);
        value.addClass("__value__");
        // Scenery, like a Configurator's label: nothing here is interactive, and a text run that ate the
        // pointer would make the row's whole right-hand side dead to a click that was aimed past it.
        value.setHitTest(false);
        addInternalChild(value);
    }

    /** The text element, for a host that wants to style or measure it. */
    public UIText text() {
        return value;
    }

    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }

    @Override
    public Object getValueObject() {
        return value.getText();
    }

    @Override
    protected void applyValue(Object incoming) {
        // Still writable PROGRAMMATICALLY -- read-only means the user cannot type into it, not that a
        // panel cannot refresh it. The compile stats are exactly this: facts that change every emit.
        value.setText(incoming == null ? "" : String.valueOf(incoming));
    }
}
