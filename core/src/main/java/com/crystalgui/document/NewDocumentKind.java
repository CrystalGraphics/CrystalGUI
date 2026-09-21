package com.crystalgui.document;

import com.crystalgui.fs.CgPath;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * One row of <b>New ▸</b> — what a jar contributes to say "you can make one of these here, and here is
 * what it starts out as".
 *
 * <pre>{@code
 * // A whole file type, in one declaration:
 * NewDocumentKind.of("shadergraph:new", "Shader Graph")
 *         .icon(ActionIcons.SHADER_FILE)
 *         .suffix(".shadergraph")
 *         .template(target -> ShaderGraphDocument.starter(target.typeName()));
 *
 * // Offered only where it means something, and asked for by NAME and KIND:
 * NewDocumentKind.of("java:class", "Java Class")
 *         .suffix(".java")
 *         .where(at -> at.sourceRootHolds("java"))
 *         .variant("Class",     JavaTemplates::declaredClass)
 *         .variant("Interface", JavaTemplates::declaredInterface);
 * }</pre>
 *
 * <h3>Why the New menu is a registry rather than a list</h3>
 *
 * <p>It was a list: the explorer knew that Java classes exist, that {@code src/main/js} holds scripts,
 * and what a {@code .cgui} file starts life as. So a document kind could be registered, opened, edited
 * and saved by a jar of its own — and still not be something you could <em>make</em> without editing
 * the explorer. {@link DocumentKinds} names this menu in as many words as the thing it expects to
 * re-read its registrations; this is that menu's half of it.</p>
 *
 * <h3>{@code where} is asked per click, not per registration</h3>
 *
 * <p>Which is what makes the menu contextual: a right-click resolves a {@link NewDocumentContext} — the
 * directory, and which source root it sits in if any — and every kind is asked whether it belongs there.
 * A kind that says nothing is offered everywhere, which is right for a plain file and wrong for a Java
 * class, and the difference is one lambda rather than a branch in the explorer.</p>
 *
 * <h3>Variants are how one row asks a second question</h3>
 *
 * <p>A Java class is a class, an interface, a record or three other things, and IntelliJ asks with one
 * prompt: a name, and a list of kinds. Declaring them here means the prompt is <b>generic</b> — it
 * renders whatever variants a kind carries — so a contributor gets the same chooser for free rather
 * than needing a widget of its own. A kind with no variants gets the plain name prompt.</p>
 */
public final class NewDocumentKind {

    /** Orders below this are the engine's; a contributor's default is exactly this. @see #at */
    public static final int RESERVED = 100;

    /** What a kind's template is handed once the name is known. */
    public record Target(CgPath directory, String fileName, String packageName) {

        /** The file's stem — {@code Greeter} for {@code Greeter.java}, which is the type's name. */
        public String typeName() {
            int dot = fileName.lastIndexOf('.');
            return dot <= 0 ? fileName : fileName.substring(0, dot);
        }

        /** Whether the file lands directly under a source root, so there is no package to declare. */
        public boolean inDefaultPackage() {
            return packageName.isEmpty();
        }
    }

    /** One answer to a kind's second question — {@code Class}, {@code Interface}, {@code Record}. */
    public record Variant(String id, String label, @Nullable String icon,
                          Function<Target, String> template) {
    }

    private final String id;
    private final String label;
    private final List<Variant> variants = new ArrayList<>();

    @Nullable private String icon;
    private String suffix = "";
    private String group = "1_new";
    private int order = RESERVED;
    private boolean directory;
    private Predicate<NewDocumentContext> where = at -> true;
    private Function<Target, String> template = target -> "";

    private NewDocumentKind(String id, String label) {
        this.id = id;
        this.label = label;
    }

    /**
     * @param id    namespaced and stable — it becomes the command id, so a keymap and a session may name
     *              it. {@code "shadergraph:new"}, never {@code "new"}.
     * @param label the menu row's text.
     */
    public static NewDocumentKind of(String id, String label) {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("a new-document kind needs an id");
        if (label == null || label.isEmpty()) {
            throw new IllegalArgumentException("a new-document kind needs a label — it is a menu row");
        }
        return new NewDocumentKind(id, label);
    }

    /** The mark drawn before the label, {@code namespace:path} under {@code ui/icons/}. */
    public NewDocumentKind icon(@Nullable String iconName) {
        this.icon = iconName;
        return this;
    }

    /**
     * Appended to the typed name unless it is already there, so {@code Greeter} and {@code Greeter.java}
     * mean one file rather than two — and the second cannot produce {@code Greeter.java.java}.
     */
    public NewDocumentKind suffix(String suffix) {
        this.suffix = suffix == null ? "" : suffix;
        return this;
    }

    /** Makes a DIRECTORY rather than a file; the template is then unused. */
    public NewDocumentKind directory() {
        this.directory = true;
        return this;
    }

    /**
     * Where this appears: {@code group} then {@code order}, as {@code Command.menu} orders rows.
     *
     * <h3>Below {@link #RESERVED} is the engine's</h3>
     *
     * <p>The order a New menu reads in is a design decision somebody made once — a plain file, then a
     * folder, then the document types this engine ships, then everything a jar added — and it should not
     * come down to who registered first or who picked the smaller number. Orders under
     * {@code RESERVED} are the engine's own, and {@code order} defaults to it, so a contributor that
     * says nothing lands after all of them and in registration order among its peers.</p>
     *
     * <p>Nothing enforces it, deliberately: a product assembling its own workbench may well want its
     * document above ours, and a thrown exception would be a rule about taste.</p>
     */
    public NewDocumentKind at(String group, int order) {
        this.group = group == null ? "1_new" : group;
        this.order = order;
        return this;
    }

    /** Which directories offer it. Unset means every one. @see NewDocumentContext */
    public NewDocumentKind where(Predicate<NewDocumentContext> predicate) {
        this.where = predicate == null ? at -> true : predicate;
        return this;
    }

    /** What the new file starts out as. Unset means empty, which is what a plain file wants. */
    public NewDocumentKind template(Function<Target, String> template) {
        this.template = template == null ? target -> "" : template;
        return this;
    }

    /**
     * Adds a variant, and so declares that this kind asks a second question.
     *
     * <p>Order is declaration order, and the first is the default — the one a prompt opens on.</p>
     */
    public NewDocumentKind variant(String id, String label, @Nullable String icon,
                                   Function<Target, String> template) {
        variants.add(new Variant(id, label, icon, template));
        return this;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    @Nullable
    public String icon() {
        return icon;
    }

    public String suffix() {
        return suffix;
    }

    public String group() {
        return group;
    }

    public int order() {
        return order;
    }

    public boolean isDirectory() {
        return directory;
    }

    public boolean offeredAt(NewDocumentContext at) {
        return where.test(at);
    }

    public List<Variant> variants() {
        return Collections.unmodifiableList(variants);
    }

    /** The content for {@code target}, from {@code variant} when this kind has any. */
    public String contentFor(Target target, @Nullable Variant variant) {
        return variant != null ? variant.template().apply(target) : template.apply(target);
    }
}
