package com.crystalgui.workbench.explorer;

import com.crystalgui.core.command.ActionIcons;
import com.crystalgui.document.NewDocumentKind;
import com.crystalgui.document.NewDocumentKinds;

/**
 * The rows <b>New ▸</b> has before any jar contributes one: a file, a folder, and what the two source
 * roots this project lays out by convention hold.
 *
 * <p><b>Registered through the same seam a contributor uses, deliberately.</b> These were branches in
 * the explorer — it knew that Java classes exist and what a package is — which made the built-in path
 * more capable than the public one, and that is how an extension API rots. {@code new Workbench(...)}
 * with none of these registered has a New menu with nothing in it, which is the honest test of the
 * seam.</p>
 *
 * @see NewDocumentKind
 */
public final class BuiltInNewDocuments {

    /** Kept for the Explorer's own use; a contributor names its own. */
    public static final String FILE = "explorer.newFile";
    public static final String FOLDER = "explorer.newFolder";
    public static final String PACKAGE = "explorer.newPackage";
    public static final String JAVA_CLASS = "explorer.newJavaClass";
    public static final String PACKAGE_INFO = "explorer.newPackageInfo";
    public static final String JS_FILE = "explorer.newJavaScriptFile";

    private static final String NL = "\n";

    private BuiltInNewDocuments() {
    }

    public static void registerInto(NewDocumentKinds kinds) {
        // A PLAIN FILE IS OFFERED EVERYWHERE, and it is the one row that is never wrong.
        kinds.register(NewDocumentKind.of(FILE, "New File…")
                .icon(ActionIcons.ADD_FILE)
                .at("1_new", 90));

        // ...and a directory everywhere a directory IS one. Only a java root makes it a package
        // instead, so that is the only place it is withheld -- offering both there would name one thing
        // twice and let a reader make the one that is not a package. A js root has plain directories,
        // and so does a root this project has never heard of, which is what stops an unrecognised
        // `src/main/resources` losing the row.
        kinds.register(NewDocumentKind.of(FOLDER, "New Folder…")
                .icon(ActionIcons.ADD_DIRECTORY)
                .directory()
                .where(at -> !at.sourceRootHolds("java"))
                .at("1_new", 95));

        registerJava(kinds);
        registerJavaScript(kinds);
    }

    private static void registerJava(NewDocumentKinds kinds) {
        kinds.register(NewDocumentKind.of(JAVA_CLASS, "Java Class")
                .icon(ActionIcons.JAVA_CLASS)
                .suffix(".java")
                .where(at -> at.sourceRootHolds("java"))
                .at("1_new", 10)
                // THE SIX THINGS JAVA CAN DECLARE AT THE TOP LEVEL, in IntelliJ's order. Declaring them
                // as variants is what makes the prompt generic: it renders whatever a kind carries, so a
                // contributor gets the same name-and-kind chooser without a widget of its own.
                .variant("class", "Class", "crystalgui:nodes/java/class",
                        target -> type(target, "public class " + target.typeName() + " {"))
                .variant("interface", "Interface", "crystalgui:nodes/java/interface",
                        target -> type(target, "public interface " + target.typeName() + " {"))
                .variant("record", "Record", "crystalgui:nodes/java/record",
                        target -> type(target, "public record " + target.typeName() + "() {"))
                .variant("enum", "Enum", "crystalgui:nodes/java/enum",
                        target -> type(target, "public enum " + target.typeName() + " {"))
                .variant("annotation", "Annotation", "crystalgui:nodes/java/annotation",
                        target -> type(target, "public @interface " + target.typeName() + " {"))
                // NOT A KEYWORD, and the one that is not: an exception is a class that extends
                // Exception, which is exactly what SymbolKind.EXCEPTION documents itself as -- a display
                // refinement of CLASS rather than something the JLS has.
                .variant("exception", "Exception", "crystalgui:nodes/java/exception",
                        target -> type(target, "public class " + target.typeName() + " extends Exception {")));

        kinds.register(NewDocumentKind.of(PACKAGE, "Package")
                .icon(ActionIcons.PACKAGE)
                .directory()
                .where(at -> at.sourceRootHolds("java"))
                .at("1_new", 20));

        // ONE PER PACKAGE AND ALREADY NAMED, so it asks nothing: a prompt here would be a question with
        // one answer. Its own group, which is what puts a rule above it.
        kinds.register(NewDocumentKind.of(PACKAGE_INFO, "package-info.java")
                .icon(ActionIcons.JAVA_FILE)
                .suffix(".java")
                .where(at -> at.sourceRootHolds("java"))
                .at("2_kinds", 10)
                .template(target -> target.inDefaultPackage()
                        ? "" : "package " + target.packageName() + ";" + NL));
    }

    private static void registerJavaScript(NewDocumentKinds kinds) {
        // NO PACKAGE ROW HERE: JavaScript has directories and no package namespace, so "Package" would
        // name something the language does not have -- and no package-info either, for the same reason.
        kinds.register(NewDocumentKind.of(JS_FILE, "JavaScript File")
                .icon(ActionIcons.JAVASCRIPT_FILE)
                .suffix(".js")
                .where(at -> at.sourceRootHolds("js"))
                .at("1_new", 10));
    }

    /** {@code package a.b;} then a declaration, which is what a New Class writes in every IDE. */
    private static String type(NewDocumentKind.Target target, String declaration) {
        String header = target.inDefaultPackage()
                ? "" : "package " + target.packageName() + ";" + NL + NL;
        return header + declaration + NL + "}" + NL;
    }
}
