package com.crystalgui.mc.fabric.v1165.common.lang;

import com.crystalgui.language.map.ReadableView;
import com.crystalgui.language.platform.MappingCoordinates;
import com.crystalgui.language.platform.NamespaceProbe;
import com.crystalgui.language.platform.ScriptService;

import java.nio.file.Path;

/** A {@link ScriptService} living where a merged jar's Fabric 1.16.5 node puts its own. @see NodeCopiesTypeIndexTest */
public final class NodeScriptService implements ScriptService {

    @Override
    public ReadableView.ByteSource liveBytes() {
        return NONE.liveBytes();
    }

    @Override
    public Path cacheRoot() {
        return NONE.cacheRoot();
    }

    @Override
    public MappingCoordinates mappings() {
        return NONE.mappings();
    }

    @Override
    public NamespaceProbe namespaceProbe() {
        return NONE.namespaceProbe();
    }

    @Override
    public String runtimeClassName(String onDiskInternalName) {
        return onDiskInternalName;
    }
}
