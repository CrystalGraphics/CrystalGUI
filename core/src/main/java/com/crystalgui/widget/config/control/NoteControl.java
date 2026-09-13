package com.crystalgui.widget.config.control;

import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.widget.config.ValueControl;
import com.crystalgui.widget.text.UIText;

import javax.annotation.Nullable;

/**
 * A paragraph across the whole field, with no label column — Blender's {@code layout.label}, Unity's help
 * box.
 *
 * <pre>{@code
 * form.note("Rim is the hairline at the boundary; glow is the broad falloff.");
 * form.prop(ConfigDescriptor.note("css"), Property.derived(this::css));   // a live readout
 * }</pre>
 *
 * <p>The text wraps, unlike an {@link InfoControl}'s: a fact is one value the panel scrolls sideways to
 * reach, while a note is prose written to the panel's width.</p>
 */
public class NoteControl extends ValueControl<String> {

    public static final Name NAME = Name.of("notecontrol");

    public static final String NOTE_CLASS = "__note__";

    private final UIText text;

    /** The no-argument constructor the registry's factory needs, over a NEUTRAL descriptor. */
    public NoteControl() {
        this(ConfigDescriptor.note(""), null);
    }

    public NoteControl(ConfigDescriptor descriptor, @Nullable String initial) {
        super(NAME, descriptor, initial == null ? "" : initial);
        addClass(NOTE_CLASS);
        text = new UIText(getValue());
        text.addClass("__value__");
        text.setHitTest(false);
        append(text);
    }

    /** A note says what it is by being what it is, so a row adds no label. */
    @Override
    public boolean selfLabelling() {
        return true;
    }

    /** The text element. */
    public UIText text() {
        return text;
    }

    @Override
    protected void writeToWidgets(@Nullable String value) {
        text.setText(value == null ? "" : value);
    }
}
