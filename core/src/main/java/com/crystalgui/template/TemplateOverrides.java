package com.crystalgui.template;

import java.util.Map;

import com.google.gson.JsonObject;

import com.crystalgui.serialization.JsonOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.contract.WidgetContracts;
import com.crystalgui.ui.dom.UIElement;

/** Applies per-id state to an inflated tree — what an instance's {@code overrides} block means. */
final class TemplateOverrides {

    private TemplateOverrides() {
    }

    static void apply(UiTemplate template, UIElement tree, Map<String, Map<String, Object>> byId) {
        if (byId == null || byId.isEmpty()) return;
        for (Map.Entry<String, Map<String, Object>> entry : byId.entrySet()) {
            UIElement target = "".equals(entry.getKey()) ? tree : tree.getElementById(entry.getKey());
            if (target == null) {
                throw new UiTemplateException(template.origin(), null,
                        "an override names the id \"" + entry.getKey() + "\", which this document has not got");
            }
            applyState(template, target, TemplateValues.mapToJson(entry.getValue()));
        }
    }

    static void applyState(UiTemplate template, UIElement target, JsonObject state) {
        WidgetContract<UIElement> contract = WidgetContracts.of(target);
        if (contract == null || !contract.carriesState()) {
            throw new UiTemplateException(template.origin(), null,
                    "<" + target.tagName() + "> carries no state, so there is nothing to override on it");
        }
        contract.read(target, new StateMap<>(JsonOps.INSTANCE, state));
    }
}
