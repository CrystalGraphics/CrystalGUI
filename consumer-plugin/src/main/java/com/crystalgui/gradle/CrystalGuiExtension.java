package com.crystalgui.gradle;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.gradle.api.GradleException;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;

/**
 * The {@code crystalgui} block: what a mod builds for, and what it wants beside the API.
 *
 * <p>Applying the plugin alone puts CrystalGUI's API on {@code compileOnly} — {@code com.crystalgui:core},
 * which brings CrystalGraphics, Taffy and JOML with it. That is the whole of it for a module with no
 * Minecraft in it:</p>
 *
 * <pre>{@code
 * plugins { id("com.crystalgui") version "1.0.0" }
 * }</pre>
 *
 * <p>A mod names its target, and the plugin puts both mods on the dev run through that toolchain's own
 * mod remapping — the jars players install, so a dev run is the production shape:</p>
 *
 * <pre>{@code
 * plugins {
 *     id("net.neoforged.moddev.legacyforge") version "2.0.141"
 *     id("com.crystalgui") version "1.0.0"
 * }
 * crystalgui {
 *     minecraft("1.20.1", "forge")
 *     language()   // the optional scripting mod on the run too
 *     testing()    // the engine on testImplementation, runnable headless
 * }
 * }</pre>
 *
 * <ul>
 *   <li>The loader is {@code forge}, {@code neoforge} or {@code fabric}; {@code forge} includes 1.7.10
 *       and 1.8–1.12.2.</li>
 *   <li>The toolchain is found from the plugins applied: ModDevGradle (either mode), Loom, ForgeGradle,
 *       RetroFuturaGradle. Declaring {@code minecraft} without one fails the build and says so.</li>
 *   <li>Every {@code run*} task first checks that both jars have a variant for the target, and names the
 *       versions they do run on when not.</li>
 *   <li>Every {@code run*} task reads assets from this project's resource directories ahead of the jars
 *       (and from a checkout's, with {@code com.crystalgui.settings}), so a stylesheet edit shows on F3+T.
 *       A run that sets {@code crystalgraphics.resourceOverrideDirs} itself keeps its own.</li>
 *   <li>With {@code com.crystalgui.settings}, {@code minecraft} may be declared there instead; declaring
 *       two different targets fails. Request this plugin with no version then: settings already loaded
 *       it, and Gradle refuses a second, versioned request.</li>
 *   <li>The mod's own descriptor still says it depends on {@code crystalgui} (and {@code crystalgraphics}):
 *       the plugin writes no {@code mods.toml} or {@code fabric.mod.json}.</li>
 * </ul>
 */
public abstract class CrystalGuiExtension {

    /** CrystalGraphics' resource loader tries these directories before the classpath. */
    static final String RESOURCE_OVERRIDE = "crystalgraphics.resourceOverrideDirs";

    private final Project project;
    private Target target;
    private boolean fromSettings;
    private boolean language;
    /** Told whenever {@link #mods()} may have changed. @see ModRoute#declared */
    private Runnable onDeclared = () -> { };

    @Inject
    public CrystalGuiExtension(Project project) {
        this.project = project;
    }

    /** What this mod builds for: {@code minecraft("1.20.1", "forge")}. */
    public void minecraft(String version, String loader) {
        Target declared = Target.of(version, loader);
        if (target != null && !target.equals(declared)) {
            throw new InvalidUserDataException("crystalgui: " + project + " declares " + declared
                + " but " + target + " was declared first");
        }
        target = declared;
        onDeclared.run();
    }

    /** Puts {@code crystalgui_language}, the optional scripting mod, on the dev run as well. */
    public void language() {
        language = true;
        onDeclared.run();
    }

    /** Runs {@code listener} now and on every later declaration. */
    void onDeclared(Runnable listener) {
        onDeclared = listener;
        listener.run();
    }

    /**
     * Puts the engine on {@code testImplementation}, with what the game would otherwise supply at run
     * time, so a test can build and lay out a UI with no game running.
     */
    public void testing() {
        project.getPluginManager().withPlugin("java", p -> {
            project.getDependencies().add("testImplementation", Artifacts.at(Artifacts.CORE));
            project.getDependencies().add("testRuntimeOnly", Artifacts.LOG4J_CORE);
            project.getDependencies().add("testRuntimeOnly", Artifacts.COMMONS_IO);
        });
    }

    /** A target settings declared: every project with a toolchain takes it, and one without is left alone. */
    void inherit(Target declared) {
        minecraft(declared.minecraft(), declared.loader());
        fromSettings = true;
    }

    /** The mods the dev run takes: empty until a target is declared. Read when the toolchain resolves. */
    List<ModuleDependency> mods() {
        return modCoordinates().stream().map(this::mod).collect(Collectors.toList());
    }

    private List<String> modCoordinates() {
        List<String> mods = new ArrayList<>();
        if (target == null) {
            return mods;
        }
        mods.add(Artifacts.CG_MOD);
        if (target.below(Artifacts.FIRST_WITH_JOML)) {
            mods.add(Artifacts.CG_JOML_MOD);
        }
        mods.add(Artifacts.GUI_MOD);
        if (language) {
            mods.add(Artifacts.LANGUAGE_MOD);
        }
        return mods;
    }

    /**
     * Fails a target no toolchain can run, and checks the jars before every run. Once the build script
     * is evaluated; the mods themselves were routed when the toolchain was applied.
     */
    void validate(ModRoute route) {
        if (target == null) {
            return;
        }
        if (route.toolchain() == null) {
            if (fromSettings) {
                return;
            }
            throw new GradleException("crystalgui: " + project + " builds for " + target + " but applies no"
                + " toolchain crystalgui knows. Apply ModDevGradle (net.neoforged.moddev or .legacyforge),"
                + " Loom (fabric-loom), ForgeGradle (net.minecraftforge.gradle) or RetroFuturaGradle.");
        }
        project.getLogger().info("crystalgui: {} through {}: {}", target, route.toolchain(), modCoordinates());

        TaskProvider<CheckCrystalGuiTarget> check = project.getTasks().register("checkCrystalGuiTarget",
            CheckCrystalGuiTarget.class, task -> {
                task.setGroup("verification");
                task.setDescription("Checks that CrystalGUI and CrystalGraphics have a variant for " + target + ".");
                task.getJars().from(project.getConfigurations().detachedConfiguration(
                    mods().toArray(new Dependency[0])));
                task.getTarget().set(target.encode());
                task.getReport().set(project.getLayout().getBuildDirectory().file("crystalgui/target.txt"));
            });
        String overrides = resourceRoots();
        project.getTasks().withType(JavaExec.class).matching(t -> t.getName().startsWith("run"))
            .configureEach(t -> {
                t.dependsOn(check);
                if (!t.getSystemProperties().containsKey(RESOURCE_OVERRIDE)) {
                    t.systemProperty(RESOURCE_OVERRIDE, overrides);
                }
            });
        project.getPluginManager().withPlugin("base", p ->
            project.getTasks().named("check").configure(t -> t.dependsOn(check)));
    }

    /**
     * Asset roots read ahead of the jars' on a run, so an edit shows on the next resource reload (F3+T):
     * this project's resource directories, then a checkout's two. First found wins, so the mod's own
     * override of an engine asset is the one being edited.
     */
    private String resourceRoots() {
        List<File> roots = new ArrayList<>(project.getExtensions().getByType(SourceSetContainer.class)
            .getByName("main").getResources().getSrcDirs());
        Object checkout = project.getGradle().getExtensions().getExtraProperties().getProperties().get(Checkout.DIR);
        if (checkout != null) {
            roots.add(new File((String) checkout, "core/src/main/resources"));
            roots.add(new File((String) checkout, "CrystalGraphics/core/src/main/resources"));
        }
        return roots.stream().filter(File::isDirectory).map(File::getAbsolutePath)
            .collect(Collectors.joining(File.pathSeparator));
    }

    /**
     * One mod jar, asking for its own coordinate as a capability. A module offers that implicitly; a
     * checkout's root offers several jars and tells them apart by it — and a substitution cannot ask
     * for one, so the dependency must.
     */
    private ModuleDependency mod(String coordinate) {
        ModuleDependency dependency = (ModuleDependency) project.getDependencies().create(Artifacts.at(coordinate));
        dependency.setTransitive(false);
        dependency.capabilities(c -> c.requireCapability(coordinate));
        return dependency;
    }
}
