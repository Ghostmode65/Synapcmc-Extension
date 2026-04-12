package com.synapmc.repo;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.synapmc.SynapMcShared;
import com.synapmc.model.RepoManifest;
import com.synapmc.model.ScriptEntry;
import com.synapmc.model.ScriptRegister;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RepoManager {


    public static class InstalledInfo {
        public String path;
        public String version;
        public String repoName;
    }

    private static RepoManager INSTANCE;

    public static synchronized RepoManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new RepoManager();
        }
        return INSTANCE;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private File roamingDir;
    private final Map<String, RepoManifest> manifests = new LinkedHashMap<String, RepoManifest>();
    private final Map<String, InstalledInfo> installed = new LinkedHashMap<String, InstalledInfo>();
    private String lastFetchedName;
    private final DashboardServer dashboard = new DashboardServer();

    private RepoManager() {}

    public DashboardServer getDashboard() { return dashboard; }


    public void init(File roamingDir) {
        this.roamingDir = roamingDir;
        new File(roamingDir, "repos").mkdirs();
        loadInstalledIndex();
        for (String url : SynapMcShared.core.getConfiguredRepos().values()) {
            try {
                fetchAndCacheManifest(url);
            } catch (Exception e) {
                System.err.println("[SynapMc] Failed to fetch manifest from " + url + ": " + e.getMessage());
            }
        }
        applyRegisters();
    }

    public void addRepo(String url) {
        addRepo(url, true);
    }

    public void addRepo(String url, boolean overwrite) {
        try {
            fetchAndCacheManifest(url);
        } catch (Exception e) {
            throw new RuntimeException("[SynapMc] Failed to add repo from " + url + ": " + e.getMessage(), e);
        }
        RepoManifest manifest = manifests.get(lastFetchedName);
        if (manifest != null) {
            boolean alreadyPresent = SynapMcShared.core.getConfiguredRepos().containsKey(manifest.name);
            if (!alreadyPresent || overwrite) {
                SynapMcShared.core.addConfigRepo(manifest.name, url);
            }
        }
    }

    public void removeRepo(String name) {
        manifests.remove(name.toLowerCase());
        SynapMcShared.core.removeConfigRepo(name);
    }

    public boolean isInstalled(String name) {
        return installed.containsKey(name.toLowerCase());
    }

    public void addRepos(List<String> urls) {
        for (String url : urls) {
            try {
                addRepo(url);
            } catch (Exception e) {
                System.err.println("[SynapMc] bulk-add failed for " + url + ": " + e.getMessage());
            }
        }
    }

    public void refreshRepos() {
        manifests.clear();
        for (String url : SynapMcShared.core.getConfiguredRepos().values()) {
            try {
                fetchAndCacheManifest(url);
            } catch (Exception e) {
                System.err.println("[SynapMc] refresh: failed to fetch manifest from " + url + ": " + e.getMessage());
            }
        }
        System.out.println("[SynapMc] Repos refreshed (" + manifests.size() + " loaded).");
    }

    public String installScript(String name, String location) {
        return installScript(name, location, new HashSet<String>());
    }

    private String installScript(String name, String location, Set<String> visited) {
        if (!visited.add(name.toLowerCase())) {
            System.err.println("[SynapMc] install: dependency cycle detected at: " + name);
            return null;
        }

        ScriptEntry entry = findEntry(name);
        if (entry == null) {
            System.err.println("[SynapMc] install: script not found in any manifest: " + name);
            return null;
        }

        if (entry.dependencies != null) {
            for (String dep : entry.dependencies) {
                if (!isInstalled(dep)) {
                    System.out.println("[SynapMc] install: installing dependency '" + dep + "' for '" + name + "'");
                    installScript(dep, location, new HashSet<String>(visited));
                }
            }
        }

        InstalledInfo existing = installed.get(name.toLowerCase());
        if (existing != null && existing.version != null && entry.version != null) {
            if (compareVersions(entry.version, existing.version) <= 0) {
                System.out.println("[SynapMc] install: " + name + " is up-to-date (" + existing.version + "), skipping.");
                return existing.path;
            }
        }

        String destRel = resolveDestination(entry, location);
        File destDir = new File(roamingDir, destRel);
        destDir.mkdirs();

        String filename = entry.source.substring(entry.source.lastIndexOf('/') + 1);
        File destFile = new File(destDir, filename);

        try {
            byte[] bytes = SynapMcShared.core.httpGet(entry.source);
            if (bytes == null || bytes.length == 0) {
                System.err.println("[SynapMc] install: empty response from " + entry.source);
                return null;
            }
            Files.write(destFile.toPath(), bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            System.err.println("[SynapMc] install: download failed for " + name + ": " + e.getMessage());
            return null;
        }

        InstalledInfo info = new InstalledInfo();
        info.path = destFile.getAbsolutePath();
        info.version = entry.version;
        info.repoName = entry.repoName;
        installed.put(name.toLowerCase(), info);
        saveInstalledIndex();

        if (entry.register != null && SynapMcShared.core.isAutoRegisterTriggers()) {
            applyRegister(entry, destFile);
        }

        System.out.println("[SynapMc] Installed " + name + " -> " + destFile.getAbsolutePath());

        if (SynapMcShared.server != null && SynapMcShared.server.hasClients()) {
            Map<String, String> notif = new LinkedHashMap<String, String>();
            notif.put("type", "installed");
            notif.put("name", name);
            notif.put("path", destFile.getAbsolutePath());
            SynapMcShared.server.broadcast(GSON.toJson(notif));
        }

        return destFile.getAbsolutePath();
    }

    public boolean removeScript(String name) {
        InstalledInfo info = installed.remove(name.toLowerCase());
        if (info == null) {
            System.err.println("[SynapMc] remove: script not installed: " + name);
            return false;
        }
        File f = new File(info.path);
        if (f.exists() && !f.delete()) {
            System.err.println("[SynapMc] remove: could not delete file: " + info.path);
        }
        removeTrigger(f.getName());
        saveInstalledIndex();
        return true;
    }

    public List<Object> listAll() {
        List<Object> result = new ArrayList<Object>();
        for (Map.Entry<String, RepoManifest> mEntry : manifests.entrySet()) {
            RepoManifest manifest = mEntry.getValue();
            if (manifest.scripts == null) continue;
            for (ScriptEntry script : manifest.scripts) {
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("name", script.name);
                row.put("author", script.author);
                row.put("description", script.description);
                row.put("version", script.version);
                row.put("repoName", manifest.name);
                row.put("category", script.category);
                row.put("isExecuteReady", script.isExecuteReady);
                InstalledInfo info = installed.get(script.name.toLowerCase());
                row.put("installed", info != null);
                row.put("installedVersion", info != null ? info.version : null);
                row.put("path", info != null ? info.path : null);
                result.add(row);
            }
        }
        return result;
    }

    public String installScript(String name, String location, String repoName) {
        ScriptEntry entry = findEntry(name, repoName);
        if (entry == null) {
            System.err.println("[SynapMc] install: script '" + name + "' not found in repo '" + repoName + "'");
            return null;
        }
        return installScript(name, location, new HashSet<String>());
    }

    public String installJar(String name, String repoScope) {
        ScriptEntry entry = repoScope != null ? findEntry(name, repoScope) : findEntry(name);
        if (entry == null) {
            System.err.println("[SynapMc] installJar: script not found: " + name);
            return null;
        }

        // 2.0.0 and community edition use an /Extensions/ folder; null or 1.9.2 go next to the running JAR
        String jsmVer = entry.jsmacrosVersion != null ? entry.jsmacrosVersion.toLowerCase() : "";
        File jarDir;
        if (jsmVer.equals("2.0.0") || jsmVer.equals("community")) {
            jarDir = new File(roamingDir.getParentFile(), "Extensions");
        } else {
            // null or "1.9.2" — place alongside the running SynapMc JAR
            try {
                java.security.CodeSource cs = RepoManager.class.getProtectionDomain().getCodeSource();
                jarDir = cs != null ? new File(cs.getLocation().toURI()).getParentFile() : roamingDir;
            } catch (Exception e) {
                System.err.println("[SynapMc] installJar: could not locate JAR dir, falling back to roaming: " + e.getMessage());
                jarDir = roamingDir;
            }
        }
        jarDir.mkdirs();

        String filename = entry.source.substring(entry.source.lastIndexOf('/') + 1);
        File destFile = new File(jarDir, filename);

        try {
            byte[] bytes = SynapMcShared.core.httpGet(entry.source);
            if (bytes == null || bytes.length == 0) {
                System.err.println("[SynapMc] installJar: empty response from " + entry.source);
                return null;
            }
            Files.write(destFile.toPath(), bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            System.err.println("[SynapMc] installJar: download failed for " + name + ": " + e.getMessage());
            return null;
        }

        InstalledInfo info = new InstalledInfo();
        info.path = destFile.getAbsolutePath();
        info.version = entry.version;
        info.repoName = entry.repoName;
        installed.put(name.toLowerCase(), info);
        saveInstalledIndex();

        System.out.println("[SynapMc] Installed JAR: " + name + " -> " + destFile.getAbsolutePath());
        System.out.println("[SynapMc] *** Restart JsMacros for the extension to load. ***");
        return destFile.getAbsolutePath();
    }

    public String installExe(String name, String repoScope) {
        ScriptEntry entry = repoScope != null ? findEntry(name, repoScope) : findEntry(name);
        if (entry == null) {
            System.err.println("[SynapMc] installExe: script not found: " + name);
            return null;
        }

        File desktop;
        try {
            desktop = javax.swing.filechooser.FileSystemView.getFileSystemView().getHomeDirectory();
        } catch (Exception e) {
            desktop = new File(System.getProperty("user.home"), "Desktop");
        }
        desktop.mkdirs();

        String filename = entry.source.substring(entry.source.lastIndexOf('/') + 1);
        File destFile = new File(desktop, filename);

        try {
            byte[] bytes = SynapMcShared.core.httpGet(entry.source);
            if (bytes == null || bytes.length == 0) {
                System.err.println("[SynapMc] installExe: empty response from " + entry.source);
                return null;
            }
            Files.write(destFile.toPath(), bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            System.err.println("[SynapMc] installExe: download failed for " + name + ": " + e.getMessage());
            return null;
        }

        System.out.println("[SynapMc] Downloaded EXE to Desktop: " + destFile.getAbsolutePath());
        return destFile.getAbsolutePath();
    }

    public List<Object> listByRepo(String repoName) {
        List<Object> result = new ArrayList<Object>();
        for (Map.Entry<String, RepoManifest> mEntry : manifests.entrySet()) {
            RepoManifest manifest = mEntry.getValue();
            if (!repoName.equalsIgnoreCase(manifest.name)) continue;
            if (manifest.scripts == null) continue;
            for (ScriptEntry script : manifest.scripts) {
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("name", script.name);
                row.put("author", script.author);
                row.put("description", script.description);
                row.put("version", script.version);
                row.put("repoName", manifest.name);
                row.put("category", script.category);
                row.put("isExecuteReady", script.isExecuteReady);
                InstalledInfo info = installed.get(script.name.toLowerCase());
                row.put("installed", info != null);
                row.put("installedVersion", info != null ? info.version : null);
                row.put("path", info != null ? info.path : null);
                result.add(row);
            }
        }
        return result;
    }

    public String getString(String name) {
        return getString(name, false);
    }

    public String getString(String name, boolean source) {
        if (!source) {
            InstalledInfo info = installed.get(name.toLowerCase());
            if (info != null) {
                File f = new File(info.path);
                if (f.exists()) {
                    try {
                        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        System.err.println("[SynapMc] getString: read failed for " + info.path + ": " + e.getMessage());
                    }
                }
            }
        }

        ScriptEntry entry = findEntry(name);
        if (entry == null || entry.source == null) {
            System.err.println("[SynapMc] getString: script not found: " + name);
            return null;
        }

        try {
            return SynapMcShared.core.httpGetText(entry.source);
        } catch (Exception e) {
            System.err.println("[SynapMc] getString: fetch failed for " + name + ": " + e.getMessage());
            return null;
        }
    }

    public void applyRegisters() {
        if (!SynapMcShared.core.isAutoRegisterTriggers()) return;
        for (Map.Entry<String, InstalledInfo> entry : installed.entrySet()) {
            String scriptName = entry.getKey();
            InstalledInfo info = entry.getValue();
            ScriptEntry scriptEntry = findEntry(scriptName);
            if (scriptEntry == null || scriptEntry.register == null) continue;
            File scriptFile = new File(info.path);
            if (!scriptFile.exists()) continue;
            applyRegister(scriptEntry, scriptFile);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public void applyRegister(ScriptEntry entry, File scriptFile) {
        ScriptRegister reg = entry.register;
        if (reg == null) return;

        if ("service".equalsIgnoreCase(reg.type)) {
            String lang = null;
            String fname = scriptFile.getName();
            if (fname.endsWith(".lua")) lang = "lua";
            else if (fname.endsWith(".js")) lang = "js";
            else if (fname.endsWith(".py")) lang = "py";
            if (lang == null) {
                System.err.println("[SynapMc] applyRegister: unknown language for service: " + fname);
                return;
            }
            try {
                if (!SynapMcShared.core.hasExtensionForFile(scriptFile)) {
                    System.err.println("[SynapMc] No language extension found for " + fname);
                    return;
                }
                String content = new String(Files.readAllBytes(scriptFile.toPath()), StandardCharsets.UTF_8);
                final String finalLang = lang;
                SynapMcShared.core.exec(finalLang, content, scriptFile,
                    ex -> System.err.println("[SynapMc] service error in " + fname + ": " + ex.getMessage()));
                System.out.println("[SynapMc] Started service: " + entry.name);
            } catch (Exception e) {
                System.err.println("[SynapMc] applyRegister: failed to start service " + entry.name + ": " + e.getMessage());
            }
            return;
        }

        try {
            String triggerClassName = SynapMcShared.core.getScriptTriggerClassName();
            Class<?> triggerClass = Class.forName(triggerClassName);
            Class<?> triggerTypeClass = Class.forName(triggerClassName + "$TriggerType");

            Object triggerTypeValue;
            if ("keydown".equalsIgnoreCase(reg.type)) {
                triggerTypeValue = Enum.valueOf((Class<Enum>) triggerTypeClass, "KEY_RISING");
            } else if ("keyup".equalsIgnoreCase(reg.type)) {
                triggerTypeValue = Enum.valueOf((Class<Enum>) triggerTypeClass, "KEY_FALLING");
            } else if ("event".equalsIgnoreCase(reg.type)) {
                triggerTypeValue = Enum.valueOf((Class<Enum>) triggerTypeClass, "EVENT");
            } else {
                System.err.println("[SynapMc] applyRegister: unknown trigger type: " + reg.type);
                return;
            }

            String eventOrKey;
            if ("event".equalsIgnoreCase(reg.type)) {
                eventOrKey = reg.event != null ? reg.event : "";
            } else {
                eventOrKey = reg.key != null ? reg.key : "";
            }

            String repoSubDir = sanitizeFilename(entry.repoName != null ? entry.repoName : "unknown");
            String filePath = "unified/" + repoSubDir + "/" + scriptFile.getName();

            Object profile = SynapMcShared.core.getProfile();
            Object registry = profile.getClass().getMethod("getRegistry").invoke(profile);
            Method getTriggersMethod = registry.getClass().getMethod("getScriptTriggers");
            List<?> triggers = (List<?>) getTriggersMethod.invoke(registry);

            for (Object t : triggers) {
                Object sf = t.getClass().getField("scriptFile").get(t);
                if (sf != null && sf.toString().equals(filePath)) {
                    return;
                }
            }

            Constructor<?> ctor = triggerClass.getConstructor(
                    triggerTypeClass, String.class, String.class, boolean.class, boolean.class);
            Object trigger = ctor.newInstance(triggerTypeValue, eventOrKey, filePath, true, false);

            Method addMethod = registry.getClass().getMethod("addScriptTrigger", triggerClass);
            addMethod.invoke(registry, trigger);

            System.out.println("[SynapMc] Registered trigger for " + entry.name + " (" + reg.type + ")");

        } catch (ClassNotFoundException e) {
            System.err.println("[SynapMc] applyRegister: ScriptTrigger class not found: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("[SynapMc] applyRegister: failed for " + entry.name + ": " + e);
        }
    }

    private void fetchAndCacheManifest(String url) throws Exception {
        fetchAndCacheManifest(url, new HashSet<String>());
    }

    private void fetchAndCacheManifest(String url, Set<String> inFlight) throws Exception {
        String normalizedUrl = url.split("\\?")[0];
        if (!inFlight.add(normalizedUrl)) {
            System.err.println("[SynapMc] Manifest cycle detected, skipping: " + url);
            return;
        }
        fetchAndCacheManifestInternal(url, inFlight);
    }

    private void fetchAndCacheManifestInternal(String url, Set<String> inFlight) throws Exception {
        String fetchUrl = url + (url.contains("?") ? "&" : "?") + "_t=" + System.currentTimeMillis();
        String json = SynapMcShared.core.httpGetText(fetchUrl);
        if (json == null || json.isEmpty()) {
            throw new IOException("Empty response from " + url);
        }

        RepoManifest manifest;
        try {
            manifest = GSON.fromJson(json, RepoManifest.class);
        } catch (JsonSyntaxException e) {
            throw new IOException("Invalid JSON from " + url + ": " + e.getMessage());
        }

        if (manifest.name == null || manifest.name.isEmpty()) {
            throw new IOException("Manifest from " + url + " has no 'name' field");
        }

        manifest.manifestUrl = url;

        if (manifest.scripts != null) {
            for (ScriptEntry entry : manifest.scripts) {
                entry.repoName = manifest.name;
            }
        }

        File cacheFile = new File(roamingDir, "repos/" + sanitizeFilename(manifest.name) + ".json");
        Files.write(cacheFile.toPath(),
                GSON.toJson(manifest).getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        manifests.put(manifest.name.toLowerCase(), manifest);
        lastFetchedName = manifest.name.toLowerCase();

        if (manifest.repos != null) {
            for (String subUrl : manifest.repos) {
                if (subUrl == null || subUrl.isEmpty()) continue;
                try {
                    fetchAndCacheManifest(subUrl, inFlight);
                } catch (Exception e) {
                    System.err.println("[SynapMc] Failed to fetch sub-repo " + subUrl + ": " + e.getMessage());
                }
            }
        }
    }

    private ScriptEntry findEntry(String name) {
        return findEntry(name, null);
    }

    private ScriptEntry findEntry(String name, String repoName) {
        for (RepoManifest manifest : manifests.values()) {
            if (repoName != null && !repoName.equalsIgnoreCase(manifest.name)) continue;
            if (manifest.scripts == null) continue;
            for (ScriptEntry entry : manifest.scripts) {
                if (entry.name != null && entry.name.equalsIgnoreCase(name)) {
                    return entry;
                }
            }
        }
        return null;
    }

    private String resolveDestination(ScriptEntry entry, String location) {
        if (entry.destination != null && !entry.destination.isEmpty()) {
            String dest = entry.destination.endsWith("/") ? entry.destination : entry.destination + "/";
            return dest + sanitizeFilename(entry.repoName != null ? entry.repoName : "unknown") + "/";
        }

        String repoSub = sanitizeFilename(entry.repoName != null ? entry.repoName : "unknown");
        if ("load".equalsIgnoreCase(location)) {
            return "scripts/load/" + repoSub + "/";
        }
        if (location == null || "library".equalsIgnoreCase(location)) {
            if (entry.register != null) {
                return "scripts/macros/" + repoSub + "/";
            }
            return "scripts/library/" + repoSub + "/";
        }
        return "scripts/" + (location.endsWith("/") ? location : location + "/");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void removeTrigger(String filename) {
        try {
            String triggerClassName = SynapMcShared.core.getScriptTriggerClassName();
            Class<?> triggerClass = Class.forName(triggerClassName);

            Object profile = SynapMcShared.core.getProfile();
            Object registry = profile.getClass().getMethod("getRegistry").invoke(profile);
            Method getTriggersMethod = registry.getClass().getMethod("getScriptTriggers");
            List<?> triggers = (List<?>) getTriggersMethod.invoke(registry);

            List<Object> toRemove = new ArrayList<Object>();
            for (Object t : triggers) {
                Object sf = t.getClass().getField("scriptFile").get(t);
                if (sf != null && sf.toString().equals(filename)) {
                    toRemove.add(t);
                }
            }

            Method removeMethod = registry.getClass().getMethod("removeScriptTrigger", triggerClass);
            for (Object t : toRemove) {
                removeMethod.invoke(registry, t);
            }
        } catch (Exception e) {
            System.err.println("[SynapMc] removeTrigger: " + e.getMessage());
        }
    }

    private void loadInstalledIndex() {
        File indexFile = new File(roamingDir, "repos/installed.json");
        if (!indexFile.exists()) return;
        try {
            String json = new String(Files.readAllBytes(indexFile.toPath()), StandardCharsets.UTF_8);
            Type type = new TypeToken<Map<String, InstalledInfo>>() {}.getType();
            Map<String, InstalledInfo> loaded = GSON.fromJson(json, type);
            if (loaded != null) installed.putAll(loaded);
        } catch (Exception e) {
            System.err.println("[SynapMc] Could not load installed.json: " + e.getMessage());
        }
    }

    private void saveInstalledIndex() {
        File indexFile = new File(roamingDir, "repos/installed.json");
        try {
            Files.write(indexFile.toPath(),
                    GSON.toJson(installed).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            System.err.println("[SynapMc] Could not save installed.json: " + e.getMessage());
        }
    }

    private int compareVersions(String a, String b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        String[] partsA = a.split("\\.");
        String[] partsB = b.split("\\.");
        int len = Math.max(partsA.length, partsB.length);
        for (int i = 0; i < len; i++) {
            String segA = i < partsA.length ? partsA[i] : "0";
            String segB = i < partsB.length ? partsB[i] : "0";
            int cmp;
            try {
                cmp = Integer.compare(Integer.parseInt(segA), Integer.parseInt(segB));
            } catch (NumberFormatException e) {
                cmp = segA.compareTo(segB);
            }
            if (cmp != 0) return cmp;
        }
        return 0;
    }

    private String sanitizeFilename(String name) {
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }

    public ScriptEntry findEntryPublic(String name) {
        return findEntry(name);
    }

    Map<String, RepoManifest> getManifests() { return manifests; }
    Map<String, InstalledInfo> getInstalled() { return installed; }

    public Map<String, String> getRepos() {
        return SynapMcShared.core.getConfiguredRepos();
    }
}
