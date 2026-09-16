package com.crystalgui.app.uibuilder.style;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.crystalgraphics.platform.input.CgKeyCodes;

import com.crystalgui.app.uibuilder.inspect.LiveEdits;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.event.KeyboardEvent;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.widget.overlay.Popover;
import com.crystalgui.widget.scroll.ScrollerView;
import com.crystalgui.widget.text.UIText;

/**
 * The properties a section can add, as a list you can see rather than a list of names — what a family's
 * {@code +} opens.
 *
 * <pre>{@code
 * PropertyPalette.open(plusButton, StyleFamilies.Family.FILL,
 *         name -> target.declaring(name) != null,          // what is already declared, shown as such
 *         property -> fields.add(property.name, ""));      // picked
 * }</pre>
 *
 * <p>Every row carries a <b>live sample</b>: a small element with that very property applied at a
 * demonstration value, drawn by the engine itself. A person looking for the rounded-corner property finds it
 * by seeing a rounded corner, which is the entire reason this is not a dropdown of eighty names.</p>
 *
 * <p>Typing searches every registered property rather than the open family, since somebody who knows the
 * name should never have to know which section it was filed under. Escape closes, Enter takes the first row.</p>
 */
public final class PropertyPalette {

    public static final String PALETTE_CLASS = "__property-palette__";
    public static final String SEARCH_CLASS = "__palette-search__";
    public static final String ROW_CLASS = "__palette-row__";
    public static final String SAMPLE_CLASS = "__palette-sample__";
    public static final String NAME_CLASS = "__palette-name__";
    public static final String DECLARED_CLASS = "__declared__";

    /** On the sample of a property with nothing to draw: the column stays, the box does not. */
    public static final String EMPTY_CLASS = "__empty__";

    /** How many rows a search lists. A palette is for finding, not for browsing everything at once. */
    private static final int MAX_ROWS = 40;

    /**
     * What an <i>Add property</i> opens on: the declarations a person writes by hand most often, in the
     * order a form would ask for them. Everything else is one search away.
     */
    private static final List<String> COMMON = List.of(
            "display", "position", "width", "height", "padding-top", "padding-left", "margin-top",
            "margin-left", "gap", "flex-direction", "align-items", "justify-content", "flex-grow",
            "color", "font-size", "font-weight", "text-align", "background", "background-color", "opacity",
            "border-top-left-radius-x", "border-top-width", "border-color", "outline", "overflow",
            "transform", "backdrop-filter", "text-shadow", "cursor", "transition");

    private final Popover popover = new Popover();
    private final TextField search = new TextField();
    private final UIElement rows = new UIElement();

    /** The family listed until something is typed, or null for every property. */
    @Nullable
    private final StyleFamilies.Family family;
    private final Predicate<String> declared;
    private final Consumer<StyleProperty<?>> pick;

    private PropertyPalette(@Nullable StyleFamilies.Family family, Predicate<String> declared,
                            Consumer<StyleProperty<?>> pick) {
        this.family = family;
        this.declared = declared;
        this.pick = pick;

        popover.addClass(PALETTE_CLASS);
        search.addClass(SEARCH_CLASS);
        search.setPlaceholder("Search properties…");
        search.setUpdateMode(TextField.UpdateMode.IMMEDIATE);
        search.attachListener(text -> fill());
        search.onKeyDown.attachListener((element, event) -> {
            if (!(event instanceof KeyboardEvent.Down down)) return;
            if (down.getKeyCode() == CgKeyCodes.KEY_ESCAPE) {
                popover.hide();
                event.preventDefault();
            } else if (down.getKeyCode() == CgKeyCodes.KEY_RETURN && !listed.isEmpty()) {
                choose(listed.get(0));
                event.preventDefault();
            }
        }, false, true);

        // A ScrollerView takes content as ordinary children -- there is no viewport to reach through.
        ScrollerView scroller = new ScrollerView();
        scroller.append(rows);
        popover.append(search);
        popover.append(scroller);
    }

    private final List<StyleProperty<?>> listed = new ArrayList<>();

    /**
     * Opens the palette under {@code anchor}.
     *
     * @param family   the family whose properties are listed until something is typed
     * @param declared whether a property is already declared here — listed, and marked, since a person
     *                 looking for it wants to be told it is already on rather than shown nothing
     * @param pick     what a chosen property does
     */
    public static void open(UIElement anchor, StyleFamilies.Family family, Predicate<String> declared,
                            Consumer<StyleProperty<?>> pick) {
        show(anchor, new PropertyPalette(family, declared, pick));
    }

    /**
     * Opens over every property rather than one family — what an <i>Add property</i> at the end of a list
     * means.
     *
     * <p>It opens on the ones people actually reach for, because a list of eighty-odd in registration order
     * is not something anybody reads; typing searches all of them.</p>
     */
    public static void openAll(UIElement anchor, Predicate<String> declared, Consumer<StyleProperty<?>> pick) {
        show(anchor, new PropertyPalette(null, declared, pick));
    }

    private static void show(UIElement anchor, PropertyPalette palette) {
        palette.fill();
        // AWAY FROM THE ROW THAT OPENED IT: a popover attaches to the nearest ancestor that takes children,
        // and a press inside it would then bubble back to that row. @see StyleLab#open
        UIDocument window = anchor.document();
        if (window != null && palette.popover.parentElement() == null) {
            window.topLayerNode().append(palette.popover);
        }
        palette.popover.showFor(anchor, anchor);
        if (window != null) window.focus().requestPointerFocus(palette.search);
    }

    private void fill() {
        rows.removeAll();
        listed.clear();
        listed.addAll(matching(search.getText()));
        for (StyleProperty<?> property : listed) rows.append(row(property));
    }

    /** What is typed, else the family — and never the same property twice. */
    private List<StyleProperty<?>> matching(String query) {
        Set<StyleProperty<?>> found = new LinkedHashSet<>(
                query == null || query.isBlank() ? offered() : StyleFamilies.search(query));
        List<StyleProperty<?>> out = new ArrayList<>(found);
        return out.size() > MAX_ROWS ? out.subList(0, MAX_ROWS) : out;
    }

    /** What an unsearched palette lists: the family's own, or the ones people reach for. */
    private List<StyleProperty<?>> offered() {
        if (family != null && family != StyleFamilies.Family.OTHER) return family.properties();
        List<StyleProperty<?>> common = new ArrayList<>();
        for (String name : COMMON) {
            StyleProperty<?> property = StylePropertyRegistry.byName(name);
            if (property != null) common.add(property);
        }
        return common;
    }

    private UIElement row(StyleProperty<?> property) {
        UIElement row = new UIElement();
        row.addClass(ROW_CLASS);
        row.setHitTest(true);
        if (declared.test(property.name)) row.addClass(DECLARED_CLASS);

        row.append(sample(property));
        UIText name = new UIText(property.name);
        name.addClass(NAME_CLASS);
        row.append(name);
        row.onMouseDown.attachListener((element, event) -> {
            choose(property);
            event.preventDefault();
        }, false, true);
        return row;
    }

    /**
     * A small element with {@code property} applied at a demonstration value — the row's picture.
     *
     * <p>Drawn by the engine rather than by an icon set, so it is right by construction: what the sample
     * shows is what the property does, and a property whose effect cannot be shown in a chip this size
     * simply shows the chip.</p>
     */
    private static UIElement sample(StyleProperty<?> property) {
        UIElement sample = new UIElement();
        sample.addClass(SAMPLE_CLASS);
        String demo = PropertySamples.valueFor(property);
        if (demo == null) {
            sample.addClass(EMPTY_CLASS);
        } else {
            LiveEdits.setInline(sample, cast(property), demo);
        }
        return sample;
    }

    private void choose(StyleProperty<?> property) {
        popover.hide();
        pick.accept(property);
    }

    /** The demonstration values a sample is drawn with. */
    static final class PropertySamples {

        private PropertySamples() {
        }

        @Nullable
        static String valueFor(StyleProperty<?> property) {
            String name = property.name;
            if (name.endsWith("-color") || name.equals("color") || name.equals("background-color")) {
                return "#6AA9FF";
            }
            if (name.contains("radius")) return "7px";
            if (name.equals("opacity")) return "0.35";
            if (name.equals("background") || name.equals("overlay")) {
                return "linear-gradient(135deg, #6AA9FF, #C86AFF)";
            }
            if (name.equals("transform")) return "rotate(14deg)";
            if (name.endsWith("-width") && name.startsWith("border")) return "2px";
            if (name.equals("outline")) return "2px #6AA9FF";
            if (name.equals("text-shadow")) return "0 1px 2px #6AA9FF";
            // An enum, a length, a font: the chip stays plain rather than showing something that is not
            // what the property does.
            return null;
        }
    }

    /** The section header a family's rows sit under, in the palette's own wording. */
    public static String titleOf(StyleFamilies.Family family) {
        return family.label().toLowerCase(Locale.ROOT) + " properties";
    }

    @SuppressWarnings("unchecked")
    private static StyleProperty<Object> cast(StyleProperty<?> property) {
        return (StyleProperty<Object>) property;
    }
}
