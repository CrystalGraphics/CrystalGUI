package com.crystalgui.workbench.editor;

import com.crystalgui.document.BytesDocumentModel;
import com.crystalgui.document.DocumentKind;
import com.crystalgui.document.TextDocumentModel;
import com.crystalgui.text.TextEncoding;
import com.crystalgui.text.cursor.IndentationProvider;
import com.crystalgui.text.fold.FoldingRangeProvider;
import com.crystalgui.text.syntax.DocComments;
import com.crystalgui.text.syntax.LanguageRegistry;
import com.crystalgui.widget.texteditor.TextEditor;
import com.crystalgui.workbench.Workbench;
import com.crystalgui.workbench.WorkbenchSettings;

/**
 * The fallback file type — every text file, and every resource in a registered scheme.
 *
 * <p>Declared here rather than inside a constructor, which is where it was: eighty lines of model and
 * editor factory in the middle of a method that was already the largest in the repository. It is the
 * kind every workbench has, because a shell that could not open a text file would not be one — which
 * is why the engine registers it and no application has to.</p>
 *
 * <p>A decompiled class opens through this too, which is what makes a separate viewer lane
 * unnecessary rather than merely shorter. @see com.crystalgui.workbench.editor.EditorService</p>
 */
public final class TextFileKind {

    private TextFileKind() {
    }

    /** The kind, bound to one workbench — its editors ask that workbench's settings and workspace. */
    public static DocumentKind declare(Workbench workbench) {
        // THE FALLBACK KIND: every text file, and every resource in a registered scheme. A decompiled
        // class opens through this one too, which is what makes the viewer lane unnecessary rather than
        // merely shorter -- see EditorService.
        return DocumentKind.of(Workbench.FILE_TYPE, "File").fallback()
                // THE MODEL WIRES THE LANGUAGE, because the language belongs to the DOCUMENT and not to a
                // view of it. It hung off the editor, so two panes onto one file held two parse trees and
                // a document with no tab could not analyse at all -- which is the state the Problems
                // panel, a background compile and Go to Definition all want it in.
                .model((resource, bytes) -> {
                    // BINARY OPENS AS BYTES. Decoding a PNG as UTF-8 gives an editor full of
                    // replacement characters -- and it is EDITABLE, so the first Ctrl+S writes that
                    // back over the file. The sniff is a NUL in the first 8000 bytes, with UTF-16
                    // excepted because its mark says outright that every other byte is a NUL.
                    if (TextEncoding.looksBinary(bytes)) return new BytesDocumentModel(bytes);
                    TextDocumentModel model = TextDocumentModel.of(bytes);
                    LanguageRegistry.Entry entry = LanguageRegistry.forFileName(workbench.opener.languageFileNameOf(resource));
                    // A FRESH tokenizer per document -- the interface exists for implementations holding
                    // a parse tree per file, and sharing one would cross-contaminate them.
                    //
                    // AND ITS DOC COMMENTS READ. A grammar reports `/** ... */` as ONE comment token,
                    // because to a parser that is what it is -- the tags and the HTML inside are a
                    // convention rather than syntax. `DocComments` is the lexing pass that reads them,
                    // composed here rather than inside `newTokenizer` so the registry keeps answering
                    // with what was registered.
                    //
                    // Fresh services per document too, and for the same reason: they hold a compile
                    // result about THIS text. Null unless a language module registered an engine, which
                    // is the whole feature flag -- see LanguageServices. Released by the model.
                    model.setLanguage(entry.language(), DocComments.refining(entry.newTokenizer()),
                            entry.newServices(model.buffer(), resource));
                    return model;
                })
                .editor(document -> {
                    // NOT A TEXT EDITOR FOR SOMETHING THAT IS NOT TEXT. A viewer says what the file is
                    // and offers no way to write nonsense back; VS Code's binary editor and IntelliJ's
                    // "file is not displayable" occupy the same slot.
                    if (document.model() instanceof BytesDocumentModel binary) {
                        return new BinaryFileView(document.resource(), binary);
                    }
                    TextDocumentModel model = (TextDocumentModel) document.model();
                    TextEditor created = new TextEditor("");
                    created.addClass(Workbench.FILE_EDITOR_CLASS);
                    // A VIEW OF THE MODEL'S BUFFER, never a copy of its text: two split panes are two
                    // editors over one buffer, so a keystroke in either is one edit on one document with
                    // one undo history. Copying is what made a second pane a second document.
                    created.setBuffer(model.buffer());
                    created.setLanguage(model.language());
                    created.setTokenizer(model.tokenizer());
                    created.setLanguageServices(model.services());
                    // WRITABLE ONLY IF THE RESOURCE IS. A decompiled class is read-only because its
                    // provider says so, and asking the workbench.workspace is what lets one kind serve both.
                    if (workbench.workspace.isReadOnly(document.resource())) {
                        created.addClass(Workbench.VIEWER_CLASS);
                        created.setReadOnly(true);
                    }
                    // AND IF THE TOKENIZER CAN FOLD, IT FOLDS. A tokenizer holding a parse tree already
                    // knows where a block begins and ends, which is strictly better than guessing from
                    // indentation -- and asking it costs no second parse, which a separate provider
                    // would. The indentation provider stays the default and answers for every language
                    // with no grammar behind it, which is most of them.
                    if (model.tokenizer() instanceof FoldingRangeProvider folding) {
                        created.setFoldingProvider(folding);
                    }
                    // AND IF IT CAN SAY HOW DEEP A LINE IS, Enter asks it rather than reading the last
                    // character of the line -- which is right for a brace language and silently wrong for
                    // a `case` arm, a wrapped expression, or a nested CSS rule.
                    if (model.tokenizer() instanceof IndentationProvider indent) {
                        created.setIndentationProvider(indent);
                    }
                    // A CROSS-FILE jump, which the editor announces rather than performs. Same-file jumps
                    // never arrive here because the editor already made them; this hears only what
                    // genuinely needs the workbench.workspace.
                    workbench.opener.routeDefinitionsOf(created);
                    // Here rather than only from WorkbenchSettings.apply: a document opened after the
                    // settings were installed would otherwise get the widget's own defaults, so folding
                    // and tab size would apply to the files that happened to be open when a preference
                    // was last changed and to no others -- which reads as the setting working
                    // intermittently.
                    WorkbenchSettings.applyTo(workbench, created);
                    return new TextEditorView(created);
                });
    }
}
