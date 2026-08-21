package com.crystalgui.net;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.net.protocol.Call;
import com.crystalgui.net.protocol.Envelope;
import com.crystalgui.net.protocol.EnvelopeCodec;
import com.crystalgui.net.protocol.MessageRouter;
import com.crystalgui.net.protocol.UiMethods;
import com.crystalgui.serialization.DynamicOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.serialization.UIDescriptionCodec;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.Checkbox;
import com.crystalgui.ui.elements.Slider;
import com.crystalgui.ui.elements.Switch;
import com.crystalgui.ui.elements.TextField;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The client's half: rebuilds the tree a server described, and holds a content-addressed cache of
 * descriptions.
 *
 * <h3>The cache</h3>
 * <p>Descriptions are keyed by content hash, so invalidation does not exist as a problem — a changed
 * UI is simply a different key, and a stale entry can never be served. A UI the client has opened
 * before therefore costs one {@code OpenWindow} packet and nothing else, whatever its size.</p>
 *
 * <p>Scoped to this session for now. Persisting it across restarts is purely additive precisely
 * because the store is content-addressed.</p>
 */
public final class ClientUiSession<T> {

    private final UITransport<T> transport;
    private final DynamicOps<T> ops;

    /** hash → encoded description. Encoded, not decoded: a decoded tree is live elements, and a live
     * element can only be attached in one place, so a cache of them would be a cache of things that
     * cannot be reused. */
    private final Map<String, T> descriptionCache = new ConcurrentHashMap<>();

    private final Deque<T> mailbox = new ArrayDeque<>();

    /** Everything inbound goes through here; nothing is dispatched by type. @see #registerUiMethods */
    private final MessageRouter<T> router;

    /** How long a call waits before its error handler is told. @see ServerUiSession */
    private long callTimeoutMillis = 10_000L;

    private int windowId = -1;
    private int expectedElementCount = -1;
    @Nullable
    private UIElement root;
    private List<SheetRef> sheets = List.of();
    private boolean useUserAgentSheet = true;

    @Nullable
    private Consumer<UIElement> onWindowOpened;
    @Nullable
    private Consumer<String> onWindowClosed;

    public ClientUiSession(UITransport<T> transport, DynamicOps<T> ops) {
        this.transport = transport;
        this.ops = ops;
        this.router = new MessageRouter<>(envelope -> transport.send(EnvelopeCodec.encode(ops, envelope)));
        registerUiMethods();
        transport.setReceiver(packet -> {
            synchronized (mailbox) {
                mailbox.add(packet);
            }
        });
    }

    /** Fired once the tree exists — where a host would hand it to a {@code UIWindow} and render it. */
    public ClientUiSession<T> onWindowOpened(Consumer<UIElement> handler) {
        this.onWindowOpened = handler;
        return this;
    }

    public ClientUiSession<T> onWindowClosed(Consumer<String> handler) {
        this.onWindowClosed = handler;
        return this;
    }

    @Nullable
    public UIElement root() {
        return root;
    }

    public int windowId() {
        return windowId;
    }

    public List<SheetRef> sheets() {
        return sheets;
    }

    public boolean useUserAgentSheet() {
        return useUserAgentSheet;
    }

    public boolean hasCached(String descHash) {
        return descriptionCache.containsKey(descHash);
    }

    public int cacheSize() {
        return descriptionCache.size();
    }

    /** Processes everything that arrived. Called on the thread that owns the tree — never from the
     * transport's own thread, since elements are single-threaded by contract. */
    public void tick() {
        List<T> batch;
        synchronized (mailbox) {
            if (mailbox.isEmpty()) return;
            batch = new ArrayList<>(mailbox);
            mailbox.clear();
        }
        for (T raw : batch) {
            Envelope envelope;
            try {
                envelope = EnvelopeCodec.decode(ops, raw);
            } catch (RuntimeException malformed) {
                CrystalGuiCore.LOGGER.warn("Dropping an undecodable message: {}", malformed.getMessage());
                continue;
            }
            router.accept(envelope);
        }
        router.tickTimeouts(System.currentTimeMillis());
    }

    /** The UI half of the vocabulary. RPC methods register themselves through {@link #onCall}. */
    private void registerUiMethods() {
        router.onNotify(UiMethods.OPEN_WINDOW, payload -> {
            StateMap<T> in = read(payload);
            int protocol = in.getInt("protocol", -1);
            int id = in.getInt(UiMethods.WINDOW, -1);
            if (protocol != EnvelopeCodec.VERSION) {
                // Refuse rather than open something we will misread. A version mismatch that opens
                // anyway shows up as a UI that is subtly wrong, which is far harder to trace than a
                // window that plainly did not appear.
                CrystalGuiCore.LOGGER.error("Refusing window {}: server protocol {} but this client "
                        + "speaks {}", id, protocol, EnvelopeCodec.VERSION);
                return;
            }
            this.windowId = id;
            this.sheets = in.getList("sheets", entry -> SheetRef.CODEC.decode(ops, entry.encode()));
            this.useUserAgentSheet = in.getBool("ua", false);
            this.expectedElementCount = in.getInt("count", 0);

            String hash = in.getString("hash", "");
            T cached = descriptionCache.get(hash);
            if (cached != null) {
                buildFrom(cached);
                return;
            }
            // A REQUEST now, where it used to be a bare RequestDescription with nothing tying the answer
            // back to it. The id correlates, so two opens in flight cannot cross their descriptions.
            StateMap<T> ask = new StateMap<>(ops);
            ask.putInt(UiMethods.WINDOW, id);
            ask.putString("hash", hash);
            router.request(UiMethods.DESCRIPTION, ask.encode(),
                    answer -> {
                        StateMap<T> body = read(answer);
                        T encoded = body.getRaw("root");
                        if (encoded == null) return;
                        descriptionCache.put(body.getString("hash", hash), encoded);
                        buildFrom(encoded);
                    },
                    error -> CrystalGuiCore.LOGGER.warn("Description for window {} refused: {}", id, error));
        });

        router.onNotify(UiMethods.STATE_DELTA, payload -> {
            StateMap<T> in = read(payload);
            if (in.getInt(UiMethods.WINDOW, windowId) != windowId || root == null) return;
            for (StateMap<T> entry : in.getList("entries", e -> e)) {
                int nid = entry.getInt("nid", -1);
                UIElement target = NetworkIds.find(root, nid);
                if (target == null) {
                    CrystalGuiCore.LOGGER.warn("State update for unknown element {}", nid);
                    continue;
                }
                if (shouldSuppress(target)) continue;
                T state = entry.getRaw("s");
                if (state == null) continue;
                try {
                    target.readStateFrom(new StateMap<>(ops, state));
                } catch (RuntimeException bad) {
                    // Per-entry, so one malformed update cannot take the rest of the batch with it.
                    CrystalGuiCore.LOGGER.warn("Bad state for element {}: {}", nid, bad.getMessage());
                }
            }
        });

        router.onNotify(UiMethods.CLOSE_WINDOW, payload -> {
            StateMap<T> in = read(payload);
            if (in.getInt(UiMethods.WINDOW, windowId) != windowId) return;
            String reason = in.getString("reason", "");
            root = null;
            windowId = -1;
            if (onWindowClosed != null) onWindowClosed.accept(reason);
        });
    }

    private StateMap<T> read(@Nullable T payload) {
        return payload == null ? new StateMap<>(ops) : new StateMap<>(ops, payload);
    }

    /**
     * Don't overwrite what someone is actively editing.
     *
     * <p>Without this, typing into a field produces a report, the server echoes it back, and the
     * incoming value resets the caret mid-word. Narrow on purpose: only the focused element, and
     * only one that consumes text.</p>
     */
    private boolean shouldSuppress(UIElement target) {
        return target.isFocused() && target.consumesTextInput();
    }

    private void buildFrom(T encoded) {
        UIElement rebuilt = UIDescriptionCodec.CODEC.decode(ops, encoded);
        int actual = NetworkIds.assign(rebuilt);

        // Ids are positions in a document-order walk, so they only agree if both sides built the same
        // structure. Internals are never serialized, so a client whose widget constructors differ from
        // the server's would shift every id past the difference and apply updates to the wrong
        // elements — silently. The count is the cheapest thing that catches it.
        if (actual != expectedElementCount) {
            CrystalGuiCore.LOGGER.error("Refusing window {}: server described {} elements but rebuilding "
                    + "produced {}. The two sides' widget constructors disagree — check for a version "
                    + "mismatch.", windowId, expectedElementCount, actual);
            windowId = -1;
            return;
        }

        root = rebuilt;
        wireReportedEvents(root);
        if (onWindowOpened != null) onWindowOpened.accept(root);
    }

    /**
     * Installs a listener for every interaction the description asked to hear about.
     *
     * <p>The client runs the real DOM dispatch — capture, target, bubble, hit-testing, all of it —
     * and only the outcome is reported. The server never sees a coordinate.</p>
     */
    private void wireReportedEvents(UIElement element) {
        for (String kind : element.getReportedEvents()) {
            switch (kind) {
                case UiEventKinds.ACTIVATE -> {
                    if (element instanceof Button button) {
                        button.attachListener(() -> report(element, kind, null));
                    }
                }
                case UiEventKinds.TOGGLE -> {
                    if (element instanceof Checkbox checkbox) {
                        checkbox.attachListener(checked -> report(element, kind,
                                new StateMap<T>(ops).putBool("checked", checked)));
                    } else if (element instanceof Switch toggle) {
                        toggle.attachListener(checked -> report(element, kind,
                                new StateMap<T>(ops).putBool("checked", checked)));
                    }
                }
                case UiEventKinds.VALUE -> {
                    if (element instanceof Slider slider) {
                        slider.attachListener(value -> report(element, kind,
                                new StateMap<T>(ops).putFloat("value", value)));
                    }
                }
                case UiEventKinds.TEXT -> {
                    if (element instanceof TextField field) {
                        field.attachListener(text -> report(element, kind,
                                new StateMap<T>(ops).putString("text", text)));
                    }
                }
                default -> CrystalGuiCore.LOGGER.warn(
                        "Description asked to report '{}' on <{}>, which this client cannot observe",
                        kind, element.tagName());
            }
        }
        for (UIElement child : element.getChildren()) wireReportedEvents(child);
    }

    private void report(UIElement element, String kind, @Nullable StateMap<T> payload) {
        StateMap<T> out = new StateMap<>(ops);
        out.putInt(UiMethods.WINDOW, windowId);
        out.putInt("nid", element.getNetworkId());
        out.putString("kind", kind);
        if (payload != null) out.putRaw("p", payload.encode());
        router.notify(UiMethods.EVENT, out.encode());
    }

    /** Registers a client-side RPC method the server may call. */
    public ClientUiSession<T> onCall(String method, Call.Handler<T> handler) {
        // Same handler type, so nothing that calls this moves; underneath, an RPC is now an ordinary
        // REQUEST and its correlation is the router's rather than a second id space of its own.
        router.onRequest(method, (payload, respond) ->
                handler.invoke(read(payload), new Call.Responder<T>() {
                    @Override
                    public void ok(@Nullable StateMap<T> value) {
                        respond.ok(value == null ? null : value.encode());
                    }

                    @Override
                    public void fail(String error) {
                        respond.fail(error);
                    }
                }));
        return this;
    }

    /** Calls a server-side method. */
    public void call(String method, @Nullable StateMap<T> args,
                     @Nullable Consumer<StateMap<T>> onResult, @Nullable Consumer<String> onError) {
        router.request(method, args == null ? null : args.encode(),
                value -> {
                    if (onResult != null) onResult.accept(read(value));
                },
                onError,
                System.currentTimeMillis() + callTimeoutMillis);
    }
}
