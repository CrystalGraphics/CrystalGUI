package com.crystalgui.app;

import com.crystalgui.app.crystaleditor.CrystalEditor;
import com.crystalgui.desktop.app.ApplicationKinds;
import com.crystalgui.desktop.app.ApplicationRegistry;

/**
 * <b>The application layer's products</b> — what this jar offers a desktop.
 *
 * <p>Its own service rather than a call a host makes, for the reason {@link ApplicationKinds} exists:
 * an application a host installs is installed only on the hosts that remembered to. The 1.7.10 screen
 * and two harness scenes each named {@code CrystalEditor} and each would have had to name the next one
 * — while a mod's own application could never be in that list at all, because the list is in code it
 * does not own.</p>
 *
 * <p>Beside {@link AppKinds}, which does the same job for this layer's element tags. Two services
 * because they answer different questions at different moments: a tag is needed the instant a
 * description is decoded, a manifest the instant a launcher is drawn.</p>
 */
public final class Applications implements ApplicationKinds {

    /** {@code ServiceLoader} needs a public no-argument constructor. */
    public Applications() {
    }

    @Override
    public void register(ApplicationRegistry applications) {
        applications.install(CrystalEditor.KIND);
    }
}
