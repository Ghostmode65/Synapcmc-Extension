package xyz.wagyourtail.jsmacros.synapmc;

import com.google.common.collect.Sets;
import xyz.wagyourtail.jsmacros.core.Core;
import xyz.wagyourtail.jsmacros.core.extensions.Extension;
import xyz.wagyourtail.jsmacros.core.language.BaseLanguage;
import xyz.wagyourtail.jsmacros.core.language.BaseWrappedException;
import xyz.wagyourtail.jsmacros.core.library.BaseLibrary;
import xyz.wagyourtail.jsmacros.synapmc.config.SynapMcConfig;
import xyz.wagyourtail.jsmacros.synapmc.library.FSynapMc;
import xyz.wagyourtail.jsmacros.synapmc.repo.RepoManager;
import xyz.wagyourtail.jsmacros.synapmc.server.BridgeServer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.*;

public class SynapMcExtension implements Extension {

    public static BridgeServer server;
    public static File roamingDir;

    @Override
    public String getLanguageImplName() {
        return "synapmc";
    }

    @Override
    public void init() {
        try {
            Core.getInstance().config.addOptions("synapmc", SynapMcConfig.class);
        } catch (Exception e) {
            throw new RuntimeException("[SynapMc] Failed to register config", e);
        }

        SynapMcConfig cfg = Core.getInstance().config.getOptions(SynapMcConfig.class);

        //roaming/.jsMacros
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            String appdata = System.getenv("APPDATA");
            roamingDir = new File(appdata != null ? appdata : System.getProperty("user.home"), ".jsMacros");
        } else {
            roamingDir = new File(System.getProperty("user.home"), ".jsMacros");
        }
        roamingDir.mkdirs();

        //unified junction
        createDirs(cfg);
        RepoManager.getInstance().init(roamingDir, cfg);

        if (cfg.unifyFolder) {
            createUnifiedJunction();
        }

        // Execute scripts,should work for any language?
        if (cfg.loadEnabled) {
            File loadDir = resolveDir(cfg.loadDirectory, "scripts/load");
            if (loadDir.isDirectory()) {
                runLoadScripts(loadDir, cfg);
            }
        }

        //webhook
        if (cfg.webhookEnabled) {
            server = new BridgeServer(cfg.webhookPort, cfg.webhookPassword);
            try {
                server.start();
            } catch (Exception e) {
                System.err.println("[SynapMc] Failed to start webhook on port " + cfg.webhookPort + ": " + e.getMessage());
            }
        }
    }

    private void createDirs(SynapMcConfig cfg) {
        String[] dirs = {
            "scripts/library",
            "scripts/library/main",
            "scripts/load",
            "scripts/macros",
            "scripts/plugins",
            "logs",
            "assets",
            "repos"
        };
        for (String rel : dirs) {
            new File(roamingDir, rel).mkdirs();
        }
    }

    private void createUnifiedJunction() {
        // The junction lives at <instanceMacros>/unified -> roamingDir/.jsmacros/scripts/macros
        File macrosTarget = new File(roamingDir, "scripts/macros");

        //gotta detect instance macro folder from JsMacros config, launchers are all over the place
        File instanceMacros = Core.getInstance().config.macroFolder;
        if (instanceMacros == null) return;

        File junction = new File(instanceMacros, "unified");
        if (junction.exists()) return;

        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            ProcessBuilder pb;
            if (os.contains("win")) {
                pb = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                    junction.getAbsolutePath(),
                    macrosTarget.getAbsolutePath());
            } else {
                pb = new ProcessBuilder("ln", "-s",
                    macrosTarget.getAbsolutePath(),
                    junction.getAbsolutePath());
            }
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            proc.waitFor();
        } catch (Exception e) {
            System.err.println("[SynapMc] Could not create unified junction: " + e.getMessage());
        }
    }

    private void runLoadScripts(File loadDir, SynapMcConfig cfg) {
        for (File f : sortedFiles(loadDir)) {
            if (f.isFile() && !f.getName().startsWith("_")) {
                String lang = detectLang(f);
                if (lang != null) execScript(f, lang, cfg);
            }
        }
    }

    private void execScript(File file, String lang, SynapMcConfig cfg) {
        String content;
        try {
            content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            writeLog(file, e);
            return;
        }

        Core.getInstance().exec(lang, content, file, null, null, ex -> {
            writeLog(file, ex);

            if (server != null && server.hasClients()) {
                server.broadcast(BridgeServer.errorJson(
                    ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName(),
                    file.getName(), -1));
            }

            if (!cfg.silent) {
                if (cfg.debug) {
                    System.err.println("[SynapMc] Error in " + file.getName() + ": " + ex);
                } else {
                    System.err.println("[SynapMc] \u00a7c" + file.getName() + " failed to load: " + file.getName());
                }
            }
        });
    }

    private void writeLog(File script, Throwable ex) {
        try {
            File log = new File(roamingDir, "logs/synapmc.log");
            log.getParentFile().mkdirs();
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            String entry = "[" + timestamp + "] Error in " + script.getName() + ": " + ex + "\n";
            Files.write(log.toPath(), entry.getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
    }

    private File resolveDir(String configured, String defaultRel) {
        if (configured != null && !configured.isEmpty()) return new File(configured);
        return new File(roamingDir, defaultRel);
    }

    //plan to make this optional, will be required for some repo tags.
    private String detectLang(File f) {
        String name = f.getName();
        if (name.endsWith(".lua"))  return "lua";
        if (name.endsWith(".js"))   return "js";
        if (name.endsWith(".py"))   return "py";
        return null;
    }

    private File[] sortedFiles(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return new File[0];
        Arrays.sort(files, Comparator.comparing(File::getName));
        return files;
    }

    @Override
    public int getPriority() {
        return -1;
    }

    @Override
    public ExtMatch extensionMatch(File file) {
        return ExtMatch.NOT_MATCH;
    }

    @Override
    public String defaultFileExtension() {
        return "";
    }

    @Override
    public BaseLanguage<?, ?> getLanguage(Core<?, ?> runner) {
        return null;
    }

    @Override
    public Set<Class<? extends BaseLibrary>> getLibraries() {
        return Sets.newHashSet(FSynapMc.class);
    }

    @Override
    public BaseWrappedException<?> wrapException(Throwable ex) {
        if (server != null && server.hasClients()) {
            String message = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            server.broadcast(BridgeServer.errorJson(message, null, -1));
        }
        return null;
    }

    @Override
    public boolean isGuestObject(Object o) {
        return false;
    }
}
