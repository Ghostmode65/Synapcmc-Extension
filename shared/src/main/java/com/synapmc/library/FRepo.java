package com.synapmc.library;

import com.synapmc.repo.RepoManager;

import java.util.List;

public class FRepo {

    private final String repoScope;

    public FRepo() { this.repoScope = null; }
    public FRepo(String repoScope) { this.repoScope = repoScope; }

    public void add(String url) {
        RepoManager.getInstance().addRepo(url, true);
    }

    public void add(String url, boolean overwrite) {
        RepoManager.getInstance().addRepo(url, overwrite);
    }

    public void addAll(List<String> urls) {
        RepoManager.getInstance().addRepos(urls);
    }

    public boolean isInstalled(String name) {
        return RepoManager.getInstance().isInstalled(name);
    }

    public FInstall install() {
        return new FInstall(repoScope);
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
        return RepoManager.getInstance().getString(name, false);
    }

    public String getString(String name, boolean source) {
        return RepoManager.getInstance().getString(name, source);
    }
}
