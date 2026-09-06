package com.crystalgui.core.storage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

/**
 * One named corner of another config store - <b>a key prefix, not a second store</b>.
 *
 * <p>What {@link ConfigStorage#scoped} answers by default: names are written as {@code <scope>/<name>},
 * {@link #list()} returns only this scope's and strips the prefix back off, and everything else is the
 * delegate's. A store backed by a real directory overrides {@code scoped} with a real subdirectory
 * instead, because {@link #directory()} has to be somewhere a compiler can write.</p>
 *
 * <p>Why you meet it: two applications on one desktop share one config directory and both want to write
 * {@code settings.json} and a session record. The registry scopes each launch's storage by the
 * application's id, so neither can read or overwrite the other's - unscoped, the second to save simply
 * wins and the user's two products quietly become one.</p>
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
