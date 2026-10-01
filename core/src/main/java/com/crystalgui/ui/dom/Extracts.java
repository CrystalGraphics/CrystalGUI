package com.crystalgui.ui.dom;

import com.crystalgraphics.trace.CgTrace;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.async.HostThread;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.core.trace.UiTrace;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A document's extracts: what it reads of the game each frame (plan engine-threaded-ui §2.8, Bevy's extract stage).
 * Read on the thread each names, used on the document's. @see UINode#extract
 */
final class Extracts {

    /** One extract. Its last value and in-flight flag are the document's; {@code pending} is set off it too. */
    private final class Extract<T> {
        final HostThread thread;
        final Supplier<T> read;
        final Consumer<T> use;
        @Nullable
        T last;
        boolean used;
        volatile boolean pending;

        Extract(HostThread thread, Supplier<T> read, Consumer<T> use) {
            this.thread = thread;
            this.read = read;
            this.use = use;
        }

        /** On the document: the use runs when the answer changed. */
        void deliver(@Nullable T value) {
            if (used && Objects.equals(last, value)) return;
            last = value;
            used = true;
            use.accept(value);
        }
    }

    private final UIDocument document;
    /** Registered on the document's thread, read on the host's. */
    private final List<Extract<?>> all = new CopyOnWriteArrayList<>();
    private volatile boolean failureReported;

    Extracts(UIDocument document) {
        this.document = document;
    }

    <T> Connection add(HostThread thread, Supplier<T> read, Consumer<T> use) {
        Extract<T> extract = new Extract<>(thread, read, use);
        all.add(extract);
        return () -> all.remove(extract);
    }

    /**
     * Takes this frame's readings, on the host's frame thread, and answers what delivers them on the document's; null
     * when there is nothing to deliver now. An extract whose thread is this one is read here and delivered this frame;
     * any other is handed to its thread, one read in flight at a time, and lands at the start of a later frame.
     */
    @Nullable
    Runnable read() {
        if (all.isEmpty()) return null;
        long timed = CgTrace.stamp(UiTrace.FRAME);
        List<Runnable> deliveries = new ArrayList<>(all.size());
        for (Extract<?> extract : all) {
            Runnable delivery = readOne(extract);
            if (delivery != null) deliveries.add(delivery);
        }
        CgTrace.zoneDone(UiTrace.FRAME, "extract:read", timed);
        if (deliveries.isEmpty()) return null;
        return () -> {
            long delivered = CgTrace.stamp(UiTrace.FRAME);
            for (Runnable delivery : deliveries) delivery.run();
            CgTrace.zoneDone(UiTrace.FRAME, "extract:deliver", delivered);
        };
    }

    @Nullable
    private <T> Runnable readOne(Extract<T> extract) {
        HostThread thread = extract.thread;
        if (thread.isCurrent()) {
            T value = safeRead(extract);
            return value == FAILED ? null : () -> extract.deliver(value);
        }
        if (extract.pending || !thread.isAvailable()) return null;
        extract.pending = true;
        thread.run(() -> {
            T value = safeRead(extract);
            document.post(() -> {
                extract.pending = false;
                if (value != FAILED) extract.deliver(value);
            });
        });
        return null;
    }

    /** What a read that threw answers, so nothing is delivered for it. */
    private static final Object FAILED = new Object();

    @SuppressWarnings("unchecked")
    private <T> T safeRead(Extract<T> extract) {
        try {
            return extract.read.get();
        } catch (RuntimeException failed) {
            if (!failureReported) {
                failureReported = true;
                CrystalGuiCore.LOGGER.error("[cgui] an extract failed reading on the {} thread; it is skipped each frame it throws",
                        extract.thread, failed);
            }
            return (T) FAILED;
        }
    }
}
