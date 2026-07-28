package com.crystalgui.net;

import java.util.List;

/**
 * One message between a {@code ServerUiSession} and its client mirror.
 *
 * <h3>Every packet carries a window id</h3>
 * <p>LDLib2 resolves each incoming packet against "whatever menu the player currently has open",
 * which means a packet in flight when a GUI closes lands on the <em>next</em> one and is applied to
 * whatever element happens to share its index. A window id costs four bytes and makes that
 * impossible.</p>
 *
 * <p>Payload-carrying packets are generic in the encoded representation, so a description can be
 * cached and forwarded without being decoded into live elements first — a cache holding attached
 * {@code UIElement}s would be a cache of things that can only exist in one place at a time.</p>
 */
public interface UIPacket {

    /** Wire discriminator. Must be stable — it is the key {@link UIPacketCodec} dispatches on. */
    String type();

    /** Which session this belongs to. */
    int windowId();

    // ── Server → client ─────────────────────────────────────────────────────

    /**
     * Open a window. Carries the description's <em>hash</em>, not the description.
     *
     * <p>A client that already holds that hash rebuilds immediately with no transfer at all; one that
     * doesn't asks for it. Re-opening the same UI therefore costs a single small packet, however
     * large the tree is.</p>
     */
    record OpenWindow(int protocol, int windowId, String descHash, int elementCount,
                      List<SheetRef> sheets, boolean useUserAgentSheet) implements UIPacket {
        public static final String TYPE = "open";

        @Override
        public String type() {
            return TYPE;
        }
    }

    /** The description body, sent only in answer to a {@link RequestDescription}. */
    record Description<T>(int windowId, String descHash, T root) implements UIPacket {
        public static final String TYPE = "desc";

        @Override
        public String type() {
            return TYPE;
        }
    }

    /** Close a window. {@code reason} is for logs, never shown to a player. */
    record CloseWindow(int windowId, String reason) implements UIPacket {
        public static final String TYPE = "close";

        @Override
        public String type() {
            return TYPE;
        }
    }

    // ── Client → server ─────────────────────────────────────────────────────

    /**
     * Widget state that changed since the last flush, one entry per element.
     *
     * <p>Batched, and re-read at flush time rather than captured at mutation time, so ten changes to
     * one widget in a tick become one entry holding the final value. LDLib2 instead re-serializes
     * every synced value every tick just to <em>detect</em> change, which for a large inventory is
     * hundreds of codec passes per side per tick.</p>
     */
    record StateDelta<T>(int windowId, List<Entry<T>> entries) implements UIPacket {
        public static final String TYPE = "state";

        /** {@code state} is a widget's own {@code writeState} output. */
        public record Entry<T>(int nid, T state) {
        }

        @Override
        public String type() {
            return TYPE;
        }
    }

    /** Cache miss — the client has never seen {@code descHash} and needs the body. */
    record RequestDescription(int windowId, String descHash) implements UIPacket {
        public static final String TYPE = "req-desc";

        @Override
        public String type() {
            return TYPE;
        }
    }

    /**
     * A user did something the server asked to hear about.
     *
     * <p>Deliberately <b>positionless</b>. The server has no layout, so any coordinate it received
     * would be a number it cannot interpret — {@code screenToLocal} against a tree with no geometry
     * returns nonsense, and a widget doing pointer arithmetic on it would be silently wrong. LDLib2
     * can ride its server handlers on the real capture/bubble dispatch because its server ticks
     * elements; here the client runs the real dispatch and reports only what it meant.</p>
     */
    record UiEvent<T>(int windowId, int nid, String kind, T payload) implements UIPacket {
        public static final String TYPE = "event";

        @Override
        public String type() {
            return TYPE;
        }
    }

    /** A typed call in either direction. {@code callId} is 0 when no reply is wanted. */
    record RpcCall<T>(int windowId, int callId, String method, T args) implements UIPacket {
        public static final String TYPE = "rpc";

        @Override
        public String type() {
            return TYPE;
        }
    }

    /** The reply. {@code ok} false means {@code error} is set and {@code value} is absent. */
    record RpcResult<T>(int windowId, int callId, boolean ok, T value, String error) implements UIPacket {
        public static final String TYPE = "rpc-result";

        @Override
        public String type() {
            return TYPE;
        }
    }
}
