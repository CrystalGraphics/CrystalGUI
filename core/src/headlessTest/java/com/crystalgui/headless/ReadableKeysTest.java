package com.crystalgui.headless;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import com.crystalgui.net.mirror.DocumentExtras;
import com.crystalgui.net.mirror.UIElementMirror;
import com.crystalgui.serialization.ContentHash;
import com.crystalgui.serialization.JsonOps;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.widget.text.UIText;

/**
 * <b>One encoder, two dialects, one hash.</b> A {@code .cgui} file is a description with readable keys,
 * and re-spelling the keys must not move what a description hashes to.
 *
 * <p>The second half is the one worth a test: the hash is what {@code ServerWindows} sends instead of a
 * whole tree, so a key table that leaked into it would invalidate every cached description on the day
 * the document format was invented.</p>
 */
public class ReadableKeysTest {

    private static UIElement tree() {
        UIElementRegistry.bootstrap();
        UIElement root = new UIElement();
        root.setId("root");
        root.addClass("panel");
        root.addClass("wide");
        UIText title = new UIText("Status");
        title.setId("title");
        title.addClass("heading");
        root.append(title);
        return root;
    }

    private static UIElementMirror<JsonElement> wire() {
        return new UIElementMirror<>(JsonOps.INSTANCE);
    }

    private static UIElementMirror<JsonElement> document() {
        return new UIElementMirror<>(JsonOps.INSTANCE, UIElementMirror.Keys.DOCUMENT);
    }

    @Test
    public void theDocumentDialectIsReadable() {
        JsonObject described = document().describe(tree()).getAsJsonObject();

        assertEquals("element", described.get("kind").getAsString());
        assertEquals("root", described.get("id").getAsString());
        assertEquals("panel wide", described.get("class").getAsString());
        assertTrue(described.has("children"));
        assertFalse("the short spelling is the wire's alone", described.has("n"));

        JsonObject child = described.getAsJsonArray("children").get(0).getAsJsonObject();
        assertEquals("text", child.get("kind").getAsString());
        assertEquals("Status", child.getAsJsonObject("state").get("text").getAsString());
    }

    @Test
    public void aDocumentRoundTripsToItself() {
        JsonElement once = document().describe(tree());
        JsonElement twice = document().describe(document().decode(once));

        assertEquals(once, twice);
    }

    /** The point of using the codec at all: a document IS a description of the same tree. */
    @Test
    public void bothDialectsHashTheSame() {
        UIElement built = tree();
        UIElement fromDocument = document().decode(document().describe(built));

        assertEquals(ContentHash.of(JsonOps.INSTANCE, wire().describe(built)),
                ContentHash.of(JsonOps.INSTANCE, wire().describe(fromDocument)));
    }

    /**
     * A recorded description hashes to a recorded string.
     *
     * <p>Over the description rather than over a tree, so this pins the hash function and the canonical
     * byte form — not whatever widgets happen to encode this week.</p>
     */
    @Test
    public void theWireHashOfARecordedDescriptionIsUnchanged() {
        String recorded = "{\"n\":\"crystalgui:element\",\"i\":\"root\",\"c\":\"panel wide\","
                + "\"k\":[{\"n\":\"crystalgui:text\",\"i\":\"title\",\"c\":\"heading\","
                + "\"v\":{\"text\":\"Status\"}}]}";

        assertEquals("30897677676211abc0ee43e9318cbdbc5f4df4d023f9faa10ddb6b835baf2784",
                ContentHash.of(JsonOps.INSTANCE, new JsonParser().parse(recorded)));
    }

    @Test
    public void extrasAreCarriedByTheDocumentDialectOnly() {
        UIElement root = tree();
        UIElement title = root.getElementById("title");
        DocumentExtras<JsonElement> extras = new DocumentExtras<>();
        JsonObject bind = new JsonObject();
        bind.add("text", new JsonPrimitive("subject"));
        extras.put(title, DocumentExtras.BIND, bind);

        JsonObject described = document().describe(root, extras).getAsJsonObject();
        JsonObject child = described.getAsJsonArray("children").get(0).getAsJsonObject();
        assertEquals("subject", child.getAsJsonObject("bind").get("text").getAsString());

        DocumentExtras<JsonElement> back = new DocumentExtras<>();
        UIElement decoded = document().decode(described, back);
        assertEquals(bind, back.get(decoded.getElementById("title"), DocumentExtras.BIND));

        // And a description of the same tree carries none of it.
        assertFalse(wire().describe(root).toString().contains("subject"));
    }

    /** Decoding without a table is how {@code UiTemplate.inflate} strips design data. */
    @Test
    public void decodingWithoutATableDropsThem() {
        UIElement root = tree();
        DocumentExtras<JsonElement> extras = new DocumentExtras<>();
        JsonObject design = new JsonObject();
        design.add("state", new JsonObject());
        extras.put(root, DocumentExtras.DESIGN, design);
        JsonObject described = document().describe(root, extras).getAsJsonObject();

        UIElement inflated = document().decode(described);
        DocumentExtras<JsonElement> nothing = new DocumentExtras<>();

        assertTrue(described.has("design"));
        assertNull(nothing.get(inflated, DocumentExtras.DESIGN));
        assertNotEquals(described, wire().describe(inflated));
    }

    @Test
    public void theWireDialectRefusesExtras() {
        try {
            wire().describe(tree(), new DocumentExtras<>());
            assertTrue("the wire dialect must refuse document data", false);
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Keys.DOCUMENT"));
        }
    }
}
