package com.crystalgui.ui.elements.config;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.ui.UIElement;

/**
 * <b>The bare editor for one value — no label, no row, no idea where it is mounted.</b>
 *
 * <h3>Why this is not the row</h3>
 * <p>LDLib2's {@code Configurator} <em>is</em> the row: it owns a label, an inline area and a tip, and
 * every concrete editor extends it. That is the right design for an inspector and the wrong one here,
 * because this engine has <b>three hosts and only one of them is a row</b>:</p>
 *
 * <ul>
 *   <li>the inspector — {@link Configurator}, a label column beside a control column;</li>
 *   <li>a node's property row — the same shape at node scale;</li>
 *   <li><b>a node's unconnected input</b> — the control alone, floating outside the node box beside its
 *       port, labelled by the port itself.</li>
 * </ul>
 *
 * <p>The third is the common case in a node graph, and it has no row to inherit. Fold the label into
 * the control and it can reuse nothing. So the control is the unit of reuse and the row wraps one.
 * Unity's own editor is built this way: the same dropdown appears beside a port on a master node and as
 * an inspector row, changing only its anchor and its width.</p>
 *
 * <h3>{@link #changed} fires for the USER, never for a programmatic set</h3>
 * <p>A host that writes a value back in response to a change — every host does, sooner or later — would
 * otherwise drive an infinite echo. {@link #setValueObject} therefore silences the signal for the
 * duration of the write, once, here, so that no concrete control has to remember it. {@code TextField}
 * and {@code ColorSelector} both already carry a private version of this guard; this is the general
 * one.</p>
 *
 * <h3>Undo belongs to the host</h3>
 * <p>LDLib2 hangs an {@code EditAction} off the row. Ours cannot: an {@code UndoStack} belongs to a
 * <em>document</em>, and a control has no document — the same control edits a shader graph, a settings
 * object and a preview pane. So {@link #changed} reports what the user did and the host decides whether
 * that is an {@code Edit}, which is also what keeps a read-only inspector from silently recording
 * history.</p>
 */
public abstract class ConfigControl extends UIElement {

    /** Marks every control in the kit, whatever its kind — the hook a host or theme sizes them by. */
    public static final String CONTROL_CLASS = "__config-control__";

    /** Fires when the <b>user</b> changes the value. Never on {@link #setValueObject}. */
    public final Signal.Value<Object> changed = new Signal.Value<>();

    private final ConfigDescriptor descriptor;

    /** True while a programmatic write is in flight; suppresses {@link #changed}. */
    private boolean updating;

    protected ConfigControl(ConfigDescriptor descriptor) {
        this.descriptor = descriptor;
        addClass(CONTROL_CLASS);
        if (descriptor.tooltip() != null) {
            com.crystalgui.ui.elements.Tooltip.attach(this, descriptor.tooltip());
        }
    }

    public ConfigDescriptor descriptor() {
        return descriptor;
    }

    /** The current value, in whatever type this control speaks. */
    public abstract Object getValueObject();

    /**
     * Pushes a value in without echoing it back out.
     *
     * <p>Subclasses implement {@link #applyValue}; this method owns the guard. Overriding <em>this</em>
     * instead would lose it, which is why it is final.</p>
     */
    public final void setValueObject(Object value) {
        boolean wasUpdating = updating;
        updating = true;
        try {
            applyValue(value);
        } finally {
            updating = wasUpdating;
        }
    }

    /** Writes {@code value} into the underlying widgets. Never emits. */
    protected abstract void applyValue(Object value);

    /**
     * Emits {@link #changed}, unless a programmatic write is in flight.
     *
     * <p>Every concrete control routes its widget's listener through here rather than touching
     * {@code changed} directly. A control that emits by hand is the one that will echo.</p>
     */
    protected final void emitChanged(Object value) {
        if (updating) return;
        changed.emit(value);
    }

    /** True while {@link #setValueObject} is running — for controls that rebuild rather than write. */
    protected final boolean isUpdating() {
        return updating;
    }

    /**
     * True when the control carries its own label and the host must not add one.
     *
     * <p><b>The control decides, because only it knows whether it self-describes.</b> A colour swatch
     * and a 4×4 matrix grid say what they are by being what they are; "Value: [a colour]" adds nothing
     * the colour does not, and costs the swatch the width it needs to be judged against. This is the
     * same rule {@code GraphNode.addControl} already applies through {@code FULL_WIDTH_CLASS}.</p>
     */
    public boolean selfLabelling() {
        return false;
    }
}
