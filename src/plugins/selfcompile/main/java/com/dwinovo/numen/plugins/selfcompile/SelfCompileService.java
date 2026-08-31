package com.dwinovo.numen.plugins.selfcompile;

import java.nio.file.Path;

/** Small state holder for the plugin's read-only first milestone. */
public final class SelfCompileService {
    private final MutationWorkspace workspace;
    private volatile MutationManifest current;

    public SelfCompileService(Path root) {
        this.workspace = new MutationWorkspace(root);
    }

    public String status() {
        MutationManifest m = current;
        if (m == null) return "{\"module\":\"selfcompile\",\"state\":\"idle\",\"mode\":\"controlled\"}";
        return "{\"module\":\"selfcompile\",\"state\":\"" + m.state()
                + "\",\"id\":\"" + m.id() + "\"}";
    }

    public MutationManifest create(String requirement) throws java.io.IOException {
        current = workspace.create(requirement);
        return current;
    }

    public MutationWorkspace workspace() {
        return workspace;
    }
}
