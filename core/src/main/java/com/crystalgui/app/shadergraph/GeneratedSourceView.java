package com.crystalgui.app.shadergraph;

import javax.annotation.Nullable;

import com.crystalgraphics.shadergraph.CgShaderEmitter;
import com.crystalgui.core.data.DataContext;
import com.crystalgui.core.notify.StatusBar;
import com.crystalgui.core.notify.StatusBarAlignment;
import com.crystalgui.core.notify.StatusBarEntry;
import com.crystalgui.core.notify.StatusBarEntryAccessor;
import com.crystalgui.graph.NodeData;
import com.crystalgui.text.syntax.Language;
import com.crystalgui.text.syntax.LanguageRegistry;
import com.crystalgui.text.syntax.SyntaxTokenizer;
import com.crystalgui.ui.data.UiDataKeys;
import com.crystalgui.widget.texteditor.TextEditor;

/**
 * The GLSL a {@link ShaderGraphDocument} compiles to, read-only and kept current — one per tab showing it, since a
 * tab is a view and two of them may show one graph's source.
 *
 * <pre>{@code
 * GeneratedSourceView source = new GeneratedSourceView(document);
 * host.append(source.editor());   // recompiled in place from then on
 * }</pre>
 *
 * <p>Says which node wrote the line the caret is on, as {@code "line 12 emitted by cg:Math/Basic/multiply"}: a
 * driver reports an error at a line in code the user never wrote, and this turns it into somewhere to go and look.</p>
 */
public final class GeneratedSourceView {

    /** On the editor, which the sheets style as the generated file. */
    public static final String SOURCE_CLASS = "__shader-source__";

    /**
     * Which node emitted the line the caret is on — a separate status item from the compile summary, since it
     * changes on every caret move and sharing one slot erased the summary a few milliseconds after every compile.
     */
    public static final String LINE_OWNER_STATUS = "shadergraph.lineOwner";

    private static final int LINE_OWNER_PRIORITY = 90;

    private final ShaderGraphDocument document;
    private final TextEditor editor = new TextEditor();

    @Nullable
    private StatusBarEntryAccessor lineOwnerEntry;

    public GeneratedSourceView(ShaderGraphDocument document) {
        this.document = document;
        editor.addClass(SOURCE_CLASS);
        editor.setReadOnly(true);
        // The generated file IS GLSL, highlighted by whatever the registry has for it -- the grammar when the
        // language mod is installed, the keyword lexer when it is not -- as a .glsl file tab is. Held while shown
        // and closed when not: a grammar's tokenizer holds a parse tree natively, and no document owns this one.
        editor.setLanguage(Language.glsl());
        editor.whileConnected(() -> {
            SyntaxTokenizer tokenizer = LanguageRegistry.forLanguage(Language.glsl()).newTokenizer();
            editor.setTokenizer(tokenizer);
            return () -> {
                editor.setTokenizer(SyntaxTokenizer.NONE);
                tokenizer.close();
            };
        });
        editor.onSelectionChanged.connect(this::reportLineOwner);
        CgShaderEmitter.Result last = document.lastCompile();
        if (last != null) show(last);
        editor.whileConnected(() -> document.shader().compiled.connect(this::show));
        editor.onConnected(() -> {
            CgShaderEmitter.Result now = document.lastCompile();
            if (now != null) show(now);
        });
    }

    public TextEditor editor() {
        return editor;
    }

    public ShaderGraphDocument document() {
        return document;
    }

    private void show(CgShaderEmitter.Result result) {
        String text = result.source().isEmpty()
                ? "// nothing to compile yet\n" + String.join("\n", result.errors())
                : result.source();
        if (!text.equals(editor.getText())) editor.setText(text);
    }

    private void reportLineOwner() {
        CgShaderEmitter.Result last = document.lastCompile();
        if (last == null) return;
        int line = editor.caretPoint().row() + 1;
        String owner = last.ownerOfLine(line);
        if (owner == null) return;
        NodeData node = document.graph().node(owner);
        StatusBarEntry entry = StatusBarEntry.of("Emitting node", "line " + line + " emitted by "
                + (node == null ? owner : node.typeId() + "  (" + owner + ")"));
        if (lineOwnerEntry != null) {
            lineOwnerEntry.update(entry);
            return;
        }
        StatusBar bar = DataContext.from(editor).get(UiDataKeys.STATUS_BAR);
        if (bar == null) return;
        lineOwnerEntry = bar.addEntry(entry, LINE_OWNER_STATUS, StatusBarAlignment.LEFT, LINE_OWNER_PRIORITY);
    }
}
