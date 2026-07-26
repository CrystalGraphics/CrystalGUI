package com.crystalgui.ui;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Named factory lookup for {@link UIElement} subtypes — {@code ElementRegistry.create("button")}
 * builds a fresh instance of whatever type registered under that tag. Infrastructure only: nothing
 * populates this registry yet (no concrete widget elements exist in this codebase — the base
 * {@link UIElement} itself is the only always-available type, and it isn't auto-registered here
 * either, since callers that just want a plain element can already {@code new UIElement()} directly).
 *
 * <p>Deliberately manual registration, not annotation/classpath-scanning-driven — matches every
 * other registry in this codebase ({@code CgMaterialRegistry}, {@code CgMeshRegistry},
 * {@code CgFontRegistry}, {@code CgUiSpriteRegistry}, etc. all use this same static-singleton +
 * {@code Map} cache shape). Factory-based ({@link Supplier}), not reflection-based class
 * instantiation, so a registered element's constructor can take required arguments via a lambda
 * (e.g. {@code register("text", () -> new UIText(""))}) without the registry needing to know about
 * them.</p>
 *
 * <p>Primarily exists to let deserialization (see {@code com.crystalgui.serialization}) reconstruct
 * the correct concrete {@link UIElement} subtype from a saved tag name, without a giant
 * hand-written {@code switch} over every known element type.</p>
 */
public final class ElementRegistry {

    private static final Map<String, Supplier<UIElement>> FACTORIES = new ConcurrentHashMap<>();

    private ElementRegistry() {
    }

    /** Registers {@code factory} under {@code tag}. Throws if {@code tag} is already registered —
     * matches {@code CgMeshRegistry}'s existing duplicate-key convention: a silent overwrite would
     * hide a real bug (two elements fighting over the same tag) far more often than it would ever
     * be an intentional re-registration. */
    public static void register(String tag, Supplier<UIElement> factory) {
        if (FACTORIES.putIfAbsent(tag, factory) != null) {
            throw new IllegalArgumentException("Element tag already registered: " + tag);
        }
    }

    /** Builds a fresh {@link UIElement} instance for {@code tag}. Throws if nothing is registered
     * under that tag — never returns {@code null}, matching this codebase's fail-fast convention. */
    public static UIElement create(String tag) {
        Supplier<UIElement> factory = FACTORIES.get(tag);
        if (factory == null) {
            throw new IllegalArgumentException("Unknown element tag: " + tag);
        }
        return factory.get();
    }

    public static boolean isRegistered(String tag) {
        return FACTORIES.containsKey(tag);
    }
}
