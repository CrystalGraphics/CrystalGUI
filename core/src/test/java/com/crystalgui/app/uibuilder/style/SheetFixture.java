package com.crystalgui.app.uibuilder.style;

import java.util.List;

import com.crystalgraphics.job.reply.CgPendingReply;
import com.crystalgraphics.job.reply.CgReply;
import com.crystalgui.document.Document;
import com.crystalgui.document.DocumentKind;
import com.crystalgui.document.DocumentReference;
import com.crystalgui.document.TextDocumentModel;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.ui.dom.UIDocument;

/** A project stylesheet, opened on a buffer a test holds — what the workspace hands the builder for real. */
final class SheetFixture {

    private SheetFixture() {
    }

    /** Installs {@code buffer} on {@code window} as the document's one sheet. */
    static SheetDocuments install(UIDocument window, TextBuffer buffer, String name) {
        SheetDocuments sheets = new SheetDocuments(resource -> reply(reference(resource, buffer)),
                Resource.of(CgPath.of("p", "ui/page.cgui")));
        sheets.install(window, List.of(name));
        return sheets;
    }

    static DocumentReference reference(Resource resource, TextBuffer buffer) {
        TextDocumentModel model = new TextDocumentModel(buffer);
        Document document = new Document(resource,
                DocumentKind.of("test.css", "CSS").model((r, bytes) -> model), model);
        return new DocumentReference() {
            @Override
            public Document document() {
                return document;
            }

            @Override
            public void dispose() {
            }
        };
    }

    static <T> CgReply<T> reply(T value) {
        CgPendingReply<T> settled = new CgPendingReply<>(null);
        settled.resolve(value);
        return settled;
    }
}
