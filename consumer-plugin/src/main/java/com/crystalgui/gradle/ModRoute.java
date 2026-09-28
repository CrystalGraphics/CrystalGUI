package com.crystalgui.gradle;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ModuleDependency;

/**
 * How one toolchain puts a PRODUCTION mod jar on its dev run: the single jars are the files players
 * install, so each toolchain remaps them the way it remaps any other mod.
 *
 * <p>Found by the toolchain plugin the project applies, never by the Minecraft version: two toolchains
 * serve one version (ForgeGradle and ModDevGradle's legacy mode both build Forge 1.20.1). The mods are
 * added LAZILY, as a provider the toolchain evaluates when it resolves: Loom resolves its mod
 * configurations in its own {@code afterEvaluate}, before one of ours would run. The two {@code deobf}
 * toolchains are the exception, and say why.</p>
 *
 * <p>Toolchains are reached by extension name and reflection, not by type: a consumer's toolchain may be
 * applied in another project than ours, where its classes are not ours to see.</p>
 */
final class ModRoute {

    /** Where every route that needs a configuration of its own puts the mods: runtime, never published. */
    static final String BUCKET = "crystalguiRun";

    private final Project project;
    private final Supplier<List<ModuleDependency>> mods;
    private String toolchain;
    /** Routes every declared mod not yet routed; set by the deobf toolchains only. */
    private Runnable remapDeclared;

    private ModRoute(Project project, Supplier<List<ModuleDependency>> mods) {
        this.project = project;
        this.mods = mods;
    }

    /**
     * Routes {@code mods} — asked for when the toolchain resolves, empty when no target is declared —
     * through whichever toolchain {@code project} applies, whenever it applies it.
     */
    static ModRoute install(Project project, Supplier<List<ModuleDependency>> mods) {
        ModRoute route = new ModRoute(project, mods);
        route.on("net.neoforged.moddev.legacyforge", "ModDevGradle legacyForge", () -> {
            // MDG's legacy mode stops at Forge 1.20.1, which all run SRG names: always remapped.
            Configuration remapped = (Configuration) invoke(project.getExtensions().getByName("obfuscation"),
                "createRemappingConfiguration", Configuration.class, bucket(project));
            route.into(remapped);
        });
        route.on("net.neoforged.moddev", "ModDevGradle", () -> route.into(bucket(project)));
        for (String loom : new String[] {"fabric-loom", "net.fabricmc.fabric-loom"}) {
            route.on(loom, "Loom", () -> route.into(project.getConfigurations().getByName("modLocalRuntime")));
        }
        route.on("com.gtnewhorizons.retrofuturagradle", "RetroFuturaGradle", () -> route.deobf("modUtils", "deobfuscate"));
        route.on("net.minecraftforge.gradle", "ForgeGradle", () -> route.deobf("fg", "deobf"));
        return route;
    }

    /** The toolchain found, or null while none is applied. */
    String toolchain() {
        return toolchain;
    }

    private void on(String pluginId, String name, Runnable wire) {
        project.getPluginManager().withPlugin(pluginId, p -> {
            if (toolchain == null) {
                toolchain = name;
                wire.run();
            }
        });
    }

    /** Adds the mods to {@code configuration} as they are: the toolchain remaps what it resolves there. */
    private void into(Configuration configuration) {
        into(configuration, mod -> mod);
    }

    private void into(Configuration configuration, Function<ModuleDependency, Object> remap) {
        configuration.getDependencies().addAllLater(project.provider(() -> mods.get().stream()
            .map(mod -> {
                Object notation = remap.apply(mod);
                return notation instanceof Dependency ? (Dependency) notation : project.getDependencies().create(notation);
            })
            .collect(Collectors.toList())));
    }

    /**
     * ForgeGradle and RetroFuturaGradle both remap a dependency through their extension: ForgeGradle 6's
     * {@code fg.deobf(dependency)}, RetroFuturaGradle 2's {@code modUtils.deobfuscate(dependency)}.
     *
     * <p>As each mod is declared, not lazily: ForgeGradle records the original in its own
     * {@code __obfuscated} configuration and resolves it in an {@code afterEvaluate} registered before any
     * of ours, so even one of ours is too late. @see #declared</p>
     */
    private void deobf(String extension, String method) {
        Object ext = project.getExtensions().getByName(extension);
        Configuration bucket = bucket(project);
        Set<String> routed = new HashSet<>();
        remapDeclared = () -> {
            for (ModuleDependency mod : mods.get()) {
                String coordinates = mod.getGroup() + ":" + mod.getName() + ":" + mod.getVersion();
                if (!routed.add(coordinates)) continue;
                // A STRING: RetroFuturaGradle refuses a Dependency object. What the string cannot carry is
                // put back on whatever comes out.
                Object notation = invoke(ext, method, Object.class, coordinates);
                Dependency remapped = notation instanceof Dependency
                    ? (Dependency) notation : project.getDependencies().create(notation);
                if (remapped instanceof ModuleDependency) {
                    ((ModuleDependency) remapped).setTransitive(false);
                    ((ModuleDependency) remapped).capabilities(c ->
                        c.requireCapability(mod.getGroup() + ":" + mod.getName()));
                }
                bucket.getDependencies().add(remapped);
            }
        };
        remapDeclared.run();
        project.afterEvaluate(p -> remapDeclared.run());
    }

    /** The build declared a target or the language mod. Only a {@code deobf} toolchain acts on it. */
    void declared() {
        if (remapDeclared != null) remapDeclared.run();
    }

    private static Configuration bucket(Project project) {
        return project.getConfigurations().maybeCreate(BUCKET);
    }

    private static Object invoke(Object target, String name, Class<?> parameter, Object argument) {
        try {
            Method method = target.getClass().getMethod(name, parameter);
            return method.invoke(target, argument);
        } catch (InvocationTargetException e) {
            throw new GradleException("crystalgui: " + name + " failed: " + e.getCause().getMessage(), e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new GradleException("crystalgui: " + target.getClass().getName() + "." + name
                + " is not what this plugin was written against", e);
        }
    }
}
