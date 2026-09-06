package com.crystalgui.net.projection;

import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.contract.State;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.contract.WidgetContracts;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * <b>Wires a panel to a model by name</b>, and says what it could not wire.
 *
 * <p>A panel's widgets are FIELDS, and the field name already means something — {@code UiType} derives
 * each widget's CSS id from it. So a panel field {@code throughput} beside a model accessor
 * {@code throughput()} or {@code getThroughput()} is a projection nobody needs to write. JavaFX's
 * {@code @FXML} is the same trick over the same reflection; Android generates code instead because it
 * has a build step and we do not.</p>
 *
 * <h3>The report is not optional</h3>
 *
 * <p>A convention that quietly skips a field is the exact failure this whole mechanism exists to
 * remove: the widget keeps whatever it was built with, which is usually right, so it looks correct on
 * open and then never moves. The engine's own rule covers it — <i>live and inert look identical, so a
 * capability that can be silently skipped must say it is on</i> — so {@link #wire} always answers a
 * {@link Report} naming every widget it left alone and why, and the caller is expected to log it.</p>
 *
 * <h3>What it will not do</h3>
 *
 * <p><b>It never guesses which slot a widget means.</b> A widget is wired only if its contract declares
 * a {@link WidgetContract#primary()}; {@code Slider} carries {@code MIN}, {@code MAX} and
 * {@code VALUE}, all floats, so neither the first slot nor the type could choose, and a convention that
 * guessed would be wrong on the widget it is most useful for. A widget with no primary is reported, not
 * assumed.</p>
 *
 * <p>It is also a <b>starting point, not a ceiling</b>: an explicit projection for the same widget
 * simply wins, because it was declared first and {@link #wire} skips what is already covered.</p>
 */
public final class AutoProjection {

    private AutoProjection() {
    }

    /**
     * What {@link #wire} did, and — the half that matters — what it did not.
     *
     * <h3>Why there are two kinds of "not"</h3>
     *
     * <p>A first version reported every widget it did not wire, and on a real panel that was seventeen
     * lines of which fifteen were buttons: honest, useless, and the sort of wall a reader learns to
     * scroll past — which would take the two lines that mattered with it.</p>
     *
     * <p>So the split is by whether there was anything to act on. A field the model has <b>no accessor
     * for</b> was never a candidate: a button called {@code purge} beside a model with no
     * {@code purge()} is not a near miss, it is a control. Those are counted, not listed. A field whose
     * accessor <b>exists</b> and could still not be wired is the actionable case — the data is there and
     * the widget cannot take it — and every one of those is named with its reason.</p>
     *
     * @param wired     panel field name → the model accessor it was matched to
     * @param skipped   panel field name → why, where the model HAS a matching accessor
     * @param unmatched fields with no model accessor at all, in declaration order
     */
    public record Report(Map<String, String> wired, Map<String, String> skipped,
                         List<String> unmatched) {

        public boolean isEmpty() {
            return wired.isEmpty() && skipped.isEmpty();
        }

        /** One line per actionable outcome, wired first. What a host logs at construction. */
        public List<String> lines() {
            List<String> out = new ArrayList<>(wired.size() + skipped.size());
            for (Map.Entry<String, String> entry : wired.entrySet()) {
                out.add("wired   " + entry.getKey() + " <- " + entry.getValue());
            }
            for (Map.Entry<String, String> entry : skipped.entrySet()) {
                out.add("SKIPPED " + entry.getKey() + " — " + entry.getValue());
            }
            return out;
        }

        @Override
        public String toString() {
            return "auto-projection: " + wired.size() + " wired, " + skipped.size()
                    + " skipped, " + unmatched.size() + " with no matching accessor";
        }
    }

    /**
     * Matches {@code panel}'s widget fields against {@code model}'s accessors and adds a projection for
     * each pair that lines up.
     *
     * <p>A widget something already projects onto is left alone, silently — that is the author being
     * specific rather than a gap. <b>Recognised by identity, so the order of declaration does not
     * matter</b>: an earlier version matched by field NAME and required every explicit projection to be
     * both named and declared first, two rules whose only symptom when broken is a widget written twice
     * a tick by two projections that may disagree, the later one winning.</p>
     */
    public static Report wire(Object panel, Object model, Projections into) {
        return wire(panel, model, into, level -> true);
    }

    /**
     * As {@link #wire(Object, Object, Projections, java.util.Set)}, with the caller deciding which
     * inheritance levels hold panel widgets.
     *
     * <p>Needed because {@code UIElement} has widget-typed fields of its own — {@code parent} and
     * {@code popoverInvoker} — so a walk that runs to {@code Object} inspects the element's own
     * plumbing and reports it as unwired UI. {@code UiType.collect} stops at {@code Networked} levels
     * for exactly this reason, and a caller that knows about panels passes the same rule; this package
     * cannot name {@code Networked} without inverting the dependency.</p>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Report wire(Object panel, Object model, Projections into,
                              java.util.function.Predicate<Class<?>> isPanelLevel) {
        Map<String, String> wired = new LinkedHashMap<>();
        Map<String, String> skipped = new LinkedHashMap<>();
        List<String> unmatched = new ArrayList<>();

        for (Field field : widgetFields(panel.getClass(), isPanelLevel)) {
            String name = field.getName();

            // THE ACCESSOR FIRST, and the order is the whole reason the report is readable: a field the
            // model cannot answer for was never a projection candidate, so it is counted rather than
            // explained. Everything below this line is a case where the DATA EXISTS and something else
            // stopped the wiring -- which is the only kind worth a reader's attention.
            Method accessor = accessorFor(model.getClass(), name);
            if (accessor == null) {
                unmatched.add(name);
                continue;
            }

            UIElement widget;
            try {
                field.setAccessible(true);
                widget = (UIElement) field.get(panel);
            } catch (ReflectiveOperationException | RuntimeException blocked) {
                skipped.put(name, "unreadable: " + blocked);
                continue;
            }
            if (widget == null) {
                skipped.put(name, "null at wiring time — a panel builds its parts in its constructor");
                continue;
            }
            // Stated by hand already. Not a gap, and not reported as one.
            if (into.targets(widget)) continue;

            WidgetContract<?> contract = WidgetContracts.of(widget);
            if (contract == null) {
                skipped.put(name, "no contract: " + widget.getClass().getSimpleName()
                        + " is local-only, so nothing about it travels");
                continue;
            }
            State<?, ?> primary = contract.primary();
            if (primary == null) {
                skipped.put(name, "contract '" + contract.name() + "' declares no primary state, so "
                        + "which slot to write is not a question a convention may answer — project it "
                        + "explicitly");
                continue;
            }

            accessor.setAccessible(true);
            final Method reader = accessor;
            final UIElement target = widget;
            final State slot = primary;
            into.onto(target, () -> {
                try {
                    return reader.invoke(model);
                } catch (ReflectiveOperationException failed) {
                    // Unwrapped, so the projection's own log line names the real cause rather than
                    // InvocationTargetException.
                    Throwable cause = failed.getCause();
                    throw cause instanceof RuntimeException ? (RuntimeException) cause
                            : new IllegalStateException(failed);
                }
            }, value -> slot.set(target, value));
            wired.put(name, accessor.getName() + "()");
        }
        return new Report(wired, skipped, unmatched);
    }

    /**
     * A panel's own widget fields, up the levels the caller says are panel levels.
     *
     * <p><b>Never {@code UIElement} itself or above.</b> That class has widget-typed fields of its own —
     * {@code parent} and {@code popoverInvoker} — so a walk that does not stop reports an element's
     * internal plumbing as unwired UI. Found by running this against a real panel and reading the
     * report, which is the thing the report is for.</p>
     */
    private static List<Field> widgetFields(Class<?> type,
                                            java.util.function.Predicate<Class<?>> isPanelLevel) {
        List<Field> found = new ArrayList<>();
        for (Class<?> level = type; level != null && level != Object.class
                && level != UIElement.class && isPanelLevel.test(level);
                level = level.getSuperclass()) {
            for (Field field : level.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                if (!UIElement.class.isAssignableFrom(field.getType())) continue;
                found.add(field);
            }
        }
        return found;
    }

    /** {@code name()} first — the record-style accessor this codebase writes — then {@code getName()}. */
    @Nullable
    private static Method accessorFor(Class<?> model, String name) {
        Method direct = lookup(model, name);
        if (direct != null) return direct;
        String capitalised = capitalise(name);
        Method bean = lookup(model, "get" + capitalised);
        if (bean != null) return bean;
        // isRunning() for a field named `running`, and `power` will not find it -- which is correct,
        // and is why the report exists.
        return lookup(model, "is" + capitalised);
    }

    @Nullable
    private static Method lookup(Class<?> model, String name) {
        try {
            Method found = model.getMethod(name);
            if (found.getReturnType() == void.class) return null;
            if (found.getParameterCount() != 0) return null;
            return found;
        } catch (NoSuchMethodException absent) {
            return null;
        }
    }

    private static String capitalise(String name) {
        if (name.isEmpty()) return name;
        return name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
    }
}
