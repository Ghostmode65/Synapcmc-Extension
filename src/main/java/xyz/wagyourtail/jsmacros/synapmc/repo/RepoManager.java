package xyz.wagyourtail.jsmacros.synapmc.repo;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import xyz.wagyourtail.jsmacros.core.Core;
import xyz.wagyourtail.jsmacros.core.library.impl.classes.HTTPRequest;
import xyz.wagyourtail.jsmacros.synapmc.SynapMcExtension;
import xyz.wagyourtail.jsmacros.synapmc.config.SynapMcConfig;
import xyz.wagyourtail.jsmacros.synapmc.model.RepoManifest;
import xyz.wagyourtail.jsmacros.synapmc.model.ScriptEntry;
import xyz.wagyourtail.jsmacros.synapmc.model.ScriptRegister;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Singleton that manages repo manifests and installed script state.
 *
 * Manifests are cached as JSON files at .jsMacros/repos/<reponame>.json.
 * Installation state is persisted at .jsMacros/repos/installed.json.
 */
public class RepoManager {

    // -------------------------------------------------------------------------
    // Inner types
    // -------------------------------------------------------------------------

    /** Persisted record for an installed script. */
    public static class InstalledInfo {
        /** Absolute path to the installed file on disk. */
        public String path;
        /** Version string at install time. */
        public String version;
        /** Name of the repo this script belongs to. */
        public String repoName;
    }

    // -------------------------------------------------------------------------
    // Singleton
    // -------------------------------------------------------------------------

    private static RepoManager INSTANCE;

    public static synchronized RepoManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new RepoManager();
        }
        return INSTANCE;
    }

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Roaming .jsMacros directory. Set by init(). */
    private File roamingDir;

    /** Loaded/fetched manifests, keyed by repo name (lower-case). */
    private final Map<String, RepoManifest> manifests = new LinkedHashMap<String, RepoManifest>();

    /** Installation records, keyed by script name (lower-case). */
    private final Map<String, InstalledInfo> installed = new LinkedHashMap<String, InstalledInfo>();

    /** Name of the manifest most recently loaded by fetchAndCacheManifest(). */
    private String lastFetchedName;

    private final DashboardServer dashboard = new DashboardServer();

    private RepoManager() {}

    public DashboardServer getDashboard() {
        return dashboard;
    }

    // -------------------------------------------------------------------------
    // Init
    // -------------------------------------------------------------------------

    /**
     * Must be called once from SynapMcExtension.init().
     * Fetches all configured manifests (blocking) and loads the installed index.
     */
    public void init(File roamingDir, SynapMcConfig cfg) {
        this.roamingDir = roamingDir;

        // Ensure repos directory exists.
        new File(roamingDir, "repos").mkdirs();

        // Load existing installation index.
        loadInstalledIndex();

        // Fetch/refresh each configured manifest URL (Map<name, url>).
        for (String url : cfg.repos.values()) {
            try {
                fetchAndCacheManifest(url);
            } catch (Exception e) {
                System.err.println("[SynapMc] Failed to fetch manifest from " + url + ": " + e.getMessage());
            }
        }

        // Re-apply trigger registrations for installed scripts.
        applyRegisters();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Fetches a manifest from url, caches it locally, and adds the URL to the
     * config's repos list if it is not already present.
     */
    public void addRepo(String url) {
        try {
            fetchAndCacheManifest(url);
        } catch (Exception e) {
            throw new RuntimeException("[SynapMc] Failed to add repo from " + url + ": " + e.getMessage(), e);
        }

        // Add to config map keyed by manifest name (already fetched above).
        SynapMcConfig cfg = Core.getInstance().config.getOptions(SynapMcConfig.class);
        RepoManifest manifest = manifests.get(lastFetchedName);
        if (manifest != null && !cfg.repos.containsKey(manifest.name)) {
            cfg.repos.put(manifest.name, url);
            Core.getInstance().config.saveConfig();
        }
    }

    /**
     * Removes a repo by name: evicts the in-memory manifest and removes the entry
     * from the config map so it is not re-fetched on next startup.
     * Installed scripts are NOT touched.
     *
     * @param name Repo name exactly as stored in the config (case-sensitive map key).
     */
    public void removeRepo(String name) {
        manifests.remove(name.toLowerCase());
        SynapMcConfig cfg = Core.getInstance().config.getOptions(SynapMcConfig.class);
        cfg.repos.remove(name);
        Core.getInstance().config.saveConfig();
    }

    /**
     * Re-fetches all configured repo manifests from their URLs, replacing the
     * in-memory and on-disk cache. Useful when a manifest has been updated remotely.
     */
    public void refreshRepos() {
        manifests.clear();
        SynapMcConfig cfg = Core.getInstance().config.getOptions(SynapMcConfig.class);
        for (String url : cfg.repos.values()) {
            try {
                fetchAndCacheManifest(url);
            } catch (Exception e) {
                System.err.println("[SynapMc] refresh: failed to fetch manifest from " + url + ": " + e.getMessage());
            }
        }
        System.out.println("[SynapMc] Repos refreshed (" + manifests.size() + " loaded).");
    }

    /**
     * Downloads the named script to its destination folder and registers a trigger
     * if the manifest entry has a register field.
     *
     * location is used as the sub-folder when the manifest entry does not specify one.
     * Pass "load" to land in scripts/load/<repoName>/<file>, or null/"library" for
     * scripts/library/<repoName>/<file>.
     *
     * Only overwrites if the new version is strictly greater than what is installed.
     *
     * Returns the absolute path of the installed file, or null on failure.
     */
    public String installScript(String name, String location) {
        ScriptEntry entry = findEntry(name);
        if (entry == null) {
            System.err.println("[SynapMc] install: script not found in any manifest: " + name);
            return null;
        }

        // Version check: skip if already installed and same/older version.
        InstalledInfo existing = installed.get(name.toLowerCase());
        if (existing != null && existing.version != null && entry.version != null) {
            if (compareVersions(entry.version, existing.version) <= 0) {
                System.out.println("[SynapMc] install: " + name + " is up-to-date (" + existing.version + "), skipping.");
                return existing.path;
            }
        }

        // Resolve destination folder.
        String destRel = resolveDestination(entry, location);
        File destDir = new File(roamingDir, destRel);
        destDir.mkdirs();

        // Derive filename from the source URL.
        String filename = entry.source.substring(entry.source.lastIndexOf('/') + 1);
        File destFile = new File(destDir, filename);

        // Download.
        try {
            String content = new HTTPRequest(entry.source).get().text();
            if (content == null) {
                System.err.println("[SynapMc] install: empty response from " + entry.source);
                return null;
            }
            Files.write(destFile.toPath(),
                    content.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            System.err.println("[SynapMc] install: download failed for " + name + ": " + e.getMessage());
            return null;
        }

        // Record installation.
        InstalledInfo info = new InstalledInfo();
        info.path = destFile.getAbsolutePath();
        info.version = entry.version;
        info.repoName = entry.repoName;
        installed.put(name.toLowerCase(), info);
        saveInstalledIndex();

        // Register trigger if the entry requests it and the setting is enabled.
        SynapMcConfig installCfg = Core.getInstance().config.getOptions(SynapMcConfig.class);
        if (entry.register != null && installCfg.autoRegisterTriggers) {
            applyRegister(entry, destFile);
        }

        System.out.println("[SynapMc] Installed " + name + " -> " + destFile.getAbsolutePath());

        // Notify connected bridge clients.
        if (SynapMcExtension.server != null && SynapMcExtension.server.hasClients()) {
            Map<String, String> notif = new LinkedHashMap<String, String>();
            notif.put("type", "installed");
            notif.put("name", name);
            notif.put("path", destFile.getAbsolutePath());
            SynapMcExtension.server.broadcast(GSON.toJson(notif));
        }

        return destFile.getAbsolutePath();
    }

    /**
     * Removes an installed script: deletes the file from disk, removes the trigger
     * (via reflection), and removes the entry from the installed index.
     */
    public boolean removeScript(String name) {
        InstalledInfo info = installed.remove(name.toLowerCase());
        if (info == null) {
            System.err.println("[SynapMc] remove: script not installed: " + name);
            return false;
        }

        // Delete the file.
        File f = new File(info.path);
        if (f.exists() && !f.delete()) {
            System.err.println("[SynapMc] remove: could not delete file: " + info.path);
        }

        // Remove the trigger if one was registered.
        removeTrigger(f.getName());

        saveInstalledIndex();
        return true;
    }

    /**
     * Returns a list of maps suitable for direct scripting consumption.
     * Each map has keys: name, version, path, repoName, installed (boolean).
     */
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
                row.put("isApi", script.isApi);
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

    /**
     * Same as {@link #installScript(String, String)} but scoped to a specific repo.
     * Returns null (with a log message) if the script does not exist in that repo.
     */
    public String installScript(String name, String location, String repoName) {
        ScriptEntry entry = findEntry(name, repoName);
        if (entry == null) {
            System.err.println("[SynapMc] install: script '" + name + "' not found in repo '" + repoName + "'");
            return null;
        }
        return installScript(name, location);
    }

    /**
     * Returns the same descriptor maps as {@link #listAll()} but filtered to a single repo.
     * Returns an empty list if the repo is not loaded.
     */
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
                row.put("isApi", script.isApi);
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

    /**
     * If the script is installed locally, reads and returns its content from disk.
     * Otherwise fetches it from the source URL declared in the manifest.
     * Does not require installation.
     */
    public String getString(String name) {
        // Try installed file first.
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

        // Fall back to fetching from the source URL.
        ScriptEntry entry = findEntry(name);
        if (entry == null || entry.source == null) {
            System.err.println("[SynapMc] getString: script not found: " + name);
            return null;
        }

        try {
            return new HTTPRequest(entry.source).get().text();
        } catch (Exception e) {
            System.err.println("[SynapMc] getString: fetch failed for " + name + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Re-applies all trigger bindings for installed scripts that have a register field.
     * Called during init() and after any install.
     */
    public void applyRegisters() {
        SynapMcConfig cfg = Core.getInstance().config.getOptions(SynapMcConfig.class);
        if (!cfg.autoRegisterTriggers) return;
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

    /**
     * Registers a JsMacros ScriptTrigger for the given script entry using reflection,
     * so that we do not need a compile-time dependency on ScriptTrigger internals.
     *
     * Registration is idempotent: if a trigger for the same file already exists, the
     * method returns without adding a duplicate.
     *
     * Service-type triggers are not yet implemented and are silently skipped.
     */
    public void applyRegister(ScriptEntry entry, File scriptFile) {
        ScriptRegister reg = entry.register;
        if (reg == null) return;

        // Service type is deferred.
        if ("service".equalsIgnoreCase(reg.type)) return;

        try {
            Class<?> triggerClass = Class.forName("xyz.wagyourtail.jsmacros.core.config.ScriptTrigger");
            Class<?> triggerTypeClass = Class.forName("xyz.wagyourtail.jsmacros.core.config.ScriptTrigger$TriggerType");

            // Resolve the TriggerType enum constant.
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

            // Resolve eventOrKey string.
            String eventOrKey;
            if ("event".equalsIgnoreCase(reg.type)) {
                eventOrKey = reg.event != null ? reg.event : "";
            } else {
                eventOrKey = reg.key != null ? reg.key : "";
            }

            // JsMacros resolves trigger paths relative to macroFolder.
            // Scripts with triggers are installed under .jsMacros/scripts/macros/<repoName>/,
            // which is reachable from macroFolder via the unified junction as unified/<repoName>/<file>.
            String repoSubDir = sanitizeFilename(entry.repoName != null ? entry.repoName : "unknown");
            String filePath = "unified/" + repoSubDir + "/" + scriptFile.getName();

            // Idempotency check: if a trigger for this file already exists, skip.
            Object profile = getProfile();
            Object registry = profile.getClass().getMethod("getRegistry").invoke(profile);
            Method getTriggersMethod = registry.getClass().getMethod("getScriptTriggers");
            List<?> triggers = (List<?>) getTriggersMethod.invoke(registry);

            String finalFilePath = filePath;
            for (Object t : triggers) {
                Object sf = t.getClass().getField("scriptFile").get(t);
                if (sf != null && sf.toString().equals(finalFilePath)) {
                    // Trigger already registered for this file.
                    return;
                }
            }

            // Construct ScriptTrigger(TriggerType, String, String, boolean, boolean).
            Constructor<?> ctor = triggerClass.getConstructor(
                    triggerTypeClass,
                    String.class,
                    String.class,
                    boolean.class,
                    boolean.class
            );
            Object trigger = ctor.newInstance(triggerTypeValue, eventOrKey, filePath, true, false);

            // Register the trigger.
            Method addMethod = registry.getClass().getMethod("addScriptTrigger", triggerClass);
            addMethod.invoke(registry, trigger);

            System.out.println("[SynapMc] Registered trigger for " + entry.name + " (" + reg.type + ")");

        } catch (ClassNotFoundException e) {
            System.err.println("[SynapMc] applyRegister: ScriptTrigger class not found (JsMacros API mismatch): " + e.getMessage());
        } catch (Exception e) {
            System.err.println("[SynapMc] applyRegister: failed for " + entry.name + ": " + e);
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /** Fetches a manifest JSON from url, stamps it with the URL, caches it, and adds it to manifests. */
    private void fetchAndCacheManifest(String url) throws Exception {
        // Append a timestamp to bust any HTTP-level cache (e.g. GitHub raw serves max-age=300).
        String fetchUrl = url + (url.contains("?") ? "&" : "?") + "_t=" + System.currentTimeMillis();
        String json = new HTTPRequest(fetchUrl).get().text();
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

        // Stamp each entry with the repo name (used later for path construction).
        if (manifest.scripts != null) {
            for (ScriptEntry entry : manifest.scripts) {
                entry.repoName = manifest.name;
            }
        }

        // Cache to disk.
        File cacheFile = new File(roamingDir, "repos/" + sanitizeFilename(manifest.name) + ".json");
        Files.write(cacheFile.toPath(),
                GSON.toJson(manifest).getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);

        manifests.put(manifest.name.toLowerCase(), manifest);
        lastFetchedName = manifest.name.toLowerCase();
    }

    /**
     * Scans all loaded manifests for a script with the given name (case-insensitive).
     * Returns null if not found.
     */
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

    /**
     * Resolves the destination directory relative to roamingDir.
     * Priority: manifest entry's destination field -> caller-supplied location.
     * "load" -> scripts/load/<repoName>/
     * anything else (or null) -> scripts/library/<repoName>/
     */
    private String resolveDestination(ScriptEntry entry, String location) {
        // Prefer what the manifest entry declares.
        if (entry.destination != null && !entry.destination.isEmpty()) {
            // Append repoName sub-directory so scripts from different repos don't collide.
            String dest = entry.destination.endsWith("/")
                    ? entry.destination : entry.destination + "/";
            return dest + sanitizeFilename(entry.repoName != null ? entry.repoName : "unknown") + "/";
        }

        // Fall back to caller-supplied location.
        String repoSub = sanitizeFilename(entry.repoName != null ? entry.repoName : "unknown");
        if ("load".equalsIgnoreCase(location)) {
            return "scripts/load/" + repoSub + "/";
        }
        if (location == null || "library".equalsIgnoreCase(location)) {
            // Scripts with a trigger must live under macros/ so the unified junction path works.
            if (entry.register != null) {
                return "scripts/macros/" + repoSub + "/";
            }
            return "scripts/library/" + repoSub + "/";
        }

        // Custom path — treated as relative to .jsMacros/scripts/
        return "scripts/" + (location.endsWith("/") ? location : location + "/");
    }

    /** Removes JsMacros triggers whose scriptFile field matches the given filename. */
    @SuppressWarnings("unchecked")
    private void removeTrigger(String filename) {
        try {
            Object profile = getProfile();
            Object registry = profile.getClass().getMethod("getRegistry").invoke(profile);
            Method getTriggersMethod = registry.getClass().getMethod("getScriptTriggers");
            List<?> triggers = (List<?>) getTriggersMethod.invoke(registry);

            // Collect triggers to remove (avoid ConcurrentModification).
            List<Object> toRemove = new ArrayList<Object>();
            for (Object t : triggers) {
                Object sf = t.getClass().getField("scriptFile").get(t);
                if (sf != null && sf.toString().equals(filename)) {
                    toRemove.add(t);
                }
            }

            Class<?> triggerClass = Class.forName("xyz.wagyourtail.jsmacros.core.config.ScriptTrigger");
            Method removeMethod = registry.getClass().getMethod("removeScriptTrigger", triggerClass);
            for (Object t : toRemove) {
                removeMethod.invoke(registry, t);
            }
        } catch (Exception e) {
            // Non-fatal; triggers may already be gone.
            System.err.println("[SynapMc] removeTrigger: " + e.getMessage());
        }
    }

    /** Obtains the current JsMacros profile via reflection to avoid tight coupling. */
    private Object getProfile() throws Exception {
        Core<?, ?> core = Core.getInstance();
        try {
            // Try getProfile() method first.
            Method m = core.getClass().getMethod("getProfile");
            return m.invoke(core);
        } catch (NoSuchMethodException e) {
            // Fall back to public field.
            return core.getClass().getField("profile").get(core);
        }
    }

    /** Loads .jsMacros/repos/installed.json into the installed map. */
    private void loadInstalledIndex() {
        File indexFile = new File(roamingDir, "repos/installed.json");
        if (!indexFile.exists()) return;
        try {
            String json = new String(Files.readAllBytes(indexFile.toPath()), StandardCharsets.UTF_8);
            Type type = new TypeToken<Map<String, InstalledInfo>>() {}.getType();
            Map<String, InstalledInfo> loaded = GSON.fromJson(json, type);
            if (loaded != null) {
                installed.putAll(loaded);
            }
        } catch (Exception e) {
            System.err.println("[SynapMc] Could not load installed.json: " + e.getMessage());
        }
    }

    /** Persists the installed map to .jsMacros/repos/installed.json. */
    private void saveInstalledIndex() {
        File indexFile = new File(roamingDir, "repos/installed.json");
        try {
            Files.write(indexFile.toPath(),
                    GSON.toJson(installed).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            System.err.println("[SynapMc] Could not save installed.json: " + e.getMessage());
        }
    }

    /**
     * Compares two simple version strings (e.g. "1.2.3").
     * Returns positive if a > b, negative if a < b, 0 if equal.
     * Falls back to string comparison for non-numeric segments.
     */
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

    /** Strips characters that are not safe for use in file or directory names. */
    private String sanitizeFilename(String name) {
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }

    // -------------------------------------------------------------------------
    // Package-level accessors for DashboardServer
    // -------------------------------------------------------------------------

    Map<String, RepoManifest> getManifests() {
        return manifests;
    }

    Map<String, InstalledInfo> getInstalled() {
        return installed;
    }

    public Map<String, String> getRepos() {
        return Core.getInstance().config.getOptions(SynapMcConfig.class).repos;
    }
}
