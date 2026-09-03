package com.crystalgui.widget.config;

import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.control.Checkbox;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.widget.config.control.ArrayControl;
import com.crystalgui.core.config.ConfigDescriptor;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * The kit's guard rail — <b>every control kind, measured, in one pass.</b>
 *
 * <p>The point of P6.1.8 is that a control is reviewed once and then reused ~170 times. That only pays
 * off if "reviewed once" stays true, so the properties a human checked by eye are asserted here rather
 * than trusted: one height, one rhythm, no echo, no leak. A control that drifts is a named failure
 * instead of a review round on whichever node happened to show it.</p>
 */
public class ConfigKitTest extends UiDocumentTestBase {

    /** From `--cfg-ctrl-h`. Restated, because a test that reads its expectation out of the sheet
     * asserts only that the sheet agrees with itself — which it always does.
     *
     * <p>18, measured off Unity's own inspector: a 20px row pitch with an 18px control in it. */
    private static final float CTRL_H = 18f;

    private UIElement root;

    private ConfiguratorPanel openPanel() {
        root = new UIElement();
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        ConfiguratorPanel panel = new ConfiguratorPanel();
        root.append(panel);
        return panel;
    }

    /** A descriptor per kind, with a value each will accept. */
    private static ConfigDescriptor descriptorFor(ConfigDescriptor.Kind kind) {
        switch (kind) {
            case SELECT:
                return ConfigDescriptor.select("f", "Mode", List.of("One", "Two", "Three"));
            case MASK:
                return ConfigDescriptor.mask("f", "Mode", List.of("One", "Two", "Three"));
            case VECTOR:
                return ConfigDescriptor.vector("f", "Value", 3);
            case NUMBER:
                return ConfigDescriptor.number("f", "Value");
            case ARRAY:
                return ConfigDescriptor.of("f", "Entries", kind)
                        .element(ConfigDescriptor.text("f.e", ""));
            case MATRIX:
                return ConfigDescriptor.matrix("f", "Value", 4);
            default:
                return ConfigDescriptor.of("f", "Value", kind);
        }
    }

    private static List<ConfigDescriptor.Kind> registeredKinds() {
        List<ConfigDescriptor.Kind> kinds = new ArrayList<>();
        for (ConfigDescriptor.Kind kind : ConfigDescriptor.Kind.values()) {
            if (kind == ConfigDescriptor.Kind.GROUP) continue; // structure, not a value
            if (ConfigControls.isRegistered(kind)) kinds.add(kind);
        }
        return kinds;
    }

    private static float height(UIElement e) {
        // Zero for a control that is not on screen: a hidden node has no box here, where the old
        // engine's runtime cache always answered one.
        return heightOf(e);
    }

    /** True when {@code e} sits inside a {@code dialog}/{@code popover}/{@code menu} between itself and
     * {@code stopAt} — a popup a control can summon, not the row it lives in. */
    private static boolean isInsidePopup(UIElement e, UIElement stopAt) {
        for (UIElement p = e.parent(); p != null && p != stopAt; p = p.parent()) {
            String tag = p.tagName();
            if ("dialog".equals(tag) || "popover".equals(tag) || "menu".equals(tag)) return true;
        }
        return false;
    }

    /**
     * <b>The kit is a set.</b> Every control lands on one height, so a panel of unlike controls reads
     * as a form rather than as a pile.
     *
     * <p>Composites are exempt from the whole-control measurement and checked through their leaf
     * widgets instead: an array is a header plus n rows plus a footer and is legitimately tall. What
     * must match is the thing a user compares — the editable box on each line.</p>
     */
    @Test
    public void everyControlLandsOnTheKitHeight() {
        List<String> wrong = new ArrayList<>();
        for (ConfigDescriptor.Kind kind : registeredKinds()) {
            // BOOLEAN is the kit's one deliberate exception — square, and smaller than the row on
            // purpose. Covered by aCheckboxIsSquareAndSmallerThanTheRow instead of being fudged here.
            if (kind == ConfigDescriptor.Kind.BOOLEAN) continue;
            ConfiguratorPanel panel = openPanel();
            ConfigDescriptor descriptor = descriptorFor(kind);
            // An ARRAY is given an entry, so what gets measured is an entry's editable box rather than
            // the empty-state placeholder. A composite's own height is legitimately whatever its parts
            // add up to; the rhythm has to hold at the leaf a user actually clicks.
            Object value = kind == ConfigDescriptor.Kind.ARRAY ? List.of((Object) "a") : null;
            Configurator row = panel.add(descriptor, value);
            assertNotNull("no row built for " + kind, row);
            frame();

            ConfigControl control = row.control();
            // The leaf a user actually clicks. For a leaf control that IS the control; for a composite
            // it is each of its parts, which is the level the rhythm has to hold at.
            List<UIElement> leaves = new ArrayList<>();
            // `checkbox` is NOT in this list, and the omission is the same carve-out the sheet makes: a
            // checkbox is square and Unity keeps it ~13px inside a 20px row. Stretching it to the kit
            // height makes the quietest control on the panel the loudest. Its size is asserted on its
            // own below, so it is exempted rather than unchecked.
            for (String tag : List.of("textfield", "dropdown", "slider")) {
                leaves.addAll(deepAll(control, tag));
            }
            // A COLOR control's swatch summons a full ColorSelector — hex field, four channel sliders —
            // inside a Dialog it is not showing yet. querySelectorAll finds them regardless of the
            // Dialog's `display: none`, because that is a paint property, not a tree-structure one — so
            // without this filter the row rhythm would be checked against a picker that was never meant
            // to sit in it. The row rhythm applies to what IS the row, not to what the row can summon.
            leaves.removeIf(leaf -> isInsidePopup(leaf, control));
            // ...AND ANYTHING WITH NO BOX, which is the same carve-out stated in the engine's own
            // terms and now the one that does the work. A COLOR control summons a whole ColorSelector
            // inside a Dialog it is not showing, and the popup filter above was written when a
            // `display: none` subtree still LAID OUT -- it had a box of zero size, so it could be
            // recognised by walking to its Dialog. Here it has no box at all, so it is not laid out,
            // so it is not in the row: four hex/channel fields were being measured at 0.0px and
            // reported as controls that had missed the kit height.
            leaves.removeIf(leaf -> leaf.box() == null);
            if (leaves.isEmpty()) leaves.add(control);

            for (UIElement leaf : leaves) {
                if (Math.abs(height(leaf) - CTRL_H) > 0.5f) {
                    wrong.add(kind + " -> " + leaf.tagName() + " is " + height(leaf) + "px");
                }
            }
        }
        assertTrue("controls off the kit height (" + CTRL_H + "px) — add the tag to the kit selector "
                + "in default.css:\n  " + String.join("\n  ", wrong), wrong.isEmpty());
    }

    /** Every kind either builds or is a NAMED gap. A blank row is the failure this prevents. */
    @Test
    public void everyKindIsAccountedFor() {
        List<String> missing = new ArrayList<>();
        for (ConfigDescriptor.Kind kind : ConfigDescriptor.Kind.values()) {
            if (kind == ConfigDescriptor.Kind.GROUP) continue;
            if (!ConfigControls.isRegistered(kind)) missing.add(kind.name());
        }
        // Step 6 of the plan filled MASK/COLOR/MATRIX/ASSET; GRADIENT stays deferred to step 9 (the
        // expensive one, and nothing in the near node set needs it). Asserted as an exact set so
        // finishing one is a test change rather than a silent tightening, and so a REGRESSION shows up
        // as loudly as a gap.
        assertEquals("kinds still to build (plan step 9)", List.of("GRADIENT"), missing);
    }

    /**
     * <b>No echo.</b> A programmatic write must not fire {@code changed} — every host writes a value
     * back sooner or later, and a control that reported its own write would loop.
     */
    @Test
    public void aProgrammaticWriteDoesNotEmit() {
        for (ConfigDescriptor.Kind kind : registeredKinds()) {
            ConfiguratorPanel panel = openPanel();
            Configurator row = panel.add(descriptorFor(kind), null);
            frame();

            int[] emits = { 0 };
            row.control().changed.connect(v -> emits[0]++);
            row.control().setValueObject(row.control().getValueObject());
            assertEquals(kind + " emitted on a programmatic write", 0, emits[0]);
        }
    }

    /** The panel's one signal carries the id, so a host wires a panel rather than a row at a time. */
    @Test
    public void thePanelReportsChangesByIdIncludingInsideGroups() {
        ConfiguratorPanel panel = openPanel();
        ConfigDescriptor group = ConfigDescriptor.group("Section")
                .child(ConfigDescriptor.bool("nested", "Nested"));
        panel.build(List.of(ConfigDescriptor.number("top", "Top"), group), id -> null);
        frame();

        List<String> seen = new ArrayList<>();
        panel.changed.connect((id, value) -> seen.add(id));

        assertNotNull("a grouped row must be reachable by id", panel.control("nested"));
        panel.control("nested").changed.emit(true);
        assertEquals(List.of("nested"), seen);
    }

    /** A group collapses to nothing — and every box inside one then measures 0. */
    @Test
    public void aCollapsedGroupHidesItsContent() {
        ConfiguratorPanel panel = openPanel();
        ConfiguratorGroup group = new ConfiguratorGroup("Section");
        panel.append(group);
        panel.addTo(group.content(), ConfigDescriptor.number("n", "N"), null);
        frame();
        float open = height(group);

        group.setCollapsed(true);
        frame();
        assertTrue("collapsing must reclaim the content's height: " + open + " -> " + height(group),
                height(group) < open);
    }

    /** An empty array is a row saying so, never an absence. */
    @Test
    public void anEmptyArrayShowsItsPlaceholder() {
        ConfiguratorPanel panel = openPanel();
        Configurator row = panel.add(descriptorFor(ConfigDescriptor.Kind.ARRAY), null);
        frame();

        ArrayControl array = (ArrayControl) row.control();
        assertEquals(0, array.size());
        assertFalse("an empty list must still show a row",
                deepAll(array, "." + ArrayControl.EMPTY_CLASS).isEmpty());
        assertTrue("an empty list must still have height", height(array) > 0f);
    }

    /** A self-labelling control gets no label — the array's header already carries the name. */
    @Test
    public void aSelfLabellingControlSuppressesTheRowLabel() {
        ConfiguratorPanel panel = openPanel();
        Configurator array = panel.add(descriptorFor(ConfigDescriptor.Kind.ARRAY), null);
        Configurator matrix = panel.add(descriptorFor(ConfigDescriptor.Kind.MATRIX), null);
        Configurator number = panel.add(ConfigDescriptor.number("n", "Number"), null);
        frame();

        assertNull("an array labels itself in its header", array.label());
        assertNull("a matrix says what it is by its shape", matrix.label());
        assertNotNull("a number does not", number.label());
    }

    /**
     * The summary line — Unity's own three cases: nothing, everything, or a comma list. Driven through
     * {@link com.crystalgui.widget.config.control.MaskControl#writeToWidgets} rather than a
     * simulated checkbox click: both a programmatic write and a real toggle end at the same
     * {@code applySummary}, so exercising it this way covers the logic without depending on the exact
     * shape of a synthesized mouse event.
     */
    @Test
    public void aMaskSummarisesWhatIsActuallyChecked() {
        ConfiguratorPanel panel = openPanel();
        Configurator row = panel.add(ConfigDescriptor.mask("m", "M", List.of("A", "B")), null);
        frame();

        com.crystalgui.widget.config.control.MaskControl mask =
                (com.crystalgui.widget.config.control.MaskControl) row.control();
        assertEquals("nothing checked reads as Nothing", "Nothing", mask.toggle().getText());

        mask.setValue(java.util.Set.of("A"));
        assertEquals("A", mask.toggle().getText());

        mask.setValue(java.util.Set.of("A", "B"));
        assertEquals("every option checked reads as Everything", "Everything", mask.toggle().getText());

        mask.setValue(java.util.Set.of());
        assertEquals("Nothing", mask.toggle().getText());
    }

    /**
     * A header is structure, not a value: it must self-label (its caption IS the label), sit on the
     * kit height like every other leaf, and take the same band a group's head uses — the property that
     * makes it read as "a section starts here" rather than as a plain row.
     */
    @Test
    public void aHeaderIsABandedCaptionWithNoLabelColumnAndNoValue() {
        ConfiguratorPanel panel = openPanel();
        Configurator row = panel.add(ConfigDescriptor.header("Node Settings"), null);
        frame();

        assertNull("a header labels itself; the row must add no second label", row.label());
        assertNull("a header carries no state to report", row.control().getValueObject());
        assertEquals("a header sits on the kit height like any other leaf",
                CTRL_H, height(row.control()), 0.5f);
        assertEquals("a header takes the SAME band a group's head uses — one language for 'a section "
                        + "starts here', not two",
                0xFF383838, backgroundOf(row.control()));

        int[] emits = { 0 };
        row.control().changed.connect(v -> emits[0]++);
        row.control().setValueObject("ignored");
        assertEquals("a header must never emit — there is nothing a user could have changed",
                0, emits[0]);
    }

    /**
     * A full-width title must CLIP, never wrap. The default sheet's pixel font is narrow enough that
     * "Node Settings"/"Advanced"/"Entries" always fit their band regardless of whether this was set —
     * a screenshot under ore.css (a wider font, {@code MinecraftRegular.otf}) caught each one wrapping
     * onto a second line in turn, ONE AT A TIME, because each is a separate rule with no shared
     * ancestor to have fixed them all at once. The label column already solves this for an ordinary
     * row ({@code .__configurator__ > .__label__}); a header, a group head and an array's head are all
     * full-width with no such column to have inherited the fix from, so each needed its own — three
     * separate CSS rules is exactly why this was found one screenshot at a time instead of all at once.
     */
    @Test
    public void aFullWidthTitleClipsRatherThanWraps() {
        ConfiguratorPanel panel = openPanel();
        Configurator header = panel.add(ConfigDescriptor.header("Node Settings"), null);
        ConfiguratorGroup group = new ConfiguratorGroup("Advanced");
        panel.append(group);
        Configurator array = panel.add(ConfigDescriptor.of("entries", "Entries", ConfigDescriptor.Kind.ARRAY)
                .element(ConfigDescriptor.text("entries.e", "")), null);
        frame();

        UIElement headerTitle = deepAll(header.control(), ".__title__").get(0);
        UIElement groupTitle = deepAll(group.head(), ".__title__").get(0);
        // Scoped to `.__head__ text`, not a bare "text" query — an empty array ALSO shows a
        // `.__empty__` placeholder, itself a `text` tag, and the two must not be confused.
        UIElement arrayTitle = deepAll(array.control(), ".__head__ text").get(0);
        for (UIElement title : List.of(headerTitle, groupTitle, arrayTitle)) {
            assertEquals("must not wrap onto a second line",
                    com.crystalgui.style.property.visual.text.WhiteSpace.NOWRAP,
                    title.getStyle().getComputed(
                            com.crystalgui.style.property.StylePropertyRegistry.WHITE_SPACE));
            assertEquals("must clip with an ellipsis instead",
                    com.crystalgui.style.property.visual.text.TextOverflow.ELLIPSIS,
                    title.getStyle().getComputed(
                            com.crystalgui.style.property.StylePropertyRegistry.TEXT_OVERFLOW));
            // The bug the first pass at this fix actually shipped: `text-overflow` decides what the
            // clipped glyphs look like, it does not itself clip anything. Without `overflow: hidden`
            // establishing the clip box, the title's own box shrinks correctly but the glyphs still
            // paint at full width, bleeding out past it — a near-invisible sliver with ghost text
            // escaping past it, not a wrapped line.
            assertTrue("must actually clip, not just resolve an ellipsis value nothing enforces",
                    title.getStyle().getComputed(
                            com.crystalgui.style.property.StylePropertyRegistry.OVERFLOW).clips());
        }
    }

    /**
     * The gallery page's exact arrangement, laid out headlessly.
     *
     * <p>Mirrors {@code CgUiGalleryScene.configuratorPage} — every kind the page shows, two nesting
     * levels, and a populated array — because the page itself needs a GL context and this does not.
     * A panel that throws on build, or lays out to nothing, is then a test failure rather than a
     * harness launch that ends in a stack trace.</p>
     */
    @Test
    public void theGalleryPageArrangementBuildsAndLaysOut() {
        ConfiguratorPanel panel = openPanel();
        // The width a Dialog gives it in the harness. Without this the panel content-sizes to ~76px and
        // every row is squeezed to the same width, which hides exactly the raggedness being looked for.
        panel.layout(l -> l.width(300f));
        panel.add(ConfigDescriptor.header("Node Settings"), null);
        panel.add(ConfigDescriptor.text("name", "Name"), "Untitled");
        panel.add(ConfigDescriptor.number("scale", "Scale"), 1.0);
        panel.add(ConfigDescriptor.number("opacity", "Opacity").range(0f, 1f), 0.5);
        panel.add(ConfigDescriptor.number("count", "Count").integral(true), 3);
        panel.add(ConfigDescriptor.bool("exposed", "Exposed"), true);
        panel.add(ConfigDescriptor.select("space", "Space", List.of("Object", "World")), "World");
        panel.add(ConfigDescriptor.vector("offset", "Offset", 3), new double[] { 0, 1, 0 });

        ConfiguratorGroup advanced = new ConfiguratorGroup("Advanced");
        panel.append(advanced);
        panel.addTo(advanced.content(), ConfigDescriptor.number("bias", "Bias"), 0.0);
        ConfiguratorGroup nested = new ConfiguratorGroup("Nested", true);
        advanced.content().append(nested);
        panel.addTo(nested.content(), ConfigDescriptor.text("note", "Note"), "deep");

        panel.add(ConfigDescriptor.of("entries", "Entries", ConfigDescriptor.Kind.ARRAY)
                .element(ConfigDescriptor.text("entries.e", "")), List.of((Object) "alpha", "beta"));

        ConfiguratorGroup leaves = new ConfiguratorGroup("Step 6");
        panel.append(leaves);
        panel.addTo(leaves.content(), ConfigDescriptor.color("tint", "Tint"), 0xFF3C8CFF);
        panel.addTo(leaves.content(), ConfigDescriptor.mask("layers", "Layers", List.of("Default", "Water")),
                java.util.Set.of("Default"));
        panel.addTo(leaves.content(), ConfigDescriptor.matrix("transform", "Transform", 4), null);
        panel.addTo(leaves.content(), ConfigDescriptor.asset("shader", "Shader"), "Shaders/Lit.shader");
        frame();

        assertTrue("the panel must lay out to something", height(panel) > 0f);
        assertNotNull("a row two groups deep must still be reachable", panel.control("note"));
        assertNotNull("step 6's leaves must all be reachable too", panel.control("transform"));
        // Every row shares the panel's width, which is the property a ragged label column breaks.
        float panelWidth = panel.box().width();
        for (UIElement row : deepAll(panel, "." + Configurator.ROW_CLASS)) {
            // A row inside something that is not showing -- a folded group, an unshown picker -- has
            // NO box here rather than a zero one, and it is not on screen to be too wide.
            if (row.box() == null) continue;
            assertTrue("a row wider than its panel means the label column is not shrinking: "
                            + row.box().width() + " > " + panelWidth,
                    row.box().width() <= panelWidth + 0.5f);
        }
    }

    /**
     * <b>A nested row must occupy the same span as a top-level one.</b>
     *
     * <p>A group indents its content, so the rows inside it are narrower — but they must still END on
     * the panel's right edge. Reported by eye first: a row inside {@code Advanced} ran 7px past the
     * rows above it, which reads as the panel being ragged rather than as anything being wrong.</p>
     */
    @Test
    public void aNestedRowDoesNotOverflowThePanel() {
        ConfiguratorPanel panel = openPanel();
        Configurator top = panel.add(ConfigDescriptor.number("top", "Top"), 0.0);
        ConfiguratorGroup group = new ConfiguratorGroup("Advanced");
        panel.append(group);
        Configurator nested = panel.addTo(group.content(), ConfigDescriptor.number("bias", "Bias"), 0.0);
        frame();

        float topRight = top.box().x() + top.box().width();
        float nestedRight = nested.box().x() + nested.box().width();
        assertEquals("a nested row must end on the same right edge as a top-level one",
                topRight, nestedRight, 0.5f);
    }

    /**
     * <b>Nesting moves the LABEL, never the control column.</b>
     *
     * <p>Unity's rule, from {@code docs/research/unity-inspector/07-full-document.png}: a row inside a
     * foldout has its control on exactly the same x as a top-level one. Indenting the row instead
     * narrows it, which shrinks a percentage label column with it and steps the controls right at every
     * level — a panel that reads as ragged, and the defect this was shipped with.</p>
     */
    @Test
    public void nestingDoesNotMoveTheControlColumn() {
        ConfiguratorPanel panel = openPanel();
        panel.layout(l -> l.width(300f));
        Configurator top = panel.add(ConfigDescriptor.number("top", "Top"), 0.0);

        ConfiguratorGroup one = new ConfiguratorGroup("One");
        panel.append(one);
        Configurator inOne = panel.addTo(one.content(), ConfigDescriptor.number("a", "A"), 0.0);

        ConfiguratorGroup two = new ConfiguratorGroup("Two");
        one.content().append(two);
        Configurator inTwo = panel.addTo(two.content(), ConfigDescriptor.number("b", "B"), 0.0);
        frame();

        float expected = top.inline().box().x();
        assertEquals("one level deep must not shift the control column",
                expected, inOne.inline().box().x(), 0.5f);
        assertEquals("two levels deep must not shift it either",
                expected, inTwo.inline().box().x(), 0.5f);
        // ...and the labels DO step right, or the nesting would be invisible. Asserted through the
        // resolved padding rather than the label's X, because that is exactly the mechanism: padding is
        // taken out of a border-box element's own width, so the TEXT moves and the BOX does not. A test
        // that watched getX() here would report the fix as broken precisely when it was working.
        assertTrue("a nested label must be indented, and a top-level one must not",
                indentOf(inOne.label()) > indentOf(top.label()));
        assertTrue("two levels must indent further than one",
                indentOf(inTwo.label()) > indentOf(inOne.label()));
    }

    /** The cascaded {@code padding-left} on a label, in pixels. */
    private static float indentOf(UIElement label) {
        var value = label.getStyle().getComputed(
                com.crystalgui.style.property.layout.LayoutProperties.PADDING_LEFT);
        return value == null || !value.isLength() ? 0f : value.getValue();
    }

    /**
     * The palette actually reaches the controls.
     *
     * <p>Sampled from Unity and written into the token block — but a token is only worth as much as the
     * rule that uses it, and every one of these has a base rule in the same sheet competing with it.
     * {@code checkbox:checked .__mark__} shipped green (#3C8527) and beat a configurator rule that set
     * the size and forgot the colour: the size looked deliberate, so the green looked deliberate too.
     * Asserting the resolved value is the only way that fails instead of merely looking odd.</p>
     */
    @Test
    public void theKitsColoursWinOverTheBaseWidgetRules() {
        ConfiguratorPanel panel = openPanel();
        Configurator select = panel.add(
                ConfigDescriptor.select("s", "S", List.of("One", "Two")), "One");
        Configurator text = panel.add(ConfigDescriptor.text("t", "T"), "x");
        Configurator bool = panel.add(ConfigDescriptor.bool("b", "B"), true);
        frame();

        assertEquals("a dropdown must take the RAISED popup face, not the base sheet's button grey",
                0xFF4B4B4B, backgroundOf(deepAll(select.control(), "dropdown").get(0)));
        assertEquals("a text field must take the RECESSED field face",
                0xFF1E1E1E, backgroundOf(deepAll(text.control(), "textfield").get(0)));

        // The inset bevel: this is the property most likely to resolve-but-not-paint, since
        // border-top-color/border-bottom-color only DO anything when border-width is also non-zero —
        // a theme could set the colours and forget the width (or vice versa) and nothing would warn.
        UIElement field = deepAll(text.control(), "textfield").get(0);
        assertEquals("the field must carry a non-zero border for the bevel colours to have anything "
                        + "to stroke", 1f, field.box().border().left, 0.01f);
        assertEquals("top edge must be the DARK bevel colour",
                0xFF121212, field.getStyle().getGeneralGroup().borderTopColor());
        assertEquals("bottom edge must be the LIGHT bevel colour",
                0xFF545454, field.getStyle().getGeneralGroup().borderBottomColor());
        assertNotEquals("top and bottom must actually differ, or the split-border shader path never "
                        + "engages and this is just a slower way to draw a uniform border",
                field.getStyle().getGeneralGroup().borderTopColor(),
                field.getStyle().getGeneralGroup().borderBottomColor());
        assertEquals("a checked box must not be the base sheet's semantic green — the kit draws a "
                        + "real checkmark on the SAME dark field colour instead of colour-swapping",
                0xFF1E1E1E, backgroundOf(deepAll(bool.control(), "." + Checkbox.MARK_PART).get(0)));

        UIElement mark = deepAll(bool.control(), "." + Checkbox.MARK_PART).get(0);
        var overlay = mark.getStyle().getGeneralGroup().overlay();
        assertTrue("the on/off distinction must be the vector checkmark, not a colour swap",
                overlay instanceof com.crystalgui.render.texture.CgUiShape
                        && ((com.crystalgui.render.texture.CgUiShape) overlay).kind()
                        == com.crystalgui.render.texture.CgUiShape.Kind.CHECKMARK);

        // A mask panel's checkboxes are NOT `.__config-control__.__boolean__ checkbox` — MaskControl
        // adds `.__mask-row__` straight onto the Checkbox itself, so the boolean row's fix does not
        // reach them by construction. Caught by eye: a screenshot of an open mask panel showed the
        // base sheet's green squares sitting right next to a BooleanControl drawing the neutral one.
        Configurator mask = panel.add(ConfigDescriptor.mask("m", "M", List.of("X")), Set.of("X"));
        frame();
        // TWO STEPS, because one selector cannot make this journey any more: `.__mask-row__ .__mark__`
        // reaches through a CLASS into what is now a PART, and `::part()` has no spelling for a part
        // under a descendant -- it is one of the shipped rules the port counts as unexpressible.
        // Finding the row and then querying inside it crosses the boundary the same way the cascade
        // will have to when `exportparts` exists.
        UIElement maskRow = deepAll(mask.control(), ".__mask-row__").get(0);
        UIElement maskMark = deepAll(maskRow, "." + Checkbox.MARK_PART).get(0);
        assertEquals("a checked mask row must match BooleanControl's neutral field colour, not the "
                        + "base sheet's green",
                0xFF1E1E1E, backgroundOf(maskMark));
        var maskOverlay = maskMark.getStyle().getGeneralGroup().overlay();
        assertTrue("a mask row's on/off distinction must also be the vector checkmark",
                maskOverlay instanceof com.crystalgui.render.texture.CgUiShape
                        && ((com.crystalgui.render.texture.CgUiShape) maskOverlay).kind()
                        == com.crystalgui.render.texture.CgUiShape.Kind.CHECKMARK);
    }

    /**
     * The base sheet's {@code button:hover, dropdown:hover { background: #FF0000 }} is a deliberate
     * eyesore flagging "no theme touched this widget's hover" — and the whole config kit inherited it
     * silently, because nothing here ever overrode hover, only the resting state. Caught by a
     * screenshot of {@code MaskControl}'s toggle turning solid red on hover; a dropdown (e.g.
     * {@code SelectControl}) had the identical bug and had simply never been hovered in a screenshot.
     */
    @Test
    public void hoveringAKitButtonOrDropdownIsNotSolidRed() {
        ConfiguratorPanel panel = openPanel();
        Configurator select = panel.add(
                ConfigDescriptor.select("s", "S", List.of("One", "Two")), "One");
        Configurator mask = panel.add(ConfigDescriptor.mask("m", "M", List.of("X")), Set.of());
        frame();

        UIElement dropdown = deepAll(select.control(), "dropdown").get(0);
        dropdown.setHovered(true);
        assertNotEquals("a hovered dropdown must not be the base sheet's placeholder red",
                0xFFFF0000, backgroundOf(dropdown));

        com.crystalgui.widget.config.control.MaskControl maskControl =
                (com.crystalgui.widget.config.control.MaskControl) mask.control();
        maskControl.toggle().setHovered(true);
        assertNotEquals("a hovered mask toggle must not be the base sheet's placeholder red",
                0xFFFF0000, backgroundOf(maskControl.toggle()));
    }

    /** The resolved {@code background} of an element, as ARGB, when it is a flat fill. */
    private static int backgroundOf(UIElement e) {
        var drawable = e.getStyle().getGeneralGroup().background();
        assertTrue("expected a flat fill, got " + drawable,
                drawable instanceof com.crystalgui.render.texture.CgUiQuad);
        return ((com.crystalgui.render.texture.CgUiQuad) drawable).getColorArgb();
    }

    /** The checkbox's own size — the kit's one deliberate exception, so it needs its own assertion. */
    @Test
    public void aCheckboxIsSquareAndSmallerThanTheRow() {
        ConfiguratorPanel panel = openPanel();
        Configurator row = panel.add(ConfigDescriptor.bool("b", "B"), true);
        frame();

        UIElement mark = deepAll(row.control(), "." + Checkbox.MARK_PART).get(0);
        var c = mark.box();
        assertEquals("a checkbox must be square", c.width(), c.height(), 0.5f);
        assertEquals("...at the checkbox token, not the kit height", 13f, c.height(), 0.5f);
        assertTrue("...and it must sit INSIDE the row, not define it",
                c.height() < CTRL_H);
    }
}
