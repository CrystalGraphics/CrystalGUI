package com.crystalgui.desktop.app;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.core.cursor.Cursor;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.service.DragPresenter;
import com.crystalgui.ui.service.DragPreview;
import org.joml.Vector2f;

/**
 * The document one application runs in, on a thread of its own: its windows' client roots live here, and the shell's
 * document, which holds their frames, shows what they draw.
 *
 * <pre>{@code
 * AppDocument document = documents.open(kind);
 * Application app = document.ask(() -> kind.factory().apply(context));   // built on its own thread
 * document.execute(() -> view.refresh());                                 // anything else that touches its tree
 * }</pre>
 *
 * <ul>
 *   <li>Only its thread touches its tree: {@link #execute} posts, or runs at once when already there.</li>
 *   <li>{@link #ask} is the shell's wait for it: for a launch, never per frame. While it waits, the shell's thread
 *       serves what the application asks of the shell ({@link #callShell}), so building a window cannot deadlock it.</li>
 *   <li>The host builds it ({@link AppDocuments}); the registry closes it when its application quits.</li>
 * </ul>
 */
public final class AppDocument {

    /** What the host ends the document with, and the input it takes: a viewport is one. */
    public interface Backend extends CgSystemInput.Mouse, CgSystemInput.Keyboard {
        /** Offers the document an input method's run in progress; an empty {@code text} ends it. */
        default boolean consumeComposition(String text, int caret) {
            return false;
        }

        /** The thread running the document's work now, or null when idle: what a hang report takes the stack of. Any thread. */
        @Nullable
        default Thread runningThread() {
            return null;
        }

        /** Rung 1 of closing a document that does not answer: its engine checkpoints throw {@code ApplicationAborted}. Any thread. */
        default void abort() {
        }

        /** Rung 2: interrupts the thread running it. Any thread. */
        default void interrupt() {
        }

        /** Rung 3: gives its thread up so {@link #close} does not wait for it. Any thread. */
        default void abandon() {
        }

        /** Whether its thread has ended, by unwinding at a checkpoint or by being abandoned. Any thread. */
        default boolean stopped() {
            return false;
        }

        /** Ends the thread and frees what the document drew with; the host's thread, once. */
        void close();
    }

    private final UIDocument document;
    private final Backend backend;
    private final AppHealth health;
    /** Exceptions that escaped the document's thread, awaiting the shell. */
    private final java.util.Queue<Throwable> faults = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** What the application is called in a fault's report; set by the registry at launch. */
    private volatile String name = "Application";
    /** The caption's note while the windows are dimmed: "(Not responding)", "(Stopped working)", or empty. */
    private volatile String ghost = "";

    /** The shell's frames for this document's windows, which the shell dims while it does not answer. */
    private final List<WindowFrame> frames = new CopyOnWriteArrayList<>();
    private final List<ClientRoot> clients = new CopyOnWriteArrayList<>();
    /** What the application asked of the shell while the shell waits for it. */
    private final Queue<Runnable> pump = new ConcurrentLinkedQueue<>();
    private volatile boolean waiting;

    /** The window whose client last took input: the one the popups belong to, when more than one is open. */
    @Nullable
    private volatile ClientPortal active;
    @Nullable
    private PopupPortal popupPortal;
    /** The client the pointer is aimed at, which is on top of the others. */
    @Nullable
    private volatile ClientRoot aimed;

    public AppDocument(UIDocument document, Backend backend) {
        this.document = document;
        this.backend = backend;
        this.health = new AppHealth(backend, () -> name);
        // What the pointer looks like over a window is what the shell shows there, asked of this document.
        document.input().presentsCursor(false);
    }

    /** Where the focus owner's text caret was at the last commit, in the document's surface pixels, or null. */
    @Nullable
    private volatile float[] caret;

    /** The caret of the application's focus owner as of its last frame, {x, y, width, height}; null when there is none. Any thread. */
    @Nullable
    float[] caret() {
        return caret;
    }

    /** Offers the document an input method's run in progress. */
    void composition(String text, int caret) {
        backend.consumeComposition(text, caret);
    }

    /** The cursor the application's document wants where its pointer is. Any thread. */
    Cursor cursor() {
        return document.input().currentCursor();
    }

    /** The document's popups as a surface, or null where its host did not install a {@link PopupLayer}. */
    @Nullable
    public PopupLayer popups() {
        return document.topLayerNodeIfPresent() instanceof PopupLayer layer ? layer : null;
    }

    /** Closes every open popup of the document, as a press outside them does. Any thread. */
    public void dismissPopups() {
        document.execute(() -> document.dismiss().lightDismiss(null));
    }

    @Nullable
    ClientPortal active() {
        return active;
    }

    void activate(ClientPortal portal) {
        active = portal;
        // A caption is a strip of its window: the document is as large as the window's content, never as its caption.
        ClientPortal content = portal.windowContent();
        if (content != null) {
            sizing = content;
            content.reportSize();
        }
    }

    /** The content portal whose size the document lays out at. */
    @Nullable
    private volatile ClientPortal sizing;
    /** The logical size of that window's client area; 0 before there is one. */
    private volatile float windowWidth, windowHeight;

    /** The shell's thread: {@code portal}'s client area is now this large. Only the window the document follows is kept. */
    void sized(ClientPortal portal, float width, float height) {
        if (portal != sizing) return;
        windowWidth = width;
        windowHeight = height;
    }

    /**
     * The width the document lays out at, in logical pixels: its active window's, so a popup flips and centres within the
     * window it belongs to. 0 until a window has been sized.
     */
    public float windowWidth() {
        return windowWidth;
    }

    /** @see #windowWidth */
    public float windowHeight() {
        return windowHeight;
    }

    /** The shell's thread: shows the document's popups, once, in the shell's top layer. */
    public void showPopupsIn(UIDocument shell) {
        PopupLayer layer = popups();
        if (layer == null || popupPortal != null) return;
        popupPortal = new PopupPortal(this, layer);
        shell.append(popupPortal);
        shell.promote(popupPortal);
    }

    public UIDocument document() {
        return document;
    }

    /** Runs {@code work} on the document's thread, and waits for it. */
    public void run(Runnable work) {
        ask(() -> {
            work.run();
            return null;
        });
    }

    /**
     * Runs {@code work} on the document's thread and answers what it answered, waiting for it. From the shell's thread
     * the wait serves the application's {@link #callShell} requests.
     */
    public <T> T ask(Supplier<T> work) {
        if (document.isCurrent()) return work.get();
        CompletableFuture<T> done = new CompletableFuture<>();
        waiting = true;
        try {
            document.execute(() -> {
                try {
                    done.complete(work.get());
                } catch (Throwable failed) {
                    done.completeExceptionally(failed);
                }
            });
            while (!done.isDone()) {
                Runnable request = pump.poll();
                if (request != null) request.run();
                else LockSupport.parkNanos(100_000L);
            }
        } finally {
            waiting = false;
        }
        try {
            return done.get();
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException(cause);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    /** Runs {@code work} on the document's thread: at once when already there, else without waiting. */
    public void execute(Runnable work) {
        document.execute(work);
    }

    /**
     * What the application asks of the shell and waits for: builds what must belong to the shell's thread (a window's
     * frame, its portal), since a signal is used only by the sequence that first used it. Runs at once on the shell's
     * thread; from another, when the shell next takes a turn, or while it waits in {@link #ask}.
     *
     * <p>Blocks the caller until then. A window is opened this way; nothing per frame is.</p>
     */
    public <T> T callShell(UIDocument shell, Supplier<T> work) {
        if (shell == null || shell.isCurrent()) return work.get();
        FutureTask<T> task = new FutureTask<>(work::get);
        if (waiting) pump.add(task);
        else shell.execute(task);
        try {
            return task.get();
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException(cause);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    /** Offers the document a pointer event, in its own surface pixels. */
    void mouse(CgSystemInput.Mouse.Event event) {
        if (event.button() >= 0 && event.state() && health.state() == AppHealth.State.NOT_RESPONDING) hungPoke.run();
        health.offered();
        backend.consumeMouseEvent(event);
    }

    /**
     * Aims the pointer at {@code client}: clients share the document's corner, so the one the next event is for goes on
     * top first. The shell's thread; the change lands with the document's next commit.
     */
    void aim(ClientRoot client) {
        ClientRoot was = aimed;
        if (was == client) return;
        aimed = client;
        document.execute(() -> {
            if (was != null) was.front(false);
            client.front(true);
        });
    }

    /** The portal of this application under a point of the shell's document, or null when none is. The shell's thread. */
    @Nullable
    ClientPortal portalAt(UIDocument shell, float x, float y) {
        Box under = shell.boxes().hitTest(x, y);
        for (UIElement node = under == null ? null : under.node(); node != null; node = node.parentElement()) {
            if (node instanceof ClientPortal portal && portal.application() == this) return portal;
        }
        return null;
    }

    /** The application whose document a drag of this one's is over, which hears it as a foreign drag. The shell's thread. */
    @Nullable
    private AppDocument crossing;

    /**
     * The shell's thread, for each pointer event while this document holds the pointer: draws the document's running drag
     * (its preview, wherever the pointer is) and, when it carries a value, offers it to what the pointer is over. Another
     * application's window hears it as a drag of its own; the bare desktop on release opens it in a window of this
     * application ({@link ApplicationRegistry#openDropped}).
     *
     * <p>A drag whose payload holds nodes is not portable and never leaves; only its ghost is the shell's.</p>
     */
    void carry(UIDocument shell, float x, float y, boolean released) {
        DragPreview preview = document.input().dragPreview();
        Object payload = document.input().portableDrag();
        showGhost(shell, preview, x, y, released);
        Desktop desktop = Desktop.ifPresent(shell);
        if (payload == null) {
            leaveCrossing();
            return;
        }
        ClientPortal portal = null;
        boolean chrome = false;
        Box under = shell.boxes().hitTest(x, y);
        for (UIElement node = under == null ? null : under.node(); node != null; node = node.parentElement()) {
            if (node instanceof ClientPortal found) {
                portal = found;
                break;
            }
            if (node instanceof WindowFrame) chrome = true;
        }
        AppDocument receiver = portal == null || portal.application() == this ? null : portal.application();
        if (receiver != crossing) {
            leaveCrossing();
            crossing = receiver;
        }
        if (receiver != null) {
            Vector2f local = portal.toLocal(x, y);
            float scale = shell.boxes().uiScale();
            float at = local.x * scale, down = local.y * scale;
            if (released) {
                crossing = null;
                receiver.execute(() -> {
                    boolean taken = receiver.document.input().foreignDrag().drop(at, down);
                    if (!taken && desktop != null) shell.execute(() -> desktop.applications().offerDropped(receiver, payload));
                });
            } else {
                receiver.execute(() -> receiver.document.input().foreignDrag().move(payload, at, down));
            }
        } else if (released && portal == null && !chrome && desktop != null) {
            desktop.applications().openDropped(this, payload);
        }
    }

    /**
     * Draws the running drag's preview for as long as this shell carries it: the source's own is cut off at its document's
     * edge, so the source is told to hide it ({@code handOverGhost}) and the shell's own presenter draws it, under the
     * pointer wherever that is.
     */
    private void showGhost(UIDocument shell, @Nullable DragPreview preview, float x, float y, boolean released) {
        DragPresenter presenter = shell.input().presenter();
        if (presenter == null) return;
        if (released || preview == null) {
            presenter.hide();
            return;
        }
        document.input().handOverGhost(true);
        presenter.show(preview, x, y);
    }

    private void leaveCrossing() {
        AppDocument was = crossing;
        crossing = null;
        if (was != null) was.execute(() -> was.document.input().foreignDrag().leave());
    }

    /** Offers the document a key. */
    void key(CgSystemInput.Keyboard.Event event) {
        health.offered();
        backend.consumeKeyboardEvent(event);
    }

    /**
     * Puts {@code content} in a client root of this document, as a window's client area. The document's thread.
     *
     * <pre>{@code
     * ClientRoot client = document.openClient(tree);
     * ClientPortal view = document.portalTo(client);    // shown by whichever document the window is in
     * }</pre>
     */
    public ClientRoot openClient(UIElement content) {
        ClientRoot client = new ClientRoot();
        client.setContent(content);
        addClient(client);
        return client;
    }

    /** A portal that shows {@code client} in another document, and hands it that document's input. Built where it is shown. */
    public ClientPortal portalTo(ClientRoot client) {
        return new ClientPortal(this, client);
    }

    /** Puts a client root in the document. The document's thread. */
    void addClient(ClientRoot client) {
        clients.add(client);
        document.append(client);
    }

    /** Takes a client root out of the document. The document's thread. */
    public void removeClient(ClientRoot client) {
        if (!clients.remove(client)) return;
        if (aimed == client) aimed = null;
        document.remove(client);
    }

    /** What the shell does when a press lands on a window that does not answer: shows its dialog. */
    private volatile Runnable hungPoke = () -> { };
    private long hungSince;

    void hungPoke(Runnable poke) {
        this.hungPoke = poke;
    }

    /** The shell's thread: the verdict that it does not answer was reached at {@code now}. */
    void hungSince(long now) {
        hungSince = now;
    }

    /** How long, in nanoseconds, the shell has shown it as not answering; 0 while it answers. */
    long hungFor(long now) {
        return health.state() == AppHealth.State.NOT_RESPONDING ? now - hungSince : 0L;
    }

    /** Any thread: an exception escaped a frame or a task of this document; the shell takes it on its next frame. */
    public void fault(Throwable cause) {
        faults.add(cause);
    }

    /** The shell's thread: the next unreported fault, or null. */
    @Nullable
    Throwable pollFault() {
        return faults.poll();
    }

    AppHealth health() {
        return health;
    }

    /** What the application is called in a fault's report. */
    void name(String name) {
        this.name = name;
    }

    /** The shell's thread: {@code frame} shows one of this document's windows. */
    void windowOpened(WindowFrame frame) {
        frames.add(frame);
        frame.onDestroyed.connect(() -> frames.remove(frame));
        frame.setGhosted(ghost);
    }

    /** The shell's frames for this document's windows. */
    List<WindowFrame> frames() {
        return frames;
    }

    private boolean stoppedWorking;

    /** The shell's thread: a fault was reported; the windows stay dimmed, captioned "(Stopped working)", until it is closed. */
    void stoppedWorking() {
        stoppedWorking = true;
        showHealth();
    }

    boolean isStoppedWorking() {
        return stoppedWorking;
    }

    /** The shell's thread: dims or restores the windows to match what the document is doing. */
    void showHealth() {
        ghost = health.state() != AppHealth.State.RESPONDING ? "(Not responding)"
                : stoppedWorking ? "(Stopped working)" : "";
        for (WindowFrame frame : frames) {
            frame.setGhosted(ghost);
            repaintPortals(frame.content());
        }
    }

    /** The veil is painted by each client portal, whose picture is kept until something in it changes: so it is told. */
    private static void repaintPortals(UIElement node) {
        if (node instanceof ClientPortal portal) portal.repaint();
        for (UIElement child : node.composedChildren()) repaintPortals(child);
    }

    /** Whether the windows are ghosted: not responding or stopped working. The shell's thread. */
    boolean ghosted() {
        return !ghost.isEmpty();
    }

    /** The document's thread, after each commit: tells the windows whose client changed. */
    public void committed() {
        health.committed();
        caret = document.input().textInputArea();
        for (ClientRoot client : clients) client.committed();
        PopupLayer layer = popups();
        if (layer != null) layer.committed();
    }

    /** Ends the document: its thread stops and what it drew with is freed. The host's thread. */
    public void close() {
        backend.close();
    }
}
