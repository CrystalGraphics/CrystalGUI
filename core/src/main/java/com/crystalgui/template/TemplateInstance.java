package com.crystalgui.template;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.crystalgui.core.signal.Connection;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.ShadowRoot;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UISlot;

/**
 * One document placed inside another — {@code {"kind": "instance", "template": "mymod:ui/parts/plate"}}.
 *
 * <p>The placed template becomes this element's <b>shadow tree</b>, so its internals are unreachable
 * from the placing document's selectors except through {@code ::part(name)}, its own sheet is scoped to
 * it, and it travels as one node rather than as a copy of the whole template.</p>
 *
 * <pre>{@code
 * TemplateInstance plate = new TemplateInstance("mymod:ui/parts/plate");
 * plate.override("lamp", Map.of("lit", true));      // by internal id
 * plate.append(body);                               // lands in the template's default slot
 * }</pre>
 *
 * <p>Three things are easy to get wrong. Content a caller appends needs a matching {@code <slot>} in the
 * template or it is in no composed tree at all — no box, no paint, nothing reporting a problem. An
 * override names an id <em>inside</em> the template, not in the placing document. And a template that
 * places itself is refused rather than recursed.</p>
 */
public class TemplateInstance extends UIElement {

    public static final Name NAME = Name.of("instance");

    /** Which document is placed here. Setting it builds the shadow tree. */
    public static final Attribute<String> TEMPLATE = Attribute.of("template", String.class, "");

    /** Ids being inflated on this thread, so a template placing itself is caught rather than recursed. */
    private static final ThreadLocal<Deque<String>> INFLATING = ThreadLocal.withInitial(ArrayDeque::new);

    @Nullable
    private UiTemplate template;

    private final Map<String, Map<String, Object>> overrides = new LinkedHashMap<>();

    private final Map<String, Object> params = new LinkedHashMap<>();

    private boolean built;

    /** Held only while connected, so a detached instance is not kept alive by the signal. */
    @Nullable
    private Connection reloadWatch;

    /** Empty — the registry's factory. The template arrives as an attribute. */
    public TemplateInstance() {
        super(NAME);
    }

    public TemplateInstance(String templateId) {
        this();
        set(TEMPLATE, templateId);
    }

    /** The document placed here, or null while none is named. */
    @Nullable
    public UiTemplate template() {
        return template;
    }

    public String templateId() {
        return get(TEMPLATE);
    }

    /**
     * State to apply to one of the template's internal ids, after inflation.
     *
     * <p>Call before the instance is connected, or call {@link #rebuild} after.</p>
     */
    public TemplateInstance override(String internalId, Map<String, Object> state) {
        overrides.put(internalId, Map.copyOf(state));
        return this;
    }

    /** A value for one of the template's declared parameters. @see UiTemplate#params() */
    public TemplateInstance param(String name, Object value) {
        params.put(name, value);
        return this;
    }

    Map<String, Map<String, Object>> overrides() {
        return overrides;
    }

    Map<String, Object> params() {
        return params;
    }

    @Override
    public <T> UIElement set(Attribute<T> key, T value) {
        UIElement self = super.set(key, value);
        if (key == TEMPLATE) {
            built = false;
            rebuild();
        }
        return self;
    }

    /**
     * Re-inflates the placed template, re-slots this instance's children and re-applies the overrides.
     *
     * <p>What hot reload calls. Cheap to call twice: it does nothing when nothing has changed.</p>
     */
    public TemplateInstance rebuild() {
        String id = templateId();
        if (id == null || id.isEmpty()) return this;
        if (built && template != null && id.equals(template.origin())) return this;

        Deque<String> stack = INFLATING.get();
        if (stack.contains(id)) {
            throw new UiTemplateException(id, null,
                    "a template cannot place itself -- " + String.join(" -> ", stack) + " -> " + id);
        }
        stack.push(id);
        try {
            template = UiTemplates.load(id);
            ShadowRoot shadow = shadowRoot() != null ? shadowRoot() : attachShadow();
            shadow.removeAll();
            shadow.append(template.inflate(params, overrides));
            built = true;
        } finally {
            stack.pop();
        }
        return this;
    }

    /** The slot names this instance offers, from the placed template. */
    public List<String> slotNames() {
        ShadowRoot shadow = shadowRoot();
        if (shadow == null) return List.of();
        return shadow.slots().stream().map(UISlot::slotName).toList();
    }

    /**
     * Installs the placed template's own sheets, scoped to this instance.
     *
     * <p>Scoped, so a template's rules cannot leak into the page that placed it — the half of shadow
     * encapsulation the cascade has to be told about.</p>
     */
    @Override
    protected void connected() {
        super.connected();
        if (reloadWatch == null) {
            reloadWatch = UiTemplates.onDidReload.connect(() -> {
                built = false;
                rebuild();
                installSheets();
            });
        }
        installSheets();
    }

    /** Stops listening: a screen that closed must not rebuild on the next reload. */
    @Override
    protected void disconnected() {
        super.disconnected();
        if (reloadWatch != null) {
            reloadWatch.disconnect();
            reloadWatch = null;
        }
    }

    private void installSheets() {
        UIDocument window = document();
        if (window == null || template == null) return;
        ShadowRoot shadow = shadowRoot();
        for (String sheet : template.stylesheets()) TemplateSheets.install(window, sheet, shadow);
    }

    /** Reads {@code params} and {@code overrides} out of the document's own JSON. */
    void configureFrom(@Nullable JsonElement paramsJson, @Nullable JsonElement overridesJson) {
        if (paramsJson != null && paramsJson.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : paramsJson.getAsJsonObject().entrySet()) {
                params.put(entry.getKey(), entry.getValue());
            }
        }
        if (overridesJson != null && overridesJson.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : overridesJson.getAsJsonObject().entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject perId = entry.getValue().getAsJsonObject();
                JsonElement state = perId.get("state");
                Map<String, Object> values = new LinkedHashMap<>();
                if (state != null && state.isJsonObject()) {
                    for (Map.Entry<String, JsonElement> slot : state.getAsJsonObject().entrySet()) {
                        values.put(slot.getKey(), slot.getValue());
                    }
                }
                overrides.put(entry.getKey(), values);
            }
        }
        built = false;
        rebuild();
    }
}
