package com.crystalgui.fs.client;

import com.crystalgui.core.async.Reply;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.protocol.FsError;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.lang.SymbolInfo;
import java.util.function.Consumer;
import org.jetbrains.annotations.Nullable;

/**
 * <b>Where a non-project resource's content comes from</b> — a decompiler, a code generator, a scratch
 * buffer.
 *
 * <p>Register one per scheme, through {@link Workspace#registerScheme} for a scheme that belongs to
 * one server or {@link ContentProviders#contribute} for one that belongs to the process. The client
 * then routes on the scheme and no caller above ever asks which side answered.</p>
 *
 * <p><b>Every answer is a {@link Reply}</b>, because the work can be real: reading a source archive is
 * I/O and decompiling a class is hundreds of milliseconds, and neither may land on a frame. A provider
 * that already holds the bytes answers immediately.</p>
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

    /**
     * What file name this content should be TREATED as, for choosing a language.
     *
     * <p>{@code LanguageRegistry} answers by file name and a resource has none —
     * {@code library://java.util.ArrayList} is a type, not a file. Only the provider knows what it
     * produced, so only the provider can say that its output is Java. Defaults to
     * {@link #displayName}, then to the resource's own name, which is right for any scheme whose
     * content is already file-shaped.</p>
     */
    default String languageFileName(Resource resource) {
        String shown = displayName(resource);
        return shown == null ? resource.name() : shown;
    }

    /**
     * Where {@code member} is declared inside this content, or null.
     *
     * <p>A class with no attached source has no line numbers until it has been <b>decompiled</b>, so
     * the engine that answered "where is this declared" could only name the type. The text does not
     * exist at that moment and does here: this provider generated it and holds it cached.</p>
     *
     * <p>A {@link Reply}, because an exact answer means parsing the generated text — the same order of
     * cost as producing it, which must not land on a frame.</p>
     */
    default Reply<TextPoint> locate(Resource resource, String member) {
        return Reply.of(null);
    }

    /**
     * The DECLARATION this resource shows, when it shows one — or null.
     *
     * <p>A file name cannot answer what a resource holds: {@code FlexDirection.class} is an ENUM and
     * {@code Runnable.class} is an INTERFACE, and the extension is the same both times. A symbol rather
     * than an icon name, because the picture is not the only thing that follows — a {@code static
     * final} class carries two more marks and a tooltip that says "Final class" in words.</p>
     *
     * <p>May answer null for "not yet", and announce through {@link #onDidResolveSymbol} when it
     * knows: working it out can mean a decompile, which must not land on a frame.</p>
     */
    @Nullable
    default SymbolInfo symbolOf(Resource resource) {
        return null;
    }

    /** A resource this provider previously answered "not yet" about now has a symbol. */
    default Disposable onDidResolveSymbol(Consumer<Resource> listener) {
        return () -> {
        };
    }
}
