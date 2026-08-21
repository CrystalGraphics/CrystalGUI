package com.crystalgui.net.protocol;

import com.crystalgui.serialization.CodecException;
import com.crystalgui.serialization.Codecs;
import com.crystalgui.serialization.DynamicOps;

/**
 * The envelope on the wire — and the only codec in the protocol that is allowed to know every case.
 *
 * <p>It has four branches because {@link Envelope} has four types, and <b>it is meant never to grow a
 * fifth</b>. That is the contrast with {@code UIPacketCodec}, whose encode and decode switches gained an
 * arm for every message anyone added: this one is finished.</p>
 *
 * <p><b>Payloads are carried, never inspected.</b> A payload arrives as an opaque {@code T} in the
 * session's own ops and is handed to whichever handler claimed the method. Three things follow, and all
 * three are the point:</p>
 *
 * <ul>
 *   <li>A subsystem's wire format is private to that subsystem — {@code workspace/*} can change shape
 *       without this file knowing.</li>
 *   <li>A message can be <em>routed</em> without being parsed, so an oversized or unwanted payload is
 *       refused before it costs anything to decode.</li>
 *   <li>There is no central place where two subsystems' field names can collide.</li>
 * </ul>
 *
 * <h3>Field names are short because they are on the wire</h3>
 *
 * <p>{@code k}/{@code i}/{@code m}/{@code p} rather than {@code kind}/{@code id}/{@code method}/
 * {@code payload}. Every byte here is paid on every message, and the client→server budget is ~32 KB per
 * frame — see {@code plan_wire.md}. The method name stays spelled out, because it is the one field a
 * human reads when a capture is dumped.</p>
 */
public final class EnvelopeCodec {

    /**
     * Bumped when the envelope's own shape changes — not when a method is added or removed.
     *
     * <p>Which is most of why this number should now stay still: under {@code UIPacket} any new message
     * was arguably a protocol change, and here the vocabulary moves without the grammar moving. A peer
     * that does not know a method says so with {@link ProtocolErrors#METHOD_NOT_FOUND}, per message,
     * rather than failing the whole connection over a version integer.</p>
     */
    public static final int VERSION = 1;

    // Wire tags. Explicit values, never an enum ordinal: reordering the constants must not be able to
    // silently change what a byte means to a peer built yesterday.
    private static final String KIND_REQUEST = "q";
    private static final String KIND_RESPONSE = "r";
    private static final String KIND_NOTIFY = "n";
    private static final String KIND_CANCEL = "x";

    private EnvelopeCodec() {
    }

    public static <T> T encode(DynamicOps<T> ops, Envelope envelope) {
        if (envelope instanceof Envelope.Request<?> request) {
            @SuppressWarnings("unchecked")
            T payload = (T) request.payload();
            Codecs.MapCodecBuilder<T> out = Codecs.map(ops)
                    .field("k", Codecs.STRING, KIND_REQUEST)
                    .field("i", Codecs.INT, request.id())
                    .field("m", Codecs.STRING, request.method());
            if (payload != null) out.raw("p", payload);
            return out.build();
        }
        if (envelope instanceof Envelope.Response<?> response) {
            @SuppressWarnings("unchecked")
            T payload = (T) response.payload();
            Codecs.MapCodecBuilder<T> out = Codecs.map(ops)
                    .field("k", Codecs.STRING, KIND_RESPONSE)
                    .field("i", Codecs.INT, response.id())
                    .field("ok", Codecs.BOOL, response.ok());
            if (payload != null) out.raw("p", payload);
            out.optional("e", Codecs.STRING, response.error() == null ? "" : response.error(), "");
            return out.build();
        }
        if (envelope instanceof Envelope.Notification<?> notification) {
            @SuppressWarnings("unchecked")
            T payload = (T) notification.payload();
            Codecs.MapCodecBuilder<T> out = Codecs.map(ops)
                    .field("k", Codecs.STRING, KIND_NOTIFY)
                    .field("m", Codecs.STRING, notification.method());
            if (payload != null) out.raw("p", payload);
            return out.build();
        }
        if (envelope instanceof Envelope.Cancel cancel) {
            return Codecs.map(ops)
                    .field("k", Codecs.STRING, KIND_CANCEL)
                    .field("i", Codecs.INT, cancel.id())
                    .build();
        }
        // Unreachable while Envelope has four implementations, and a real failure the moment a fifth is
        // added without touching this file -- which is the one edit this design still requires.
        throw new CodecException("no encoder for envelope " + envelope.getClass().getName());
    }

    public static <T> Envelope decode(DynamicOps<T> ops, T input) {
        Codecs.MapCodecReader<T> in = Codecs.read(ops, input);
        String kind = in.field("k", Codecs.STRING);
        switch (kind) {
            case KIND_REQUEST:
                return new Envelope.Request<>(
                        in.field("i", Codecs.INT),
                        in.field("m", Codecs.STRING),
                        in.has("p") ? in.raw("p") : null);
            case KIND_RESPONSE:
                return new Envelope.Response<>(
                        in.field("i", Codecs.INT),
                        in.field("ok", Codecs.BOOL),
                        in.has("p") ? in.raw("p") : null,
                        in.optional("e", Codecs.STRING, ""));
            case KIND_NOTIFY:
                return new Envelope.Notification<>(
                        in.field("m", Codecs.STRING),
                        in.has("p") ? in.raw("p") : null);
            case KIND_CANCEL:
                return new Envelope.Cancel(in.field("i", Codecs.INT));
            default:
                throw new CodecException("unknown envelope kind '" + kind + "'");
        }
    }
}
