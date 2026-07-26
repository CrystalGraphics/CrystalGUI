package com.crystalgui.serialization;

import com.crystalgui.ui.UIElement;
import com.google.gson.JsonElement;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class UIElementCodecTest {

    private static UIElement buildSampleTree() {
        UIElement root = new UIElement();
        root.setId("root");
        root.addClass("container");
        root.addClass("row");

        UIElement childA = new UIElement();
        childA.setId("a");
        childA.addClass("item");
        root.addChild(childA);

        UIElement childB = new UIElement();
        childB.addClass("item");
        childB.addClass("highlighted");
        root.addChild(childB);

        UIElement grandchild = new UIElement();
        grandchild.setId("nested");
        childA.addChild(grandchild);

        return root;
    }

    private static void assertTreesStructurallyEqual(UIElement expected, UIElement actual) {
        assertEquals(expected.getId(), actual.getId());
        assertEquals(expected.getClasses(), actual.getClasses());
        assertEquals(expected.getChildren().size(), actual.getChildren().size());
        for (int i = 0; i < expected.getChildren().size(); i++) {
            assertTreesStructurallyEqual(expected.getChildren().get(i), actual.getChildren().get(i));
        }
    }

    @Test
    public void roundTripsThroughJsonOps() {
        UIElement original = buildSampleTree();
        JsonElement encoded = UIElementCodec.CODEC.encode(JsonOps.INSTANCE, original);
        UIElement decoded = UIElementCodec.CODEC.decode(JsonOps.INSTANCE, encoded);
        assertTreesStructurallyEqual(original, decoded);
    }

    /** Deliberately trivial second {@link DynamicOps} implementation, backed by plain
     * {@code Map<String,Object>}/{@code List<Object>} instead of Gson's {@code JsonElement} —
     * exercises the EXACT SAME {@link UIElementCodec#CODEC} against a completely different tree
     * representation, with zero changes to the codec itself. This is the concrete proof of the
     * format-agnosticism claim: if the codec definitions secretly depended on JSON specifics, this
     * test would fail to compile or fail at runtime. */
    private static final class PlainMapOps implements DynamicOps<Object> {
        static final PlainMapOps INSTANCE = new PlainMapOps();

        @Override
        public Object empty() {
            return null;
        }

        @Override
        public Object createString(String value) {
            return value;
        }

        @Override
        public Object createNumber(Number value) {
            return value;
        }

        @Override
        public Object createBoolean(boolean value) {
            return value;
        }

        @Override
        public Object createList(List<Object> values) {
            return new ArrayList<>(values);
        }

        @Override
        public Object createMap(Map<Object, Object> entries) {
            return new LinkedHashMap<>(entries);
        }

        @Override
        public String getStringValue(Object value) {
            if (!(value instanceof String)) throw new CodecException("Not a string: " + value);
            return (String) value;
        }

        @Override
        public Number getNumberValue(Object value) {
            if (!(value instanceof Number)) throw new CodecException("Not a number: " + value);
            return (Number) value;
        }

        @Override
        public boolean getBooleanValue(Object value) {
            if (!(value instanceof Boolean)) throw new CodecException("Not a boolean: " + value);
            return (Boolean) value;
        }

        @Override
        @SuppressWarnings("unchecked")
        public List<Object> getListValue(Object value) {
            if (!(value instanceof List)) throw new CodecException("Not a list: " + value);
            return (List<Object>) value;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<Object, Object> getMapValue(Object value) {
            if (!(value instanceof Map)) throw new CodecException("Not a map: " + value);
            return (Map<Object, Object>) value;
        }
    }

    @Test
    public void sameCodecRoundTripsThroughACompletelyDifferentDynamicOps() {
        UIElement original = buildSampleTree();
        Object encoded = UIElementCodec.CODEC.encode(PlainMapOps.INSTANCE, original);
        UIElement decoded = UIElementCodec.CODEC.decode(PlainMapOps.INSTANCE, encoded);
        assertTreesStructurallyEqual(original, decoded);
    }
}
