package com.crystalgui.ui.elements.dock;

import com.crystalgui.core.notify.Notification;

import java.util.ArrayList;
import java.util.List;

/**
 * Every {@link DockBannerProvider} anything has contributed.
 *
 * <p>Global and explicit, for the reasons {@code CommandRegistry} and {@code InspectorRegistry} are:
 * what can describe a panel is a fact about the application rather than about a window, and nothing
 * self-registers.</p>
 *
 * <p><b>Every provider that answers gets a banner</b>, rather than the first — a read-only file that is
 * also generated has two things to say, and picking one arbitrarily loses the other. They stack in
 * registration order, which is the only order available and is not worth an {@code order()} until
 * something has two.</p>
 */
public final class DockBanners {

    private DockBanners() {
    }

    private static final List<DockBannerProvider> PROVIDERS = new ArrayList<>();

    /** Idempotent per instance, so a contribution that runs twice does not double every banner. */
    public static void register(DockBannerProvider provider) {
        if (provider == null || PROVIDERS.contains(provider)) return;
        PROVIDERS.add(provider);
    }

    /** What every provider had to say about {@code panel}. Empty is the common answer. */
    public static List<Notification> bannersFor(DockPanelRef panel) {
        if (PROVIDERS.isEmpty() || panel == null) return List.of();
        List<Notification> found = new ArrayList<>();
        for (DockBannerProvider provider : PROVIDERS) {
            Notification banner = provider.bannerFor(panel);
            if (banner != null) found.add(banner);
        }
        return found;
    }

    /** Empties the registry. For tests that need isolation, never for production. */
    public static void resetForTesting() {
        PROVIDERS.clear();
    }
}
