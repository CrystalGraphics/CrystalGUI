package com.crystalgui.ui.elements.config;

import com.crystalgui.core.config.ConfigDescriptor;
import javax.annotation.Nullable;

/**
 * A {@link ConfigControl} that holds a typed value.
 *
 * <p>Ported from LDLib2's {@code ValueConfigurator<T>}, minus the row it was welded to. The seam is the
 * part worth keeping: a control is handed a value and a sink, and never sees a document. That is what
 * lets one control serve a live editor, a preview pane and a read-only inspector without knowing which
 * it is in.</p>
 *
 * <h3>Typed here, string at the graph boundary</h3>
 * <p>A shader graph stores {@code vec4(1.0, 0.5, 0.0, 1.0)} — GLSL text — so something must parse and
 * format. That codec goes on the <b>graph</b> side, not here, and the asymmetry is deliberate rather
 * than arbitrary: an inspector over a real object already has the types and would be discarding them to
 * speak in strings, whereas the graph has to parse regardless of what this class chooses, because its
 * document is text either way. Pushing strings down here would cost the first consumer nothing and the
 * second one everything.</p>
 */
public abstract class ValueControl<T> extends ConfigControl {

    @Nullable
    private T value;

    @Nullable
    private final T defaultValue;

    protected ValueControl(ConfigDescriptor descriptor, @Nullable T defaultValue) {
        super(descriptor);
        this.defaultValue = defaultValue;
        this.value = defaultValue;
    }

    @Nullable
    public T getValue() {
        return value;
    }

    @Nullable
    public T getDefaultValue() {
        return defaultValue;
    }

    /** Pushes a value in without echoing back out. */
    public final void setValue(@Nullable T next) {
        setValueObject(next);
    }

    /** Restores the value this control was built with. Silent, like any other programmatic write. */
    public final void reset() {
        setValue(defaultValue);
    }

    @Override
    @Nullable
    public Object getValueObject() {
        return value;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected final void applyValue(Object incoming) {
        T typed = (T) incoming;
        this.value = typed;
        writeToWidgets(typed);
    }

    /**
     * Records a user edit and emits.
     *
     * <p>The field is written <b>before</b> the emit, so a listener that reads the control back during
     * the callback sees the new value rather than the old one. Reversing those two is the kind of thing
     * that works everywhere except in the one listener that reads back.</p>
     */
    protected final void commit(@Nullable T next) {
        if (isUpdating()) return;
        this.value = next;
        emitChanged(next);
    }

    /** Writes {@code value} into the underlying widgets. Never emits — {@link #commit} is the way out. */
    protected abstract void writeToWidgets(@Nullable T value);
}
