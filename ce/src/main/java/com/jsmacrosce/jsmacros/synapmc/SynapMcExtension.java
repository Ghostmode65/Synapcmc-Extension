package com.jsmacrosce.jsmacros.synapmc;

import com.jsmacrosce.jsmacros.core.Core;
import com.jsmacrosce.jsmacros.core.extensions.Extension;
import com.jsmacrosce.jsmacros.core.extensions.LibraryExtension;
import com.jsmacrosce.jsmacros.core.library.BaseLibrary;
import com.synapmc.ICoreAccess;
import com.synapmc.SynapMcShared;
import com.synapmc.repo.RepoManager;
import com.synapmc.server.BridgeServer;
import com.jsmacrosce.jsmacros.synapmc.config.SynapMcConfig;
import com.jsmacrosce.jsmacros.synapmc.library.FSynapMc;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.function.Consumer;

public class SynapMcExtension implements Extension, LibraryExtension, ICoreAccess {

    private Core<?, ?> core;

    @Override
    public String getExtensionName() {
        return "synapmc";
    }

    @Override
    public void init(Core<?, ?> core) {
        this.core = core;

        try {
            core.config.addOptions("synapmc", SynapMcConfig.class);
        } catch (Exception e) {
            throw new RuntimeException("[SynapMc] Failed to register config", e);
        }

        SynapMcConfig cfg = core.config.getOptions(SynapMcConfig.class);

        String os = System.getProperty("os.name", "").toLowerCase();
        File roamingDir;
        if (os.contains("win")) {
            String appdata = System.getenv("APPDATA");
            roamingDir = new File(appdata != null ? appdata : System.getProperty("user.home"), ".jsMacros");
        } else {
            roamingDir = new File(System.getProperty("user.home"), ".jsMacros");
        }
        roamingDir.mkdirs();

        SynapMcShared.core = this;
        SynapMcShared.roamingDir = roamingDir;

        createDirs(roamingDir);
        RepoManager.getInstance().init(roamingDir);

        if (cfg.unifyFolder) {
            createUnifiedJunction(roamingDir);
        }

        if (cfg.loadEnabled) {
            File loadDir = resolveDir(cfg.loadDirectory, roamingDir, "scripts/load");
            if (loadDir.isDirectory()) {
                runLoadScripts(loadDir, cfg);
            }
        }

        if (cfg.tcpPortEnabled) {
            SynapMcShared.server = new BridgeServer(cfg.tcpPort, cfg.tcpPortPassword);
            try {
                SynapMcShared.server.start();
            } catch (Exception e) {
                System.err.println("[SynapMc] Failed to start TCP port on port " + cfg.tcpPort + ": " + e.getMessage());
            }
        }
    }

    @Override
    public java.util.Set<java.net.URL> getDependencies() {
        return java.util.Collections.emptySet();
    }

    @Override
    public byte[] httpGet(String url) throws IOException {
        URLConnection conn = new URL(url).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);
        try (InputStream in = conn.getInputStream()) {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) != -1) buf.write(chunk, 0, n);
            return buf.toByteArray();
        }
    }

    @Override
    public String httpGetText(String url) throws IOException {
        return new String(httpGet(url), StandardCharsets.UTF_8);
    }

    @Override
    public void exec(String lang, String script, File file, Consumer<Throwable> errHandler) {
        core.exec(lang, script, file, null, null, ex -> errHandler.accept(ex));
    }

    @Override
    public File getMacroFolder() {
        return core.config.macroFolder;
    }

    @Override
    public Object getProfile() throws Exception {
        try {
            Method m = core.getClass().getMethod("getProfile");
            return m.invoke(core);
        } catch (NoSuchMethodException e) {
            return core.getClass().getField("profile").get(core);
        }
    }

    @Override
    public boolean hasExtensionForFile(File f) {
        return core.extensions.getExtensionForFile(f) != null;
    }

    @Override
    public boolean isAutoRegisterTriggers() {
        return core.config.getOptions(SynapMcConfig.class).autoRegisterTriggers;
    }

    @Override
    public int getTcpPort() {
        return core.config.getOptions(SynapMcConfig.class).tcpPort;
    }

    @Override
    public String getTcpPortPassword() {
        return core.config.getOptions(SynapMcConfig.class).tcpPortPassword;
    }

    @Override
    public Map<String, String> getConfiguredRepos() {
        return core.config.getOptions(SynapMcConfig.class).repos;
    }

    @Override
    public void addConfigRepo(String name, String url) {
        SynapMcConfig cfg = core.config.getOptions(SynapMcConfig.class);
        if (!cfg.repos.containsKey(name)) {
            cfg.repos.put(name, url);
            core.config.saveConfig();
        }
    }

    @Override
    public void removeConfigRepo(String name) {
        core.config.getOptions(SynapMcConfig.class).repos.remove(name);
        core.config.saveConfig();
    }

    @Override
    public void saveConfig() {
        core.config.saveConfig();
    }

    @Override
    public String getScriptTriggerClassName() {
        // ⚠ Verify this class name exists in jsmacrosce 2.0.0.
        return "com.jsmacrosce.jsmacros.core.config.ScriptTrigger";
    }

    @Override
    public Set<Class<? extends BaseLibrary>> getLibraries() {
        Set<Class<? extends BaseLibrary>> set = new HashSet<>();
        set.add(FSynapMc.class);
        return set;
    }

    private void createDirs(File roamingDir) {
        String[] dirs = {
            "scripts/library", "scripts/library/main",
            "scripts/load", "scripts/macros",
            "scripts/plugins", "logs", "assets", "repos"
        };
        for (String rel : dirs) {
            new File(roamingDir, rel).mkdirs();
        }
    }

    private void createUnifiedJunction(File roamingDir) {
        File macrosTarget = new File(roamingDir, "scripts/macros");
        File instanceMacros = core.config.macroFolder;
        if (instanceMacros == null) return;

        File junction = new File(instanceMacros, "unified");
        if (junction.exists()) return;

        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            ProcessBuilder pb;
            if (os.contains("win")) {
                pb = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                    junction.getAbsolutePath(), macrosTarget.getAbsolutePath());
            } else {
                pb = new ProcessBuilder("ln", "-s",
                    macrosTarget.getAbsolutePath(), junction.getAbsolutePath());
            }
            pb.redirectErrorStream(true);
            pb.start().waitFor();
        } catch (Exception e) {
            System.err.println("[SynapMc] Could not create unified junction: " + e.getMessage());
        }
    }

    private void runLoadScripts(File loadDir, SynapMcConfig cfg) {
        for (File f : sortedFiles(loadDir)) {
            if (f.isFile() && !f.getName().startsWith("_")) {
                String lang = detectLang(f);
                if (lang != null) execLoadScript(f, lang, cfg);
            }
        }
    }

    private void execLoadScript(File file, String lang, SynapMcConfig cfg) {
        if (core.extensions.getExtensionForFile(file) == null) {
            System.err.println("[SynapMc] No language extension found for " + file.getName());
            return;
        }
        String content;
        try {
            content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            writeLog(file, e);
            return;
        }
        core.exec(lang, content, file, null, null, ex -> {
            writeLog(file, ex);
            if (SynapMcShared.server != null && SynapMcShared.server.hasClients()) {
                SynapMcShared.server.broadcast(BridgeServer.errorJson(
                    ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName(),
                    file.getName(), -1));
            }
            if (!cfg.silent) {
                if (cfg.debug) {
                    System.err.println("[SynapMc] Error in " + file.getName() + ": " + ex);
                } else {
                    System.err.println("[SynapMc] \u00a7c" + file.getName() + " failed to load.");
                }
            }
        });
    }

    private void writeLog(File script, Throwable ex) {
        try {
            File log = new File(SynapMcShared.roamingDir, "logs/synapmc.log");
            log.getParentFile().mkdirs();
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            String entry = "[" + timestamp + "] Error in " + script.getName() + ": " + ex + "\n";
            Files.write(log.toPath(), entry.getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
    }

    private File resolveDir(String configured, File roamingDir, String defaultRel) {
        if (configured != null && !configured.isEmpty()) return new File(configured);
        return new File(roamingDir, defaultRel);
    }

    private String detectLang(File f) {
        String name = f.getName();
        if (name.endsWith(".lua")) return "lua";
        if (name.endsWith(".js"))  return "js";
        if (name.endsWith(".py"))  return "py";
        return null;
    }

    private File[] sortedFiles(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return new File[0];
        Arrays.sort(files, Comparator.comparing(File::getName));
        return files;
    }
}
