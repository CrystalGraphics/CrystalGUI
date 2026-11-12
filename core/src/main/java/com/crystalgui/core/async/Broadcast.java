package com.crystalgui.core.async;

import com.crystalgraphics.job.CgHostThread;
import com.crystalgraphics.platform.service.CgGameThreads;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.signal.Connection;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

/**
 * An event that crosses sequences: posted from any thread, heard by each subscriber on the sequence it subscribed
 * from. What a service or a process-wide fact tells the documents that care; inside one document, a {@link
 * com.crystalgui.core.signal.Signal} is the tool. Semantics ported from Chromium's {@code ObserverListThreadSafe}
 * (BSD-3).
 *
 * <pre>{@code
 * // The owner, any thread
 * public static final Broadcast<NotificationEvent> CHANGES = Broadcast.create("notifications");
 * CHANGES.post(NotificationEvent.added(notification, index));
 *
 * // A subscriber inside a document: heard on that document's sequence, at its next turn
 * element.whileConnected(() -> NotificationService.current().onDidChange.subscribe(this::apply));
 * }</pre>
 *
 * <p>A change with nothing to say beyond "it changed" is a {@code Runnable}:</p>
 *
 * <pre>{@code
 * public static final Broadcast<Long> RELOADED = Broadcast.create("templates");
 * RELOADED.post(generation);
 * Connection watch = RELOADED.subscribe(() -> rebuild());
 * }</pre>
 *
 * <ul>
 *   <li><b>Always posted</b>, also to a subscriber on the posting sequence: one order everywhere, and a listener
 *       never runs inside the {@code post} that caused it.</li>
 *   <li><b>Where a subscriber is told</b>: inside a running document (its frame, an input event, a node's attach),
 *       on that document, wherever it runs then; else on the calling sequence; else on the host thread it is on
 *       ({@link CgHostThread#here()}). A worker has nowhere to be told, and subscribing there throws.</li>
 *   <li><b>Disconnecting drops what is queued</b> for that subscriber; a delivery already running finishes.</li>
 *   <li><b>{@code T} is a value</b>: a record, a number, an immutable list. Never a node or a model: it reaches
 *       other threads.</li>
 *   <li>Events from one poster reach each subscriber in posting order. Coalescing is the subscriber's.</li>
 * </ul>
 *
 * @param <T> the event, a value
 */
public final class Broadcast<T> {

    private final String name;
    private final List<Subscriber<T>> subscribers = new CopyOnWriteArrayList<>();

    private Broadcast(String name) {
        this.name = name;
    }

    /** A broadcast named for what it tells: the name is what an error about a subscriber says. */
    public static <T> Broadcast<T> create(String name) {
        return new Broadcast<>(Objects.requireNonNull(name, "name"));
    }

    /** Hears every later post on the calling sequence or host thread. */
    public Connection subscribe(Consumer<? super T> listener) {
        Subscriber<T> subscriber = new Subscriber<>(this, Objects.requireNonNull(listener, "listener"), where());
        subscribers.add(subscriber);
        return subscriber;
    }

    /** As {@link #subscribe(Consumer)}, for a listener that needs only to know it happened. */
    public Connection subscribe(Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        return subscribe(ignored -> listener.run());
    }

    /** Tells every subscriber, each on its own sequence. Any thread. */
    public void post(T event) {
        for (Subscriber<T> subscriber : subscribers) subscriber.deliver(event);
    }

    /** Disconnects every subscriber, dropping what is queued for them. A registry's test reset. */
    public void disconnectAll() {
        for (Subscriber<T> subscriber : subscribers) subscriber.disconnect();
    }

    /** How many subscribe now: what a leak test counts. */
    public int subscriberCount() {
        return subscribers.size();
    }

    public String name() {
        return name;
    }

    /** Where a subscriber inside a document is told, or null outside one: the UI's documents register here. */
    private static volatile Supplier<Executor> documentContext = () -> null;

    /**
     * Routes a subscriber made inside a running document to that document, so its events follow the document when
     * it is handed from the thread that built it to its sequence. {@code UIDocument} registers this; nothing else
     * should.
     */
    public static void deliverThrough(Supplier<Executor> current) {
        documentContext = Objects.requireNonNull(current, "current");
    }

    /**
     * The document or sequence the caller runs in, or null outside both. For an owner whose own callbacks arrive on
     * another thread (a wire's tick) and must be handed back before they touch a signal.
     */
    @Nullable
    public static Executor sequenceHere() {
        Executor document = documentContext.get();
        if (document != null) return document;
        return UiSequence.current();
    }

    private Executor where() {
        Executor owner = sequenceHere();
        if (owner != null) return owner;
        CgGameThreads.Binding host = CgHostThread.here();
        if (host != null) return host::execute;
        throw new IllegalStateException("'" + name + "' was subscribed from " + Thread.currentThread().getName()
                + ", which is neither a sequence nor a host thread: there is nowhere to deliver to");
    }

    @Override
    public String toString() {
        return "Broadcast(" + name + ", " + subscribers.size() + " subscribed)";
    }

    private static final class Subscriber<T> implements Connection {
        private final Broadcast<T> owner;
        private final Consumer<? super T> listener;
        private final Executor where;
        private volatile boolean connected = true;

        Subscriber(Broadcast<T> owner, Consumer<? super T> listener, Executor where) {
            this.owner = owner;
            this.listener = listener;
            this.where = where;
        }

        void deliver(T event) {
            where.execute(() -> {
                // Checked where it runs: a disconnect after the post and before this turn drops it.
                if (!connected) return;
                try {
                    listener.accept(event);
                } catch (RuntimeException failed) {
                    CrystalGuiCore.LOGGER.error("[cgui] a subscriber to '{}' threw", owner.name, failed);
                }
            });
        }

        @Override
        public void disconnect() {
            if (!connected) return;
            connected = false;
            owner.subscribers.remove(this);
        }

        @Override
        public boolean isConnected() {
            return connected;
        }
    }
}
