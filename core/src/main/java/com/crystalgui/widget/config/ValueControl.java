package com.crystalgui.widget.config;

import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.data.DataContext;
import com.crystalgui.core.property.Property;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.data.UiDataKeys;
import com.crystalgui.ui.dom.UIDocument;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * A {@link ConfigControl} that edits a typed value — <b>held in a {@link Property}.</b>
 *
 * <p>Built with a value of its own, or bound to one that lives somewhere else, in which case the control
 * is simply a view of that property: an edit writes it, a change made anywhere else shows up here, and a
 * continuous gesture is one step of the property's history.</p>
 *
 * <pre>{@code
 * NumberControl angle = new NumberControl(ConfigDescriptor.number("r", "Rotation").unit("°"), 0d);
 * angle.bind(Property.derived(() -> gesture.degrees(), gesture::setDegrees).editedIn(history));
 * }</pre>
 *
 * <p>Usually through a form, which builds the control and binds it in one call:</p>
 *
 * <pre>{@code
 * form.prop(ConfigDescriptor.number("r", "Rotation").unit("°"), angle);
 * }</pre>
 *
 * <h3>Following</h3>
 *
 * <ul>
 *   <li>A property that announces its changes is listened to; a {@linkplain Property#isPolled polled} one
 *       is re-read after every layout. Either way only while the control is on screen, and a control that
 *       comes back re-reads.</li>
 *   <li><b>A field holding an edit is never overwritten</b> — Dear ImGui's rule, and Unity's. What moved
 *       meanwhile is shown once the edit lands or is dropped.</li>
 *   <li>Nothing is followed mid-gesture: a scrub is not interrupted by its own writes.</li>
 * </ul>
 *
 * <h3>Writing</h3>
 *
 * <ul>
 *   <li>{@link #commit} is a user edit: it writes the property and emits {@link #changed}.</li>
 *   <li>{@link #setValue} is a programmatic one: it writes the property too, and emits nothing. On a bound
 *       control that means the model changes — there is no second copy of the value to set instead.</li>
 *   <li>A read-only property is shown and never written; edits to it are dropped.</li>
 * </ul>
 *
 * <h3>Typed here, string at the graph boundary</h3>
 * <p>A shader graph stores {@code vec4(1.0, 0.5, 0.0, 1.0)} — GLSL text. That codec is the graph's, as a
 * {@link Property#map} over the stored text, and not this class's: an inspector over a real object already
 * has the types and would be discarding them to speak in strings.</p>
 */
public abstract class ValueControl<T> extends ConfigControl {

    @Nullable
    private final T defaultValue;

    private Property<T> source;

    /** What the property held when it was bound. @see #restoreBoundValue */
    @Nullable
    private T boundValue;

    private final PropertyWatch watch;

    /** True while this control writes its own property, so the write is not followed back in. */
    private boolean pushing;

    /** A change that arrived while the field held an edit, to be shown once it no longer does. */
    private boolean staleWhileEditing;

    /** The history a gesture in flight is holding a merge run on — kept, in case the property is rebound. */
    @Nullable
    private UndoStack heldRun;

    protected ValueControl(Name name, ConfigDescriptor descriptor, @Nullable T defaultValue) {
        super(name, descriptor);
        this.defaultValue = defaultValue;
        this.source = Property.of(defaultValue);
        this.boundValue = defaultValue;
        this.watch = new PropertyWatch(this, source, this::sourceMoved);
        whileConnected(watch::start);
        interacting.connect(this::holdGesture);
    }

    /**
     * Makes {@code property} this control's value, showing what it holds now.
     *
     * <p>The property's type is the control's: {@code Double} for a number or a slider, {@code String} for
     * text, a choice, an asset or a fact, {@code Boolean}, {@code Integer} ARGB for a colour,
     * {@code double[]} for a vector, a matrix or an anchor, {@code Set<String>} for a mask and
     * {@code List<Object>} for an array. {@link Property#map} converts one that is stored differently.</p>
     */
    public ValueControl<T> bind(Property<T> property) {
        Objects.requireNonNull(property, "property");
        Object before = getValueObject();
        source = property;
        boundValue = property.get();
        // A BASELINE, so the first frame on screen reports only what moved after binding.
        property.refresh();
        watch.retarget(property);
        if (!isEditing()) show(boundValue);
        if (!Objects.deepEquals(before, boundValue)) notifyStateChanged();
        return this;
    }

    /** The property this control edits — its own until {@link #bind}. */
    public Property<T> property() {
        return source;
    }

    @Nullable
    public T getValue() {
        return source.get();
    }

    @Nullable
    public T getDefaultValue() {
        return defaultValue;
    }

    /** Writes a value without emitting {@link #changed}. @see ValueControl the note on writing */
    public final void setValue(@Nullable T next) {
        setValueObject(next);
    }

    /** Writes the value this control was built with. Silent, like any other programmatic write. */
    public final void reset() {
        setValue(defaultValue);
    }

    /**
     * Writes back what the property held when it was bound — a tuning panel's Reset.
     *
     * <pre>{@code
     * Button reset = new Button("Reset");
     * reset.onPressed.connect(() -> panel.controls().values().forEach(c -> {
     *     if (c instanceof ValueControl<?> value) value.restoreBoundValue();
     * }));
     * }</pre>
     */
    public final void restoreBoundValue() {
        setValue(boundValue);
    }

    /** Whether the user holds an edit here that has not landed yet. False for a control nothing is typed into. */
    public boolean isEditing() {
        return false;
    }

    @Override
    @Nullable
    public Object getValueObject() {
        return source.get();
    }

    @Override
    @SuppressWarnings("unchecked")
    protected final void applyValue(Object incoming) {
        if (!source.isReadOnly()) write((T) incoming);
        writeToWidgets(source.get());
    }

    /**
     * Records a user edit and emits what the property kept.
     *
     * <p>The property is written <b>before</b> the emit, so a listener that reads the control back sees the
     * new value. A model that clamps keeps less than it was offered, and the field is corrected to that
     * once the user is no longer typing into it.</p>
     */
    protected final void commit(@Nullable T next) {
        if (isUpdating() || source.isReadOnly()) return;
        write(next);
        T kept = source.get();
        if (!Objects.deepEquals(kept, next) && !isEditing()) show(kept);
        emitChanged(kept);
    }

    /**
     * {@link #commit}s and shows what the property kept — for a control whose widgets do not already show the edit.
     *
     * <pre>{@code
     * pad.onDrag(at -> commitAndShow(at));      // the dot moves as it is dragged
     * field.onTyped(text -> commit(parse(text))); // the field already shows what was typed
     * }</pre>
     *
     * <p>A control never repaints for its own commit, which is right for a text field and wrong for a gizmo or a
     * list: a dragged dot, a removed row or a needle aimed by a typed number shows nothing until something else
     * changes the value.</p>
     */
    protected final void commitAndShow(@Nullable T next) {
        commit(next);
        show(source.get());
    }

    /** Writes {@code value} into the underlying widgets. Never emits — {@link #commit} is the way out. */
    protected abstract void writeToWidgets(@Nullable T value);

    private void write(@Nullable T next) {
        pushing = true;
        try {
            source.set(next);
        } finally {
            pushing = false;
        }
    }

    private void sourceMoved(@Nullable T was, @Nullable T now) {
        if (pushing) return;
        if (isEditing() || isInteracting()) {
            showOnceIdle();
            return;
        }
        show(now);
        notifyStateChanged();
    }

    private void show(@Nullable T value) {
        quietly(() -> writeToWidgets(value));
    }

    /**
     * The history a gesture here is one step of: the property's, else the one Ctrl+Z pressed here would reach.
     *
     * <p>The second because a row can name its history without its property doing so, and then a drag had no
     * run to hold and recorded one step per frame.</p>
     */
    @Nullable
    private UndoStack history() {
        UndoStack own = source.history();
        return own != null ? own : DataContext.from(this).get(UiDataKeys.UNDO_STACK);
    }

    /** Shows the property's value on the first frame nothing is being typed or dragged here. */
    private void showOnceIdle() {
        if (staleWhileEditing) return;
        UIDocument window = document();
        if (window == null) return;
        staleWhileEditing = true;
        window.animation().afterLayout(this, delta -> {
            if (isEditing() || isInteracting()) return true;
            staleWhileEditing = false;
            show(source.get());
            notifyStateChanged();
            return false;
        });
    }

    private void holdGesture(Boolean active) {
        if (Boolean.TRUE.equals(active)) {
            if (heldRun != null) return;
            heldRun = history();
            if (heldRun != null) heldRun.beginMergeRun();
        } else if (heldRun != null) {
            UndoStack run = heldRun;
            heldRun = null;
            run.endMergeRun();
        }
    }
}
