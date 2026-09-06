package com.crystalgui.headless;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import com.crystalgui.app.WidgetCensus;
import com.crystalgui.net.mirror.UIElementMirror;
import com.crystalgui.serialization.ContentHash;
import com.crystalgui.serialization.JsonOps;
import com.crystalgui.template.TemplateInstance;
import com.crystalgui.template.UiTemplate;
import com.crystalgui.template.UiTemplateException;
import com.crystalgui.template.UiTemplates;
import com.crystalgui.ui.contract.WidgetContracts;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.widget.text.UIText;

/**
 * <b>A document parses and inflates with no display anywhere.</b> This source set has no CrystalGraphics
 * core on it, so a template that reached a font, a sheet or a texture would fail here rather than on a
 * dedicated server — which is exactly what a server inflating one into a {@code Networked} panel does.
 *
 * <p>Reading a document <em>by id</em> goes through {@code CgIO} and is tested in {@code test};
 * {@link UiTemplates#parse} is the half a server uses, because whoever owns the file has the bytes
 * already.</p>
 *
 * <p>The round trip is the other claim: every kind that travels, described, decoded and described again,
 * byte-identical. A document is content-addressed, so a kind that does not survive its own encoding
 * would give one tree two hashes.</p>
 */
public class UiTemplateRoundTripTest {

    /** The fixture as a string, since this source set cannot read one. */
    private static final String SAMPLE = "{"
            + "\"cgui\": 1,"
            + "\"stylesheets\": [\"cguitest:sample\"],"
            + "\"model\": \"com.example.SampleModel\","
            + "\"package\": \"com.example.ui\","
            + "\"preview\": { \"sizes\": [[800, 480]], \"uiScale\": 2 },"
            + "\"root\": { \"kind\": \"element\", \"id\": \"root\", \"class\": \"sample\","
            + "  \"children\": ["
            + "    { \"kind\": \"text\", \"id\": \"title\", \"class\": \"heading\","
            + "      \"state\": { \"text\": \"Status\" } },"
            + "    { \"kind\": \"text\", \"id\": \"subject\","
            + "      \"bind\": { \"text\": \"subject\" },"
            + "      \"design\": { \"state\": { \"text\": \"- Dev\" } } }"
            + "  ] } }";

    @After
    public void forgetTemplates() {
        UiTemplates.reloadAll();
    }

    private static UiTemplate sample() {
        return UiTemplates.parse(SAMPLE, "cguitest:ui/sample");
    }

    private static UIElementMirror<JsonElement> document() {
        return new UIElementMirror<>(JsonOps.INSTANCE, UIElementMirror.Keys.DOCUMENT);
    }

    /**
     * Every kind a document may name: buildable, not the root, and not local-only.
     *
     * <p><b>Local-only is the engine's own answer</b> to "does this travel", declared with a reason in
     * {@code WidgetCensus} — a dock group, a taskbar, a text editor and the shader graph are shell
     * machinery, not content, and a {@code .cgui} has no business naming one. Asking the census rather
     * than keeping a list here is what stops this test disagreeing with the codec.</p>
     */
    private static List<Name> buildableKinds() {
        UIElementRegistry.bootstrap();
        WidgetCensus.register();
        List<Name> kinds = new ArrayList<>();
        for (Name kind : UIElementRegistry.names()) {
            if (!UIElementRegistry.isBuildable(kind)) continue;
            // A DOCUMENT IS A ROOT and refuses to be a child, which is right and is not a round-trip
            // failure. It is also the one kind a .cgui can never name.
            if (kind.equals(UIDocument.NAME)) continue;
            // An INSTANCE builds itself from the template it names, and names none here.
            if (kind.equals(TemplateInstance.NAME)) continue;
            if (WidgetContracts.isLocalOnly(UIElementRegistry.create(kind).getClass())) continue;
            kinds.add(kind);
        }
        kinds.sort((a, b) -> a.toString().compareTo(b.toString()));
        return kinds;
    }

    private static String oneOf(Name kind) {
        JsonArray children = new JsonArray();
        JsonObject child = new JsonObject();
        child.add("kind", new JsonPrimitive(kind.toString()));
        children.add(child);
        return documentOf(children);
    }

    private static String everyKind() {
        JsonArray children = new JsonArray();
        for (Name kind : buildableKinds()) {
            JsonObject child = new JsonObject();
            child.add("kind", new JsonPrimitive(kind.toString()));
            children.add(child);
        }
        return documentOf(children);
    }

    private static String documentOf(JsonArray children) {
        JsonObject root = new JsonObject();
        root.add("kind", new JsonPrimitive("element"));
        root.add("children", children);
        JsonObject document = new JsonObject();
        document.add("cgui", new JsonPrimitive(1));
        document.add("root", root);
        return document.toString();
    }

    @Test
    public void everyShippedKindSurvivesTheRoundTrip() {
        // ONE DOCUMENT PER KIND, so a failure names the widget rather than handing over a diff of the
        // whole registry -- and all of them collected, because every one is the same one-line omission
        // and finding them a run at a time is what makes a sweep like this take a day.
        List<String> offenders = new ArrayList<>();
        for (Name kind : buildableKinds()) {
            UiTemplate template = UiTemplates.parse(oneOf(kind), "test:" + kind.local());
            JsonElement once = document().describe(template.inflate());
            JsonElement twice = document().describe(document().decode(once));
            if (!once.toString().equals(twice.toString())) offenders.add(kind.toString());
        }
        assertEquals("kinds that do not survive their own encoding -- each needs describedChildren(): "
                + offenders, List.of(), offenders);
    }

    /** And all of them together, which is what a real document with many widgets is. */
    @Test
    public void aDocumentOfEveryKindInflates() {
        UiTemplate template = UiTemplates.parse(everyKind(), "test:every-kind");

        assertEquals(buildableKinds().size(), template.inflate().children().size());
    }

    @Test
    public void aDocumentInflatesToATree() {
        UIElement root = sample().inflate();

        assertEquals("root", root.id());
        assertTrue(root.hasClass("sample"));
        assertEquals(2, root.children().size());
        assertNotNull(root.getElementById("title"));
    }

    @Test
    public void theHeaderIsRead() {
        UiTemplate template = sample();

        assertEquals(1, template.formatVersion());
        assertEquals(List.of("cguitest:sample"), template.stylesheets());
        assertEquals("com.example.SampleModel", template.modelClass());
        assertEquals("com.example.ui", template.packageName());
        assertNull(template.kindName());
        assertNotNull(template.preview());
    }

    /** Design values are the builder's; a tree a player sees has none of them. */
    @Test
    public void inflateStripsDesignAndBindings() {
        UiTemplate template = sample();
        JsonElement described = document().describe(template.inflate());

        assertTrue(template.root().toString().contains("design"));
        assertTrue("nothing design-time reaches the tree", !described.toString().contains("Dev"));
        assertTrue(!described.toString().contains("bind"));
    }

    @Test
    public void inflatingTwiceGivesTwoTrees() {
        UiTemplate template = sample();

        UIElement first = template.inflate();
        UIElement second = template.inflate();

        assertTrue(first != second);
        assertTrue(first.getElementById("title") != second.getElementById("title"));
    }

    /** The hash the wire would send for a window built from this document. */
    @Test
    public void aTemplateHashesLikeTheTreeItBuilds() {
        UiTemplate template = sample();

        assertEquals(ContentHash.of(JsonOps.INSTANCE,
                        new UIElementMirror<JsonElement>(JsonOps.INSTANCE).describe(template.inflate())),
                template.contentHash());
    }

    @Test
    public void overridesAreAppliedById() {
        UIElement root = sample().inflate(Map.of("title", Map.of("text", "Sheet")));

        assertEquals("Sheet", ((UIText) root.getElementById("title")).getText());
    }

    @Test
    public void anIdIsNamespaceAndPath() {
        assertEquals("/assets/mymod/ui/status.cgui", UiTemplates.pathOf("mymod:ui/status"));
        try {
            UiTemplates.pathOf("no-namespace");
            assertTrue("an id without a namespace must be refused", false);
        } catch (UiTemplateException expected) {
            assertTrue(expected.getMessage().contains("namespace:path"));
        }
    }

    @Test
    public void aNewerFormatIsRefused() {
        try {
            UiTemplates.parse("{\"cgui\": 99, \"root\": {\"kind\": \"element\"}}", "test:future");
            assertTrue("a document from the future must be refused", false);
        } catch (UiTemplateException expected) {
            assertTrue(expected.getMessage().contains("99"));
            assertEquals("test:future", expected.document());
        }
    }

    @Test
    public void aDocumentWithoutARootIsRefused() {
        try {
            UiTemplates.parse("{\"cgui\": 1}", "test:rootless");
            assertTrue("a document without a root must be refused", false);
        } catch (UiTemplateException expected) {
            assertTrue(expected.getMessage().contains("root"));
        }
    }

    @Test
    public void nonsenseIsRefusedByName() {
        try {
            UiTemplates.parse("not json at all", "test:garbage");
            assertTrue("malformed JSON must be refused", false);
        } catch (UiTemplateException expected) {
            assertTrue(expected.getMessage().startsWith("test:garbage"));
        }
    }
}
