package com.crystalgui.template;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.crystalgui.net.mirror.UIElementMirror;
import com.crystalgui.text.SimilarNames;
import com.crystalgui.ui.contract.State;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.NodeContract;
import com.crystalgui.ui.dom.UIElementRegistry;

/**
 * What a document is checked for before it is allowed to inflate — the structural half, read off the
 * JSON with nothing built.
 *
 * <p>Every refusal names the document and the node path, because "unknown kind" three levels into a tree
 * is unfindable otherwise. Run once at parse; the value half (a {@code StateType} refusing a value, a
 * slot that does not exist, an override id nobody has) can only be known while inflating and is reported
 * there.</p>
 */
final class TemplateValidation {

    private TemplateValidation() {
    }

    private static final UIElementMirror.Keys KEYS = UIElementMirror.Keys.DOCUMENT;

    static void check(UiTemplate template) {
        UIElementRegistry.bootstrap();
        for (UiTemplate.Node node : template.nodes()) {
            Name kind = kindOf(template, node);
            if (TemplateInstance.NAME.equals(kind)) continue;
            checkDeclaredState(template, node, kind);
        }
    }

    /** The kind this node names, refused with the nearest registered spelling when there is none. */
    private static Name kindOf(UiTemplate template, UiTemplate.Node node) {
        JsonElement declared = node.json().get(KEYS.name());
        if (declared == null || !declared.isJsonPrimitive()) {
            throw new UiTemplateException(template.origin(), node.path(), "a node names its kind");
        }
        Name kind;
        try {
            kind = Name.parse(declared.getAsString());
        } catch (RuntimeException bad) {
            throw new UiTemplateException(template.origin(), node.path(),
                    "\"" + declared.getAsString() + "\" is not a kind name", bad);
        }
        if (UIElementRegistry.isRegistered(kind)) return kind;
        throw new UiTemplateException(template.origin(), node.path(),
                "nothing is registered as <" + kind + ">" + didYouMean(kind));
    }

    private static String didYouMean(Name kind) {
        Set<String> registered = new LinkedHashSet<>();
        for (Name name : UIElementRegistry.names()) registered.add(name.toString());
        List<String> ranked = SimilarNames.rank(kind.toString(), registered);
        if (ranked.isEmpty()) return "";
        // Also try the bare local name, since a document may drop the engine's own namespace.
        return " -- did you mean <" + ranked.get(0) + ">?";
    }

    /**
     * Every state key the node sets must be one the widget's contract declares.
     *
     * <p>Otherwise it is dropped in silence: the mirror applies what the contract knows and ignores the
     * rest, which is right for a newer peer on a wire and wrong for a file somebody is writing.</p>
     */
    private static void checkDeclaredState(UiTemplate template, UiTemplate.Node node, Name kind) {
        JsonElement state = node.json().get(KEYS.state());
        if (state == null || !state.isJsonObject()) return;
        NodeContract contract = UIElementRegistry.contractFor(kind);
        List<String> declared = new ArrayList<>();
        if (contract instanceof WidgetContract<?> widget) {
            for (State<?, ?> slot : widget.states()) declared.add(slot.key());
        }
        for (Map.Entry<String, JsonElement> entry : state.getAsJsonObject().entrySet()) {
            if (declared.contains(entry.getKey())) continue;
            throw new UiTemplateException(template.origin(), node.path(), "<" + kind + "> declares no "
                    + "state called \"" + entry.getKey() + "\"" + (declared.isEmpty()
                            ? " -- it carries none at all" : " -- it carries " + declared));
        }
    }

    /** A node's declared kind, for a caller that has already been through {@link #check}. */
    static Name kindOf(JsonObject node) {
        JsonElement declared = node.get(KEYS.name());
        return declared == null ? null : Name.parse(declared.getAsString());
    }
}
