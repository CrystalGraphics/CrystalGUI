package com.crystalgui.net;

import com.crystalgui.serialization.Codec;
import com.crystalgui.serialization.CodecException;
import com.crystalgui.serialization.Codecs;
import com.crystalgui.serialization.DynamicOps;

import java.util.ArrayList;
import java.util.List;

/**
 * Encodes and decodes every {@link UIPacket}, dispatching on its {@code type} discriminator.
 *
 * <h3>Protocol version</h3>
 * <p>{@link #PROTOCOL_VERSION} rides on every {@code OpenWindow}. A client that doesn't recognise it
 * refuses the window rather than opening one it will misinterpret. LDLib2 registers its channel with
 * no version at all, so two different builds of it connect happily and then speak past each other —
 * the failure surfaces as widgets showing wrong values, which is a long way from the cause.</p>
 *
 * <p><b>Bump this on any wire change</b>, including adding an optional field: the decoder's defaults
 * are what make an added field safe, and an older client has different defaults.</p>
 */
public final class UIPacketCodec {

    public static final int PROTOCOL_VERSION = 1;

    private UIPacketCodec() {
    }

    private static final Codec<SheetRef> SHEET_REF = new Codec<SheetRef>() {
        @Override
        public <T> T encode(DynamicOps<T> ops, SheetRef input) {
            return Codecs.map(ops)
                    .field("hash", Codecs.STRING, input.hash())
                    .optional("id", Codecs.STRING, input.id(), null)
                    .build();
        }

        @Override
        public <T> SheetRef decode(DynamicOps<T> ops, T input) {
            var in = Codecs.read(ops, input);
            return new SheetRef(in.field("hash", Codecs.STRING), in.optional("id", Codecs.STRING, null));
        }
    };

    /** Encodes any packet, tagging it with its type so {@link #decode} can dispatch. */
    public static <T> T encode(DynamicOps<T> ops, UIPacket packet) {
        Codecs.MapCodecBuilder<T> out = Codecs.map(ops)
                .field("type", Codecs.STRING, packet.type())
                .field("w", Codecs.INT, packet.windowId());

        if (packet instanceof UIPacket.OpenWindow open) {
            out.field("protocol", Codecs.INT, open.protocol());
            out.field("hash", Codecs.STRING, open.descHash());
            out.field("count", Codecs.INT, open.elementCount());
            out.optional("ua", Codecs.BOOL, open.useUserAgentSheet(), false);
            List<T> sheets = new ArrayList<>(open.sheets().size());
            for (SheetRef ref : open.sheets()) sheets.add(SHEET_REF.encode(ops, ref));
            if (!sheets.isEmpty()) out.raw("sheets", ops.createList(sheets));

        } else if (packet instanceof UIPacket.StateDelta<?> delta) {
            List<T> entries = new ArrayList<>(delta.entries().size());
            for (UIPacket.StateDelta.Entry<?> entry : delta.entries()) {
                @SuppressWarnings("unchecked")
                T state = (T) entry.state();
                entries.add(Codecs.map(ops).field("nid", Codecs.INT, entry.nid()).raw("s", state).build());
            }
            out.raw("entries", ops.createList(entries));

        } else if (packet instanceof UIPacket.UiEvent<?> event) {
            out.field("nid", Codecs.INT, event.nid());
            out.field("kind", Codecs.STRING, event.kind());
            @SuppressWarnings("unchecked")
            T payload = (T) event.payload();
            if (payload != null) out.raw("p", payload);

        } else if (packet instanceof UIPacket.RpcCall<?> call) {
            out.field("call", Codecs.INT, call.callId());
            out.field("m", Codecs.STRING, call.method());
            @SuppressWarnings("unchecked")
            T args = (T) call.args();
            if (args != null) out.raw("a", args);

        } else if (packet instanceof UIPacket.RpcResult<?> result) {
            out.field("call", Codecs.INT, result.callId());
            out.field("ok", Codecs.BOOL, result.ok());
            @SuppressWarnings("unchecked")
            T value = (T) result.value();
            if (value != null) out.raw("v", value);
            out.optional("err", Codecs.STRING, result.error(), "");

        } else if (packet instanceof UIPacket.Description<?> description) {
            out.field("hash", Codecs.STRING, description.descHash());
            // Already in this ops' representation — the session encodes the tree before wrapping it.
            @SuppressWarnings("unchecked")
            T root = (T) description.root();
            out.raw("root", root);

        } else if (packet instanceof UIPacket.RequestDescription request) {
            out.field("hash", Codecs.STRING, request.descHash());

        } else if (packet instanceof UIPacket.CloseWindow close) {
            out.optional("reason", Codecs.STRING, close.reason(), "");

        } else {
            throw new CodecException("No encoder for packet type '" + packet.type() + "'");
        }
        return out.build();
    }

    public static <T> UIPacket decode(DynamicOps<T> ops, T input) {
        var in = Codecs.read(ops, input);
        String type = in.field("type", Codecs.STRING);
        int windowId = in.field("w", Codecs.INT);

        return switch (type) {
            case UIPacket.OpenWindow.TYPE -> {
                List<SheetRef> sheets = new ArrayList<>();
                T rawSheets = in.raw("sheets");
                if (rawSheets != null) {
                    for (T sheet : ops.getListValue(rawSheets)) sheets.add(SHEET_REF.decode(ops, sheet));
                }
                yield new UIPacket.OpenWindow(
                        in.field("protocol", Codecs.INT),
                        windowId,
                        in.field("hash", Codecs.STRING),
                        in.field("count", Codecs.INT),
                        sheets,
                        in.optional("ua", Codecs.BOOL, false));
            }
            case UIPacket.StateDelta.TYPE -> {
                List<UIPacket.StateDelta.Entry<T>> entries = new ArrayList<>();
                for (T raw : ops.getListValue(in.raw("entries"))) {
                    var entry = Codecs.read(ops, raw);
                    entries.add(new UIPacket.StateDelta.Entry<>(
                            entry.field("nid", Codecs.INT), entry.raw("s")));
                }
                yield new UIPacket.StateDelta<>(windowId, entries);
            }
            case UIPacket.UiEvent.TYPE -> new UIPacket.UiEvent<>(
                    windowId, in.field("nid", Codecs.INT), in.field("kind", Codecs.STRING), in.raw("p"));

            case UIPacket.RpcCall.TYPE -> new UIPacket.RpcCall<>(
                    windowId, in.field("call", Codecs.INT), in.field("m", Codecs.STRING), in.raw("a"));

            case UIPacket.RpcResult.TYPE -> new UIPacket.RpcResult<>(
                    windowId, in.field("call", Codecs.INT), in.field("ok", Codecs.BOOL),
                    in.raw("v"), in.optional("err", Codecs.STRING, ""));

            case UIPacket.Description.TYPE -> new UIPacket.Description<>(
                    windowId, in.field("hash", Codecs.STRING), in.raw("root"));

            case UIPacket.RequestDescription.TYPE -> new UIPacket.RequestDescription(
                    windowId, in.field("hash", Codecs.STRING));

            case UIPacket.CloseWindow.TYPE -> new UIPacket.CloseWindow(
                    windowId, in.optional("reason", Codecs.STRING, ""));

            default -> throw new CodecException("Unknown packet type '" + type + "'");
        };
    }
}
