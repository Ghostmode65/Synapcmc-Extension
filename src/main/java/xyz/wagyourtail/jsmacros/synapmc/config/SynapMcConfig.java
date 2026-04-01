package xyz.wagyourtail.jsmacros.synapmc.config;

import xyz.wagyourtail.jsmacros.core.config.Option;
import xyz.wagyourtail.jsmacros.core.config.OptionType;
import xyz.wagyourtail.jsmacros.synapmc.repo.RepoManager;

import java.util.LinkedHashMap;
import java.util.Map;

public class SynapMcConfig {

    @Option(translationKey = "AutorunEnabled", group = {"synapmc"})
    public boolean loadEnabled = true;

    @Option(translationKey = "LoadDirectory", group = {"synapmc"})
    public String loadDirectory = "";

    // Webhook
    @Option(translationKey = "Enabled", group = {"synapmc", "Webhook"})
    public boolean webhookEnabled = false;

    @Option(translationKey = "Port", group = {"synapmc", "Webhook"})
    public int webhookPort = 8765;

    @Option(translationKey = "Password", group = {"synapmc", "Webhook"})
    public String webhookPassword = "";

    //repo
    @Option(translationKey = "OpenRepoBrowser", group = {"synapmc", "Repos"}, setter = "setOpenDashboard")
    public boolean getOpenDashboard() {
        return false;
    }

    public void setOpenDashboard(boolean ignored) {
        RepoManager.getInstance().getDashboard().openDashboard();
    }

    //default is false till I can get register tiggers to work properly.
    @Option(translationKey = "AutoRegisterTriggers", group = {"synapmc", "Repos"})
    public boolean autoRegisterTriggers = false;

    @Option(translationKey = "RepoSources", group = {"synapmc", "Repos", "Sources"}, type = @OptionType(value = "string"))
    public Map<String, String> repos = new LinkedHashMap<String, String>() {{
        put("luaJ", "https://raw.githubusercontent.com/Ghostmode65/luaJ/main/manifest.json");
        put("SynapMc", "https://raw.githubusercontent.com/Ghostmode65/Synapcmc-Extension/refs/heads/main/repository/manifest.json");
    }};

    // Dev
    @Option(translationKey = "UnifyFolder", group = {"synapmc", "Dev"})
    public boolean unifyFolder = true;

    @Option(translationKey = "Debug", group = {"synapmc", "Dev"})
    public boolean debug = false;

    @Option(translationKey = "Silent", group = {"synapmc", "Dev"})
    public boolean silent = false;
}
