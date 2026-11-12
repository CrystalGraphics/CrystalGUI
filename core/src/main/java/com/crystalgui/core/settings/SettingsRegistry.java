package com.crystalgui.core.settings;

import com.crystalgui.core.async.Contributions;
import com.crystalgui.core.async.Owner;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * id → {@link Setting}. What a generated preferences panel enumerates.
 *
 * <p>Ported from VS Code's {@code IConfigurationRegistry}
 * ({@code src/vs/platform/configuration/common/configurationRegistry.ts}), MIT.</p>
 *
 * <h3>Shaped like {@code Commands}, deliberately</h3>
 * <p>Insertion-ordered so {@link #all} is stable — a panel wants that and a test needs it — and a
 * re-registration <b>replaces and logs</b> rather than being rejected, because that is how a theme or a
 * mod overrides a built-in declaration. Both decisions are copied from {@code Commands} rather
 * than re-argued, since the two registries answer the same question about the same kind of thing.</p>
 *
 * <p>The one divergence: this is not per-{@code UIWindow}. A <b>declaration</b> is a fact about the code
 * — {@code "editor.fontSize"} is a number defaulting to 14 in every window there will ever be — so
 * declarations are global while <em>values</em> are per-scope. That split is the same one
 * {@code StylePropertyRegistry} makes against {@code ElementStyle}, and it is what stops two windows
 * from disagreeing about what a key means while still letting them disagree about its value.</p>
 */
public final class SettingsRegistry {

    private static final SettingsRegistry INSTANCE = new SettingsRegistry();

    /** The declarations. Global, because a declaration is a fact about the code — see the class note. */
    public static SettingsRegistry get() {
        return INSTANCE;
    }

    private final Contributions<Setting<?>> declared = Contributions.of("settings", Setting::getId);
    private final Contributions.Index<Map<String, Setting<?>>> byId = declared.index(entries -> {
        Map<String, Setting<?>> map = new LinkedHashMap<>();
        for (Contributions.Entry<Setting<?>> entry : entries) map.put(entry.item().getId(), entry.item());
        return Collections.unmodifiableMap(map);
    });

    /** Registers {@code setting}, replacing and logging any previous declaration of the same id. */
    public <T> Setting<T> register(Setting<T> setting) {
        declared.add(Owner.PROCESS, setting);
        return setting;
    }

    @Nullable
    public Setting<?> get(String id) {
        return byId.get().get(id);
    }

    public boolean contains(String id) {
        return byId.get().containsKey(id);
    }

    public Collection<Setting<?>> all() {
        return byId.get().values();
    }

    /**
     * Every declaration under {@code section} — {@code "editor"} yields {@code "editor.fontSize"} and
     * friends, in declaration order.
     *
     * <p>What a preferences page for one area is built from. The dot is required, so {@code "editor"}
     * does not also claim {@code "editorial.mode"} — same rule {@link SettingsChange#affects} follows.</p>
     */
    public List<Setting<?>> section(String section) {
        List<Setting<?>> found = new ArrayList<>();
        String prefix = section + ".";
        for (Setting<?> setting : byId.get().values()) {
            if (setting.getId().equals(section) || setting.getId().startsWith(prefix)) found.add(setting);
        }
        return found;
    }

    public SettingsRegistry unregister(String id) {
        Setting<?> setting = byId.get().get(id);
        if (setting != null) declared.remove(setting);
        return this;
    }
}
