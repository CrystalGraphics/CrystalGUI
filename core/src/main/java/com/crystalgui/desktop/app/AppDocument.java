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

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.ui.dom.UIDocument;

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
        /** Ends the thread and frees what the document drew with; the host's thread, once. */
        void close();
    }

    private final UIDocument document;
    private final Backend backend;
    private final List<ClientRoot> clients = new CopyOnWriteArrayList<>();
    /** What the application asked of the shell while the shell waits for it. */
    private final Queue<Runnable> pump = new ConcurrentLinkedQueue<>();
    private volatile boolean waiting;

    public AppDocument(UIDocument document, Backend backend) {
        this.document = document;
        this.backend = backend;
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
    <T> T callShell(UIDocument shell, Supplier<T> work) {
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

    /** Offers the document a key. */
    void key(CgSystemInput.Keyboard.Event event) {
        backend.consumeKeyboardEvent(event);
    }

    /** Puts a client root in the document. The document's thread. */
    void addClient(ClientRoot client) {
        clients.add(client);
        document.append(client);
    }

    /** Takes a client root out of the document. The document's thread. */
    void removeClient(ClientRoot client) {
        clients.remove(client);
        document.remove(client);
    }

    /** The document's thread, after each commit: tells the windows whose client changed. */
    public void committed() {
        for (ClientRoot client : clients) client.committed();
    }

    /** Ends the document: its thread stops and what it drew with is freed. The host's thread. */
    public void close() {
        backend.close();
    }
}
