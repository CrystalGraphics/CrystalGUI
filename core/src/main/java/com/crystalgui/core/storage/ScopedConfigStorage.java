package com.crystalgui.core.storage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

/**
 * One named corner of another store — <b>a key prefix, not a second store</b>.
 *
 * <p>The general answer to {@link ConfigStorage#scoped}: names are written as {@code <scope>/<name>},
 * {@link #list()} answers only this scope's and strips the prefix back off, and everything else is the
 * delegate's. A store that is a real directory overrides {@code scoped} with a real subdirectory
 * instead ({@link LocalConfigStorage}), because {@link #directory()} has to be somewhere a compiler can
 * write.</p>
 *
 * <p>Why it exists: two applications on one desktop share one config directory, and both write
 * {@code settings.json} and a session record. Unscoped, the second to save wins and the user's two
 * products quietly become one — the same collision two status bars were, and the reason D20 scopes by
 * application id.</p>
 */
public final class ScopedConfigStorage implements ConfigStorage {

    private final ConfigStorage delegate;
    private final String prefix;

    ScopedConfigStorage(ConfigStorage delegate, String scope) {
        this.delegate = delegate;
        this.prefix = scope + "/";
    }

    @Override
    @Nullable
    public String read(String name) {
        return delegate.read(prefix + name);
    }

    @Override
    public void write(String name, String contents) {
        delegate.write(prefix + name, contents);
    }

    @Override
    public List<String> list() {
        List<String> mine = new ArrayList<>();
        for (String name : delegate.list()) {
            if (name.startsWith(prefix)) mine.add(name.substring(prefix.length()));
        }
        return mine;
    }

    @Override
    public void delete(String name) {
        delegate.delete(prefix + name);
    }

    @Override
    public boolean isWritable() {
        return delegate.isWritable();
    }

    /**
     * The delegate's, unscoped, and deliberately.
     *
     * <p>A cache directory is asked for BY NAME ({@code ConfigStorage.directory()} then a named
     * subdirectory), so a scoped store handing back the same root is not a collision — and answering
     * null here would take the compiler's output directory away from every application that reached its
     * storage through a scope.</p>
     */
    @Override
    @Nullable
    public Path directory() {
        return delegate.directory();
    }
}
