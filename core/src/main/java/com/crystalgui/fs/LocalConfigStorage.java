package com.crystalgui.fs;

import com.crystalgui.core.CrystalGuiCore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nullable;

/**
 * A {@link ConfigStorage} over one real directory.
 *
 * <h3>Writes are atomic, for the reason {@link LocalFileSystem}'s are</h3>
 *
 * <p>Temp file <b>in the same directory</b>, then a rename. A temp file elsewhere is on a different
 * filesystem as often as not, and the rename silently degrades to copy-then-delete — exactly the
 * non-atomic thing being avoided. A crash mid-write leaves the previous preferences intact rather than a
 * truncated file that fails to parse, which for configuration means starting up with everything apparently
 * reset and nothing to say why.</p>
 *
 * <h3>Every failure is a warning, never a throw</h3>
 *
 * <p>Except writing to a store already known to be read-only, which is a caller bug. Being unable to read
 * preferences must not stop the editor opening: the worst honest outcome is defaults, and an exception
 * here would turn a missing directory into a crash on launch.</p>
 */
public final class LocalConfigStorage implements ConfigStorage {

    private final Path directory;
    private final boolean writable;

    public LocalConfigStorage(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
        boolean usable = true;
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            CrystalGuiCore.LOGGER.warn("Could not create the config directory {}; preferences will not be "
                    + "saved this session", directory, e);
            usable = false;
        }
        this.writable = usable;
    }

    public Path directory() {
        return directory;
    }

    @Nullable
    @Override
    public String read(String name) {
        Path file = directory.resolve(name);
        if (!Files.isRegularFile(file)) return null;
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            CrystalGuiCore.LOGGER.warn("Could not read {}; continuing without it", file, e);
            return null;
        }
    }

    @Override
    public void write(String name, String contents) {
        if (!writable) throw new IllegalStateException("Config storage at " + directory + " is read-only");
        Path target = directory.resolve(name);
        Path temp = null;
        try {
            Files.createDirectories(target.getParent());
            temp = Files.createTempFile(target.getParent(), ".cgui-", ".tmp");
            Files.write(temp, contents.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            temp = null;
        } catch (IOException e) {
            CrystalGuiCore.LOGGER.warn("Could not write {}", target, e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // A leftover temp file is untidy, not harmful, and a second warning about failing to
                    // clean up after a failure only buries the failure that matters.
                }
            }
        }
    }

    @Override
    public List<String> list() {
        if (!Files.isDirectory(directory)) return Collections.emptyList();
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                if (Files.isRegularFile(entry)) names.add(entry.getFileName().toString());
            }
        } catch (IOException e) {
            CrystalGuiCore.LOGGER.warn("Could not list {}", directory, e);
        }
        Collections.sort(names);
        return names;
    }

    @Override
    public void delete(String name) {
        try {
            Files.deleteIfExists(directory.resolve(name));
        } catch (IOException e) {
            CrystalGuiCore.LOGGER.warn("Could not delete {}", directory.resolve(name), e);
        }
    }

    @Override
    public boolean isWritable() {
        return writable;
    }
}
