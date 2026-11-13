package com.crystalgui.screen;

import com.crystalgui.desktop.DesktopWindowMount;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Where the windows a server asks to show as screens ({@code Presentation.SCREEN}) go: a {@link ScreenKind} each, built
 * from what the mount gives, shown through {@link CgUiScreen#show}.
 *
 * <ul>
 *   <li>The screen has the kind's defaults: dimmed, Escape closes, the game keeps running.</li>
 *   <li>Closed by the player or replaced by another screen, it reports back; closed by the server, it does not.</li>
 *   <li>A close that arrives before the screen has been built stops the build.</li>
 * </ul>
 */
final class ServerScreens implements DesktopWindowMount.Screens {

    @Override
    public Shown show(String name, BiConsumer<UIDocument, UIElement> build, Runnable closedByUser) {
        // Whoever ends it first reports it or not: the server's close must never come back as the player's.
        AtomicBoolean ended = new AtomicBoolean();
        ScreenKind kind = ScreenKind.of("server:" + name, screen -> {
            if (ended.get()) {
                screen.close();
                return;
            }
            screen.onClosed(() -> {
                if (ended.compareAndSet(false, true)) closedByUser.run();
            });
            build.accept(screen.document(), screen.root());
        });
        CgUiScreen.show(kind);
        return () -> {
            if (ended.compareAndSet(false, true)) CgUiScreen.closeScreen(kind);
        };
    }
}
