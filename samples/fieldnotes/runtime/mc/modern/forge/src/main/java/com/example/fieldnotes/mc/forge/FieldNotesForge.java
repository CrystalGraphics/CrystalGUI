package com.example.fieldnotes.mc.forge;

import com.crystalgraphics.mc.shared.VariantEntry;
import com.example.fieldnotes.FieldNotes;
import com.example.fieldnotes.mc.modern.Game;

/** This node's Forge variant, which {@link ForgeBootstrap} starts: hands the core what only the game knows. */
public final class FieldNotesForge implements VariantEntry {

    @Override
    public void start(Object context) {
        FieldNotes.start("Forge", Game.version(), Game.id("notes"));
    }
}
