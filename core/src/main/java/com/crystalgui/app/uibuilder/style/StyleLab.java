package com.crystalgui.app.uibuilder.style;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.inspect.LiveEdits;
import com.crystalgui.core.property.Property;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.overlay.Popover;
import com.crystalgui.widget.text.UIText;

/**
 * The full-size editor for one declaration: a specimen, the gizmos that change it, the measured caption,
 * and the CSS it produces — opened beside the row rather than squeezed into it.
 *
 * <pre>{@code
 * StyleLab lab = StyleLab.over(chip, "Background", css);
 * lab.specimen(sample -> sample.layout(l -> l.height(64)));   // what the value is shown on
 * lab.content().append(new GradientBar(css));
 * lab.caption(() -> stops + " stops at " + angle + "°");
 * lab.open();
 * }</pre>
 *
 * <p><b>The specimen is drawn on both grounds</b> — dark and light — because the properties worth a lab are
 * the ones a single background cannot settle: a stroke, a shadow, a translucent fill. Each is a real element
 * with the real declaration applied, so what is shown is what the engine will draw, not an illustration
 * of it.</p>
 *
 * <p>The CSS readout under the gizmos is the value itself, live. It is the same string the sheet is about
 * to hold: a lab whose printed value and written value can differ is a lab you have to double-check.</p>
 */
public final class StyleLab {

    public static final String LAB_CLASS = "__style-lab__";
    public static final String TITLE_CLASS = "__lab-title__";
    public static final String GROUNDS_CLASS = "__lab-grounds__";
    public static final String GROUND_CLASS = "__lab-ground__";
    public static final String DARK_CLASS = "__dark__";
    public static final String LIGHT_CLASS = "__light__";
    public static final String SPECIMEN_CLASS = "__lab-specimen__";
    public static final String CONTENT_CLASS = "__lab-content__";
    public static final String CAPTION_CLASS = "__lab-caption__";
    public static final String CSS_CLASS = "__lab-css__";
    public static final String SHAPE_CLASS = "__lab-shape__";

    private final UIElement anchor;
    private final Property<String> css;

    @Nullable
    private final StyleProperty<?> property;

    private final Popover popover = new Popover();
    private final UIElement grounds = new UIElement();
    private final UIElement content = new UIElement();
    private final UIText caption = new UIText("");
    private final UIText readout = new UIText("");
    private final List<UIElement> specimens = new ArrayList<>();
    private final List<UIElement> contents = new ArrayList<>();

    @Nullable
    private Supplier<String> captionText;

    private StyleLab(UIElement anchor, String title, @Nullable StyleProperty<?> property, Property<String> css) {
        this.anchor = anchor;
        this.property = property;
        this.css = css;

        popover.addClass(LAB_CLASS);
        UIText heading = new UIText(title);
        heading.addClass(TITLE_CLASS);
        popover.append(heading);

        grounds.addClass(GROUNDS_CLASS);
        popover.append(grounds);

        content.addClass(CONTENT_CLASS);
        popover.append(content);

        caption.addClass(CAPTION_CLASS);
        popover.append(caption);

        readout.addClass(CSS_CLASS);
        popover.append(readout);
    }

    /** A lab for {@code css}, anchored to the row's gizmo. */
    public static StyleLab over(UIElement anchor, String title, @Nullable StyleProperty<?> property,
                               Property<String> css) {
        return new StyleLab(anchor, title, property, css);
    }

    /** Where a lab's own gizmos go. */
    public UIElement content() {
        return content;
    }

    /**
     * Adds the specimen pair: the same element on a dark ground and on a light one, both carrying the
     * declaration as it stands.
     */
    public StyleLab specimens() {
        return specimens(null);
    }

    /**
     * As {@link #specimens()}, with something of the lab's own inside each — a line of text for the type
     * lab. <b>One per ground</b>, since an element lives in one tree at a time; {@link #contents()} hands
     * back what was made so the lab can style both.
     */
    public StyleLab specimens(@Nullable Supplier<UIElement> content) {
        specimens.clear();
        contents.clear();
        grounds.removeAll();
        grounds.append(ground(DARK_CLASS, content));
        grounds.append(ground(LIGHT_CLASS, content));
        return this;
    }

    /** Puts coloured shapes behind the specimens — what a blur or a refraction needs to be visible at all. */
    public StyleLab backdrop() {
        for (UIElement specimen : specimens) {
            UIElement ground = specimen.parentElement();
            if (ground == null) continue;
            for (int i = 0; i < 3; i++) {
                UIElement shape = new UIElement();
                shape.addClass(SHAPE_CLASS);
                shape.addClass(SHAPE_CLASS + i);
                // BEFORE the specimen, so the specimen filters them rather than sitting under them.
                ground.insertAt(i, shape);
            }
        }
        return this;
    }

    /** What {@link #specimens(Supplier)} built, one per ground. */
    public List<UIElement> contents() {
        return List.copyOf(contents);
    }

    private UIElement ground(String tone, @Nullable Supplier<UIElement> content) {
        UIElement ground = new UIElement();
        ground.addClass(GROUND_CLASS);
        ground.addClass(tone);
        UIElement specimen = new UIElement();
        specimen.addClass(SPECIMEN_CLASS);
        if (content != null) {
            UIElement inside = content.get();
            contents.add(inside);
            specimen.append(inside);
        }
        ground.append(specimen);
        specimens.add(specimen);
        return ground;
    }

    /** What the caption says, asked whenever the value changes — measured, never a literal. */
    public StyleLab caption(Supplier<String> text) {
        this.captionText = text;
        return this;
    }

    /**
     * Re-reads the value: the specimens take it, the caption is asked again, and the readout prints what
     * will be written. Call after any gizmo changes something.
     */
    public void refresh() {
        String value = css.get();
        if (property != null) {
            for (UIElement specimen : specimens) {
                if (value == null || value.isBlank()) {
                    LiveEdits.clearInline(specimen, property);
                } else {
                    LiveEdits.setInline(specimen, cast(property), value);
                }
            }
        }
        caption.setText(captionText == null ? "" : captionText.get());
        readout.setText(name() + ": " + (value == null || value.isBlank() ? "—" : value));
    }

    private String name() {
        return property == null ? "value" : property.name;
    }

    /** Opens it beside the row. */
    public StyleLab open() {
        refresh();
        popover.showFor(anchor, anchor);
        return this;
    }

    public void close() {
        popover.hide();
    }

    /** The popover itself, for a lab that wants to add something outside the standard parts. */
    public Popover popover() {
        return popover;
    }

    /** The window the lab is in, or null before it is opened. */
    @Nullable
    public UIDocument window() {
        return popover.document();
    }

    @SuppressWarnings("unchecked")
    private static StyleProperty<Object> cast(StyleProperty<?> property) {
        return (StyleProperty<Object>) property;
    }
}
