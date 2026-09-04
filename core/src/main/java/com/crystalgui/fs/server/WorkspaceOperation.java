package com.crystalgui.fs.server;

/**
 * What is being attempted, for {@link WorkspacePermission}.
 *
 * <p>Read and write are separate rights because "may look at the scripts" and "may change the scripts"
 * are different answers on any server that has more than one kind of player.</p>
 */
public enum WorkspaceOperation {

    /** Listing a directory or reading a file. */
    READ,

    /** Creating, replacing, renaming or deleting. */
    WRITE
}
