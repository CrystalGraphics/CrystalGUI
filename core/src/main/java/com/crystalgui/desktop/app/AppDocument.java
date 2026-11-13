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

        /** Ends the thread and frees what the document drew with; the host's thread, once. */
        void close();
    }

    private final UIDocument document;
    private final Backend backend;
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
     * The shell's thread, for each pointer event while this document holds the pointer: offers a drag of this document that
     * carries a value to what the pointer is over. Another application's window hears it as a drag of its own; the bare
     * desktop on release opens it in a window of this application ({@link ApplicationRegistry#openDropped}).
     *
     * <p>A drag whose payload holds nodes is not portable and never leaves; nothing here runs for it.</p>
     */
    void carry(UIDocument shell, float x, float y, boolean released) {
        Object payload = document.input().portableDrag();
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
        Desktop desktop = Desktop.ifPresent(shell);
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

    private void leaveCrossing() {
        AppDocument was = crossing;
        crossing = null;
        if (was != null) was.execute(() -> was.document.input().foreignDrag().leave());
    }

    /** Offers the document a key. */
    void key(CgSystemInput.Keyboard.Event event) {
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

    /** The document's thread, after each commit: tells the windows whose client changed. */
    public void committed() {
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
