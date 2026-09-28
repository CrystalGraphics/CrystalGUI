package com.example.fieldnotes;

import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.app.Application;
import com.crystalgui.desktop.app.ApplicationKind;
import com.crystalgui.desktop.app.LaunchContext;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.fs.Resource;
import com.crystalgui.widget.text.UIText;

/**
 * Field Notes' one window: says which of the jar's variants is running. Needs no server, so it is offered
 * on a title screen as well as in a world.
 *
 * <pre>{@code
 * desktop.applications().launch(FieldNotesApplication.KIND, (Workspace) null);   // no server needed
 * }</pre>
 */
public final class FieldNotesApplication implements Application {

    public static final ApplicationKind KIND = ApplicationKind.of("fieldnotes:notes", "Field Notes")
            .standalone()
            .launch(FieldNotesApplication::new);

    private final Desktop desktop;
    private final WindowFrame window;

    private FieldNotesApplication(LaunchContext context) {
        desktop = context.desktop();
        window = desktop.addWindow(new WindowFrame(KIND.displayName()));
        window.setApplication(KIND).markApplicationMain();
        window.content().append(new UIText(FieldNotes.where()), new UIText("Note id: " + FieldNotes.noteId()));
        window.resizeTo(320, 120);
    }

    @Override
    public ApplicationKind kind() {
        return KIND;
    }

    @Override
    public WindowFrame mainWindow() {
        return window;
    }

    @Override
    public boolean open(Resource resource) {
        return false;
    }

    @Override
    public void activate() {
        window.show(true);
        desktop.activate(window);
    }

    @Override
    public void dispose() {
        window.destroy();
        desktop.applications().forget(this);
    }
}
