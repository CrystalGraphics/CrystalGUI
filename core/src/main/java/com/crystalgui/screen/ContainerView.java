package com.crystalgui.screen;

import com.crystalgraphics.job.CgHostThread;
import com.crystalgraphics.mc.container.Action;
import com.crystalgraphics.mc.container.CgContainers;
import com.crystalgraphics.mc.container.ContainerMirror;
import com.crystalgraphics.mc.container.SlotKind;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.ui.dom.UIDocument;

import javax.annotation.Nullable;

/**
 * A game screen's view of the player's open container: the game's menu mirrored on the host's thread, each change
 * handed to the document as a delta, and the player's actions sent back through the game's own call.
 *
 * <pre>{@code
 * ScreenKind.of("mymod:chest", screen -> {
 *     ContainerView view = ContainerView.of(screen);
 *     view.onUpdate.connect(update -> {                                // on the document
 *         if (update.closed()) { screen.close(); return; }
 *         for (int i : update.slots()) show(i, update.menu().slot(i).stack());   // only what changed
 *     });
 *     ContainerInteraction input = view.interaction();                 // vanilla's gestures: press, move, release, keys
 *     slotElement.onMouseDown.subscribe((el, e) -> input.press(slotIndex, e.getButtonId(), shift), false, true);
 * });
 * }</pre>
 *
 * <ul>
 *   <li>{@link #onUpdate} fires on the document with a {@link ContainerMirror.Update}: the first one has every slot, the
 *       next only the slots, cursor or state that differ; {@code closed()} when the player has no menu. Nothing fires
 *       while nothing changed.</li>
 *   <li>The game is asked every 20 ms for its slots' hashes; only a slot whose hash moved is copied.</li>
 *   <li>{@link #act} may be called from any thread: it is made on the client thread against the menu last shown, so an
 *       action on a menu that has since closed is dropped by the game.</li>
 * </ul>
 */
public final class ContainerView {

    private static final long PERIOD_NANOS = 20_000_000L;

    /** Each change of the menu, on the document. */
    public final Signal.Value<ContainerMirror.Update> onUpdate = new Signal.Value<>();

    private final UIDocument document;
    private final ContainerInteraction interaction;
    @Nullable
    private volatile CgContainers.Menu menu;
    // The host thread's.
    private ContainerMirror mirror;
    private long readAt;
    private boolean readOnce;

    private ContainerView(UIDocument document) {
        this.document = document;
        this.interaction = new ContainerInteraction(this::menu, this::mayPlace, click -> act(click), System::currentTimeMillis);
    }

    /** Opens the view on {@code screen}: polled every host frame while it is up, dropped with it. On the document. */
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

    /** This screen's mouse and key gestures, turned into actions on this view. On the document. */
    public ContainerInteraction interaction() {
        return interaction;
    }

    /** Sends {@code action} against the menu shown, through the game's own call. Any thread. */
    public void act(Action action) {
        CgContainers.Menu shown = menu;
        if (shown == null) return;
        int id = shown.id();
        CgHostThread.CLIENT.run(() -> CgContainers.act(id, action));
    }

    /** Closes the open container as the player does, telling the server. Any thread. */
    public void close() {
        act(new Action.Close());
    }

    /** What the document can tell of a drag target: a result takes nothing, the server settles the rest. */
    private boolean mayPlace(int slot) {
        CgContainers.Menu shown = menu;
        return shown != null && slot >= 0 && slot < shown.slots().size() && shown.slot(slot).kind() != SlotKind.RESULT;
    }

    /** Host thread. */
    private void poll() {
        long now = System.nanoTime();
        if (readOnce && now - readAt < PERIOD_NANOS) return;
        readAt = now;
        if (mirror == null) mirror = CgContainers.mirror();
        ContainerMirror.Update update = mirror.poll();
        if (update == null && readOnce) return;
        readOnce = true;
        document.execute(() -> {
            menu = update == null ? null : update.menu();
            if (update != null) onUpdate.emit(update);
        });
    }
}
