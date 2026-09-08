package com.crystalgui.app.uibuilder;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.crystalgui.serialization.JsonOps;
import com.crystalgui.serialization.style.InlineStyleCodec;
import com.crystalgui.serialization.style.StyleParts;
import com.crystalgui.serialization.style.TransformParts;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.texture.DrawableKinds;
import com.crystalgui.style.property.visual.transform.Transform;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;

/**
 * <b>The scratch document really does carry one of every composite, with all of its parts.</b>
 *
 * <p>It exists to be copied from by hand — open it, Copy Attributes, and every section Paste Attributes
 * can draw has something in it. That is only useful while it stays true, and a hand-written element in
 * a file nobody compiles is exactly the thing that quietly stops matching the registries. So this reads
 * the file, applies its declarations for real, and asks what came back.</p>
 *
 * <p>It does NOT demand one of all 101 properties: most are variations of a family that behaves
 * identically ({@code padding-left} tells you nothing {@code padding} did not). What it demands is
 * every kind of value that is handled DIFFERENTLY — <b>and it asks the registries rather than a list</b>,
 * so a drawable function added tomorrow is a failing test tomorrow rather than a section nobody ever
 * sees anything in.</p>
 */
public class TestDocumentCoversEveryStyleTest extends UiDocumentTestBase {

    /** Read from the resources copy, so the test does not depend on a harness run directory existing. */
    private static final String DOCUMENT = "/every-style.cgui";

    /** The value shapes that are neither a drawable nor a transform, and are each handled their own way. */
    private static final List<String> MUST_CARRY = List.of(
            "backdrop-filter",        // a filter-function list, and the one CSS property with our own
                                      // functions inside it
            "gap",                    // a paired length, and one of the properties that had no codec
            "font-family",            // a list
            "transition",             // a list of records, with an easing inside
            "text-decoration-line",   // a set of keywords
            "grid-row",               // a placement pair
            "grid-column");

    /**
     * Drawable functions that cannot be computed without a GL context, and so are covered by their TEXT
     * here and by the harness for real.
     *
     * <p>{@code asset()} resolves a named nine-slice through {@code CgUiSpriteRegistry}, which hands
     * back a sprite over a real {@code CgTexture2D} — and reaches {@code CgTextureManager} even for its
     * fallback. Headlessly that throws, {@code StyleValue} degrades it to null the way it degrades any
     * malformed value, and the declaration is simply gone. Worth knowing beyond this test:
     * {@code ShippedStyleRoundTripTest} skips a null value, so every {@code asset()} in {@code ore.css}
     * is outside that corpus for the same reason.</p>
     */
    private static final Set<String> NEEDS_A_GL_CONTEXT = Set.of("asset");

    /** Every element in the fixture that carries a style, by id — the whole document, not one element. */
    private static Map<String, JsonObject> styles() throws IOException {
        try (InputStream in = TestDocumentCoversEveryStyleTest.class.getResourceAsStream(DOCUMENT)) {
            assertNotNull("the fixture document is missing: " + DOCUMENT, in);
            JsonObject root = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("root");
            Map<String, JsonObject> found = new LinkedHashMap<>();
            collect(root, found);
            assertFalse("no styled elements in the fixture at all", found.isEmpty());
            return found;
        }
    }

    private static void collect(JsonObject node, Map<String, JsonObject> into) {
        if (node.has("id") && node.has("style")) {
            into.put(node.get("id").getAsString(), node.getAsJsonObject("style"));
        }
        if (!node.has("children")) return;
        for (JsonElement child : node.getAsJsonArray("children")) {
            collect(child.getAsJsonObject(), into);
        }
    }

    /** Every value in the fixture, whatever property or element it came from. */
    private static List<String> allValues(Map<String, JsonObject> styles) {
        List<String> values = new ArrayList<>();
        for (JsonObject style : styles.values()) {
            for (String name : style.keySet()) values.add(style.get(name).getAsString());
        }
        return values;
    }

    /** Every value of one property across the fixture. */
    private static List<String> valuesOf(Map<String, JsonObject> styles, String property) {
        List<String> values = new ArrayList<>();
        for (JsonObject style : styles.values()) {
            if (style.has(property)) values.add(style.get(property).getAsString());
        }
        return values;
    }

    /**
     * Every declaration in it is a property this engine knows, and every one of them applies.
     *
     * <p>A typo in a hand-written document is otherwise silent: the declaration is skipped and the
     * element simply does not carry what it claims to, which is precisely the failure a fixture like
     * this is supposed to rule out. A drawable function whose arguments do not parse is the same
     * failure with a longer spelling — {@code blur(nonsense)} yields null and the property is gone.</p>
     */
    @Test
    public void everyDeclarationInTheFixtureIsRealAndApplies() throws IOException {
        UIElementRegistry.bootstrap();

        List<String> unknown = new ArrayList<>();
        List<String> lost = new ArrayList<>();
        for (Map.Entry<String, JsonObject> element : styles().entrySet()) {
            JsonObject style = element.getValue();
            Set<String> skipped = new LinkedHashSet<>();
            for (String name : style.keySet()) {
                if (StylePropertyRegistry.byName(name) == null) unknown.add(element.getKey() + "." + name);
                DrawableKinds.Kind kind = DrawableKinds.matching(style.get(name).getAsString());
                if (kind != null && NEEDS_A_GL_CONTEXT.contains(kind.id())) skipped.add(name);
            }

            // WITHOUT the GL-bound ones, rather than ignoring them afterwards: the codec does not
            // degrade a value it cannot read back, it THROWS -- so one of them in the object takes the
            // whole element with it. @see NEEDS_A_GL_CONTEXT
            JsonObject applied = new JsonObject();
            for (String name : style.keySet()) {
                if (!skipped.contains(name)) applied.add(name, style.get(name));
            }

            UIElement node = new UIElement();
            document.append(node);
            InlineStyleCodec.decodeInto(JsonOps.INSTANCE, applied, node);
            document.update(W, H);

            // WHAT COMES BACK OUT, which is the only proof they landed rather than being dropped on
            // the way.
            JsonElement encoded = InlineStyleCodec.encode(JsonOps.INSTANCE, node);
            assertNotNull(encoded);
            for (String name : applied.keySet()) {
                if (!encoded.getAsJsonObject().has(name)) lost.add(element.getKey() + "." + name);
            }
        }
        assertTrue("declarations naming no registered property: " + unknown, unknown.isEmpty());
        assertTrue("declared but did not survive being applied and read back: " + lost, lost.isEmpty());
    }

    /**
     * <b>Every registered drawable function is written down somewhere in the fixture.</b>
     *
     * <p>Read off {@link DrawableKinds} rather than a list here, which is the same reason the grouping
     * is: a tenth function added to the registry gets a section of its own in Paste Attributes with
     * nothing to put in it, and nothing else anywhere says so.</p>
     */
    @Test
    public void everyRegisteredDrawableKindIsExercised() throws IOException {
        List<String> values = allValues(styles());

        Set<String> covered = new LinkedHashSet<>();
        for (String value : values) {
            DrawableKinds.Kind kind = DrawableKinds.matching(value);
            if (kind != null) covered.add(kind.id());
        }

        List<String> missing = new ArrayList<>();
        for (DrawableKinds.Kind kind : DrawableKinds.all()) {
            if (!covered.contains(kind.id())) missing.add(kind.id());
        }
        assertTrue("registered drawable functions the fixture has no example of: " + missing
                + " (it covers " + covered + ")", missing.isEmpty());
    }

    /**
     * <b>Every part {@code transform} comes apart into, and the case that refuses to.</b>
     *
     * <p>The refusal matters as much as the four: a kind appearing either side of another cannot be
     * described as "the translation" without saying where the rotation went, so the whole property is
     * offered instead — and that path has no example anywhere else.</p>
     */
    @Test
    public void everyPartOfTransformIsExercisedIncludingTheOneThatRefuses() throws IOException {
        List<String> transforms = valuesOf(styles(), "transform");
        assertFalse("no transform in the fixture at all", transforms.isEmpty());

        Set<String> covered = new LinkedHashSet<>();
        boolean sawOneThatRefuses = false;
        for (String text : transforms) {
            Transform value = StylePropertyRegistry.TRANSFORM.valueParser.parse(text).compute();
            assertNotNull("a transform in the fixture does not parse: " + text, value);
            if (!TransformParts.INSTANCE.divides(value)) {
                sawOneThatRefuses = true;
                continue;
            }
            for (StyleParts.Part part : TransformParts.INSTANCE.parts()) {
                if (TransformParts.INSTANCE.encodePart(JsonOps.INSTANCE, value, part.id()) != null) {
                    covered.add(part.id());
                }
            }
        }

        List<String> missing = new ArrayList<>();
        for (StyleParts.Part part : TransformParts.INSTANCE.parts()) {
            if (!covered.contains(part.id())) missing.add(part.id());
        }
        assertTrue("transform functions the fixture has no example of: " + missing, missing.isEmpty());
        assertTrue("nothing in the fixture exercises a transform that cannot be divided, so the whole"
                + " property is never offered and that path is untested", sawOneThatRefuses);
    }

    /** And the value shapes that are neither a drawable nor a transform. */
    @Test
    public void theFixtureCoversEveryOtherKindOfValue() throws IOException {
        Map<String, JsonObject> styles = styles();
        List<String> missing = new ArrayList<>();
        for (String property : MUST_CARRY) {
            if (valuesOf(styles, property).isEmpty()) missing.add(property);
        }
        assertTrue("the fixture no longer exercises: " + missing, missing.isEmpty());
    }
}
