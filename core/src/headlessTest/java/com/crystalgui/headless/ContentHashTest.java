package com.crystalgui.headless;

import com.crystalgui.net.mirror.UINodeMirror;
import com.crystalgui.serialization.ContentHash;
import com.crystalgui.serialization.JsonOps;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.UIDescriptionCodec;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.widget.control.Checkbox;
import com.crystalgui.widget.control.Slider;
import com.crystalgui.widget.text.UIText;
import com.google.gson.JsonElement;
import org.junit.Test;

import java.util.function.Supplier;

import static org.junit.Assert.*;

/**
 * {@link ContentHash} — the identity a client caches descriptions under.
 *
 * <p>Every property here is load-bearing for the cache. If the hash of an unchanged UI ever varies,
 * the cache silently never hits and every open re-transfers the tree; if two different UIs ever
 * collide, a player is shown the wrong screen.</p>
 */
public class ContentHashTest {

    /** A tree with enough shape to exercise maps, lists, strings, numbers and booleans. */
    private static Supplier<UINode> sampleTree() {
        return () -> {
            UINode root = new UINode();
            root.setId("settings").addClass("panel").addClass("dark");
            root.layout(l -> l.width(200).height(120));
            root.append(new UIText("Title"));
            Checkbox checkbox = new Checkbox("Enable");
            checkbox.setChecked(true);
            root.append(checkbox);
            Slider slider = new Slider();
            slider.setRange(0f, 10f).setValue(4f);
            root.append(slider);
            return root;
        };
    }

    private String hashOfJson(UINode element) {
        return ContentHash.of(JsonOps.INSTANCE, new UINodeMirror<>(JsonOps.INSTANCE).describe(element));
    }

    // ── Stability ───────────────────────────────────────────────────────────

    /** The property the whole cache rests on. */
    @Test
    public void anIdenticalTreeHashesIdentically() {
        assertEquals(hashOfJson(sampleTree().get()), hashOfJson(sampleTree().get()));
    }

    /** Encoding twice must be byte-identical too — the payload itself is what gets transferred. */
    @Test
    public void encodingIsByteIdentical() {
        JsonElement first = new UINodeMirror<>(JsonOps.INSTANCE).describe(sampleTree().get());
        JsonElement second = new UINodeMirror<>(JsonOps.INSTANCE).describe(sampleTree().get());
        assertEquals("a HashMap anywhere in the encode path would break this",
                first.toString(), second.toString());
    }

    /**
     * The hash must describe the value, not the format that carried it. Without this, a server on
     * one ops and a client on another could never agree on an identity.
     */
    @Test
    public void theSameTreeHashesTheSameThroughDifferentOps() {
        UINode tree = sampleTree().get();
        String viaJson = ContentHash.of(JsonOps.INSTANCE,
                new UINodeMirror<>(JsonOps.INSTANCE).describe(tree));
        String viaPlain = ContentHash.of(PlainOps.INSTANCE,
                new UINodeMirror<>(PlainOps.INSTANCE).describe(tree));
        assertEquals(viaJson, viaPlain);
    }

    // ── Sensitivity ─────────────────────────────────────────────────────────

    @Test
    public void anyMeaningfulChangeChangesTheHash() {
        String base = hashOfJson(sampleTree().get());

        UINode differentState = sampleTree().get();
        ((Checkbox) differentState.children().get(1)).setChecked(false);
        assertNotEquals("widget state must affect the identity", base, hashOfJson(differentState));

        UINode differentClass = sampleTree().get();
        differentClass.addClass("extra");
        assertNotEquals(base, hashOfJson(differentClass));

        UINode differentStyle = sampleTree().get();
        differentStyle.layout(l -> l.width(201));
        assertNotEquals(base, hashOfJson(differentStyle));

        UINode differentStructure = sampleTree().get();
        differentStructure.append(new UIText("extra"));
        assertNotEquals(base, hashOfJson(differentStructure));
    }

    /** Child order is meaningful — it decides paint and tab order. */
    @Test
    public void childOrderAffectsTheHash() {
        UINode a = new UINode();
        a.append(new UIText("one"));
        a.append(new UIText("two"));

        UINode b = new UINode();
        b.append(new UIText("two"));
        b.append(new UIText("one"));

        assertNotEquals(hashOfJson(a), hashOfJson(b));
    }

    // ── Canonical form properties ───────────────────────────────────────────

    /**
     * Two lists whose concatenated contents are identical but whose boundaries differ must not
     * collide.
     *
     * <p>Note what actually secures this, because it is not what it looks like: the type tag and the
     * element count already separate the two, so removing the length prefixes leaves this passing.
     * The prefixes are defensive — a canonical form should be unambiguous by construction rather
     * than by an accident of which bytes the tags happen to use — but this test does not prove they
     * are doing the work, and shouldn't claim to.</p>
     */
    @Test
    public void listsWithTheSameContentButDifferentBoundariesDiffer() {
        UINode ab = new UINode();
        ab.addClass("ab").addClass("c");
        UINode a = new UINode();
        a.addClass("a").addClass("bc");
        assertNotEquals(hashOfJson(ab), hashOfJson(a));
    }

    /** Map key order must not matter — this is the belt to the codec's braces. */
    @Test
    public void mapKeyOrderDoesNotAffectTheHash() {
        var first = new java.util.LinkedHashMap<Object, Object>();
        first.put("alpha", "1");
        first.put("beta", "2");

        var second = new java.util.LinkedHashMap<Object, Object>();
        second.put("beta", "2");
        second.put("alpha", "1");

        assertEquals(ContentHash.of(PlainOps.INSTANCE, first), ContentHash.of(PlainOps.INSTANCE, second));
    }

    /** An int and a float of the same value are the same value, and must not re-transfer. */
    @Test
    public void numericRepresentationDoesNotAffectTheHash() {
        var asInt = new java.util.LinkedHashMap<Object, Object>();
        asInt.put("n", 3);
        var asFloat = new java.util.LinkedHashMap<Object, Object>();
        asFloat.put("n", 3.0f);
        assertEquals(ContentHash.of(PlainOps.INSTANCE, asInt), ContentHash.of(PlainOps.INSTANCE, asFloat));
    }

    /** A string "3" is not the number 3 — type tags keep them apart. */
    @Test
    public void aStringIsNotItsNumber() {
        var asString = new java.util.LinkedHashMap<Object, Object>();
        asString.put("n", "3");
        var asNumber = new java.util.LinkedHashMap<Object, Object>();
        asNumber.put("n", 3);
        assertNotEquals(ContentHash.of(PlainOps.INSTANCE, asString), ContentHash.of(PlainOps.INSTANCE, asNumber));
    }

    @Test
    public void theHashIsAFullLengthSha256() {
        String hash = hashOfJson(sampleTree().get());
        assertEquals("SHA-256 is 64 hex characters", 64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"));
    }
}
