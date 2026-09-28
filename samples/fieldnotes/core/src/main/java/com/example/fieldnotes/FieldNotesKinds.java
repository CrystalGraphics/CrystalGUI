package com.example.fieldnotes;

import com.crystalgui.desktop.app.ApplicationKinds;
import com.crystalgui.desktop.app.ApplicationRegistry;

/** Puts Field Notes in every desktop's launcher. Found through {@code META-INF/services}. */
public final class FieldNotesKinds implements ApplicationKinds {

    public FieldNotesKinds() {
    }

    @Override
    public void register(ApplicationRegistry applications) {
        applications.install(FieldNotesApplication.KIND);
    }
}
