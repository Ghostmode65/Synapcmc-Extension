package xyz.wagyourtail.jsmacros.synapmc.repo;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import xyz.wagyourtail.jsmacros.synapmc.SynapMcExtension;
import xyz.wagyourtail.jsmacros.synapmc.model.RepoManifest;
import xyz.wagyourtail.jsmacros.synapmc.model.ScriptEntry;

import xyz.wagyourtail.jsmacros.core.library.impl.classes.HTTPRequest;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public class DashboardServer {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private HttpServer httpServer;
    private int port;

    public void openDashboard() {
        if (httpServer == null) {
            startServer();
        }
        openBrowser("http://127.0.0.1:" + port + "/");
    }

    // -------------------------------------------------------------------------
    // Server lifecycle
    // -------------------------------------------------------------------------

    private void startServer() {
        try {
            InetSocketAddress addr = new InetSocketAddress("127.0.0.1", 0);
            httpServer = HttpServer.create(addr, 0);
            port = httpServer.getAddress().getPort();

            httpServer.createContext("/", new RootHandler());
            httpServer.createContext("/api/scripts", new ScriptsApiHandler());
            httpServer.createContext("/api/scripts/install", new ScriptsInstallHandler());
            httpServer.createContext("/api/scripts/remove", new ScriptsRemoveHandler());
            httpServer.createContext("/api/repos", new ReposApiHandler());
            httpServer.createContext("/api/repos/add", new ReposAddHandler());
            httpServer.createContext("/api/repos/remove", new ReposRemoveHandler());
            httpServer.createContext("/api/repos/refresh", new ReposRefreshHandler());
            httpServer.createContext("/api/status", new StatusApiHandler());
            httpServer.createContext("/api/manifest/fetch", new ManifestFetchHandler());
            httpServer.createContext("/api/scripts/reveal", new RevealHandler());
            httpServer.createContext("/api/exec", new ExecApiHandler());

            httpServer.setExecutor(Executors.newCachedThreadPool());
            httpServer.start();

            System.out.println("[SynapMc] Dashboard available at http://127.0.0.1:" + port + "/");
        } catch (IOException e) {
            System.err.println("[SynapMc] Failed to start dashboard server: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Browser launcher
    // -------------------------------------------------------------------------

    private void openBrowser(String url) {
        boolean opened = false;
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
                opened = true;
            }
        } catch (Exception ignored) {}

        if (!opened) {
            try {
                String os = System.getProperty("os.name", "").toLowerCase();
                ProcessBuilder pb;
                if (os.contains("win")) {
                    pb = new ProcessBuilder("cmd", "/c", "start", "", url);
                } else if (os.contains("mac")) {
                    pb = new ProcessBuilder("open", url);
                } else {
                    pb = new ProcessBuilder("xdg-open", url);
                }
                pb.start();
            } catch (Exception e) {
                System.err.println("[SynapMc] Could not open browser: " + e.getMessage());
                System.out.println("[SynapMc] Open manually: " + url);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Handlers
    // -------------------------------------------------------------------------

    private class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
                return;
            }
            sendResponse(exchange, 200, "text/html; charset=utf-8", buildDashboardHtml());
        }
    }

    private class ScriptsApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
                return;
            }
            List<Map<String, Object>> scripts = new ArrayList<Map<String, Object>>();
            RepoManager rm = RepoManager.getInstance();
            for (Map.Entry<String, RepoManifest> mEntry : rm.getManifests().entrySet()) {
                RepoManifest manifest = mEntry.getValue();
                if (manifest.scripts == null) continue;
                for (ScriptEntry entry : manifest.scripts) {
                    Map<String, Object> row = new LinkedHashMap<String, Object>();
                    row.put("name", entry.name);
                    row.put("author", entry.author);
                    row.put("description", entry.description);
                    row.put("version", entry.version);
                    row.put("imageurl", entry.imageurl);
                    row.put("isApi", entry.isApi);
                    row.put("isExecuteReady", entry.isExecuteReady);
                    row.put("repoName", manifest.name);
                    row.put("repoSource", manifest.source);
                    RepoManager.InstalledInfo info = rm.getInstalled().get(entry.name.toLowerCase());
                    row.put("installed", info != null);
                    row.put("installedVersion", info != null ? info.version : null);
                    row.put("path", info != null ? info.path : null);
                    row.put("hasRegister", entry.register != null);
                    // Derive language from the file extension in the source URL.
                    String lang = null;
                    if (entry.source != null) {
                        int dot = entry.source.lastIndexOf('.');
                        int slash = entry.source.lastIndexOf('/');
                        if (dot > slash && dot >= 0) lang = entry.source.substring(dot + 1).toLowerCase();
                    }
                    row.put("lang", lang);
                    // Destination: use manifest field if set, otherwise apply the same default
                    // logic as resolveDestination (scripts with triggers go to scripts/macros/).
                    String dest = (entry.destination != null && !entry.destination.isEmpty())
                            ? entry.destination
                            : (entry.register != null ? "scripts/macros/" : "scripts/library/");
                    String destNorm = dest.replace("\\", "/").toLowerCase();
                    row.put("goesToMacros", destNorm.startsWith("scripts/macros"));
                    scripts.add(row);
                }
            }
            sendResponse(exchange, 200, "application/json; charset=utf-8", GSON.toJson(scripts));
        }
    }

    private class ReposApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
                return;
            }
            Map<String, String> repos = RepoManager.getInstance().getRepos();
            sendResponse(exchange, 200, "application/json; charset=utf-8", GSON.toJson(repos));
        }
    }

    private class ReposAddHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
                return;
            }
            String body = new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8);
            String url;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> req = GSON.fromJson(body, Map.class);
                url = req != null && req.get("url") != null ? req.get("url").toString().trim() : null;
            } catch (Exception e) {
                sendResponse(exchange, 400, "application/json", "{\"error\":\"Invalid JSON\"}");
                return;
            }
            if (url == null || url.isEmpty()) {
                sendResponse(exchange, 400, "application/json", "{\"error\":\"Missing url\"}");
                return;
            }
            try {
                RepoManager.getInstance().addRepo(url);
                sendResponse(exchange, 200, "application/json", "{\"ok\":true}");
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                sendResponse(exchange, 500, "application/json",
                        "{\"error\":\"" + escapeJson(msg) + "\"}");
            }
        }
    }

    private class ReposRefreshHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
                return;
            }
            RepoManager.getInstance().refreshRepos();
            sendResponse(exchange, 200, "application/json", "{\"ok\":true}");
        }
    }

    private class ReposRemoveHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
                return;
            }
            String body = new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8);
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> req = GSON.fromJson(body, Map.class);
                String name = req != null && req.get("name") != null ? req.get("name").toString() : null;
                if (name == null || name.isEmpty()) {
                    sendResponse(exchange, 400, "application/json", "{\"error\":\"Missing name\"}");
                    return;
                }
                RepoManager.getInstance().removeRepo(name);
                sendResponse(exchange, 200, "application/json", "{\"ok\":true}");
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                sendResponse(exchange, 500, "application/json",
                        "{\"error\":\"" + escapeJson(msg) + "\"}");
            }
        }
    }

    private class StatusApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
                return;
            }
            boolean connected = SynapMcExtension.server != null && SynapMcExtension.server.hasClients();
            sendResponse(exchange, 200, "application/json; charset=utf-8",
                    "{\"webhookConnected\":" + connected + "}");
        }
    }

    private class ScriptsInstallHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
                return;
            }
            String body = new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8);
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> req = GSON.fromJson(body, Map.class);
                String name = req != null && req.get("name") != null ? req.get("name").toString() : null;
                String location = req != null && req.get("location") != null ? req.get("location").toString() : "library";
                if (name == null || name.isEmpty()) {
                    sendResponse(exchange, 400, "application/json", "{\"error\":\"Missing name\"}");
                    return;
                }
                String path = RepoManager.getInstance().installScript(name, location);
                if (path != null) {
                    sendResponse(exchange, 200, "application/json", "{\"ok\":true,\"path\":\"" + escapeJson(path) + "\"}");
                } else {
                    sendResponse(exchange, 500, "application/json", "{\"error\":\"Install failed\"}");
                }
            } catch (Exception e) {
                sendResponse(exchange, 500, "application/json", "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
        }
    }

    private class ScriptsRemoveHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
                return;
            }
            String body = new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8);
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> req = GSON.fromJson(body, Map.class);
                String name = req != null && req.get("name") != null ? req.get("name").toString() : null;
                if (name == null || name.isEmpty()) {
                    sendResponse(exchange, 400, "application/json", "{\"error\":\"Missing name\"}");
                    return;
                }
                boolean ok = RepoManager.getInstance().removeScript(name);
                sendResponse(exchange, 200, "application/json", ok ? "{\"ok\":true}" : "{\"error\":\"Script not installed\"}");
            } catch (Exception e) {
                sendResponse(exchange, 500, "application/json", "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
        }
    }

    private class ManifestFetchHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
                return;
            }
            String query = exchange.getRequestURI().getRawQuery();
            String url = null;
            if (query != null) {
                for (String param : query.split("&")) {
                    if (param.startsWith("url=")) {
                        url = URLDecoder.decode(param.substring(4), StandardCharsets.UTF_8.name());
                        break;
                    }
                }
            }
            if (url == null || url.isEmpty()) {
                sendResponse(exchange, 400, "application/json", "{\"error\":\"Missing url parameter\"}");
                return;
            }
            try {
                String json = new HTTPRequest(url).get().text();
                if (json == null || json.isEmpty()) {
                    sendResponse(exchange, 502, "application/json", "{\"error\":\"Empty response from URL\"}");
                    return;
                }
                sendResponse(exchange, 200, "application/json; charset=utf-8", json);
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                sendResponse(exchange, 502, "application/json", "{\"error\":\"" + escapeJson(msg) + "\"}");
            }
        }
    }

    private class RevealHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
                return;
            }
            String body = new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8);
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> req = GSON.fromJson(body, Map.class);
                String name = req != null && req.get("name") != null ? req.get("name").toString() : null;
                if (name == null || name.isEmpty()) {
                    sendResponse(exchange, 400, "application/json", "{\"error\":\"Missing name\"}");
                    return;
                }
                RepoManager.InstalledInfo info = RepoManager.getInstance().getInstalled().get(name.toLowerCase());
                if (info == null) {
                    sendResponse(exchange, 404, "application/json", "{\"error\":\"Script not installed\"}");
                    return;
                }
                File folder = new File(info.path).getParentFile();
                if (folder == null || !folder.exists()) {
                    sendResponse(exchange, 404, "application/json", "{\"error\":\"Folder not found\"}");
                    return;
                }
                try {
                    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                        Desktop.getDesktop().open(folder);
                    } else {
                        String os = System.getProperty("os.name", "").toLowerCase();
                        ProcessBuilder pb;
                        if (os.contains("win")) {
                            pb = new ProcessBuilder("explorer", folder.getAbsolutePath());
                        } else if (os.contains("mac")) {
                            pb = new ProcessBuilder("open", folder.getAbsolutePath());
                        } else {
                            pb = new ProcessBuilder("xdg-open", folder.getAbsolutePath());
                        }
                        pb.start();
                    }
                } catch (Exception e) {
                    sendResponse(exchange, 500, "application/json", "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    return;
                }
                sendResponse(exchange, 200, "application/json", "{\"ok\":true}");
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                sendResponse(exchange, 500, "application/json", "{\"error\":\"" + escapeJson(msg) + "\"}");
            }
        }
    }

    private class ExecApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
                return;
            }
            String body = new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8);
            String scriptName;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> req = GSON.fromJson(body, Map.class);
                scriptName = req != null && req.get("name") != null ? req.get("name").toString() : null;
            } catch (Exception e) {
                sendResponse(exchange, 400, "application/json", "{\"error\":\"Invalid JSON body\"}");
                return;
            }
            if (scriptName == null || scriptName.isEmpty()) {
                sendResponse(exchange, 400, "application/json", "{\"error\":\"Missing name\"}");
                return;
            }
            if (SynapMcExtension.server == null || !SynapMcExtension.server.hasClients()) {
                sendResponse(exchange, 503, "application/json", "{\"error\":\"Webhook not connected\"}");
                return;
            }
            String content = RepoManager.getInstance().getString(scriptName);
            if (content == null) {
                sendResponse(exchange, 404, "application/json",
                        "{\"error\":\"Script not found: " + escapeJson(scriptName) + "\"}");
                return;
            }
            String execMsg = "{\"type\":\"exec\",\"lang\":\"lua\",\"script\":\"" + escapeJson(content) + "\"}";
            SynapMcExtension.server.broadcast(execMsg);
            sendResponse(exchange, 200, "application/json", "{\"ok\":true}");
        }
    }

    // -------------------------------------------------------------------------
    // HTML
    // -------------------------------------------------------------------------

    private String buildDashboardHtml() {
        boolean webhookOn = SynapMcExtension.server != null && SynapMcExtension.server.hasClients();
        StringBuilder s = new StringBuilder();
        s.append("<!DOCTYPE html><html lang=\"en\"><head>");
        s.append("<meta charset=\"UTF-8\">");
        s.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        s.append("<title>SynapMc</title>");
        s.append("<style>");
        s.append("@import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap');");
        s.append("*{box-sizing:border-box;margin:0;padding:0}");
        s.append("body{font-family:'Inter',system-ui,sans-serif;background:#1a1816;color:#f5f0eb;min-height:100vh}");
        s.append("header{background:#211e1b;padding:14px 24px;display:flex;align-items:center;gap:16px;border-bottom:1px solid #3a3530}");
        s.append("header h1{color:#f5f0eb;font-size:1.25rem;font-weight:600;flex:1;letter-spacing:-.01em}");
        s.append("header h1 span{color:#cc785c}");
        s.append(".webhook-badge{padding:4px 12px;border-radius:20px;font-size:.75rem;font-weight:600;letter-spacing:.02em}");
        s.append(".live{background:rgba(52,211,153,.15);color:#34d399;border:1px solid rgba(52,211,153,.3)}");
        s.append(".offline{background:rgba(120,113,108,.15);color:#78716c;border:1px solid rgba(120,113,108,.3)}");
        s.append("nav{background:#211e1b;display:flex;border-bottom:1px solid #3a3530;padding:0 8px}");
        s.append("nav button{background:none;border:none;color:#78716c;padding:12px 20px;cursor:pointer;font-size:.875rem;font-weight:500;border-bottom:2px solid transparent;transition:color .15s}");
        s.append("nav button.active{color:#cc785c;border-bottom-color:#cc785c}");
        s.append("nav button:hover:not(.active){color:#d6cfc8}");
        s.append(".tab{display:none;padding:24px}.tab.active{display:block}");
        s.append(".grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(300px,1fr));gap:14px}");
        s.append(".card{background:#211e1b;border:1px solid #3a3530;border-radius:10px;padding:18px;display:flex;flex-direction:column;gap:10px;transition:border-color .15s}");
        s.append(".card:hover{border-color:#5a524a}");
        s.append(".card-img{width:100%;height:120px;object-fit:cover;border-radius:6px;background:#2c2825}");
        s.append(".card-title{font-size:1rem;font-weight:600;color:#f5f0eb}");
        s.append(".card-meta{font-size:.75rem;color:#78716c}");
        s.append(".card-desc{font-size:.85rem;color:#a8a29e;line-height:1.55}");
        s.append(".badges{display:flex;flex-wrap:wrap;gap:6px}");
        s.append(".badge{padding:2px 8px;border-radius:8px;font-size:.7rem;font-weight:600}");
        s.append(".b-api{background:rgba(204,120,92,.2);color:#cc785c;border:1px solid rgba(204,120,92,.35)}");
        s.append(".b-trigger{background:rgba(251,191,36,.12);color:#fbbf24;border:1px solid rgba(251,191,36,.3);font-size:.7rem;padding:2px 6px;border-radius:8px;font-weight:600;cursor:default}");
        s.append(".b-lua{background:rgba(100,74,168,.2);color:#a78bfa;border:1px solid rgba(167,139,250,.35)}");
        s.append(".b-js{background:rgba(234,179,8,.15);color:#eab308;border:1px solid rgba(234,179,8,.35)}");
        s.append(".b-py{background:rgba(59,130,246,.15);color:#60a5fa;border:1px solid rgba(96,165,250,.35)}");
        s.append(".b-lang{background:rgba(148,163,184,.1);color:#94a3b8;border:1px solid rgba(148,163,184,.25)}");
        s.append(".b-inst{background:rgba(52,211,153,.15);color:#34d399;border:1px solid rgba(52,211,153,.3)}");
        s.append(".b-ver{background:#2c2825;color:#a8a29e;border:1px solid #3a3530}");
        s.append(".btns{display:flex;flex-wrap:wrap;gap:8px;margin-top:auto}");
        s.append("button,a.btn{padding:6px 14px;border-radius:7px;border:none;cursor:pointer;font-size:.8rem;font-weight:600;text-decoration:none;display:inline-block;transition:opacity .15s,background .15s}");
        s.append("button:hover:not(:disabled),a.btn:hover{opacity:.88}");
        s.append(".btn-exec{background:#cc785c;color:#fff}.btn-exec:disabled{background:#3a3530;color:#5a524a;cursor:not-allowed;opacity:1}");
        s.append(".btn-src{background:transparent;color:#cc785c;border:1px solid #cc785c}");
        s.append(".btn-install{background:#1a6b4a;color:#a7f3d0;border:1px solid rgba(52,211,153,.3)}");
        s.append(".btn-remove{background:#6b2020;color:#fca5a5;border:1px solid rgba(248,113,113,.3)}");
        s.append(".btn-primary{background:#cc785c;color:#fff}.btn-secondary{background:#2c2825;color:#d6cfc8;border:1px solid #3a3530}");
        s.append(".snippet{background:#141210;border:1px solid #3a3530;border-radius:6px;padding:10px;font-family:'Fira Code',monospace;font-size:.78rem;color:#fb923c;word-break:break-all}");
        // Repos tab
        s.append(".repo-add{background:#211e1b;border:1px solid #3a3530;border-radius:10px;padding:18px;margin-bottom:20px}");
        s.append(".repo-add h3{color:#f5f0eb;font-weight:600;margin-bottom:12px;font-size:.95rem}");
        s.append(".input-row{display:flex;gap:8px}");
        s.append("input[type=text],input[type=url]{background:#141210;border:1px solid #3a3530;color:#f5f0eb;padding:8px 12px;border-radius:7px;font-size:.875rem;flex:1;transition:border-color .15s}");
        s.append("input[type=text]:focus,input[type=url]:focus{outline:none;border-color:#cc785c;box-shadow:0 0 0 3px rgba(204,120,92,.15)}");
        s.append(".repo-list{display:flex;flex-direction:column;gap:8px}");
        s.append(".repo-row{background:#211e1b;border:1px solid #3a3530;border-radius:8px;padding:14px;display:flex;align-items:center;gap:12px}");
        s.append(".repo-name{font-weight:600;color:#f5f0eb;flex:0 0 160px}");
        s.append(".repo-url{font-family:'Fira Code',monospace;font-size:.78rem;color:#78716c;flex:1;word-break:break-all}");
        s.append(".msg{padding:10px 14px;border-radius:7px;margin-bottom:14px;font-size:.85rem;font-weight:500}");
        s.append(".msg-ok{background:rgba(52,211,153,.15);color:#34d399;border:1px solid rgba(52,211,153,.3)}");
        s.append(".msg-err{background:rgba(248,113,113,.12);color:#f87171;border:1px solid rgba(248,113,113,.3)}");
        // Manifest builder
        s.append(".mf-section{background:#211e1b;border:1px solid #3a3530;border-radius:10px;padding:18px;margin-bottom:14px}");
        s.append(".mf-section h3{color:#f5f0eb;font-weight:600;font-size:.95rem;margin-bottom:14px}");
        s.append(".field-row{display:flex;flex-direction:column;gap:4px;margin-bottom:12px}");
        s.append(".field-row label{font-size:.75rem;color:#78716c;font-weight:500;letter-spacing:.01em}");
        s.append(".check-row{display:flex;align-items:center;gap:8px;margin-bottom:8px;font-size:.85rem;color:#a8a29e}");
        s.append(".script-entry{background:#141210;border:1px solid #3a3530;border-radius:8px;padding:14px;margin-bottom:12px;position:relative}");
        s.append(".script-entry .remove-entry{position:absolute;top:10px;right:10px;background:rgba(248,113,113,.15);color:#f87171;border:1px solid rgba(248,113,113,.3);border-radius:5px;padding:2px 8px;cursor:pointer;font-size:.72rem;font-weight:600}");
        s.append(".preview{background:#141210;border:1px solid #3a3530;border-radius:8px;padding:16px;font-family:'Fira Code',monospace;font-size:.8rem;color:#fb923c;white-space:pre-wrap;word-break:break-all;max-height:400px;overflow-y:auto}");
        s.append(".two-col{display:grid;grid-template-columns:1fr 1fr;gap:16px}");
        s.append("select{background:#141210;border:1px solid #3a3530;color:#f5f0eb;padding:8px 10px;border-radius:7px;font-size:.85rem;width:100%}");
        s.append("select:focus{outline:none;border-color:#cc785c}");
        s.append("#toast{position:fixed;bottom:24px;right:24px;padding:12px 20px;border-radius:9px;font-size:.85rem;font-weight:500;display:none;z-index:9999;box-shadow:0 4px 16px rgba(0,0,0,.4)}");
        s.append("h2{font-size:.95rem;font-weight:600;color:#a8a29e;text-transform:uppercase;letter-spacing:.05em;margin-bottom:16px}");
        s.append(".browse-controls{display:flex;gap:10px;align-items:center;margin-bottom:16px;flex-wrap:wrap}");
        s.append(".search-input{background:#141210;border:1px solid #3a3530;color:#f5f0eb;padding:8px 12px;border-radius:7px;font-size:.875rem;flex:1;min-width:180px;transition:border-color .15s}");
        s.append(".search-input:focus{outline:none;border-color:#cc785c;box-shadow:0 0 0 3px rgba(204,120,92,.15)}");
        s.append(".search-input::placeholder{color:#5a524a}");
        s.append(".filter-btns{display:flex;gap:6px;flex-wrap:wrap}");
        s.append(".filter-btn{padding:5px 12px;border-radius:20px;border:1px solid #3a3530;background:#2c2825;color:#78716c;cursor:pointer;font-size:.78rem;font-weight:600;transition:all .15s}");
        s.append(".filter-btn.active{background:rgba(204,120,92,.2);color:#cc785c;border-color:rgba(204,120,92,.4)}");
        s.append(".filter-btn:hover:not(.active){color:#d6cfc8;border-color:#5a524a}");
        s.append("::-webkit-scrollbar{width:6px;height:6px}::-webkit-scrollbar-track{background:#1a1816}::-webkit-scrollbar-thumb{background:#3a3530;border-radius:3px}");
        s.append("</style></head><body>");

        // Header
        s.append("<header><h1><span>Synap</span>Mc</h1>");
        s.append("<span class=\"webhook-badge ").append(webhookOn ? "live" : "offline").append("\">");
        s.append(webhookOn ? "Webhook Live" : "Webhook Offline").append("</span></header>");

        // Nav
        s.append("<nav>");
        s.append("<button class=\"active\" onclick=\"showTab('browse',this)\">Browse</button>");
        s.append("<button onclick=\"showTab('repos',this)\">Repos</button>");
        s.append("<button onclick=\"showTab('manifest',this)\">Create Manifest</button>");
        s.append("</nav>");

        // ---- Browse tab ----
        s.append("<div class=\"tab active\" id=\"tab-browse\">");
        s.append("<div class=\"browse-controls\">");
        s.append("<input class=\"search-input\" id=\"search\" type=\"text\" placeholder=\"Search scripts...\" oninput=\"filterScripts()\">");
        s.append("<div class=\"filter-btns\">");
        s.append("<button class=\"filter-btn active\" onclick=\"setFilter('all',this)\">All</button>");
        s.append("<button class=\"filter-btn\" onclick=\"setFilter('installed',this)\">Installed</button>");
        s.append("<button class=\"filter-btn\" onclick=\"setFilter('notinstalled',this)\">Not Installed</button>");
        s.append("<button class=\"filter-btn\" onclick=\"setFilter('api',this)\">API</button>");
        s.append("</div></div>");
        s.append("<div class=\"grid\" id=\"grid\"><p style=\"color:#78716c\">Loading...</p></div>");
        s.append("</div>");

        // ---- Repos tab ----
        s.append("<div class=\"tab\" id=\"tab-repos\">");
        s.append("<div style=\"display:flex;justify-content:flex-end;margin-bottom:12px\">");
        s.append("<button class=\"btn-secondary\" onclick=\"refreshRepos()\">Refresh All</button>");
        s.append("</div>");
        s.append("<div class=\"repo-add\"><h3>Add Repo</h3>");
        s.append("<div class=\"input-row\">");
        s.append("<input type=\"url\" id=\"add-url\" placeholder=\"https://raw.githubusercontent.com/.../manifest.json\">");
        s.append("<button class=\"btn-primary\" onclick=\"addRepo()\">Add</button>");
        s.append("</div><div id=\"add-msg\"></div></div>");
        s.append("<div class=\"repo-list\" id=\"repos-list\"><p style=\"color:#78716c\">Loading...</p></div>");
        s.append("</div>");

        // ---- Create Manifest tab ----
        s.append("<div class=\"tab\" id=\"tab-manifest\">");
        // Import from URL
        s.append("<div class=\"mf-section\"><h3>Import Existing Manifest</h3>");
        s.append("<div class=\"input-row\">");
        s.append("<input type=\"url\" id=\"mf-import-url\" placeholder=\"https://raw.githubusercontent.com/.../manifest.json\">");
        s.append("<button class=\"btn-secondary\" onclick=\"importManifest()\">Import</button>");
        s.append("</div></div>");
        s.append("<div class=\"two-col\">");
        s.append("<div>");
        // Repo info
        s.append("<div class=\"mf-section\"><h3>Repo Info</h3>");
        s.append("<div class=\"field-row\"><label>Name</label><input type=\"text\" id=\"mf-name\" oninput=\"updatePreview()\"></div>");
        s.append("<div class=\"field-row\"><label>Source URL</label><input type=\"url\" id=\"mf-source\" oninput=\"updatePreview()\"></div>");
        s.append("<div class=\"field-row\"><label>Author</label><input type=\"text\" id=\"mf-author\" oninput=\"updatePreview()\"></div>");
        s.append("</div>");
        // Scripts
        s.append("<div class=\"mf-section\"><h3>Scripts</h3>");
        s.append("<div id=\"mf-scripts\"></div>");
        s.append("<button class=\"btn-secondary\" onclick=\"addScriptEntry()\">+ Add Script</button>");
        s.append("</div>");
        s.append("</div>");
        // Preview
        s.append("<div>");
        s.append("<div class=\"mf-section\"><h3>Preview</h3>");
        s.append("<div class=\"preview\" id=\"mf-preview\">{}</div>");
        s.append("<div style=\"display:flex;gap:8px;margin-top:12px\">");
        s.append("<button class=\"btn-primary\" onclick=\"copyManifest()\">Copy JSON</button>");
        s.append("<button class=\"btn-secondary\" onclick=\"downloadManifest()\">Download manifest.json</button>");
        s.append("</div></div>");
        s.append("</div></div></div>"); // end two-col, manifest tab

        s.append("<div id=\"toast\"></div>");

        // ---- JavaScript ----
        s.append("<script>");
        s.append("const webhookConnected=").append(webhookOn).append(";");

        // Tab switching
        s.append("function showTab(id,btn){");
        s.append("document.querySelectorAll('.tab').forEach(t=>t.classList.remove('active'));");
        s.append("document.querySelectorAll('nav button').forEach(b=>b.classList.remove('active'));");
        s.append("document.getElementById('tab-'+id).classList.add('active');");
        s.append("if(btn)btn.classList.add('active');");
        s.append("if(id==='repos')loadRepos();");
        s.append("}");

        // Toast
        s.append("function toast(msg,ok){");
        s.append("const el=document.getElementById('toast');");
        s.append("el.textContent=msg;");
        s.append("el.style.background=ok?'#1a3d2e':'#3d1a1a';");
        s.append("el.style.color=ok?'#34d399':'#f87171';");
        s.append("el.style.border=ok?'1px solid rgba(52,211,153,.3)':'1px solid rgba(248,113,113,.3)';");
        s.append("el.style.display='block';");
        s.append("setTimeout(()=>el.style.display='none',3000);}");

        // Escape HTML
        s.append("function esc(s){return String(s||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/\"/g,'&quot;');}");

        // Browse tab
        s.append("let allScripts=[];let activeFilter='all';");

        s.append("async function loadScripts(){");
        s.append("const res=await fetch('/api/scripts');");
        s.append("allScripts=await res.json();");
        s.append("filterScripts();}");

        s.append("function setFilter(f,btn){");
        s.append("activeFilter=f;");
        s.append("document.querySelectorAll('.filter-btn').forEach(b=>b.classList.remove('active'));");
        s.append("btn.classList.add('active');");
        s.append("filterScripts();}");

        s.append("function filterScripts(){");
        s.append("const q=document.getElementById('search').value.toLowerCase();");
        s.append("let list=allScripts;");
        s.append("if(activeFilter==='installed')list=list.filter(s=>s.installed);");
        s.append("else if(activeFilter==='notinstalled')list=list.filter(s=>!s.installed);");
        s.append("else if(activeFilter==='api')list=list.filter(s=>s.isApi);");
        s.append("if(q)list=list.filter(s=>(s.name||'').toLowerCase().includes(q)||(s.description||'').toLowerCase().includes(q)||(s.author||'').toLowerCase().includes(q)||(s.repoName||'').toLowerCase().includes(q));");
        s.append("renderScripts(list);}");

        s.append("function renderScripts(scripts){");
        s.append("const grid=document.getElementById('grid');");
        s.append("grid.innerHTML='';");
        s.append("if(!scripts.length){grid.innerHTML='<p style=\"color:#78716c\">'+(allScripts.length?'No scripts match.':'No scripts found. Add a repo first.')+'</p>';return;}");
        s.append("for(const sc of scripts){");
        s.append("const card=document.createElement('div');card.className='card';");
        s.append("let h='';");
        s.append("if(sc.imageurl)h+=`<img class=\"card-img\" src=\"${esc(sc.imageurl)}\" alt=\"\" onerror=\"this.style.display='none'\">`; ");
        s.append("h+=`<div class=\"card-title\">${esc(sc.name)}</div>`;");
        s.append("h+=`<div class=\"card-meta\">by ${esc(sc.author||'Unknown')} &bull; ${esc(sc.repoName||'')}</div>`;");
        s.append("h+='<div class=\"badges\">';");
        s.append("h+=`<span class=\"badge b-ver\">v${esc(sc.version||'?')}</span>`;");
        s.append("if(sc.lang){const lc=sc.lang.toLowerCase();const lcls=lc==='lua'?'b-lua':lc==='js'?'b-js':lc==='py'?'b-py':'b-lang';h+=`<span class=\"badge ${lcls}\">.${esc(sc.lang)}</span>`;}");
        s.append("if(sc.installed)h+='<span class=\"badge b-inst\">Installed</span>';");
        s.append("if(sc.isApi)h+='<span class=\"badge b-api\">API</span>';");
        s.append("if(sc.hasRegister&&!sc.goesToMacros)h+='<span class=\"b-trigger\">⚠ trigger</span>';");
        s.append("h+='</div>';");
        s.append("if(sc.description)h+=`<div class=\"card-desc\">${esc(sc.description)}</div>`;");
        s.append("if(sc.isApi&&sc.installed)h+=`<div class=\"snippet\">local s=synapmc:repo():getString(${esc(JSON.stringify(sc.name))})</div>`;");
        s.append("h+='<div class=\"btns\">';");
        s.append("if(sc.isExecuteReady){const dis=webhookConnected?'':' disabled title=\"Webhook offline\"';");
        s.append("h+=`<button class=\"btn-exec\"${dis} onclick=\"execScript(${esc(JSON.stringify(sc.name))})\">Execute</button>`;}");
        s.append("if(!sc.installed)h+=`<button class=\"btn-install\" onclick=\"installScript(${esc(JSON.stringify(sc.name))})\">Install</button>`;");
        s.append("else{h+=`<button class=\"btn-remove\" onclick=\"removeScript(${esc(JSON.stringify(sc.name))})\">Uninstall</button>`;");
        s.append("h+=`<button class=\"btn-secondary\" onclick=\"revealScript(${esc(JSON.stringify(sc.name))})\">Show in Folder</button>`;}");
        s.append("if(sc.repoSource)h+=`<a class=\"btn btn-src\" href=\"${esc(sc.repoSource)}\" target=\"_blank\">Source</a>`;");
        s.append("h+='</div>';");
        s.append("card.innerHTML=h;grid.appendChild(card);}");
        s.append("}");

        s.append("async function installScript(name){");
        s.append("const res=await fetch('/api/scripts/install',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({name,location:'library'})});");
        s.append("const d=await res.json();");
        s.append("toast(d.ok?'Installed: '+name:'Error: '+(d.error||'?'),d.ok);");
        s.append("if(d.ok)loadScripts();}");

        s.append("async function removeScript(name){");
        s.append("const res=await fetch('/api/scripts/remove',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({name})});");
        s.append("const d=await res.json();");
        s.append("toast(d.ok?'Removed: '+name:'Error: '+(d.error||'?'),d.ok);");
        s.append("if(d.ok)loadScripts();}");

        s.append("async function execScript(name){");
        s.append("const res=await fetch('/api/exec',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({name})});");
        s.append("const d=await res.json();toast(d.ok?'Executed: '+name:'Error: '+(d.error||'?'),d.ok);}");

        // Repos tab
        s.append("async function loadRepos(){");
        s.append("const res=await fetch('/api/repos');");
        s.append("const repos=await res.json();");
        s.append("const list=document.getElementById('repos-list');");
        s.append("const keys=Object.keys(repos);");
        s.append("if(!keys.length){list.innerHTML='<p style=\"color:#78716c\">No repos configured.</p>';return;}");
        s.append("list.innerHTML=keys.map(k=>`<div class=\"repo-row\"><span class=\"repo-name\">${esc(k)}</span><span class=\"repo-url\">${esc(repos[k])}</span><button class=\"btn-remove\" onclick=\"removeRepo(${esc(JSON.stringify(k))})\">Remove</button></div>`).join('');");
        s.append("}");

        s.append("async function removeRepo(name){");
        s.append("const res=await fetch('/api/repos/remove',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({name})});");
        s.append("const d=await res.json();");
        s.append("toast(d.ok?'Removed repo: '+name:'Error: '+(d.error||'?'),d.ok);");
        s.append("if(d.ok){loadRepos();loadScripts();}}");


        s.append("async function addRepo(){");
        s.append("const url=document.getElementById('add-url').value.trim();");
        s.append("const msg=document.getElementById('add-msg');");
        s.append("if(!url){msg.innerHTML='<div class=\"msg msg-err\">Enter a URL.</div>';return;}");
        s.append("msg.innerHTML='<div class=\"msg\" style=\"background:#2d3436\">Adding...</div>';");
        s.append("try{");
        s.append("const res=await fetch('/api/repos/add',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({url})});");
        s.append("const d=await res.json();");
        s.append("if(d.ok){msg.innerHTML='<div class=\"msg msg-ok\">Repo added.</div>';");
        s.append("document.getElementById('add-url').value='';loadRepos();loadScripts();}");
        s.append("else msg.innerHTML=`<div class=\"msg msg-err\">${esc(d.error||'Failed')}</div>`;");
        s.append("}catch(e){msg.innerHTML=`<div class=\"msg msg-err\">${esc(e.message)}</div>`;}}");

        s.append("async function refreshRepos(){");
        s.append("toast('Refreshing...',true);");
        s.append("try{");
        s.append("const res=await fetch('/api/repos/refresh',{method:'POST'});");
        s.append("const d=await res.json();");
        s.append("if(d.ok){toast('Repos refreshed.',true);loadRepos();loadScripts();}");
        s.append("else toast('Refresh failed: '+(d.error||'?'),false);");
        s.append("}catch(e){toast('Refresh failed: '+e.message,false);}}");

        // Manifest builder
        s.append("let scriptCount=0;");
        s.append("function addScriptEntry(){");
        s.append("const id='se'+scriptCount++;");
        s.append("const div=document.createElement('div');div.className='script-entry';div.id=id;");
        s.append("div.innerHTML=`");
        s.append("<button class=\"remove-entry\" onclick=\"document.getElementById('${id}').remove();updatePreview()\">Remove</button>");
        s.append("<div class=\"field-row\"><label>Name</label><input type=\"text\" class=\"se-name\" oninput=\"updatePreview()\"></div>");
        s.append("<div class=\"field-row\"><label>Author</label><input type=\"text\" class=\"se-author\" oninput=\"updatePreview()\"></div>");
        s.append("<div class=\"field-row\"><label>Source URL</label><input type=\"url\" class=\"se-source\" oninput=\"updatePreview()\"></div>");
        s.append("<div class=\"field-row\"><label>Destination (e.g. scripts/library/)</label><input type=\"text\" class=\"se-dest\" oninput=\"updatePreview()\"></div>");
        s.append("<div class=\"field-row\"><label>Version</label><input type=\"text\" class=\"se-ver\" value=\"1.0.0\" oninput=\"updatePreview()\"></div>");
        s.append("<div class=\"field-row\"><label>Description</label><input type=\"text\" class=\"se-desc\" oninput=\"updatePreview()\"></div>");
        s.append("<div class=\"field-row\"><label>Image URL (optional)</label><input type=\"url\" class=\"se-img\" oninput=\"updatePreview()\"></div>");
        s.append("<div class=\"check-row\"><input type=\"checkbox\" class=\"se-api\" onchange=\"updatePreview()\"><label>isApi</label></div>");
        s.append("<div class=\"check-row\"><input type=\"checkbox\" class=\"se-exec\" onchange=\"updatePreview()\"><label>isExecuteReady</label></div>");
        s.append("<div class=\"field-row\"><label>Register Type</label>");
        s.append("<select class=\"se-reg\" onchange=\"updateRegFields(this);updatePreview()\">");
        s.append("<option value=\"\">None</option><option value=\"keydown\">keydown</option>");
        s.append("<option value=\"keyup\">keyup</option><option value=\"event\">event</option></select></div>");
        s.append("<div class=\"se-reg-key\" style=\"display:none\"><div class=\"field-row\"><label>Key (e.g. key.keyboard.keypad.4)</label><input type=\"text\" class=\"se-key\" oninput=\"updatePreview()\"></div></div>");
        s.append("<div class=\"se-reg-event\" style=\"display:none\"><div class=\"field-row\"><label>Event (e.g. JoinedWorld)</label><input type=\"text\" class=\"se-event\" oninput=\"updatePreview()\"></div></div>");
        s.append("`;");
        s.append("document.getElementById('mf-scripts').appendChild(div);updatePreview();}");

        s.append("function updateRegFields(sel){");
        s.append("const entry=sel.closest('.script-entry');");
        s.append("entry.querySelector('.se-reg-key').style.display=");
        s.append("(sel.value==='keydown'||sel.value==='keyup')?'block':'none';");
        s.append("entry.querySelector('.se-reg-event').style.display=");
        s.append("sel.value==='event'?'block':'none';}");

        s.append("function buildManifest(){");
        s.append("const m={name:document.getElementById('mf-name').value,");
        s.append("source:document.getElementById('mf-source').value,");
        s.append("author:document.getElementById('mf-author').value,scripts:[]};");
        s.append("document.querySelectorAll('.script-entry').forEach(e=>{");
        s.append("const reg=e.querySelector('.se-reg').value;");
        s.append("let register=null;");
        s.append("if(reg==='keydown'||reg==='keyup')register={type:reg,key:e.querySelector('.se-key').value};");
        s.append("if(reg==='event')register={type:'event',event:e.querySelector('.se-event').value};");
        s.append("m.scripts.push({name:e.querySelector('.se-name').value,");
        s.append("author:e.querySelector('.se-author').value,");
        s.append("source:e.querySelector('.se-source').value,");
        s.append("destination:e.querySelector('.se-dest').value,");
        s.append("version:e.querySelector('.se-ver').value,");
        s.append("description:e.querySelector('.se-desc').value,");
        s.append("imageurl:e.querySelector('.se-img').value||null,");
        s.append("isApi:e.querySelector('.se-api').checked,");
        s.append("isExecuteReady:e.querySelector('.se-exec').checked,");
        s.append("server:null,register});});");
        s.append("return m;}");

        s.append("function updatePreview(){");
        s.append("document.getElementById('mf-preview').textContent=JSON.stringify(buildManifest(),null,2);}");

        s.append("function copyManifest(){");
        s.append("navigator.clipboard.writeText(JSON.stringify(buildManifest(),null,2))");
        s.append(".then(()=>toast('Copied!',true)).catch(()=>toast('Copy failed',false));}");

        s.append("function downloadManifest(){");
        s.append("const blob=new Blob([JSON.stringify(buildManifest(),null,2)],{type:'application/json'});");
        s.append("const a=document.createElement('a');a.href=URL.createObjectURL(blob);");
        s.append("a.download='manifest.json';a.click();}");

        // Reveal in folder
        s.append("async function revealScript(name){");
        s.append("const res=await fetch('/api/scripts/reveal',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({name})});");
        s.append("const d=await res.json();");
        s.append("if(!d.ok)toast('Error: '+(d.error||'?'),false);}");

        // Import manifest from URL into the builder form
        s.append("async function importManifest(){");
        s.append("const url=document.getElementById('mf-import-url').value.trim();");
        s.append("if(!url){toast('Enter a manifest URL first',false);return;}");
        s.append("try{");
        s.append("const res=await fetch('/api/manifest/fetch?url='+encodeURIComponent(url));");
        s.append("const m=await res.json();");
        s.append("if(m.error){toast('Import failed: '+m.error,false);return;}");
        s.append("if(m.name)document.getElementById('mf-name').value=m.name;");
        s.append("if(m.source)document.getElementById('mf-source').value=m.source;");
        s.append("if(m.author)document.getElementById('mf-author').value=m.author;");
        s.append("document.getElementById('mf-scripts').innerHTML='';scriptCount=0;");
        s.append("if(m.scripts&&m.scripts.length){");
        s.append("for(const sc of m.scripts){");
        s.append("addScriptEntry();");
        s.append("const entries=document.querySelectorAll('.script-entry');");
        s.append("const e=entries[entries.length-1];");
        s.append("if(sc.name)e.querySelector('.se-name').value=sc.name;");
        s.append("if(sc.author)e.querySelector('.se-author').value=sc.author;");
        s.append("if(sc.source)e.querySelector('.se-source').value=sc.source;");
        s.append("if(sc.destination)e.querySelector('.se-dest').value=sc.destination;");
        s.append("if(sc.version)e.querySelector('.se-ver').value=sc.version;");
        s.append("if(sc.description)e.querySelector('.se-desc').value=sc.description;");
        s.append("if(sc.imageurl)e.querySelector('.se-img').value=sc.imageurl;");
        s.append("if(sc.isApi)e.querySelector('.se-api').checked=true;");
        s.append("if(sc.isExecuteReady)e.querySelector('.se-exec').checked=true;");
        s.append("if(sc.register&&sc.register.type){");
        s.append("const sel=e.querySelector('.se-reg');sel.value=sc.register.type;updateRegFields(sel);");
        s.append("if(sc.register.key)e.querySelector('.se-key').value=sc.register.key;");
        s.append("if(sc.register.event)e.querySelector('.se-event').value=sc.register.event;}");
        s.append("}}");
        s.append("updatePreview();toast('Imported!',true);");
        s.append("}catch(e){toast('Import failed: '+e.message,false);}}");

        s.append("loadScripts();");
        s.append("</script></body></html>");
        return s.toString();
    }

    // -------------------------------------------------------------------------
    // HTTP utilities
    // -------------------------------------------------------------------------

    private void sendResponse(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private byte[] readAll(InputStream is) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = is.read(chunk)) != -1) buf.write(chunk, 0, n);
        return buf.toByteArray();
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
