package com.crystalgui.ui.elements.dock;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code typeId} → what it is, and how to build one.
 *
 * <p>This is the seam every docking system has and calls something different: VS Code's
 * {@code IViewDeserializer.fromJSON}, Golden Layout's {@code componentType} lookup, ImGui's window name.
 * The layout stores an id; the registry turns it back into a thing.</p>
 *
 * <p>It is also {@link DockLayoutCodec}'s degradation point. A saved layout naming a panel type nobody
 * registers any more — a mod was uninstalled — must lose that leaf and keep the rest, never the reverse.
 * Refusing the whole restore because one panel is missing throws away the user's entire arrangement over
 * somebody else's uninstall.</p>
 *
 * <h3>Generic in what a factory builds</h3>
 *
 * <p>{@code C} is the content type — {@code UIElement} for the widget layer, anything at all for a
 * headless test. Keeping it a type parameter rather than hardcoding {@code UIElement} is what lets the
 * whole layout half of this package stay free of the widget half, which is the same boundary
 * {@link DockLayout} is drawn on.</p>
 */
public final class DockPanelRegistry<C> {

    /** Builds the content for one panel instance. */
    @FunctionalInterface
    public interface Factory<C> {
        C create(DockPanelRef ref);
    }

    private final Map<String, DockPanelDescriptor> descriptors = new LinkedHashMap<>();
    private final Map<String, Factory<C>> factories = new LinkedHashMap<>();

    public DockPanelRegistry<C> register(DockPanelDescriptor descriptor, Factory<C> factory) {
        descriptors.put(descriptor.typeId(), descriptor);
        factories.put(descriptor.typeId(), factory);
        return this;
    }

    /**
     * Registers a type the layout may reference but that nothing can build yet.
     *
     * <p>Useful while a panel is being written, and honest about it: {@link #create} returns {@code null}
     * rather than a placeholder that looks like a working panel.</p>
     */
    public DockPanelRegistry<C> declare(DockPanelDescriptor descriptor) {
        descriptors.put(descriptor.typeId(), descriptor);
        return this;
    }

    public boolean isRegistered(String typeId) {
        return descriptors.containsKey(typeId);
    }

    public DockPanelDescriptor descriptor(String typeId) {
        return descriptors.get(typeId);
    }

    public Collection<DockPanelDescriptor> descriptors() {
        return descriptors.values();
    }

    /** The content for one panel, or {@code null} when the type is unknown or has no factory. */
    public C create(DockPanelRef ref) {
        Factory<C> factory = factories.get(ref.typeId());
        return factory == null ? null : factory.create(ref);
    }

    /** The tab label for a panel: its own {@code title} state if it carries one, else the type's. */
    public String titleOf(DockPanelRef ref) {
        DockPanelDescriptor descriptor = descriptors.get(ref.typeId());
        String fallback = descriptor != null ? descriptor.title() : ref.typeId();
        return ref.state(DockPanelRef.TITLE, fallback);
    }
}
