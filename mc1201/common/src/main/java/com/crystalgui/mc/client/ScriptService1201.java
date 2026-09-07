package com.crystalgui.mc.client;

import java.nio.file.Path;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.core.storage.StorageLayout;
import com.crystalgui.language.platform.MappingCoordinates;
import com.crystalgui.language.platform.NamespaceProbe;
import com.crystalgui.language.platform.ScriptService;
import com.crystalgui.language.platform.ScriptServices;
import com.crystalgui.language.map.ReadableView;

import net.minecraft.client.Minecraft;

/**
 * The 1.20.x half of {@link ScriptService} — <b>where to put things, and nothing else</b>.
 *
 * <pre>{@code
 * ScriptService1201.install();   // from Lifecycle1201.bootstrapClient()
 * }</pre>
 *
 * <p>Registered for {@link #cacheRoot()} alone, and that is the whole reason it exists.
 * {@code EngineHost.defaultSource()} tries a configured directory, then the band bundled in the jar,
 * then a download — and <b>the second and third both begin by asking this service where they may
 * write</b>. With none registered the answer is {@code ScriptService.NONE}'s
 * {@code build/crystalgui-cache}, resolved against the process working directory, which on a client is
 * a {@code build/} folder inside the player's game directory. It works, and it is the wrong place.</p>
 *
 * <p><b>The other three members are declined honestly.</b> {@code liveBytes()} must answer with what
 * will actually execute — transformers and mixins included — and 1.20.x has ModLauncher or Knot rather
 * than LaunchWrapper, neither of which exposes a transformed-bytes call. Reading the classloader is
 * what {@code NONE} already does and its own javadoc says that lies on a Minecraft host, so this
 * inherits that answer rather than dressing it up. No mappings and no namespace probe follow from the
 * same absence. {@code plan/platform-mc1201.md} §3.7.</p>
 */
public final class ScriptService1201 implements ScriptService {

    private final Path gameDirectory;

    private ScriptService1201(Path gameDirectory) {
        this.gameDirectory = gameDirectory;
    }

    /** Registers one, if a client is up. Idempotent. */
    public static void install() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gameDirectory == null) return;
        CgPlatform.provide(ScriptServices.SERVICE, new ScriptService1201(mc.gameDirectory.toPath()));
    }

    /**
     * {@code crystalgui/cache/}, beside everything else this engine writes.
     *
     * <p>Under the engine's own root rather than a directory of its own: {@code StorageLayout} states
     * that tree once and nothing outside it may spell those segments.</p>
     */
    @Override
    public Path cacheRoot() {
        return StorageLayout.cacheIn(gameDirectory).toAbsolutePath().normalize();
    }

    @Override
    public ReadableView.ByteSource liveBytes() {
        return ScriptService.NONE.liveBytes();
    }

    @Override
    public MappingCoordinates mappings() {
        return MappingCoordinates.NONE;
    }

    @Override
    public NamespaceProbe namespaceProbe() {
        return NamespaceProbe.NONE;
    }

    /** Identity: a 1.20.x dev run is already deobfuscated and nothing here remaps. */
    @Override
    public String runtimeClassName(String onDiskInternalName) {
        return onDiskInternalName;
    }

    @Override
    public String toString() {
        return "ScriptService1201[cache=" + cacheRoot() + "]";
    }
}
