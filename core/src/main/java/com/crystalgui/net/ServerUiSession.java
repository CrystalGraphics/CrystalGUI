package com.crystalgui.net;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.serialization.ContentHash;
import com.crystalgui.serialization.DynamicOps;
import com.crystalgui.serialization.UIDescriptionCodec;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UITreeObserver;

import com.crystalgui.serialization.StateMap;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The server's half of a GUI: it owns the tree, keeps the behaviour, and never lays anything out.
 *
 * <h3>No {@code UIWindow} anywhere</h3>
 * <p>That absence is the entire headless story, and it is structural rather than a flag. No window
 * means no Taffy tree, no style engine, no layout pass — and therefore no path into text
 * measurement, which is the one thing in this engine that genuinely needs a font stack. A flag would
 * only be an assertion that something else is supposed to honour; not holding a window is a fact.</p>
 *
 * <p>This mirrors what LDLib2 does, incidentally: its {@code ModularUI.init} is
 * {@code @OnlyIn(Dist.CLIENT)} and its server tick only ticks elements and syncs.</p>
 *
 * <h3>One session per viewer</h3>
 * <p>Two players opening the same block get a tree each. Per-player UI state — which tab is showing,
 * what is half-typed in a field — is then independent by construction rather than by careful
 * bookkeeping. Fanning one tree out to several viewers needs per-viewer version state and is not
 * what this is.</p>
 */
public final class ServerUiSession<T> implements UITreeObserver {

    private final int windowId;
    private final UIElement root;
    private final UITransport<T> transport;
    private final DynamicOps<T> ops;

    private final List<SheetRef> sheets = new ArrayList<>();
    private boolean useUserAgentSheet = true;

    /** Arrives on the transport's thread, drained on ours. See {@link UITransport}. */
    private final Deque<T> mailbox = new ArrayDeque<>();

    private final Set<UIElement> dirtyState = new LinkedHashSet<>();
    private final Set<UIElement> dirtyIdentity = new LinkedHashSet<>();

    private final RpcRegistry<T> rpc;
    /** Element -> kind -> lambda. Lives here, never on the element: that is what keeps behaviour on
     * the side that owns it while the client holds only a description. */
    private final Map<UIElement, Map<String, Consumer<UiEventContext<T>>>> handlers = new LinkedHashMap<>();

    private String descHash;
    private T encodedDescription;
    private int elementCount;
    private boolean open = false;

    public ServerUiSession(int windowId, UIElement root, UITransport<T> transport, DynamicOps<T> ops) {
        this.windowId = windowId;
        this.root = root;
        this.transport = transport;
        this.ops = ops;
        this.rpc = new RpcRegistry<>(ops);
        transport.setReceiver(packet -> {
            synchronized (mailbox) {
                mailbox.add(packet);
            }
        });
    }

    public int windowId() {
        return windowId;
    }

    public UIElement root() {
        return root;
    }

    public String descHash() {
        return descHash;
    }

    /** Stylesheets to apply, in order. Order is load-bearing: cross-sheet ties at equal specificity
     * fall back to registration order, so both sides must register identically. */
    public ServerUiSession<T> addSheet(SheetRef sheet) {
        sheets.add(sheet);
        return this;
    }

    public ServerUiSession<T> setUseUserAgentSheet(boolean use) {
        this.useUserAgentSheet = use;
        return this;
    }

    /**
     * Serializes the tree, starts observing it, and tells the client to open.
     *
     * <p>Only the hash goes out. The client asks for the body if it needs it, so re-opening a UI it
     * has already seen costs one small packet regardless of how large the tree is.</p>
     */
    public void open() {
        if (open) throw new IllegalStateException("Session " + windowId + " is already open");
        open = true;

        elementCount = NetworkIds.assign(root);
        encodedDescription = UIDescriptionCodec.CODEC.encode(ops, root);
        descHash = ContentHash.of(ops, encodedDescription);

        // Observe only after the snapshot: mutations before open are already in the description, and
        // marking them dirty would send a delta restating what was just sent.
        root.setObserver(this);
        dirtyState.clear();
        dirtyIdentity.clear();

        send(new UIPacket.OpenWindow(UIPacketCodec.PROTOCOL_VERSION, windowId, descHash, elementCount,
                List.copyOf(sheets), useUserAgentSheet));
    }

    /**
     * Drains everything that arrived, then flushes once. Called from the host's tick.
     *
     * <p>Order matters. Draining <em>fully</em> before flushing is what makes a tick's worth of
     * handler-driven mutations collapse into one update: a handler that changes a widget ten times
     * marks it dirty ten times and is read once, at the end.</p>
     */
    public void tick() {
        if (!open) return;
        drainMailbox();
        rpc.sweepTimeouts(System.currentTimeMillis());
        flush();
    }

    /** Registers a server-side RPC method the client may call. */
    public ServerUiSession<T> onCall(String method, RpcRegistry.Handler<T> handler) {
        rpc.register(method, handler);
        return this;
    }

    /** Calls a client-side method. {@code onResult} may be null for fire-and-forget. */
    public void call(String method, @Nullable StateMap<T> args,
                     @Nullable Consumer<StateMap<T>> onResult, @Nullable Consumer<String> onError) {
        int callId = rpc.beginCall(onResult, onError, System.currentTimeMillis());
        send(new UIPacket.RpcCall<>(windowId, callId, method, args == null ? null : args.encode()));
    }

    // ── Server-held behaviour ───────────────────────────────────────────────

    /**
     * Runs {@code handler} when the client reports a {@code kind} interaction on {@code element}.
     *
     * <p>The lambda is recorded here, on the session, and never anywhere near the element — which is
     * what lets a server keep its behaviour while the client holds only a description. The element
     * learns the event's <em>name</em> so the client knows to report it; nothing else.</p>
     */
    public ServerUiSession<T> on(UIElement element, String kind, Consumer<UiEventContext<T>> handler) {
        if (open) {
            throw new IllegalStateException("Handlers must be registered before open() — the set of "
                    + "reported events is part of the description the client has already been sent");
        }
        element.addReportedEvent(kind);
        handlers.computeIfAbsent(element, e -> new LinkedHashMap<>()).put(kind, handler);
        return this;
    }

    /** A press, a toggle, or a commit — whatever the widget considers "the user did the thing". */
    public ServerUiSession<T> onActivate(UIElement element, Consumer<UiEventContext<T>> handler) {
        return on(element, UiEventKinds.ACTIVATE, handler);
    }

    /** What a handler is given. Carries no coordinates — see {@link UIPacket.UiEvent}. */
    public record UiEventContext<T>(ServerUiSession<T> session, UIElement element, StateMap<T> payload) {
    }

    private void flush() {
        if (dirtyState.isEmpty()) return;
        List<UIPacket.StateDelta.Entry<T>> entries = new ArrayList<>(dirtyState.size());
        for (UIElement element : dirtyState) {
            if (element.getNetworkId() < 0) continue;   // never numbered: not part of the open tree
            StateMap<T> state = new StateMap<>(ops);
            element.writeStateTo(state);
            entries.add(new UIPacket.StateDelta.Entry<>(element.getNetworkId(), state.encode()));
        }
        dirtyState.clear();
        dirtyIdentity.clear();
        if (!entries.isEmpty()) send(new UIPacket.StateDelta<>(windowId, entries));
    }

    public void close(String reason) {
        if (!open) return;
        open = false;
        root.setObserver(null);
        send(new UIPacket.CloseWindow(windowId, reason));
    }

    public boolean isOpen() {
        return open;
    }

    private void drainMailbox() {
        List<T> batch;
        synchronized (mailbox) {
            if (mailbox.isEmpty()) return;
            batch = new ArrayList<>(mailbox);
            mailbox.clear();
        }
        for (T raw : batch) {
            UIPacket packet;
            try {
                packet = UIPacketCodec.decode(ops, raw);
            } catch (RuntimeException malformed) {
                CrystalGuiCore.LOGGER.warn("Session {}: dropping an undecodable packet: {}",
                        windowId, malformed.getMessage());
                continue;
            }
            if (packet.windowId() != windowId) {
                // Not ours. A packet in flight when a window closed would otherwise be applied to
                // whatever session took its place.
                continue;
            }
            handle(packet);
        }
    }

    @SuppressWarnings("unchecked")
    private void handle(UIPacket packet) {
        if (packet instanceof UIPacket.RequestDescription request) {
            if (!request.descHash().equals(descHash)) {
                CrystalGuiCore.LOGGER.warn("Session {}: client asked for description {} but this "
                        + "session is serving {}", windowId, request.descHash(), descHash);
                return;
            }
            send(new UIPacket.Description<>(windowId, descHash, encodedDescription));

        } else if (packet instanceof UIPacket.UiEvent<?> event) {
            UIElement element = NetworkIds.find(root, event.nid());
            if (element == null) {
                CrystalGuiCore.LOGGER.warn("Session {}: event for unknown element {}", windowId, event.nid());
                return;
            }
            var byKind = handlers.get(element);
            var handler = byKind == null ? null : byKind.get(event.kind());
            if (handler == null) {
                // A client reporting something nobody asked for. Not fatal, but not normal either —
                // it means the two sides disagree about the description.
                CrystalGuiCore.LOGGER.warn("Session {}: no handler for '{}' on element {}",
                        windowId, event.kind(), event.nid());
                return;
            }
            T payload = (T) event.payload();
            handler.accept(new UiEventContext<>(this, element,
                    payload == null ? new StateMap<>(ops) : new StateMap<>(ops, payload)));

        } else if (packet instanceof UIPacket.RpcCall<?> call) {
            dispatchRpc((UIPacket.RpcCall<T>) call);

        } else if (packet instanceof UIPacket.RpcResult<?> result) {
            UIPacket.RpcResult<T> typed = (UIPacket.RpcResult<T>) result;
            rpc.complete(typed.callId(), typed.ok(), typed.value(), typed.error());
        }
    }

    private void dispatchRpc(UIPacket.RpcCall<T> call) {
        rpc.dispatch(call.method(), call.args(), new RpcRegistry.Responder<T>() {
            @Override
            public void ok(StateMap<T> value) {
                if (call.callId() != 0) {
                    send(new UIPacket.RpcResult<>(windowId, call.callId(), true,
                            value == null ? null : value.encode(), ""));
                }
            }

            @Override
            public void fail(String error) {
                if (call.callId() != 0) {
                    send(new UIPacket.RpcResult<>(windowId, call.callId(), false, null, error));
                }
            }
        });
    }

    private void send(UIPacket packet) {
        transport.send(UIPacketCodec.encode(ops, packet));
    }

    // ── UITreeObserver ──────────────────────────────────────────────────────

    @Override
    public void onAttached(UIElement element) {
        // Structural deltas arrive in the next milestone; recorded now so nothing is lost meanwhile.
        dirtyIdentity.add(element);
    }

    @Override
    public void onDetached(UIElement element) {
        dirtyState.remove(element);
        dirtyIdentity.remove(element);
    }

    @Override
    public void onStateDirty(UIElement element) {
        dirtyState.add(element);
    }

    @Override
    public void onIdentityDirty(UIElement element) {
        dirtyIdentity.add(element);
    }

    /** Visible for tests until deltas exist — what the next flush would have to cover. */
    public Set<UIElement> pendingStateChanges() {
        return Set.copyOf(dirtyState);
    }
}
