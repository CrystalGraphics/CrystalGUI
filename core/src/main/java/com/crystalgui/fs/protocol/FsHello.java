package com.crystalgui.fs.protocol;

import com.crystalgui.serialization.Codec;
import com.crystalgui.serialization.Codecs;
import com.crystalgui.serialization.DynamicOps;

import java.util.List;
import java.util.Locale;

/**
 * <b>What the server is</b> — the first thing a client asks, and the answer to four questions it used
 * to guess at.
 *
 * <h3>The provider's facts could not reach the client</h3>
 *
 * <p>{@code plan_fs_rewrite.md} D21, G7:</p>
 *
 * <ul>
 *   <li><b>Case.</b> {@code PATH_CASE_SENSITIVE} was advertised as a capability and read by nobody. So
 *       the client could not know whether {@code Main.java} and {@code main.java} are one document, and
 *       opening both on a folding host gave two documents over one file that overwrote each other.</li>
 *   <li><b>Names.</b> New File found out a name was reserved by making the round trip and being
 *       refused, which on Windows means {@code CON}, {@code PRN}, a trailing dot or a trailing space —
 *       none of which look wrong while you are typing them.</li>
 *   <li><b>Size.</b> The editor had one hard cap and no tiers, so a 30 MB log opened with a tokenizer,
 *       a folding pass and a language engine on it.</li>
 *   <li><b>Version.</b> There was none. Two builds of a mod disagreeing about a payload had no way to
 *       say so, and the failure was a missing field read as a default.</li>
 * </ul>
 *
 * <p>Modelled on HTTP/2's {@code SETTINGS}, which the wire's own multiplexer already speaks: the peer
 * states its parameters once, up front, rather than each side probing for them.</p>
 */
public record FsHello(int protocolVersion,
                      boolean caseSensitive,
                      List<String> reservedNames,
                      int maxNameLength,
                      long servicesTierBytes,
                      long readOnlyTierBytes,
                      long maxFileBytes) {

    /**
     * Bumped only for a change a client that has not been rebuilt cannot survive.
     *
     * <p>Additive fields do not bump it: {@code Codecs.MapCodecReader} reads by name, so a field a
     * client has never heard of costs it nothing. What bumps it is a field changing meaning or a method
     * changing shape — the things a tolerant reader cannot absorb.</p>
     */
    public static final int VERSION = 1;

    /**
     * Above this a document loses its tokenizer, its folding and its language services.
     *
     * <p>VS Code's own first threshold, and for its reason: at this size a syntax pass costs more than
     * it is worth and a language engine costs far more. The file still opens and is still editable.</p>
     */
    public static final long DEFAULT_SERVICES_TIER = 5L * 1024 * 1024;

    /** Above this a document opens read-only — it can be looked at, and editing it is not offered. */
    public static final long DEFAULT_READ_ONLY_TIER = 50L * 1024 * 1024;

    /** Windows refuses these whatever the extension, and a workspace served from one must too. */
    public static final List<String> WINDOWS_RESERVED = List.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    /**
     * Whether this name is one the host will accept — asked <b>before</b> the round trip.
     *
     * <p>Reserved names are matched on the stem, because {@code CON.txt} is refused too. A trailing dot
     * or space is refused because Windows silently strips them, which creates a file under a name the
     * person did not type.</p>
     */
    public boolean isValidName(String name) {
        if (name == null || name.isEmpty()) return false;
        if (name.length() > maxNameLength) return false;
        if (name.equals(".") || name.equals("..")) return false;
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) return false;
        char last = name.charAt(name.length() - 1);
        if (last == '.' || last == ' ') return false;
        int dot = name.indexOf('.');
        String stem = (dot <= 0 ? name : name.substring(0, dot)).toUpperCase(Locale.ROOT);
        for (String reserved : reservedNames) {
            if (stem.equals(reserved)) return false;
        }
        return true;
    }

    /** Which tier a file of this size falls in. */
    public SizeTier tierOf(long bytes) {
        if (bytes > maxFileBytes) return SizeTier.REFUSED;
        if (bytes > readOnlyTierBytes) return SizeTier.READ_ONLY;
        if (bytes > servicesTierBytes) return SizeTier.NO_SERVICES;
        return SizeTier.ORDINARY;
    }

    /** What a file's size costs it. */
    public enum SizeTier {
        /** Everything: syntax, folding, a language engine. */
        ORDINARY,
        /** Editable, with no tokenizer, no folding and no services. */
        NO_SERVICES,
        /** Viewable only. */
        READ_ONLY,
        /** Not served at all. */
        REFUSED
    }

    /**
     * What a client assumes when it has not asked yet, or is talking to a server too old to answer.
     *
     * <p>Case-<b>sensitive</b> is the conservative default and not the common one: assuming a folding
     * host merges two documents that are genuinely different files on Linux, which loses work; assuming
     * a sensitive one opens two documents over one file on Windows, which the etag conflict then
     * catches. The failure that is caught is the one to prefer.</p>
     */
    public static FsHello unknown() {
        return new FsHello(VERSION, true, WINDOWS_RESERVED, 255,
                DEFAULT_SERVICES_TIER, DEFAULT_READ_ONLY_TIER, 100L * 1024 * 1024);
    }

    public static final Codec<FsHello> CODEC = new Codec<>() {
        @Override
        public <U> U encode(DynamicOps<U> ops, FsHello value) {
            return Codecs.<U>map(ops)
                    .field("version", Codecs.INT, value.protocolVersion())
                    .optional("caseSensitive", Codecs.BOOL, value.caseSensitive(), true)
                    .optionalList("reserved", Codecs.STRING, value.reservedNames())
                    .optional("maxName", Codecs.INT, value.maxNameLength(), 255)
                    .optional("servicesTier", Codecs.LONG, value.servicesTierBytes(),
                            DEFAULT_SERVICES_TIER)
                    .optional("readOnlyTier", Codecs.LONG, value.readOnlyTierBytes(),
                            DEFAULT_READ_ONLY_TIER)
                    .optional("maxFile", Codecs.LONG, value.maxFileBytes(), 0L)
                    .build();
        }

        @Override
        public <U> FsHello decode(DynamicOps<U> ops, U input) {
            Codecs.MapCodecReader<U> in = Codecs.read(ops, input);
            List<String> reserved = in.optionalList("reserved", Codecs.STRING);
            return new FsHello(in.field("version", Codecs.INT),
                    in.optional("caseSensitive", Codecs.BOOL, true),
                    reserved.isEmpty() ? WINDOWS_RESERVED : reserved,
                    in.optional("maxName", Codecs.INT, 255),
                    in.optional("servicesTier", Codecs.LONG, DEFAULT_SERVICES_TIER),
                    in.optional("readOnlyTier", Codecs.LONG, DEFAULT_READ_ONLY_TIER),
                    in.optional("maxFile", Codecs.LONG, 100L * 1024 * 1024));
        }
    };
}
