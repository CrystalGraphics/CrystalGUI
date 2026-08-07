package com.crystalgui.ui.elements.workbench;

import com.crystalgui.fs.CgPath;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * A kind of document, declared by whoever owns it — IntelliJ's {@code FileEditorProvider} extension
 * point, VS Code's {@code contributes.customEditors}.
 *
 * <h3>Why this is one object and not two calls</h3>
 *
 * <p>It was {@code registerDocumentType(id, title, factory)} plus {@code bindEditorExtensions(id, …)},
 * and those are meaningless apart: a factory with no binding never opens anything, and a binding with no
 * factory throws at open time — {@code "No document factory for panel type"}. Two calls that must both
 * happen are one fact, and splitting them puts the failure at the moment a user opens a file rather than
 * at the moment somebody registers half a type.</p>
 *
 * <h3>Declared, never constructed at a call site</h3>
 *
 * <p>The point of the whole shape. {@code CrystalEditor} used to name {@code .shadergraph} and
 * {@code ShaderGraphEditor} directly, so adding a file type meant editing the general editor — invasive,
 * where both references are additive. The package that owns a type now declares it and the application
 * only chooses which contributions to enable.</p>
 *
 * <pre>{@code
 * workbench.contribute(DocumentType.of("shadergraph.file", "Shader Graph")
 *         .forExtensions("shadergraph")
 *         .document(path -> new ShaderGraphEditor().setResource(Resource.of(path))));
 * }</pre>
 */
public final class DocumentType {

    private final String typeId;
    private final String title;

    private final List<String> extensions = new ArrayList<>();
    private final List<String> fileNames = new ArrayList<>();
    private final List<String> globs = new ArrayList<>();

    private Function<CgPath, FileDocument> factory;

    private DocumentType(String typeId, String title) {
        this.typeId = Objects.requireNonNull(typeId, "typeId");
        this.title = Objects.requireNonNull(title, "title");
    }

    public static DocumentType of(String typeId, String title) {
        return new DocumentType(typeId, title);
    }

    /** How to build one. Required — a type with no factory cannot open anything. */
    public DocumentType document(Function<CgPath, FileDocument> factory) {
        this.factory = Objects.requireNonNull(factory, "factory");
        return this;
    }

    /** Files with these extensions open as this type. */
    public DocumentType forExtensions(String... extensions) {
        java.util.Collections.addAll(this.extensions, extensions);
        return this;
    }

    /** Files with these exact names — {@code build.gradle.kts}, {@code .gitignore}. */
    public DocumentType forNames(String... names) {
        java.util.Collections.addAll(this.fileNames, names);
        return this;
    }

    public DocumentType forGlobs(String... globs) {
        java.util.Collections.addAll(this.globs, globs);
        return this;
    }

    public String typeId() {
        return typeId;
    }

    public String title() {
        return title;
    }

    public Function<CgPath, FileDocument> factory() {
        return factory;
    }

    public List<String> extensions() {
        return extensions;
    }

    public List<String> fileNames() {
        return fileNames;
    }

    public List<String> globs() {
        return globs;
    }
}
