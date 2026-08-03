package com.crystalgui.ui.elements.config.control;

import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.elements.config.ConfigDescriptor;
import com.crystalgui.ui.elements.config.ValueControl;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Two, three or four numbers with {@code X Y Z W} sub-labels.
 *
 * <p>Unity reference: {@code docs/research/unity-nodes/04-vector2.png} and siblings, and the
 * {@code Default   [X 0][Y 0][Z 0]} row in {@code docs/research/unity-inspector/01-inspector-property.png}.</p>
 *
 * <h3>The sub-labels are inside the control, not rows of their own</h3>
 * <p>This is the whole reason a vector is one control rather than three. In Unity's inspector a Vector3
 * default occupies <b>one</b> row — three labelled boxes sharing the control column — and the row's own
 * label says what the vector is. Spelling it as three rows would triple the height of every transform
 * in a panel and lose the fact that the three belong together.</p>
 *
 * <h3>It composes {@link NumberControl} rather than reimplementing it</h3>
 * <p>Every rule about what a partly-typed number means lives in one place. A vector that parsed its own
 * components would be the second copy, and the second copy is where "you cannot type a minus sign"
 * comes back.</p>
 */
public class VectorControl extends ValueControl<double[]> {

    private static final String[] AXES = { "X", "Y", "Z", "W" };

    private final List<NumberControl> components = new ArrayList<>();

    public VectorControl(ConfigDescriptor descriptor, double[] defaultValue) {
        super(descriptor, defaultValue);
        int arity = Math.max(1, Math.min(AXES.length, descriptor.arity()));
        addClass("__vector__");
        markAsInternal();

        for (int i = 0; i < arity; i++) {
            final int axis = i;
            UIElement cell = new UIElement();
            cell.addClass("__vector-cell__");

            UIText axisLabel = new UIText(AXES[i]);
            axisLabel.addClass("__axis__");

            NumberControl component = new NumberControl(
                    ConfigDescriptor.number(descriptor.id() + "." + AXES[i], AXES[i])
                            .integral(descriptor.integral()),
                    defaultValue != null && axis < defaultValue.length ? defaultValue[axis] : 0d);
            component.changed.connect(v -> onComponentChanged(axis, (Double) v));

            // The letter is the DRAG HANDLE, so it takes the press rather than passing it on.
            //
            // This reverses a deliberate earlier decision — the label used to be scenery with
            // setHitTest(false), on the grounds that "a press on the letter belongs to the field beside
            // it". That was right until the letter grew a gesture, and it cannot be both. Nothing is lost:
            // a press that does not travel far enough to scrub focuses the field anyway, which is what the
            // original note was protecting. Restore the hit-test opt-out and the gesture simply never
            // starts, with no test failing to say why.
            component.scrubWith(axisLabel);
            // A scrub is one gesture, so the whole vector reports one — otherwise the host brackets the
            // component's run while the value it is recording arrives from this class.
            component.interacting.connect(this::interactionRelay);

            cell.addChild(axisLabel);
            cell.addChild(component);
            components.add(component);
            addInternalChild(cell);
        }
    }

    /**
     * Re-reports a component's gesture as this control's own.
     *
     * <p>A host listens to the <b>vector</b> — that is the control it was handed — so a scrub on the
     * {@code Y} letter has to surface here or the host sees a stream of changes with no gesture around
     * them and records one undo step per frame.</p>
     */
    private void interactionRelay(Boolean active) {
        if (Boolean.TRUE.equals(active)) beginInteraction();
        else endInteraction();
    }

    private void onComponentChanged(int axis, @Nullable Double value) {
        double[] current = getValue();
        // Copied rather than mutated in place: the array handed out by getValue() is the one a listener
        // may still be holding, and editing it under them turns "what changed?" into a question with no
        // answer. Cheap at four doubles.
        double[] next = new double[components.size()];
        for (int i = 0; i < next.length; i++) {
            next[i] = current != null && i < current.length ? current[i] : 0d;
        }
        next[axis] = value == null ? 0d : value;
        commit(next);
    }

    @Override
    protected void writeToWidgets(@Nullable double[] value) {
        for (int i = 0; i < components.size(); i++) {
            components.get(i).setValue(value != null && i < value.length ? value[i] : 0d);
        }
    }

    public List<NumberControl> components() {
        return List.copyOf(components);
    }
}
