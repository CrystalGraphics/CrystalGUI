package com.crystalgui.mc.fabric.v1165.common.lang;

import com.crystalgraphics.platform.service.CgGameClasses;

/** A game host living where a merged jar's Fabric 1.16.5 node puts its own. @see NodeCopiesTypeIndexTest */
public final class NodeGameClasses implements CgGameClasses.Host {

    @Override
    public String minecraftVersion() {
        return "";
    }

    @Override
    public byte[] bytes(String internalName) {
        return null;
    }
}
