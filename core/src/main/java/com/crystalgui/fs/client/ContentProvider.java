package com.crystalgui.fs.client;

import com.crystalgui.core.async.Reply;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.protocol.FsError;
import org.jetbrains.annotations.Nullable;

/**
 * <b>Where a non-project resource's content comes from</b> — a decompiler, a code generator, a scratch
 * buffer.
 *
 * <h3>This is what makes one open lane possible</h3>
 *
 * <p>{@code plan_fs_rewrite.md} N1. A project file went over the wire and everything else went down a
 * second lane in the workbench, because there was nowhere to say "this resource's bytes come from
 * somewhere other than the server". With a provider per scheme there is one {@code open}: the client
 * routes on the scheme and the caller never knows which side answered.</p>
 *
 * <h3>Asynchronous by construction</h3>
 *
 * <p>{@code ResourceContentProvider.read} was synchronous and its own javadoc noted it was "reached
 * from a paint path" — so every caller wrapped it in a job anyway, and the one that did not decompiled
 * a class on the frame thread. A {@link Reply} is what a job already is, so a provider that has to do
 * real work hands one back and a provider that has the bytes answers immediately.</p>
 */
public interface ContentProvider {

    /** The resource's bytes. */
    Reply<byte[]> read(Resource resource);

    /**
     * Whether this resource may be written.
     *
     * <p><b>Read-only by default</b>, and that is the honest answer for every provider that exists: a
     * decompiled class and a generated shader are derived, and writing one writes over something that
     * will be regenerated. A provider that genuinely owns its storage says so.</p>
     */
    default boolean isReadOnly(Resource resource) {
        return true;
    }

    /** Writes it back. Only ever called when {@link #isReadOnly} said false. */
    default Reply<String> write(Resource resource, byte[] content) {
        return Reply.failed(new FsError(
                FsError.NOT_PERMITTED, resource + " is read-only"));
    }

    /**
     * What a tab should be titled, or null to use the resource's own name.
     *
     * <p>Null lets the caller decide, which is right for a scheme whose content has no name of its own —
     * and a provider that knows better ({@code Minecraft.class} rather than a hash) says so.</p>
     */
    @Nullable
    default String displayName(Resource resource) {
        return null;
    }
}
