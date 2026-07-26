package com.crystalgui.serialization;

import com.crystalgui.ui.ElementRegistry;
import com.crystalgui.ui.UIElement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Structural (tag/id/classes/children) codec for {@link UIElement} trees.
 *
 * <p>Deliberately does NOT yet serialize style declarations or any runtime-only state
 * (hover/focus/pressed flags, layout cache, anything in {@code RuntimeCache}) — no
 * {@code StyleProperty} value currently supports serializing back to a string (the existing
 * machinery, {@code property.valueParser.parse(rawValue)}, is parse-only, one direction), so style
 * round-tripping is a known follow-up, not built here. Runtime-only state should never round-trip
 * regardless — it isn't part of an element's authored definition.</p>
 *
 * <p>{@code tag} round-trips through {@link ElementRegistry}: encode reads {@link UIElement#tagName()}
 * (already the same lowercased-simple-class-name CSS type selectors use); decode calls
 * {@link ElementRegistry#create(String)} if that tag is registered, otherwise falls back to a plain
 * {@code new UIElement()} — today {@link ElementRegistry} has nothing registered in it (no concrete
 * widget elements exist yet), so every decode currently produces a base {@link UIElement}, which is
 * correct and expected until real elements land.</p>
 */
public final class UIElementCodec {

    public static final Codec<UIElement> CODEC = new Codec<UIElement>() {
        @Override
        public <T> T encode(DynamicOps<T> ops, UIElement input) {
            Map<T, T> map = new LinkedHashMap<>();
            map.put(ops.createString("tag"), ops.createString(input.tagName()));
            map.put(ops.createString("id"), ops.createString(input.getId()));

            List<T> classes = new ArrayList<>();
            for (String cls : input.getClasses()) classes.add(ops.createString(cls));
            map.put(ops.createString("classes"), ops.createList(classes));

            List<T> children = new ArrayList<>();
            for (UIElement child : input.getChildren()) children.add(encode(ops, child));
            map.put(ops.createString("children"), ops.createList(children));

            return ops.createMap(map);
        }

        @Override
        public <T> UIElement decode(DynamicOps<T> ops, T input) {
            Map<T, T> map = ops.getMapValue(input);
            Map<String, T> byName = new LinkedHashMap<>();
            for (Map.Entry<T, T> entry : map.entrySet()) {
                byName.put(ops.getStringValue(entry.getKey()), entry.getValue());
            }

            String tag = byName.containsKey("tag") ? ops.getStringValue(byName.get("tag")) : null;
            UIElement element = (tag != null && ElementRegistry.isRegistered(tag))
                    ? ElementRegistry.create(tag)
                    : new UIElement();

            if (byName.containsKey("id")) {
                String id = ops.getStringValue(byName.get("id"));
                if (!id.isEmpty()) element.setId(id);
            }
            if (byName.containsKey("classes")) {
                for (T classElement : ops.getListValue(byName.get("classes"))) {
                    element.addClass(ops.getStringValue(classElement));
                }
            }
            if (byName.containsKey("children")) {
                for (T childElement : ops.getListValue(byName.get("children"))) {
                    element.addChild(decode(ops, childElement));
                }
            }
            return element;
        }
    };

    private UIElementCodec() {
    }
}
