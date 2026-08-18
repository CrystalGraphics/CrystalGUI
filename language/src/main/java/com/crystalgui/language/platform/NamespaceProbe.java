package com.crystalgui.language.platform;

/**
 * How to tell a readable runtime from an obfuscated one — <b>by asking, never by being told</b>.
 *
 * <p>A 1.7.10 development client runs Minecraft recompiled at MCP names, so {@code World} really does
 * declare {@code getBlock} and the mapping is the identity. The same client in production declares
 * {@code func_147439_a}. Both are ordinary, and the difference decides whether a
 * {@link com.crystalgui.language.map.MappingSet} is needed at all.</p>
 *
 * <h3>Why this is a probe and not a flag</h3>
 *
 * <p>A flag someone sets is a flag that will be wrong in exactly the environment nobody tests. The two
 * environments differ in an observable way, so observing it costs one class read at startup and cannot
 * disagree with reality. A setting can.</p>
 *
 * <h3>It reads through the same byte source as the compiler</h3>
 *
 * <p>Deliberately, and it is the reason this is data on {@link ScriptPlatform} rather than a check
 * somebody writes against the filesystem: the disk view lies on every Minecraft platform — 1.7.10
 * production ships Notch-obfuscated jars whose classes are remapped <em>as they load</em>. A probe that
 * read a file could therefore answer differently from what the compiler will later resolve against,
 * which is the worst of both.</p>
 */
public final class NamespaceProbe {

    /** Nothing to decide — the caller already knows which namespace it is in. */
    public static final NamespaceProbe NONE = new NamespaceProbe("", "");

    private final String internalName;
    private final String readableMember;

    private NamespaceProbe(String internalName, String readableMember) {
        this.internalName = internalName;
        this.readableMember = readableMember;
    }

    /**
     * @param internalName   the type to read, with slashes — e.g. {@code net/minecraft/world/World}
     * @param readableMember a member that type declares <b>only</b> under the readable namespace — e.g.
     *                       {@code getBlock}, which in production is {@code func_147439_a}
     */
    public static NamespaceProbe declaring(String internalName, String readableMember) {
        return new NamespaceProbe(internalName, readableMember);
    }

    public boolean isNone() {
        return internalName.isEmpty();
    }

    /** The type whose members answer the question. */
    public String internalName() {
        return internalName;
    }

    /** The member whose presence means "this runtime is already readable". */
    public String readableMember() {
        return readableMember;
    }

    @Override
    public String toString() {
        return isNone() ? "NamespaceProbe.NONE"
                : "NamespaceProbe[" + internalName + "#" + readableMember + "]";
    }
}
