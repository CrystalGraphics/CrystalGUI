package com.crystalgui.core.provider;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Every provider of a service on this host: what {@link ServiceLoader} finds, plus what a loader's
 * {@link Copies} names where the classloader cannot list a resource across mod files.
 *
 * <pre>{@code
 * Providers.forEach(WorkbenchExtension.class, WorkbenchExtensions.class.getClassLoader(),
 *         WorkbenchExtensions::contribute,
 *         broken -> LOGGER.error("a WorkbenchExtension could not be loaded", broken));
 * }</pre>
 *
 * <ul>
 *   <li>Pass the loader that defined the service interface, never the context one: on 1.7.10 the context
 *       loader is whatever the host left there.</li>
 *   <li>A provider that will not load reaches {@code broken} and costs only itself; the rest still run.</li>
 *   <li>A provider named by both routes runs once.</li>
 * </ul>
 */
public final class Providers {

    private Providers() {}

    /**
     * Every copy of a resource across the host's mod files, for a host whose classloader answers
     * {@code getResource} but not {@code getResources} -- ModLauncher 5 (Forge 29-31).
     *
     * <pre>{@code
     * CgPlatform.provide(Providers.Copies.SERVICE, path -> pathsInEveryModFileHolding(path));
     * }</pre>
     *
     * <p>Paths rather than URLs: Java 8's zip filesystem escapes a space twice when it makes one.</p>
     */
    public interface Copies {

        /** @return the copy of {@code path} in each mod file holding one; empty when there is none */
        List<Path> of(String path);

        /** Absent on every host whose classloader lists resources itself. */
        CgService<Copies> SERVICE = CgService.of("crystalgui:resource-copies", path -> Collections.emptyList());
    }

    /**
     * Instantiates every provider of {@code service} and hands each to {@code each}.
     *
     * @param broken told of each provider that could not be loaded or constructed
     */
    public static <T> void forEach(Class<T> service, ClassLoader loader, Consumer<? super T> each,
                                   Consumer<Throwable> broken) {
        Set<String> seen = new HashSet<>();
        Iterator<T> services = ServiceLoader.load(service, loader).iterator();
        while (true) {
            T provider;
            try {
                if (!services.hasNext()) break;
                provider = services.next();
            } catch (ServiceConfigurationError | RuntimeException | LinkageError failed) {
                // The iterator throws on the ENTRY, so this brackets next(): catching only around the
                // body would let one mod's missing class stop every provider after it in the file.
                broken.accept(failed);
                continue;
            }
            seen.add(provider.getClass().getName());
            each.accept(provider);
        }
        for (Path file : CgPlatform.get(Copies.SERVICE).of("META-INF/services/" + service.getName())) {
            for (String name : providerNames(file, broken)) {
                if (!seen.add(name)) continue;
                T provider;
                try {
                    provider = service.cast(Class.forName(name, true, loader).getDeclaredConstructor().newInstance());
                } catch (ReflectiveOperationException | RuntimeException | LinkageError failed) {
                    broken.accept(failed);
                    continue;
                }
                each.accept(provider);
            }
        }
    }

    /** The class names a provider-configuration file lists, in {@link ServiceLoader}'s format. */
    private static List<String> providerNames(Path file, Consumer<Throwable> broken) {
        List<String> names = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                int comment = line.indexOf('#');
                String name = (comment < 0 ? line : line.substring(0, comment)).trim();
                if (!name.isEmpty()) names.add(name);
            }
        } catch (IOException unreadable) {
            broken.accept(unreadable);
        }
        return names;
    }
}
