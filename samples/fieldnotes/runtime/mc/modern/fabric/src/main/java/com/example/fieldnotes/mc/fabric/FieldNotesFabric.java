package com.example.fieldnotes.mc.fabric;

import com.crystalgraphics.mc.shared.VariantEntry;
import com.example.fieldnotes.FieldNotes;
import com.example.fieldnotes.mc.modern.Game;

/** This node's Fabric variant, which {@link FabricBootstrap} starts. */
public final class FieldNotesFabric implements VariantEntry {

    @Override
    public void start(Object context) {
        FieldNotes.start("Fabric", Game.version(), Game.id("notes"));
    }
}
