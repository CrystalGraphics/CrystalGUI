package com.crystalgui.document;

import com.crystalgui.core.pattern.FilePatternMap;
import com.crystalgui.fs.Resource;
import com.crystalgui.text.syntax.Language;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * <b>A kind of document, declared in one place</b> — IntelliJ's {@code FileEditorProvider},
 * VS Code's {@code contributes.customEditors}.
 *
 * <pre>{@code
 * public static final DocumentKind KIND = DocumentKind.of("crystalshader:graph", "Shader Graph")
 *         .files(FilePatterns.extension("shadergraph"))
 *         .icon("crystalshader:graph")
 *         .model(GraphDocument::decode)          // bytes    -> DocumentModel
 *         .editor(ShaderGraphEditor::new)        // Document -> DocumentEditor
 *         .status(GraphStatus::contribute);      // while active
 * }</pre>
 *
 * <p>From that one declaration: the files open as graphs, the tab carries the icon and the name,
 * dirtiness is a version comparison, Ctrl+Z reaches the model's own edits, Ctrl+S encodes with the
 * etag, a change on the server reloads or conflicts according to whether the document is dirty, an
 * unsaved graph survives a quit, and the session carries the view state.</p>
 *
 * <h3>The split this makes that {@code DocumentType} could not</h3>
 *
 * <p>{@code DocumentType} answered a {@code FileDocument}, which was a widget — so the model and the
 * view were one object and neither could exist without the other. A kind names them separately, which
 * is what lets a document analyse with no tab open and two panes share one parse tree.</p>
 *
 * <h3>A kind with no model is refused</h3>
 *
 * <p>Registration throws rather than accepting a declaration that cannot open anything.
 * {@code registerDocumentType} plus {@code bindEditorExtensions} were two calls that were meaningless
 * apart and were shipped half-done, which put the failure at the moment a person opened a file.</p>
 */
public final class DocumentKind {

    private final String id;
    private final String displayName;
    private final List<Matcher> matchers = new ArrayList<>();

    @Nullable
    private String icon;
    @Nullable
    private BiFunction<Resource, byte[], DocumentModel> model;
    @Nullable
    private Function<Document, DocumentEditor> editor;
    @Nullable
    private Consumer<Document> status;
    @Nullable
    private Language language;
    private boolean frozen;

    private DocumentKind(String id, String displayName) {
        this.id = Objects.requireNonNull(id, "id");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
    }

    /**
     * @param id          namespaced, and the string a session record persists. Changing it orphans
     *                    every saved tab of this kind, so pick it once
     * @param displayName what a person reads — a tab's fallback title, a "New …" menu row
     */
    public static DocumentKind of(String id, String displayName) {
        return new DocumentKind(id, displayName);
    }

    // ── Declaring ───────────────────────────────────────────────────────────────────────────────

    /** Which files are this kind. See {@link FilePatterns} for the three shapes. */
    public DocumentKind files(Matcher... rules) {
        checkOpen();
        Collections.addAll(matchers, rules);
        return this;
    }

    /** The icon a tab and a tree row show. Resolved through the icon theme like any other. */
    public DocumentKind icon(@Nullable String iconName) {
        checkOpen();
        this.icon = iconName;
        return this;
    }

    /**
     * How bytes become a document. Required.
     *
     * <p><b>The resource is handed over too</b>, and it has to be: a text model resolves its language,
     * its tokenizer, its folding and its indentation from the file's NAME, so a factory that could not
     * see what it was a model of would have to be told separately — which is the two-calls-that-are-one
     * shape {@code DocumentType} was replaced for.</p>
     */
    public DocumentKind model(BiFunction<Resource, byte[], DocumentModel> factory) {
        checkOpen();
        this.model = Objects.requireNonNull(factory, "factory");
        return this;
    }

    /** For a model that does not care where it came from — a graph, a blob. */
    public DocumentKind model(Function<byte[], DocumentModel> factory) {
        Objects.requireNonNull(factory, "factory");
        return model((resource, bytes) -> factory.apply(bytes));
    }

    /**
     * How a document becomes a view.
     *
     * <p>Optional, and its absence is a real declaration: a kind with a model and no editor is one that
     * can be opened, analysed, saved and searched with nothing to look at it — which is what a build
     * artefact or an index source is.</p>
     */
    public DocumentKind editor(Function<Document, DocumentEditor> factory) {
        checkOpen();
        this.editor = Objects.requireNonNull(factory, "factory");
        return this;
    }

    /** What this kind publishes to the status bar while it is in front. */
    public DocumentKind status(Consumer<Document> contribution) {
        checkOpen();
        this.status = contribution;
        return this;
    }

    /**
     * <b>A text kind, in one line.</b>
     *
     * <pre>{@code
     * DocumentKind.of("mymod:notes", "Notes").files(FilePatterns.extension("notes")).text(Language.MARKDOWN)
     * }</pre>
     *
     * <p>Supplies the model — a {@link TextDocumentModel} over the bytes, with the ending, charset and
     * mark taken from them — and records the language so whoever builds the view can bind a tokenizer.
     * The editor is still the caller's or the workbench's, because a text kind may want a specialised
     * one and most want the ordinary editor.</p>
     */
    public DocumentKind text(@Nullable Language language) {
        checkOpen();
        this.language = language;
        return model((resource, bytes) -> TextDocumentModel.of(bytes));
    }

    // ── Reading ─────────────────────────────────────────────────────────────────────────────────

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    @Nullable
    public String icon() {
        return icon;
    }

    @Nullable
    public Language language() {
        return language;
    }

    public boolean hasEditor() {
        return editor != null;
    }

    /**
     * Whether this kind claims that resource.
     *
     * <p>Asked of {@code Resource.name()}, never of {@code path()}: a project resource's path carries
     * its project prefix ({@code proj:a/b/Thing.shadergraph}), so a hand-rolled last-segment split on
     * {@code /} answers the whole string for a file at a project root — and {@code .gitignore} then
     * matched an extension rule for {@code gitignore}, which is the one case the leading-dot rule
     * exists to refuse.</p>
     */
    public boolean matches(Resource resource) {
        if (resource == null) return false;
        String name = resource.name();
        for (Matcher matcher : matchers) {
            if (matcher.matches(name)) return true;
        }
        return false;
    }

    /** @throws RuntimeException whatever the model factory throws for bytes it cannot decode */
    public DocumentModel createModel(Resource resource, byte[] bytes) {
        if (model == null) throw new IllegalStateException(id + " declares no model");
        return model.apply(resource, bytes);
    }

    /** @throws IllegalStateException if this kind has no editor — ask {@link #hasEditor()} first */
    public DocumentEditor createEditor(Document document) {
        if (editor == null) throw new IllegalStateException(id + " declares no editor");
        return editor.apply(document);
    }

    public void contributeStatus(Document document) {
        if (status != null) status.accept(document);
    }

    /**
     * Sealed at registration, so a kind cannot be re-declared out from under documents already open
     * under it. Registration is the moment it becomes shared.
     */
    void freeze() {
        if (model == null) {
            throw new IllegalStateException(id + " declares no model — a kind that cannot open anything "
                    + "is a registration that fails when somebody opens a file, not when it is written");
        }
        frozen = true;
    }

    private void checkOpen() {
        if (frozen) throw new IllegalStateException(id + " is registered and cannot be re-declared");
    }

    @Override
    public String toString() {
        return "DocumentKind(" + id + ")";
    }

    /** Whether a file NAME — never a path — is this kind. */
    @FunctionalInterface
    public interface Matcher {
        boolean matches(String fileName);
    }

    /**
     * The three shapes a kind claims files by, matching {@link FilePatternMap}'s: an exact name, an
     * extension, a glob. Same three the icon theme uses, and in the same precedence — a name beats an
     * extension, because {@code build.gradle.kts} is not merely a {@code .kts}.
     */
    public static final class FilePatterns {

        private FilePatterns() {
        }

        /** {@code build.gradle.kts}, {@code .gitignore} — an exact file name. */
        public static Matcher name(String fileName) {
            String wanted = fileName.toLowerCase(java.util.Locale.ROOT);
            return candidate -> candidate.toLowerCase(java.util.Locale.ROOT).equals(wanted);
        }

        /** {@code shadergraph}, written without the dot. */
        public static Matcher extension(String extension) {
            String suffix = "." + extension.toLowerCase(java.util.Locale.ROOT);
            return candidate -> {
                String lower = candidate.toLowerCase(java.util.Locale.ROOT);
                // A LEADING dot is not an extension: `.gitignore` is a file called that, not a file with
                // a `gitignore` extension -- which is why such files are matched by name.
                return lower.length() > suffix.length() && lower.endsWith(suffix);
            };
        }

        /** {@code *.test.js}, {@code CMakeLists.*}. */
        public static Matcher glob(String glob) {
            return candidate -> globMatches(glob.toLowerCase(java.util.Locale.ROOT),
                    candidate.toLowerCase(java.util.Locale.ROOT));
        }

        private static boolean globMatches(String glob, String name) {
            int g = 0, n = 0, star = -1, mark = 0;
            while (n < name.length()) {
                if (g < glob.length() && (glob.charAt(g) == '?' || glob.charAt(g) == name.charAt(n))) {
                    g++;
                    n++;
                } else if (g < glob.length() && glob.charAt(g) == '*') {
                    star = g++;
                    mark = n;
                } else if (star >= 0) {
                    g = star + 1;
                    n = ++mark;
                } else {
                    return false;
                }
            }
            while (g < glob.length() && glob.charAt(g) == '*') g++;
            return g == glob.length();
        }
    }

    /** For a caller that has a supplier rather than a function — a kind whose model takes no bytes. */
    public DocumentKind model(Supplier<DocumentModel> factory) {
        Objects.requireNonNull(factory, "factory");
        return model((resource, bytes) -> factory.get());
    }
}
