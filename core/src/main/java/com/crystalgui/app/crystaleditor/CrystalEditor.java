package com.crystalgui.app.crystaleditor;

import java.util.List;

import com.crystalgui.core.window.WindowPolicy;
import com.crystalgui.desktop.app.ApplicationKind;
import com.crystalgui.document.DocumentKind;
import com.crystalgui.example.notes.NotesKind;
import com.crystalgui.app.shadergraph.ShaderGraphContribution;
import com.crystalgui.workbench.app.WorkbenchApplication;
import com.crystalgui.workbench.extension.WorkbenchExtensions;
import com.crystalgui.workbench.extension.InspectorExtension;
import com.crystalgui.workbench.extension.NotificationsExtension;
import com.crystalgui.workbench.extension.PresenceExtension;
import com.crystalgui.workbench.extension.ProblemsExtension;
import com.crystalgui.workbench.extension.ProjectExtension;

/**
 * <b>Crystal Editor, as a manifest</b> - the IDE-shaped product this repository ships.
 *
 * <p>Which is all a product is once there is an engine underneath it: an id, a name, an icon, the files
 * it opens, and the list of features it enables. The workbench, the window, the preferences, the
 * session, the status line and the initial focus are all {@link WorkbenchApplication}'s.</p>
 *
 * <p>Nothing installs this - {@link com.crystalgui.app.Applications} declares it as a service, and a
 * desktop lists it. To launch it by hand: {@code desktop.applications().launch(CrystalEditor.KIND,
 * workspace, storage)}.</p>
 *
 * <h3>What is actually decided here, and it is three things</h3>
 *
 * <ol>
 *   <li>which extensions are on - {@link #EXTENSIONS};</li>
 *   <li>what the window is called, keyed, and what closing it means - a workbench is not a dialog, so
 *       {@link WindowPolicy#HIDE_ON_CLOSE}: every document, the arrangement and the undo history survive
 *       it, and the taskbar entry is how it comes back;</li>
 *   <li>which files it declares itself the handler for, so "open with" can answer with nothing
 *       running.</li>
 * </ol>
 *
 * <p><b>A second product is a second constant in a file like this one.</b> A graph-only application
 * names {@code shadergraph} and {@code inspector} and stops; a notes product names {@code notes}.
 * Neither is a class, neither is a second dock, and neither has to be remembered by a host.</p>
 *
 * <h3>One instance</h3>
 *
 * <p>Because a workbench's whole promise is that closing it keeps everything - a second one would be a
 * second dock over the same documents and the same session record. A second launch activates the one
 * running and hands it whatever file it was carrying, exactly as a second {@code open} on macOS does.</p>
 */
public final class CrystalEditor {

    private CrystalEditor() {
    }

    public static final String ID = "crystalgui:editor";

    /** {@code language/}'s, contributed by {@code LanguageStack} and absent where there is no band. */
    public static final String SCRIPTING = "crystalgui:scripting";

    /** What this product turns on. Ids, resolved against {@link WorkbenchExtensions} at launch. */
    public static final List<String> EXTENSIONS = List.of(
            ProjectExtension.ID,
            ProblemsExtension.ID,
            InspectorExtension.ID,
            NotificationsExtension.ID,
            PresenceExtension.ID,
            ShaderGraphContribution.ID,
            NotesKind.ID,
            // LISTED ON EVERY HOST, present on some. An id nothing contributed is a logged absence, not
            // an error -- which is what lets scripting be named here and be simply absent on a host with
            // no engine band, the same three-tier degradation the language stack already follows.
            SCRIPTING);

    /**
     * The manifest. Data: {@code handlerFor} reads it, and a launcher will list it.
     *
     * <p>It declared a {@code category} and {@code keywords} too, and both are gone with the builder
     * methods that took them: nothing read either, so the manifest read like a searchable declaration
     * against a desktop that has no search. They come back at W8 beside the thing that consults them.</p>
     */
    public static final ApplicationKind KIND = ApplicationKind.of(ID, "Crystal Editor")
            .icon("crystalgui:logo")
            // WHAT IT DECLARES ITSELF THE HANDLER FOR. Answerable with nothing running, which is the
            // requirement: "open with" is asked of an application that may never have been launched, so
            // it cannot be derived by building one and asking its workbench which kinds it registered.
            .opens(DocumentKind.FilePatterns.extension("shadergraph"),
                    DocumentKind.FilePatterns.extension("java"),
                    DocumentKind.FilePatterns.extension("js"),
                    DocumentKind.FilePatterns.extension("json"),
                    DocumentKind.FilePatterns.extension("md"),
                    DocumentKind.FilePatterns.extension("txt"),
                    DocumentKind.FilePatterns.extension("shader"),
                    DocumentKind.FilePatterns.extension("glsl"),
                    DocumentKind.FilePatterns.extension("css"),
                    DocumentKind.FilePatterns.extension("notes"))
            .singleInstance()
            .launch(context -> WorkbenchApplication.of(context)
                    .with(EXTENSIONS)
                    .title("Crystal Editor")
                    .key("editor:main")
                    .policy(WindowPolicy.HIDE_ON_CLOSE)
                    .start());

    // install() IS GONE, and nothing replaced it.
    //
    // It did two things, and both are now discovered: the manifest is offered to every desktop by
    // `com.crystalgui.app.Applications` (an ApplicationKinds service), and the shader-graph extension
    // by a line in META-INF/services -- which is where it belonged, because contributing it from HERE
    // made an application responsible for a feature's availability. What is left in this file is a
    // constant, which is what "a manifest is data" has to mean if it means anything: a host that adds
    // this jar to its classpath offers the editor, and one that does not, does not.
}
