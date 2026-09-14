package com.crystalgui.ui.dom;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * What a picker shows for a kind: a real instance dressed for the purpose, a picture, or a plain build.
 *
 * <pre>{@code
 * KindInfo.named("Button").preview(Preview.sample(() -> new Button("Save")));
 * KindInfo.named("Machine").preview(Preview.picture("icon(\"mymod:machine\")"));
 * }</pre>
 *
 * <p>A kind that declares nothing gets {@link #DERIVED}: the picker builds one from the kind's factory.</p>
 *
 * <ul>
 *   <li>A sample's supplier runs once per card, so it must return a fresh element each call.</li>
 *   <li>A picture is any value {@code background} accepts, drawn fitted into the card.</li>
 * </ul>
 */
public sealed interface Preview {

    /** Build the kind from its registered factory. */
    Preview DERIVED = new Derived();

    static Preview sample(Supplier<? extends UIElement> sample) {
        return new Sample(sample);
    }

    static Preview picture(String background) {
        return new Picture(background);
    }

    /** A real instance, dressed for the card — a button reading {@code Save}. */
    record Sample(Supplier<? extends UIElement> sample) implements Preview {
        public Sample {
            Objects.requireNonNull(sample, "sample");
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
