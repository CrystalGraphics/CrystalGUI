package com.crystalgui.app;

import com.crystalgui.app.crystaleditor.CrystalEditor;
import com.crystalgui.desktop.app.ApplicationKinds;
import com.crystalgui.desktop.app.ApplicationRegistry;

/**
 * <b>The products this jar offers a desktop</b> - the application layer's {@link ApplicationKinds}
 * service.
 *
 * <p>Found on the classpath and run once per desktop, so shipping the jar is what makes Crystal Editor
 * appear in a launcher. No host names a product, which means a fourth host cannot forget to - and a
 * mod's own application arrives through the same door rather than needing a line in code it does not
 * own.</p>
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
