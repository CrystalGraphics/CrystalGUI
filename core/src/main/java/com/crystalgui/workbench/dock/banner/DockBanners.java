package com.crystalgui.workbench.dock.banner;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.notify.Notification;

import com.crystalgui.widget.config.inspector.InspectorRegistry;
import com.crystalgui.workbench.dock.layout.DockPanelRef;
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
            Notification banner;
            try {
                banner = provider.bannerFor(panel);
            } catch (RuntimeException failed) {
                // A PROVIDER IS CONTRIBUTED CODE and this runs inside the dock's panel build, so an
                // exception here does not cost its own banner -- it costs the PANEL, and every other
                // panel in the same rebuild with it. A workbench where nothing opens because something
                // wanted to put a message over one tab is the wrong trade in every direction.
                CrystalGuiCore.LOGGER.error("A dock banner provider failed for {}: {}",
                        panel, failed.getMessage(), failed);
                continue;
            }
            if (banner != null) found.add(banner);
        }
        return found;
    }

    /** Empties the registry. For tests that need isolation, never for production. */
    public static void resetForTesting() {
        PROVIDERS.clear();
    }
}
