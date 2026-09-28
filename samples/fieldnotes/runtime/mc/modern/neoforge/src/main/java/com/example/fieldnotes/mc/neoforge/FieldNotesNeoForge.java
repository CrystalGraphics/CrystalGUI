package com.example.fieldnotes.mc.neoforge;

import com.crystalgraphics.mc.shared.VariantEntry;
import com.example.fieldnotes.FieldNotes;
import com.example.fieldnotes.mc.modern.Game;

/** This node's NeoForge variant, which {@link NeoForgeBootstrap} starts. */
public final class FieldNotesNeoForge implements VariantEntry {

    @Override
    public void start(Object context) {
        FieldNotes.start("NeoForge", Game.version(), Game.id("notes"));
    }
}
