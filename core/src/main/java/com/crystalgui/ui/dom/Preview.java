package com.crystalgui.ui.dom;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * What a picker shows for a kind: a real instance dressed for the purpose, a picture, or a plain build.
 *
 * <pre>{@code
 * KindInfo.named("Slider").preview(Preview.sample(() -> new Slider().setValue(0.5f)).width(56));
 * KindInfo.named("Machine").preview(Preview.picture("icon(\"mymod:machine\")"));
 * }</pre>
 *
 * <p>A kind that declares nothing gets {@link #DERIVED}: the picker builds the kind's starter, or its factory.</p>
 *
 * <ul>
 *   <li>A sample's supplier runs once per card, so it must return a fresh element each call.</li>
 *   <li>A sample is laid out at its {@link Sample#width} when one is given, else at its own size, then scaled down
 *       to fit. A control that fills its row — a text field, a form — needs a width, or it lays out to nothing.</li>
 *   <li>A sample is only ever shown; what is placed is the kind's {@link KindInfo#starter}.</li>
 *   <li>A picture is any value {@code background} accepts, drawn fitted into the card.</li>
 * </ul>
 */
public sealed interface Preview {

    /** Build the kind's starter, at its own size. */
    Preview DERIVED = new Derived();

    static Sample sample(Supplier<? extends UIElement> sample) {
        return new Sample(sample, 0f, false);
    }

    static Preview picture(String background) {
        return new Picture(background);
    }

    /**
     * A real instance, dressed for the card — a slider at half, a form row with a label.
     *
     * <pre>{@code
     * Preview.sample(() -> panelOfThreeRows()).width(150).whole();   // its shape matters more than its words
     * }</pre>
     *
     * @param width the logical width it is laid out at, or 0 for its own
     * @param fitWhole whether it is shrunk to fit however small that makes it, rather than cropped once its text would
     *              stop reading — for a sample recognised by its shape, a form's band over its rows
     */
    record Sample(Supplier<? extends UIElement> sample, float width, boolean fitWhole) implements Preview {
        public Sample {
            Objects.requireNonNull(sample, "sample");
        }

        public Sample width(float logicalWidth) {
            return new Sample(sample, Math.max(0f, logicalWidth), fitWhole);
        }

        public Sample whole() {
            return new Sample(sample, width, true);
        }
    }

    /** A {@code background} value — an icon, a sprite, a gradient — for a kind that cannot be shown live. */
    record Picture(String background) implements Preview {
        public Picture {
            Objects.requireNonNull(background, "background");
        }
    }

    /** No declaration: the picker builds the kind plain. */
    record Derived() implements Preview {
    }
}
