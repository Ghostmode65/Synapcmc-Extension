package com.synapmc.library;

import com.synapmc.repo.RepoManager;

public class FInstall {

    private final String repoScope;

    public FInstall(String repoScope) {
        this.repoScope = repoScope;
    }

    public String script(String name, String location) {
        if (repoScope != null) {
            return RepoManager.getInstance().installScript(name, location, repoScope);
        }
        return RepoManager.getInstance().installScript(name, location);
    }

    public String script(String name) {
        return script(name, "library");
    }


    public String jar(String name) {
        return RepoManager.getInstance().installJar(name, repoScope);
    }
    
    public String exe(String name) {
        return RepoManager.getInstance().installExe(name, repoScope);
    }
}
