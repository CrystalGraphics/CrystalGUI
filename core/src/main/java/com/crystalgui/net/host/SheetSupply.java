package com.crystalgui.net.host;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

import javax.annotation.Nullable;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.net.ClientUiSession;
import com.crystalgui.net.SheetRef;

/**
 * Turns the {@link SheetRef}s a window names into <b>CSS a host can actually apply</b>.
 *
 * <h3>The gap this closes</h3>
 *
 * <p>A {@code SheetRef} has crossed the wire since sheets existed, and there was no way to
 * <em>fetch</em> the sheet behind one. So every host resolved refs from a constant in its own jar and
 * said so in a comment — <i>"the one place this class is not the shape a mod would use"</i>. That holds
 * up for a UI whose mod is installed on both sides, and is a wall for anything a server authors: the
 * client has a hash, no bytes, and nothing to call.</p>
 *
 * <h3>Three tiers, cheapest first</h3>
 *
 * <ol>
 *   <li><b>Local resolvers</b> — a {@link Resolver} that recognises the ref's id and hands over a sheet
 *       this installation already ships. Free, and the right answer for a shared theme: sending bytes
 *       both sides hold is waste.</li>
 *   <li><b>The cache</b>, keyed by hash. Content-addressed, so invalidation does not exist as a
 *       problem and a changed sheet is simply a different key.</li>
 *   <li><b>The wire</b> — {@code ui/sheet}, by hash, exactly as a description is fetched.</li>
 * </ol>
 *
 * <h3>Applied late, and that is fine</h3>
 *
 * <p>The wire tier is asynchronous, so a window is mounted before its sheets arrive and restyles when
 * they do — the cascade re-matches, which is what it is for. The alternative is holding a window off
 * screen waiting for a stylesheet, which trades a brief plain frame for a window that appears not to
 * have opened.</p>
 *
 * <p><b>Applied as one batch, in the order named.</b> Order is load-bearing — the engine's sheet list
 * is flat and ordered, and a later sheet wins ties — so a set that arrives out of order is collected
 * before any of it is applied. A ref that cannot be resolved at all is dropped with one line and the
 * rest are applied without it: a missing theme is a plain window, not a broken one.</p>
 */
public final class SheetSupply {

    /** Recognises a ref this installation already holds. @see SheetSupply */
    @FunctionalInterface
    public interface Resolver {
        /** The CSS behind {@code ref}, or {@code null} for "not mine". */
        @Nullable
        String resolve(SheetRef ref);
    }

    private final List<Resolver> resolvers = new CopyOnWriteArrayList<>();

    /** hash → CSS. Content-addressed, so an entry can never be stale. */
    private final Map<String, String> cache = new LinkedHashMap<>();

    /** Told when a window's sheets are all in, with the CSS in the order the server named them. */
    private final BiConsumer<ClientWindowContext, List<String>> apply;

    /**
     * @param apply what a host does with resolved CSS — parse it and add it to a style engine. Called
     *              on the thread that ticked the connection, which is the thread that owns the tree.
     */
    public SheetSupply(BiConsumer<ClientWindowContext, List<String>> apply) {
        if (apply == null) throw new IllegalArgumentException("apply is null");
        this.apply = apply;
    }

    /** Adds a local tier. Consulted in registration order, before the cache and the wire. */
    public SheetSupply addResolver(Resolver resolver) {
        if (resolver != null) resolvers.add(resolver);
        return this;
    }

    /** Seeds the cache with a sheet this client already has by hash. */
    public SheetSupply put(String hash, String css) {
        if (hash != null && css != null) cache.put(hash, css);
        return this;
    }

    public boolean has(String hash) {
        return cache.containsKey(hash);
    }

    public int cacheSize() {
        return cache.size();
    }

    /**
     * Resolves every ref for one window, then applies them together.
     *
     * <p>Package-private: a host does not call this, {@link ClientUiHost} does, once per mount and
     * again on a re-describe.</p>
     */
    void resolve(ClientUiSession<Object> session, List<SheetRef> refs, ClientWindowContext window) {
        int total = refs.size();
        String[] resolved = new String[total];
        boolean[] settled = new boolean[total];
        int[] outstanding = { 0 };

        for (int i = 0; i < total; i++) {
            SheetRef ref = refs.get(i);
            String local = fromLocal(ref);
            if (local != null) {
                resolved[i] = local;
                settled[i] = true;
                continue;
            }
            outstanding[0]++;
        }

        if (outstanding[0] == 0) {
            deliver(window, resolved, settled);
            return;
        }

        for (int i = 0; i < total; i++) {
            if (settled[i]) continue;
            final int slot = i;
            final SheetRef ref = refs.get(i);
            session.requestSheet(ref.hash(),
                    css -> {
                        cache.put(ref.hash(), css);
                        resolved[slot] = css;
                        settled[slot] = true;
                        if (--outstanding[0] == 0) deliver(window, resolved, settled);
                    },
                    error -> {
                        // ONE LINE, and the rest are applied. A theme that cannot be fetched is a plain
                        // window; refusing to style the others because one is missing would be worse.
                        CrystalGuiCore.LOGGER.warn("Could not fetch sheet {} for <{}>: {}",
                                ref.hash(), window.type(), error);
                        settled[slot] = true;
                        if (--outstanding[0] == 0) deliver(window, resolved, settled);
                    });
        }
    }

    @Nullable
    private String fromLocal(SheetRef ref) {
        String cached = cache.get(ref.hash());
        if (cached != null) return cached;
        for (Resolver resolver : resolvers) {
            String css;
            try {
                css = resolver.resolve(ref);
            } catch (RuntimeException failed) {
                CrystalGuiCore.LOGGER.warn("A sheet resolver failed for {}: {}",
                        ref.hash(), failed.getMessage());
                continue;
            }
            if (css != null) {
                // Cached under the hash the SERVER named, so a second window naming the same ref costs
                // nothing even though this one was answered locally.
                cache.put(ref.hash(), css);
                return css;
            }
        }
        return null;
    }

    private void deliver(ClientWindowContext window, String[] resolved, boolean[] settled) {
        List<String> css = new ArrayList<>(resolved.length);
        for (int i = 0; i < resolved.length; i++) {
            if (settled[i] && resolved[i] != null) css.add(resolved[i]);
        }
        if (css.isEmpty()) return;
        try {
            apply.accept(window, css);
        } catch (RuntimeException failed) {
            CrystalGuiCore.LOGGER.error("Applying sheets for <{}> failed: {}",
                    window.type(), failed.getMessage(), failed);
        }
    }
}
