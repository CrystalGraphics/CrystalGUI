package com.crystalgui.gradle;

import java.io.File;

import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.initialization.IncludedBuild;
import org.gradle.api.plugins.ExtraPropertiesExtension;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.jvm.tasks.Jar;

/**
 * {@code com.crystalgui}: CrystalGUI's API to compile against, and its mods on the dev run. The DSL is
 * {@link CrystalGuiExtension}'s.
 *
 * <pre>{@code
 * plugins { id("com.crystalgui") version "1.0.0" }
 * crystalgui { minecraft("1.20.1", "forge") }
 * }</pre>
 *
 * <p>Artifacts come from Maven local until a public repository exists; the plugin adds it, filtered to
 * CrystalGUI's and CrystalGraphics' groups. A build whose settings forbid project repositories declares
 * {@code mavenLocal()} there instead.</p>
 */
public class CrystalGuiPlugin implements Plugin<Project> {

    /** Where {@link CrystalGuiSettingsPlugin} leaves a target declared in settings. */
    static final String SETTINGS_TARGET = "com.crystalgui.target";

    @Override
    public void apply(Project project) {
        CrystalGuiExtension extension =
            project.getExtensions().create("crystalgui", CrystalGuiExtension.class, project);

        project.getRepositories().mavenLocal(repo -> repo.content(content -> {
            content.includeGroup(Artifacts.GUI_GROUP);
            content.includeGroup(Artifacts.CG_GROUP);
        }));

        project.getPluginManager().withPlugin("java", p -> {
            project.getDependencies().add("compileOnly", Artifacts.at(Artifacts.CORE));
            Configuration run = project.getConfigurations().maybeCreate(ModRoute.BUCKET);
            run.setCanBeConsumed(false);
            run.setCanBeResolved(false);
            run.setDescription("CrystalGUI's mods on the dev run; never published.");
            project.getConfigurations().named("runtimeClasspath").configure(c -> c.extendsFrom(run));

            TaskProvider<CheckCrystalGuiApi> api = project.getTasks().register("checkCrystalGuiApi",
                CheckCrystalGuiApi.class, task -> {
                    task.setGroup("verification");
                    task.setDescription("Fails on a reference to CrystalGUI's per-loader hosts, or a copy of either mod bundled.");
                    SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
                    task.getClasses().from(sourceSets.getByName("main").getOutput().getClassesDirs());
                    task.getJar().from(project.getTasks().named("jar", Jar.class).flatMap(Jar::getArchiveFile));
                });
            project.getTasks().named("check").configure(t -> t.dependsOn(api));
        });

        ExtraPropertiesExtension extra = project.getGradle().getExtensions().getExtraProperties();
        if (extra.has(SETTINGS_TARGET)) {
            extension.inherit(Target.parse((String) extra.get(SETTINGS_TARGET)));
        }

        ModRoute route = ModRoute.install(project, extension::mods);
        project.afterEvaluate(p -> extension.validate(route));

        if (project == project.getRootProject() && Boolean.TRUE.equals(extra.getProperties().get(Checkout.HARNESS))) {
            registerHarness(project, new File((String) extra.get(Checkout.DIR)));
        }
    }

    /** {@code runHarness}: the checkout's GL harness, after this project's classes it may construct. */
    private static void registerHarness(Project project, File checkout) {
        project.getTasks().register("runHarness", task -> {
            task.setGroup("crystalgui");
            task.setDescription("Runs CrystalGUI's GL harness on this build's assets and classes.");
            task.dependsOn("classes");
            IncludedBuild build = project.getGradle().getIncludedBuilds().stream()
                .filter(b -> b.getProjectDir().toPath().normalize().equals(checkout.toPath().normalize()))
                .findFirst()
                .orElseThrow(() -> new GradleException("crystalgui: no included build at " + checkout));
            task.dependsOn(build.task(":gl-debug-harness:runHarness"));
        });
    }
}
