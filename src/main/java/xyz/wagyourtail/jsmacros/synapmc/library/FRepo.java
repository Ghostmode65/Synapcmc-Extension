package xyz.wagyourtail.jsmacros.synapmc.library;

import xyz.wagyourtail.jsmacros.synapmc.repo.RepoManager;

import java.util.List;


public class FRepo {
    /** null = unscoped (all repos). Set via {@code synapmc:repo("RepoName")}. */
    private final String repoScope;

    public FRepo() { this.repoScope = null; }
    public FRepo(String repoScope) { this.repoScope = repoScope; }

    public void add(String url) {
        RepoManager.getInstance().addRepo(url);
    }

    public String install(String name, String location) {
        if (repoScope != null) {
            return RepoManager.getInstance().installScript(name, location, repoScope);
        }
        return RepoManager.getInstance().installScript(name, location);
    }

    public boolean remove(String name) {
        return RepoManager.getInstance().removeScript(name);
    }

    public List<?> list() {
        if (repoScope != null) {
            return RepoManager.getInstance().listByRepo(repoScope);
        }
        return RepoManager.getInstance().listAll();
    }

    public void refresh() {
        RepoManager.getInstance().refreshRepos();
    }
    public void openBrowser() {
        RepoManager.getInstance().getDashboard().openDashboard();
    }
    public String getString(String name) {
        return RepoManager.getInstance().getString(name);
    }
}
