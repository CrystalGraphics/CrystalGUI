package com.crystalgui.screen;

import com.crystalgraphics.job.CgHostThread;
import com.crystalgraphics.mc.container.CgContainers;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.ui.dom.UIDocument;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * A game screen's view of the player's open container: the game's menu read on the host's thread, handed to the
 * document when it changes, and clicks sent back through the game's own call.
 *
 * <pre>{@code
 * ScreenKind.of("mymod:chest", screen -> {
 *     ContainerView view = ContainerView.of(screen);
 *     view.onChanged.connect(menu -> {                              // on the document
 *         if (menu == null || menu.isPlayerInventory()) { screen.close(); return; }
 *         for (CgContainers.Slot slot : menu.slots()) show(slot.index(), slot.stack());
 *     });
 *     slotElement.onMouseDown.subscribe((el, e) -> view.click(slotIndex, e.getButtonId(), CgContainers.Click.PICKUP), false, true);
 * });
 * }</pre>
 *
 * <ul>
 *   <li>{@link #onChanged} fires on the document with the menu as it now stands, and with {@code null} when there is no
 *       player. It fires once on the first frame, and again only when a slot, the carried stack or a progress value
 *       differs.</li>
 *   <li>The game is read at most every 50 ms: a snapshot allocates, and the server's answer to a click takes longer.</li>
 *   <li>{@link #click} may be called from any thread, with the slot's index in the menu and the mouse button; it is made
 *       on the client thread against the menu that was last shown, so a click on a menu that has since closed is
 *       dropped by the game.</li>
 * </ul>
 */
public final class ContainerView {

    private static final long PERIOD_NANOS = 50_000_000L;

    /** The menu as it now stands, or null with no player. On the document. */
    public final Signal.Value<CgContainers.Menu> onChanged = new Signal.Value<>();

    private final UIDocument document;
    @Nullable
    private volatile CgContainers.Menu menu;
    // The host thread's.
    @Nullable
    private CgContainers.Menu read;
    private boolean readOnce;
    private long readAt;

    private ContainerView(UIDocument document) {
        this.document = document;
    }

    /** Opens the view on {@code screen}: read every host frame while it is up, dropped with it. On the document. */
    public static ContainerView of(ScreenContext screen) {
        ContainerView view = new ContainerView(screen.document());
        screen.onHostFrame(view::poll);
        return view;
    }

    /** The menu as the document last saw it; null with none. */
    @Nullable
    public CgContainers.Menu menu() {
        return menu;
    }

    /** Clicks {@code slot} of the menu shown, with mouse {@code button}, through the game's own call. Any thread. */
    public void click(int slot, int button, CgContainers.Click type) {
        CgContainers.Menu shown = menu;
        if (shown == null) return;
        int id = shown.id();
        CgHostThread.CLIENT.run(() -> CgContainers.click(id, slot, button, type));
    }

    /** Closes the open container as the player does, telling the server. Any thread. */
    public void close() {
        CgHostThread.CLIENT.run(CgContainers::close);
    }

    /** Host thread. */
    private void poll() {
        long now = System.nanoTime();
        if (readOnce && now - readAt < PERIOD_NANOS) return;
        readAt = now;
        CgContainers.Menu seen = CgContainers.menu();
        if (readOnce && Objects.equals(seen, read)) return;
        readOnce = true;
        read = seen;
        document.execute(() -> {
            menu = seen;
            onChanged.emit(seen);
        });
    }
}
