package com.crystalgui.ui.elements.config.control;

import com.crystalgui.ui.elements.TextField;
import com.crystalgui.ui.elements.config.ConfigDescriptor;
import com.crystalgui.ui.elements.config.ValueControl;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * One number, typed into a field.
 *
 * <p>Unity reference: {@code docs/research/unity-nodes/03-scalar-float.png}.</p>
 *
 * <h3>The value is not the text</h3>
 * <p>A field being typed into passes through states that are not numbers — {@code ""}, {@code "-"},
 * {@code "1."} are all on the way to a number and none of them parses. Committing only what parses,
 * and leaving the text alone otherwise, is what lets someone clear the field and start again. Rejecting
 * or rewriting the text mid-edit is the behaviour that makes a field impossible to type a negative
 * number into, because the minus sign is deleted before the digit arrives.</p>
 *
 * <h3>Formatting is one-way</h3>
 * <p>{@link #writeToWidgets} formats, {@link #parse} reads, and the two are deliberately not inverses:
 * {@code 0.30000001} is written as {@code 0.3}. Re-formatting on every keystroke would fight the
 * caret — type {@code 0.10} and the field would rewrite it to {@code 0.1} with the caret adrift — so
 * the text is only ever written on a programmatic set.</p>
 */
public class NumberControl extends ValueControl<Double> {

    private final TextField field = new TextField();
    private final boolean integral;

    @Nullable
    private final ConfigDescriptor.Range range;

    public NumberControl(ConfigDescriptor descriptor, double defaultValue) {
        super(descriptor, defaultValue);
        this.integral = descriptor.integral();
        this.range = descriptor.range();
        addClass("__number__");

        markAsInternal();
        addInternalChild(field);
        writeToWidgets(defaultValue);

        field.attachListener(text -> {
            Double parsed = parse(text);
            if (parsed == null) return; // mid-edit: "", "-", "1." — leave the text alone
            commit(clamp(parsed));
        });
    }

    /** The field itself, for a host that needs to reach the widget — sizing, focus, a max length. */
    public TextField field() {
        return field;
    }

    @Override
    protected void writeToWidgets(@Nullable Double value) {
        field.setText(format(value == null ? 0d : value));
    }

    private double clamp(double v) {
        if (range == null) return v;
        return Math.max(range.min(), Math.min(range.max(), v));
    }

    private String format(double v) {
        if (integral) return String.valueOf(Math.round(v));
        // Trailing zeros stripped, so 0.5 is "0.5" and 1.0 is "1" — Unity's own presentation, and the
        // difference between a readable node and one that is all decimal points.
        String s = String.format(Locale.ROOT, "%.4f", v);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    @Nullable
    private static Double parse(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return null;
        try {
            return Double.parseDouble(trimmed);
        } catch (NumberFormatException incomplete) {
            return null;
        }
    }
}
