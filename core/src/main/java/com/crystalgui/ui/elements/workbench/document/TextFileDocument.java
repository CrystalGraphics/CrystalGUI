package com.crystalgui.ui.elements.workbench.document;

import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.editor.TextEditor;
import com.crystalgui.ui.elements.workbench.FileDocument;

import java.nio.charset.StandardCharsets;

/**
 * A {@link FileDocument} backed by a {@link TextEditor} — what every file opened before the seam existed.
 *
 * <p>Deliberately a wrapper rather than {@code TextEditor implements FileDocument}: the editor is a widget
 * that knows nothing about paths, saving or workspaces, and is used in places that have no file behind
 * them at all — the shader graph's emitted source, a harness scene. Making it implement a file interface
 * would give every one of those an {@code encode()} nobody calls.</p>
 *
 * <p><b>No fields beyond the editor.</b> This carried the bytes last read from disk and compared against
 * them to answer "am I modified", which every other document kind would have had to repeat. The workbench
 * keeps that baseline now, because it is the thing that does the reading and writing.</p>
 */
public record TextFileDocument(TextEditor editor) implements FileDocument {

    @Override
    public UIElement view() {
        return editor;
    }

    @Override
    public byte[] encode() {
        return editor.getText().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void adopt(byte[] bytes) {
        // setText already suppresses an identical write, so re-reading an unchanged file costs nothing and
        // does not disturb the caret -- see TextEditor.setText.
        editor.setText(new String(bytes, StandardCharsets.UTF_8));
    }
}
