package com.synapmc.repo;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.synapmc.SynapMcShared;
import com.synapmc.model.RepoManifest;
import com.synapmc.model.ScriptEntry;

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
            httpServer.createContext("/api/repos/bulk-add", new ReposBulkAddHandler());
            httpServer.createContext("/api/repos/remove", new ReposRemoveHandler());
            httpServer.createContext("/api/repos/refresh", new ReposRefreshHandler());
            httpServer.createContext("/api/status", new StatusApiHandler());
            httpServer.createContext("/api/tcpport/start", new TcpPortStartHandler());
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
                    row.put("category", entry.category);
                    row.put("isExecuteReady", entry.isExecuteReady);
                    row.put("jsmacrosVersion", entry.jsmacrosVersion);
                    row.put("server", entry.server);
                    row.put("repoName", manifest.name);
                    row.put("repoSource", manifest.source);
                    row.put("repoDiscordUrl", manifest.discordUrl);
                    row.put("repoDonateUrl", manifest.donateUrl);
                    RepoManager.InstalledInfo info = rm.getInstalled().get(entry.name.toLowerCase());
                    row.put("installed", info != null);
                    row.put("installedVersion", info != null ? info.version : null);
                    row.put("path", info != null ? info.path : null);
                    row.put("hasRegister", entry.register != null);
                    String lang = null;
                    if (entry.source != null) {
                        int dot = entry.source.lastIndexOf('.');
                        int slash = entry.source.lastIndexOf('/');
                        if (dot > slash && dot >= 0) lang = entry.source.substring(dot + 1).toLowerCase();
                    }
                    row.put("lang", lang);
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
                sendResponse(exchange, 500, "application/json", "{\"error\":\"" + escapeJson(msg) + "\"}");
            }
        }
    }

    private class ReposBulkAddHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
                return;
            }
            String body = new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8);
            List<String> urls;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> req = GSON.fromJson(body, Map.class);
                Object raw = req != null ? req.get("urls") : null;
                if (!(raw instanceof List)) {
                    sendResponse(exchange, 400, "application/json", "{\"error\":\"Missing urls array\"}");
                    return;
                }
                urls = new ArrayList<String>();
                for (Object o : (List<?>) raw) {
                    if (o != null && !o.toString().trim().isEmpty()) urls.add(o.toString().trim());
                }
            } catch (Exception e) {
                sendResponse(exchange, 400, "application/json", "{\"error\":\"Invalid JSON\"}");
                return;
            }
            List<String> errors = new ArrayList<String>();
            int added = 0;
            for (String url : urls) {
                try {
                    RepoManager.getInstance().addRepo(url);
                    added++;
                } catch (Exception e) {
                    String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    errors.add(escapeJson(url) + ": " + escapeJson(msg));
                }
            }
            Map<String, Object> resp = new LinkedHashMap<String, Object>();
            resp.put("added", added);
            resp.put("errors", errors);
            sendResponse(exchange, 200, "application/json; charset=utf-8", GSON.toJson(resp));
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
                sendResponse(exchange, 500, "application/json", "{\"error\":\"" + escapeJson(msg) + "\"}");
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
            boolean running = SynapMcShared.server != null;
            boolean connected = running && SynapMcShared.server.hasClients();
            sendResponse(exchange, 200, "application/json; charset=utf-8",
                    "{\"tcpPortRunning\":" + running + ",\"tcpPortConnected\":" + connected + "}");
        }
    }

    private class TcpPortStartHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
                return;
            }
            if (SynapMcShared.server != null) {
                sendResponse(exchange, 200, "application/json", "{\"ok\":true,\"alreadyRunning\":true}");
                return;
            }
            int port = SynapMcShared.core.getTcpPort();
            String password = SynapMcShared.core.getTcpPortPassword();
            SynapMcShared.server = new com.synapmc.server.BridgeServer(port, password);
            try {
                SynapMcShared.server.start();
                sendResponse(exchange, 200, "application/json", "{\"ok\":true,\"alreadyRunning\":false}");
            } catch (IOException e) {
                SynapMcShared.server = null;
                sendResponse(exchange, 500, "application/json",
                        "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
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
                RepoManager rm = RepoManager.getInstance();
                ScriptEntry entry = rm.findEntryPublic(name);
                String category = entry != null ? entry.category : null;
                String path;
                String extra = "";
                if ("jar".equals(category)) {
                    path = rm.installJar(name, null);
                    extra = ",\"restart\":true";
                } else if ("exe".equals(category)) {
                    path = rm.installExe(name, null);
                } else {
                    path = rm.installScript(name, location);
                }
                if (path != null) {
                    sendResponse(exchange, 200, "application/json", "{\"ok\":true,\"path\":\"" + escapeJson(path) + "\"" + extra + "}");
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
                String json = SynapMcShared.core.httpGetText(url);
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
            if (SynapMcShared.server == null || !SynapMcShared.server.hasClients()) {
                sendResponse(exchange, 503, "application/json", "{\"error\":\"TCP Port not connected\"}");
                return;
            }
            String content = RepoManager.getInstance().getString(scriptName);
            if (content == null) {
                sendResponse(exchange, 404, "application/json",
                        "{\"error\":\"Script not found: " + escapeJson(scriptName) + "\"}");
                return;
            }
            String execMsg = "{\"type\":\"exec\",\"lang\":\"lua\",\"script\":\"" + escapeJson(content) + "\"}";
            SynapMcShared.server.broadcast(execMsg);
            sendResponse(exchange, 200, "application/json", "{\"ok\":true}");
        }
    }

    // -------------------------------------------------------------------------
    // HTML
    // -------------------------------------------------------------------------

    private String buildDashboardHtml() {
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
        s.append(".tcp-port-badge{padding:4px 12px;border-radius:20px;font-size:.75rem;font-weight:600;letter-spacing:.02em;cursor:pointer;user-select:none;transition:opacity .15s}.tcp-port-badge:hover{opacity:.75}");
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
        s.append(".mf-section{background:#211e1b;border:1px solid #3a3530;border-radius:10px;padding:18px;margin-bottom:14px}");
        s.append(".mf-section h3{color:#f5f0eb;font-weight:600;font-size:.95rem;margin-bottom:14px}");
        s.append(".field-row{display:flex;flex-direction:column;gap:4px;margin-bottom:12px}");
        s.append(".field-row label{font-size:.75rem;color:#78716c;font-weight:500;letter-spacing:.01em}");
        s.append(".check-row{display:flex;align-items:center;gap:8px;margin-bottom:8px;font-size:.85rem;color:#a8a29e}");
        s.append(".script-entry{background:#141210;border:1px solid #3a3530;border-radius:8px;padding:14px;margin-bottom:12px;position:relative}");
        s.append(".script-entry .remove-entry{background:rgba(248,113,113,.15);color:#f87171;border:1px solid rgba(248,113,113,.3);border-radius:5px;padding:2px 8px;cursor:pointer;font-size:.72rem;font-weight:600}");
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
        s.append(".filter-btn{padding:5px 12px;border-radius:20px;border:1px solid #3a3530;background:#2c2825;color:#78716c;cursor:pointer;font-size:.78rem;font-weight:600;transition:all .15s}");
        s.append(".filter-btn.active{background:rgba(204,120,92,.2);color:#cc785c;border-color:rgba(204,120,92,.4)}");
        s.append(".filter-btn:hover:not(.active){color:#d6cfc8;border-color:#5a524a}");
        s.append("::-webkit-scrollbar{width:6px;height:6px}::-webkit-scrollbar-track{background:#1a1816}::-webkit-scrollbar-thumb{background:#3a3530;border-radius:3px}");
        s.append("</style></head><body>");

        s.append("<header><h1><span>Synap</span>Mc</h1>");
        s.append("<span class=\"tcp-port-badge offline\" id=\"tcp-port-badge\" title=\"Click to start Tcp Port\">Click to Connect</span></header>");

        s.append("<nav>");
        s.append("<button class=\"active\" onclick=\"showTab('browse',this)\">Browse</button>");
        s.append("<button onclick=\"showTab('repos',this)\">Repos</button>");
        s.append("<button onclick=\"showTab('manifest',this)\">Create Manifest</button>");
        s.append("</nav>");

        s.append("<div class=\"tab active\" id=\"tab-browse\">");
        s.append("<div class=\"browse-controls\">");
        s.append("<input class=\"search-input\" id=\"search\" type=\"text\" placeholder=\"Search scripts...\" oninput=\"filterScripts()\">");
        s.append("<div style=\"position:relative\">");
        s.append("<button class=\"btn-secondary\" id=\"filter-toggle\" onclick=\"toggleFilterPanel()\">Filter &#9661;</button>");
        s.append("<div id=\"filter-panel\" style=\"display:none;position:absolute;top:calc(100% + 6px);right:0;background:#211e1b;border:1px solid #3a3530;border-radius:10px;padding:16px;z-index:100;min-width:220px;box-shadow:0 8px 24px rgba(0,0,0,.5)\">");
        s.append("<div style=\"font-size:.7rem;color:#78716c;font-weight:600;letter-spacing:.06em;text-transform:uppercase;margin-bottom:10px\">Status</div>");
        s.append("<div style=\"display:flex;flex-direction:column;gap:6px;margin-bottom:14px\">");
        s.append("<button class=\"filter-btn active\" id=\"fb-all\" onclick=\"setFilter('all',this)\">All</button>");
        s.append("<button class=\"filter-btn\" id=\"fb-installed\" onclick=\"setFilter('installed',this)\">Installed</button>");
        s.append("<button class=\"filter-btn\" id=\"fb-notinstalled\" onclick=\"setFilter('notinstalled',this)\">Not Installed</button>");
        s.append("</div>");
        s.append("<div style=\"font-size:.7rem;color:#78716c;font-weight:600;letter-spacing:.06em;text-transform:uppercase;margin-bottom:10px\">Category</div>");
        s.append("<div style=\"display:flex;flex-direction:column;gap:6px\">");
        s.append("<button class=\"filter-btn\" id=\"fb-macro\" onclick=\"setFilter('macro',this)\">Macro</button>");
        s.append("<button class=\"filter-btn\" id=\"fb-api\" onclick=\"setFilter('api',this)\">API</button>");
        s.append("<button class=\"filter-btn\" id=\"fb-exe\" onclick=\"setFilter('exe',this)\">EXE</button>");
        s.append("<button class=\"filter-btn\" id=\"fb-jar\" onclick=\"setFilter('jar',this)\">JAR</button>");
        s.append("</div>");
        s.append("<div style=\"font-size:.7rem;color:#78716c;font-weight:600;letter-spacing:.06em;text-transform:uppercase;margin-bottom:10px;margin-top:14px\">JsMacros Version</div>");
        s.append("<div style=\"display:flex;flex-direction:column;gap:6px;margin-bottom:14px\">");
        s.append("<button class=\"filter-btn v-filter-btn active\" id=\"fb-ver-all\" onclick=\"setVersionFilter('all',this)\">All</button>");
        s.append("<button class=\"filter-btn v-filter-btn\" id=\"fb-ver-192\" onclick=\"setVersionFilter('1.9.2',this)\">1.9.2</button>");
        s.append("<button class=\"filter-btn v-filter-btn\" id=\"fb-ver-200\" onclick=\"setVersionFilter('2.0.0',this)\">2.0.0</button>");
        s.append("<button class=\"filter-btn v-filter-btn\" id=\"fb-ver-ce\" onclick=\"setVersionFilter('community',this)\">Community Edition</button>");
        s.append("</div>");
        s.append("<div style=\"font-size:.7rem;color:#78716c;font-weight:600;letter-spacing:.06em;text-transform:uppercase;margin-bottom:8px\">Server</div>");
        s.append("<input class=\"search-input\" id=\"server-search\" type=\"text\" placeholder=\"Filter by server...\" oninput=\"filterScripts()\" style=\"font-size:.78rem;padding:6px 10px\">");
        s.append("<div style=\"font-size:.7rem;color:#78716c;font-weight:600;letter-spacing:.06em;text-transform:uppercase;margin-bottom:8px;margin-top:14px\">Language</div>");
        s.append("<input class=\"search-input\" id=\"lang-search\" type=\"text\" placeholder=\"lua, js, py...\" oninput=\"filterScripts()\" style=\"font-size:.78rem;padding:6px 10px\">");
        s.append("</div></div>");
        s.append("</div>");
        s.append("<div class=\"grid\" id=\"grid\"><p style=\"color:#78716c\">Loading...</p></div>");
        s.append("</div>");

        s.append("<div class=\"tab\" id=\"tab-repos\">");
        s.append("<div style=\"display:flex;justify-content:flex-end;margin-bottom:12px\">");
        s.append("<button class=\"btn-secondary\" onclick=\"refreshRepos()\">Refresh All</button>");
        s.append("</div>");
        s.append("<div class=\"repo-add\"><h3>Add Repo</h3>");
        s.append("<div class=\"input-row\">");
        s.append("<input type=\"url\" id=\"add-url\" placeholder=\"https://raw.githubusercontent.com/.../manifest.json\">");
        s.append("<button class=\"btn-primary\" onclick=\"addRepo()\">Add</button>");
        s.append("<button class=\"btn-secondary\" onclick=\"toggleBulkAdd()\" id=\"bulk-toggle\">Bulk &#9661;</button>");
        s.append("</div>");
        s.append("<div id=\"bulk-section\" style=\"display:none;margin-top:12px\">");
        s.append("<label style=\"font-size:.75rem;color:#78716c;font-weight:500;display:block;margin-bottom:6px\">Bulk Add (one URL per line)</label>");
        s.append("<textarea id=\"bulk-urls\" rows=\"4\" style=\"width:100%;background:#141210;border:1px solid #3a3530;color:#f5f0eb;padding:8px 12px;border-radius:7px;font-size:.8rem;font-family:inherit;resize:vertical\" placeholder=\"https://raw.githubusercontent.com/.../manifest.json&#10;https://...\"></textarea>");
        s.append("<button class=\"btn-secondary\" style=\"margin-top:8px\" onclick=\"bulkAddRepos()\">Add All</button>");
        s.append("</div>");
        s.append("<div id=\"add-msg\"></div></div>");
        s.append("<div class=\"repo-list\" id=\"repos-list\"></div>");
        s.append("</div>");

        s.append("<div class=\"tab\" id=\"tab-manifest\">");
        s.append("<div class=\"mf-section\"><h3>Import Existing Manifest</h3>");
        s.append("<div class=\"input-row\">");
        s.append("<input type=\"url\" id=\"mf-import-url\" placeholder=\"https://raw.githubusercontent.com/.../manifest.json\">");
        s.append("<button class=\"btn-secondary\" onclick=\"importManifest()\">Import</button>");
        s.append("</div></div>");
        s.append("<div class=\"two-col\">");
        s.append("<div>");
        s.append("<div class=\"mf-section\"><h3>Repo Info</h3>");
        s.append("<div class=\"field-row\"><label>Name</label><input type=\"text\" id=\"mf-name\" oninput=\"updatePreview()\"></div>");
        s.append("<div class=\"field-row\"><label>Source URL</label><input type=\"url\" id=\"mf-source\" oninput=\"updatePreview()\"></div>");
        s.append("<div class=\"field-row\"><label>Author</label><input type=\"text\" id=\"mf-author\" oninput=\"updatePreview()\"></div>");
        s.append("<button type=\"button\" class=\"btn-secondary\" style=\"margin-top:4px;font-size:.75rem\" onclick=\"toggleRepoAdvanced(this)\">Advanced \u25BC</button>");
        s.append("<div id=\"mf-advanced\" style=\"display:none\">");
        s.append("<div class=\"field-row\"><label>Sub-repo URLs (one per line)</label>");
        s.append("<textarea id=\"mf-repos\" rows=\"3\" style=\"width:100%;background:#141210;border:1px solid #3a3530;color:#f5f0eb;padding:8px 12px;border-radius:7px;font-size:.8rem;font-family:inherit;resize:vertical\" oninput=\"updatePreview()\"></textarea></div>");
        s.append("<div class=\"field-row\"><label>Discord URL (optional)</label><input type=\"url\" id=\"mf-discord\" oninput=\"updatePreview()\"></div>");
        s.append("<div class=\"field-row\"><label>Donate URL (optional)</label><input type=\"url\" id=\"mf-donate\" oninput=\"updatePreview()\"></div>");
        s.append("</div>");
        s.append("</div>");
        s.append("<div class=\"mf-section\"><h3>Scripts</h3>");
        s.append("<div id=\"mf-scripts\"></div>");
        s.append("<button class=\"btn-secondary\" onclick=\"addScriptEntry()\">+ Add Script</button>");
        s.append("</div>");
        s.append("</div>");
        s.append("<div>");
        s.append("<div class=\"mf-section\"><h3>Preview</h3>");
        s.append("<textarea id=\"mf-preview\" rows=\"18\" style=\"width:100%;background:#141210;border:1px solid #3a3530;color:#f5f0eb;padding:8px 12px;border-radius:7px;font-size:.78rem;font-family:monospace;resize:vertical\">{}</textarea>");
        s.append("<div style=\"display:flex;gap:8px;margin-top:12px\">");
        s.append("<button class=\"btn-primary\" onclick=\"copyManifest()\">Copy JSON</button>");
        s.append("<button class=\"btn-secondary\" onclick=\"downloadManifest()\">Download manifest.json</button>");
        s.append("<button class=\"btn-secondary\" onclick=\"verifyManifest()\">Verify</button>");
        s.append("</div></div>");
        s.append("</div></div></div>");

        s.append("<div id=\"toast\"></div>");

        s.append("<script>");
        s.append("let tcpPortConnected=false;let tcpPortRunning=false;");
        s.append("async function pollStatus(){");
        s.append("try{const r=await fetch('/api/status');const d=await r.json();");
        s.append("tcpPortRunning=!!d.tcpPortRunning;tcpPortConnected=!!d.tcpPortConnected;");
        s.append("const b=document.getElementById('tcp-port-badge');");
        s.append("if(tcpPortConnected){b.textContent='Connected';b.className='tcp-port-badge live';b.title='Tcp Port has connected clients';}");
        s.append("else if(tcpPortRunning){b.textContent='Connect';b.className='tcp-port-badge live';b.title='Tcp Port running \u2013 waiting for clients';}");
        s.append("else{b.textContent='Click to Connect';b.className='tcp-port-badge offline';b.title='Click to start Tcp Port';}");
        s.append("document.querySelectorAll('.btn-exec').forEach(btn=>{");
        s.append("btn.disabled=!tcpPortRunning;");
        s.append("btn.title=tcpPortRunning?'':'Tcp Port offline';});");
        s.append("}catch(e){}}");
        s.append("pollStatus();setInterval(pollStatus,3000);");
        s.append("document.addEventListener('DOMContentLoaded',function(){");
        s.append("const badge=document.getElementById('tcp-port-badge');");
        s.append("if(badge)badge.addEventListener('click',async function(){");
        s.append("if(tcpPortRunning)return;");
        s.append("badge.textContent='Starting...';badge.className='tcp-port-badge offline';");
        s.append("try{const r=await fetch('/api/tcpport/start',{method:'POST'});");
        s.append("const d=await r.json();");
        s.append("if(d.ok){toast('Tcp Port started.',true);}");
        s.append("else{toast('Failed to start Tcp Port: '+(d.error||'?'),false);}");
        s.append("}catch(e){toast('Failed to start Tcp Port: '+e.message,false);}");
        s.append("pollStatus();});});");
        s.append("function showTab(id,btn){");
        s.append("document.querySelectorAll('.tab').forEach(t=>t.classList.remove('active'));");
        s.append("document.querySelectorAll('nav button').forEach(b=>b.classList.remove('active'));");
        s.append("document.getElementById('tab-'+id).classList.add('active');");
        s.append("if(btn)btn.classList.add('active');");
        s.append("if(id==='repos')loadRepos();}");
        s.append("function toast(msg,ok){");
        s.append("const el=document.getElementById('toast');");
        s.append("el.textContent=msg;");
        s.append("el.style.background=ok?'#1a3d2e':'#3d1a1a';");
        s.append("el.style.color=ok?'#34d399':'#f87171';");
        s.append("el.style.border=ok?'1px solid rgba(52,211,153,.3)':'1px solid rgba(248,113,113,.3)';");
        s.append("el.style.display='block';");
        s.append("setTimeout(()=>el.style.display='none',3000);}");
        s.append("function esc(s){return String(s||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/\"/g,'&quot;');}");
        s.append("let allScripts=[];let activeFilter='all';let activeVersionFilter='all';");
        s.append("async function loadScripts(){");
        s.append("const res=await fetch('/api/scripts');");
        s.append("allScripts=await res.json();");
        s.append("filterScripts();}");
        s.append("function toggleFilterPanel(){");
        s.append("const p=document.getElementById('filter-panel');");
        s.append("const open=p.style.display==='none';");
        s.append("p.style.display=open?'block':'none';");
        s.append("document.getElementById('filter-toggle').textContent='Filter '+(open?'\\u25B2':'\\u25BC');}");
        s.append("document.addEventListener('click',function(e){");
        s.append("const panel=document.getElementById('filter-panel');");
        s.append("const toggle=document.getElementById('filter-toggle');");
        s.append("if(panel&&panel.style.display!=='none'&&!panel.contains(e.target)&&e.target!==toggle){");
        s.append("panel.style.display='none';");
        s.append("document.getElementById('filter-toggle').textContent='Filter \\u25BC';}});");
        s.append("function toggleBulkAdd(){");
        s.append("const s=document.getElementById('bulk-section');");
        s.append("const open=s.style.display==='none';");
        s.append("s.style.display=open?'block':'none';");
        s.append("document.getElementById('bulk-toggle').textContent='Bulk '+(open?'\\u25B2':'\\u25BC');}");
        s.append("function toggleRepoAdvanced(btn){");
        s.append("const d=document.getElementById('mf-advanced');");
        s.append("const open=d.style.display==='none';");
        s.append("d.style.display=open?'block':'none';");
        s.append("btn.textContent='Advanced '+(open?'\\u25B2':'\\u25BC');}");
        s.append("function setFilter(f,btn){");
        s.append("activeFilter=f;");
        s.append("document.querySelectorAll('.filter-btn:not(.v-filter-btn)').forEach(b=>b.classList.remove('active'));");
        s.append("btn.classList.add('active');");
        s.append("document.getElementById('filter-panel').style.display='none';");
        s.append("document.getElementById('filter-toggle').textContent='Filter \\u25BC';");
        s.append("filterScripts();}");
        s.append("function setVersionFilter(f,btn){");
        s.append("activeVersionFilter=f;");
        s.append("document.querySelectorAll('.v-filter-btn').forEach(b=>b.classList.remove('active'));");
        s.append("btn.classList.add('active');");
        s.append("filterScripts();}");
        s.append("function filterScripts(){");
        s.append("const q=document.getElementById('search').value.toLowerCase();");
        s.append("const sv=document.getElementById('server-search').value.toLowerCase();");
        s.append("const lv=document.getElementById('lang-search').value.toLowerCase();");
        s.append("let list=allScripts;");
        s.append("if(activeFilter!=='api')list=list.filter(s=>s.category!=='api');");
        s.append("if(activeFilter==='installed')list=list.filter(s=>s.installed);");
        s.append("else if(activeFilter==='notinstalled')list=list.filter(s=>!s.installed);");
        s.append("else if(['macro','api','exe','jar'].includes(activeFilter))list=list.filter(s=>s.category===activeFilter);");
        s.append("if(activeVersionFilter!=='all')list=list.filter(s=>s.jsmacrosVersion&&s.jsmacrosVersion.toLowerCase()===activeVersionFilter);");
        s.append("if(sv)list=list.filter(s=>(s.server||'').toLowerCase().includes(sv));");
        s.append("if(lv)list=list.filter(s=>(s.lang||'').toLowerCase().includes(lv));");
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
        s.append("if(sc.lang&&!(sc.lang==='jar'&&sc.category==='jar')){const lc=sc.lang.toLowerCase();const lcls=lc==='lua'?'b-lua':lc==='js'?'b-js':lc==='py'?'b-py':'b-lang';h+=`<span class=\"badge ${lcls}\">.${esc(sc.lang)}</span>`;}");
        s.append("if(sc.installed)h+='<span class=\"badge b-inst\">Installed</span>';");
        s.append("if(sc.category&&sc.category!=='macro')h+=`<span class=\"badge b-api\">${esc(sc.category.toUpperCase())}</span>`;");
        s.append("if(sc.jsmacrosVersion)h+=`<span class=\"badge b-ver\" title=\"JsMacros version\">${esc(sc.jsmacrosVersion)}</span>`;");
        s.append("if(sc.hasRegister&&!sc.goesToMacros)h+='<span class=\"b-trigger\">&#9888; trigger</span>';");
        s.append("h+='</div>';");
        s.append("if(sc.description)h+=`<div class=\"card-desc\">${esc(sc.description)}</div>`;");
        s.append("if(sc.category==='api'){const snip=`local s=synapmc:repo():getString(${JSON.stringify(sc.name)})`;h+=`<div style=\"display:flex;flex-direction:column;gap:4px\"><div class=\"snippet\">${esc(snip)}</div><button onclick=\"navigator.clipboard.writeText(${esc(JSON.stringify(snip))}).then(()=>toast('Copied!',true)).catch(()=>toast('Copy failed',false))\" style=\"align-self:flex-end;padding:2px 8px;font-size:.7rem;font-weight:600;border-radius:5px;border:1px solid #3a3530;background:#2c2825;color:#a8a29e;cursor:pointer\">Copy</button></div>`;}");
        s.append("h+='<div class=\"btns\">';");
        s.append("if(sc.isExecuteReady){const dis=tcpPortRunning?'':' disabled title=\"Tcp Port offline\"';");
        s.append("h+=`<button class=\"btn-exec\"${dis} onclick=\"execScript(${esc(JSON.stringify(sc.name))})\">Execute</button>`;}");
        s.append("if(!sc.installed)h+=`<button class=\"btn-install\" onclick=\"installScript(${esc(JSON.stringify(sc.name))})\">Install</button>`;");
        s.append("else{h+=`<button class=\"btn-remove\" onclick=\"removeScript(${esc(JSON.stringify(sc.name))})\">Uninstall</button>`;");
        s.append("h+=`<button class=\"btn-secondary\" onclick=\"revealScript(${esc(JSON.stringify(sc.name))})\">Show in Folder</button>`;}");
        s.append("if(sc.repoSource)h+=`<a class=\"btn btn-src\" href=\"${esc(sc.repoSource)}\" target=\"_blank\">Source</a>`;");
        s.append("if(sc.repoDiscordUrl)h+=`<a class=\"btn btn-src\" href=\"${esc(sc.repoDiscordUrl)}\" target=\"_blank\" title=\"Discord\" style=\"color:#7289da\"><svg width=\"13\" height=\"13\" viewBox=\"0 0 24 24\" fill=\"currentColor\" style=\"display:inline;vertical-align:middle\"><path d=\"M20.317 4.37a19.791 19.791 0 0 0-4.885-1.515.074.074 0 0 0-.079.037c-.21.375-.444.864-.608 1.25a18.27 18.27 0 0 0-5.487 0 12.64 12.64 0 0 0-.617-1.25.077.077 0 0 0-.079-.037A19.736 19.736 0 0 0 3.677 4.37a.07.07 0 0 0-.032.027C.533 9.046-.32 13.58.099 18.057.1 18.082.11 18.105.12 18.128a19.9 19.9 0 0 0 5.993 3.03.078.078 0 0 0 .084-.028c.462-.63.874-1.295 1.226-1.994a.076.076 0 0 0-.041-.106 13.107 13.107 0 0 1-1.872-.892.077.077 0 0 1-.008-.128 10.2 10.2 0 0 0 .372-.292.074.074 0 0 1 .077-.01c3.928 1.793 8.18 1.793 12.062 0a.074.074 0 0 1 .078.01c.12.098.246.198.373.292a.077.077 0 0 1-.006.127 12.299 12.299 0 0 1-1.873.892.077.077 0 0 0-.041.107c.36.698.772 1.362 1.225 1.993a.076.076 0 0 0 .084.028 19.839 19.839 0 0 0 6.002-3.03.077.077 0 0 0 .032-.054c.5-5.177-.838-9.674-3.549-13.66a.061.061 0 0 0-.031-.03z\"/></svg></a>`;");
        s.append("if(sc.repoDonateUrl)h+=`<a class=\"btn btn-src\" href=\"${esc(sc.repoDonateUrl)}\" target=\"_blank\" title=\"Donate\"><svg width=\"13\" height=\"13\" viewBox=\"0 0 24 24\" fill=\"currentColor\" style=\"display:inline;vertical-align:middle\"><path d=\"M12 21.593c-.5-.396-7-5.621-7-10.593 0-3.866 3.134-7 7-7s7 3.134 7 7c0 4.972-6.5 10.197-7 10.593z\"/></svg></a>`;");
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
        s.append("async function loadRepos(){");
        s.append("const list=document.getElementById('repos-list');");
        s.append("try{");
        s.append("const res=await fetch('/api/repos');");
        s.append("const repos=await res.json();");
        s.append("const keys=Object.keys(repos);");
        s.append("if(!keys.length){list.innerHTML='<p style=\"color:#78716c\">No repos configured.</p>';return;}");
        s.append("const discordByRepo={},donateByRepo={};");
        s.append("for(const s of allScripts){if(s.repoName&&s.repoDiscordUrl)discordByRepo[s.repoName]=s.repoDiscordUrl;if(s.repoName&&s.repoDonateUrl)donateByRepo[s.repoName]=s.repoDonateUrl;}");
        s.append("const discSvg=`<svg width=\"13\" height=\"13\" viewBox=\"0 0 24 24\" fill=\"currentColor\" style=\"display:inline;vertical-align:middle\"><path d=\"M20.317 4.37a19.791 19.791 0 0 0-4.885-1.515.074.074 0 0 0-.079.037c-.21.375-.444.864-.608 1.25a18.27 18.27 0 0 0-5.487 0 12.64 12.64 0 0 0-.617-1.25.077.077 0 0 0-.079-.037A19.736 19.736 0 0 0 3.677 4.37a.07.07 0 0 0-.032.027C.533 9.046-.32 13.58.099 18.057.1 18.082.11 18.105.12 18.128a19.9 19.9 0 0 0 5.993 3.03.078.078 0 0 0 .084-.028c.462-.63.874-1.295 1.226-1.994a.076.076 0 0 0-.041-.106 13.107 13.107 0 0 1-1.872-.892.077.077 0 0 1-.008-.128 10.2 10.2 0 0 0 .372-.292.074.074 0 0 1 .077-.01c3.928 1.793 8.18 1.793 12.062 0a.074.074 0 0 1 .078.01c.12.098.246.198.373.292a.077.077 0 0 1-.006.127 12.299 12.299 0 0 1-1.873.892.077.077 0 0 0-.041.107c.36.698.772 1.362 1.225 1.993a.076.076 0 0 0 .084.028 19.839 19.839 0 0 0 6.002-3.03.077.077 0 0 0 .032-.054c.5-5.177-.838-9.674-3.549-13.66a.061.061 0 0 0-.031-.03z\"/></svg>`;");
        s.append("const donSvg=`<svg width=\"13\" height=\"13\" viewBox=\"0 0 24 24\" fill=\"currentColor\" style=\"display:inline;vertical-align:middle\"><path d=\"M12 21.593c-.5-.396-7-5.621-7-10.593 0-3.866 3.134-7 7-7s7 3.134 7 7c0 4.972-6.5 10.197-7 10.593z\"/></svg>`;");
        s.append("list.innerHTML=keys.map(k=>{const disc=discordByRepo[k]?`<a class=\"btn btn-src\" href=\"${esc(discordByRepo[k])}\" target=\"_blank\" style=\"display:inline-flex;align-items:center;gap:4px;color:#7289da\">${discSvg} Discord</a>`:'';const don=donateByRepo[k]?`<a class=\"btn btn-src\" href=\"${esc(donateByRepo[k])}\" target=\"_blank\" style=\"display:inline-flex;align-items:center;gap:4px\">${donSvg} Donate</a>`:'';return`<div class=\"repo-row\"><span class=\"repo-name\">${esc(k)}</span><span class=\"repo-url\">${esc(repos[k])}</span>${disc}${don}<button class=\"btn-remove\" onclick=\"removeRepo(${esc(JSON.stringify(k))})\">Remove</button></div>`;}).join('');");
        s.append("}catch(e){list.innerHTML='<p style=\"color:#f87171\">Failed to load repos: '+esc(e.message)+'</p>';}}");
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
        s.append("async function bulkAddRepos(){");
        s.append("const raw=document.getElementById('bulk-urls').value;");
        s.append("const urls=raw.split('\\n').map(u=>u.trim()).filter(u=>u.length>0);");
        s.append("const msg=document.getElementById('add-msg');");
        s.append("if(!urls.length){msg.innerHTML='<div class=\"msg msg-err\">Enter at least one URL.</div>';return;}");
        s.append("msg.innerHTML='<div class=\"msg\" style=\"background:#2d3436\">Adding...</div>';");
        s.append("try{");
        s.append("const res=await fetch('/api/repos/bulk-add',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({urls})});");
        s.append("const d=await res.json();");
        s.append("if(d.errors&&d.errors.length)msg.innerHTML=`<div class=\"msg msg-err\">Added ${d.added}. Errors:<br>${d.errors.map(e=>esc(e)).join('<br>')}</div>`;");
        s.append("else{msg.innerHTML=`<div class=\"msg msg-ok\">Added ${d.added} repo(s).</div>`;");
        s.append("document.getElementById('bulk-urls').value='';}");
        s.append("loadRepos();loadScripts();");
        s.append("}catch(e){msg.innerHTML=`<div class=\"msg msg-err\">${esc(e.message)}</div>`;}}");
        s.append("async function refreshRepos(){");
        s.append("toast('Refreshing...',true);");
        s.append("try{");
        s.append("const res=await fetch('/api/repos/refresh',{method:'POST'});");
        s.append("const d=await res.json();");
        s.append("if(d.ok){toast('Repos refreshed.',true);loadRepos();loadScripts();}");
        s.append("else toast('Refresh failed: '+(d.error||'?'),false);");
        s.append("}catch(e){toast('Refresh failed: '+e.message,false);}}");
        s.append("let scriptCount=0;");
        s.append("function addScriptEntry(){");
        s.append("const id='se'+scriptCount++;");
        s.append("const div=document.createElement('div');div.className='script-entry';div.id=id;");
        s.append("div.innerHTML=`");
        s.append("<div style=\"display:flex;gap:6px;margin-bottom:6px;align-items:center\">");
        s.append("<button class=\"remove-entry\" onclick=\"document.getElementById('${id}').remove();updatePreview()\">Remove</button>");
        s.append("<button type=\"button\" class=\"btn-secondary\" style=\"font-size:.72rem;padding:2px 8px\" onclick=\"duplicateEntry('${id}')\">Duplicate</button>");
        s.append("<span class=\"se-collapsed-name\" style=\"display:none;font-size:.8rem;color:#d6cfc8;flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;padding:0 4px\"></span>");
        s.append("<button type=\"button\" class=\"btn-secondary\" style=\"margin-left:auto;font-size:.72rem;padding:2px 8px\" onclick=\"collapseEntry('${id}',this)\">&#9660;</button>");
        s.append("</div>");
        s.append("<div class=\"se-body\" id=\"${id}-body\">");
        s.append("<div class=\"field-row\"><label>Name</label><input type=\"text\" class=\"se-name\" oninput=\"updatePreview()\"></div>");
        s.append("<div class=\"field-row\"><label>Author</label><input type=\"text\" class=\"se-author\" oninput=\"updatePreview()\"></div>");
        s.append("<div class=\"field-row\"><label>Source URL</label><input type=\"url\" class=\"se-source\" oninput=\"updatePreview()\"></div>");
        s.append("<div class=\"field-row\"><label>Destination</label><input type=\"text\" class=\"se-dest\" oninput=\"updatePreview()\"><span class=\"se-dest-hint\" style=\"font-size:.75rem;color:#78716c;margin-left:6px\"></span></div>");
        s.append("<div class=\"field-row\"><label>Version</label><input type=\"text\" class=\"se-ver\" value=\"1.0.0\" oninput=\"updatePreview()\"></div>");
        s.append("<div class=\"field-row\"><label>Description</label><input type=\"text\" class=\"se-desc\" oninput=\"updatePreview()\"></div>");
        s.append("<div class=\"field-row\"><label>Image URL (optional)</label><input type=\"url\" class=\"se-img\" oninput=\"updatePreview()\"></div>");
        s.append("<div class=\"field-row\"><label>Category</label><select class=\"se-category\" onchange=\"onCategoryChange(this);updatePreview()\"><option value=\"macro\">macro</option><option value=\"api\">api</option><option value=\"exe\">exe</option><option value=\"jar\">jar</option></select></div>");
        s.append("<div class=\"field-row\"><label>JsMacros Version</label><select class=\"se-jsmver\" onchange=\"updatePreview()\"><option value=\"\">All</option><option value=\"1.9.2\">1.9.2</option><option value=\"2.0.0\">2.0.0</option><option value=\"community\">Community Edition</option></select></div>");
        s.append("<div class=\"field-row\"><label>Server (optional, leave blank for all)</label><input type=\"text\" class=\"se-server\" placeholder=\"e.g. hypixel.net\" oninput=\"updatePreview()\"></div>");
        s.append("<div class=\"se-exec-row\"><div class=\"check-row\"><input type=\"checkbox\" class=\"se-exec\" onchange=\"updatePreview()\"><label>isExecuteReady</label></div></div>");
        s.append("<button type=\"button\" class=\"btn-secondary\" style=\"font-size:.75rem;margin-top:4px\" onclick=\"toggleEntryAdv(this)\">Advanced \u25BC</button>");
        s.append("<div class=\"se-adv\" style=\"display:none\">");
        s.append("<div class=\"field-row\"><label>Register Type</label>");
        s.append("<select class=\"se-reg\" onchange=\"updateRegFields(this);updatePreview()\">");
        s.append("<option value=\"\">None</option><option value=\"keydown\">keydown</option>");
        s.append("<option value=\"keyup\">keyup</option><option value=\"event\">event</option></select></div>");
        s.append("<div class=\"se-reg-key\" style=\"display:none\"><div class=\"field-row\"><label>Key (e.g. key.keyboard.keypad.4)</label><input type=\"text\" class=\"se-key\" oninput=\"updatePreview()\"></div></div>");
        s.append("<div class=\"se-reg-event\" style=\"display:none\"><div class=\"field-row\"><label>Event (e.g. JoinedWorld)</label><input type=\"text\" class=\"se-event\" oninput=\"updatePreview()\"></div></div>");
        s.append("</div>");
        s.append("</div>");
        s.append("`;");
        s.append("document.getElementById('mf-scripts').appendChild(div);");
        s.append("onCategoryChange(div.querySelector('.se-category'));");
        s.append("updatePreview();}");
        s.append("function updateRegFields(sel){");
        s.append("const entry=sel.closest('.script-entry');");
        s.append("entry.querySelector('.se-reg-key').style.display=");
        s.append("(sel.value==='keydown'||sel.value==='keyup')?'block':'none';");
        s.append("entry.querySelector('.se-reg-event').style.display=");
        s.append("sel.value==='event'?'block':'none';}");
        s.append("function onCategoryChange(sel){");
        s.append("const entry=sel.closest('.script-entry');");
        s.append("const dest=entry.querySelector('.se-dest');");
        s.append("const hint=entry.querySelector('.se-dest-hint');");
        s.append("const execRow=entry.querySelector('.se-exec-row');");
        s.append("if(sel.value==='exe'){dest.value='Desktop';dest.readOnly=true;hint.textContent='(locked)';}");
        s.append("else if(sel.value==='jar'){dest.value='Extensions/';dest.readOnly=true;hint.textContent='(locked)';}");
        s.append("else if(sel.value==='api'){dest.value='scripts/library/';dest.readOnly=false;hint.textContent='';}");
        s.append("else if(sel.value==='macro'){dest.value='scripts/macros/';dest.readOnly=false;hint.textContent='';}");
        s.append("else{dest.readOnly=false;hint.textContent='';}");
        s.append("execRow.style.display=sel.value==='macro'?'block':'none';}");
        s.append("function toggleEntryAdv(btn){");
        s.append("const d=btn.nextElementSibling;");
        s.append("const open=d.style.display==='none';");
        s.append("d.style.display=open?'block':'none';");
        s.append("btn.textContent='Advanced '+(open?'\\u25B2':'\\u25BC');}");
        s.append("function collapseEntry(id,btn){");
        s.append("const entry=document.getElementById(id);");
        s.append("const body=document.getElementById(id+'-body');");
        s.append("const open=body.style.display!=='none';");
        s.append("body.style.display=open?'none':'block';");
        s.append("btn.innerHTML=open?'&#9654;':'&#9660;';");
        s.append("const nameSpan=entry.querySelector('.se-collapsed-name');");
        s.append("if(open){const n=entry.querySelector('.se-name').value;nameSpan.textContent=n;nameSpan.style.display=n?'':'none';}");
        s.append("else{nameSpan.style.display='none';}}");
        s.append("function duplicateEntry(id){");
        s.append("const src=document.getElementById(id);");
        s.append("const newId='se'+scriptCount++;");
        s.append("const clone=src.cloneNode(true);");
        s.append("clone.id=newId;");
        s.append("clone.innerHTML=clone.innerHTML.replace(new RegExp(id,'g'),newId);");
        s.append("src.parentNode.insertBefore(clone,src.nextSibling);");
        s.append("updatePreview();}");
        s.append("function buildManifest(){");
        s.append("const reposRaw=document.getElementById('mf-repos').value.split('\\n').map(u=>u.trim()).filter(u=>u.length>0);");
        s.append("const m={name:document.getElementById('mf-name').value,");
        s.append("source:document.getElementById('mf-source').value,");
        s.append("author:document.getElementById('mf-author').value,");
        s.append("repos:reposRaw.length?reposRaw:undefined,");
        s.append("discordUrl:document.getElementById('mf-discord').value||undefined,");
        s.append("donateUrl:document.getElementById('mf-donate').value||undefined,scripts:[]};");
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
        s.append("category:e.querySelector('.se-category').value,");
        s.append("isExecuteReady:e.querySelector('.se-exec').checked,");
        s.append("jsmacrosVersion:e.querySelector('.se-jsmver').value||null,server:e.querySelector('.se-server').value||null,register});});");
        s.append("return m;}");
        s.append("function updatePreview(){");
        s.append("document.getElementById('mf-preview').value=JSON.stringify(buildManifest(),null,2);}");
        s.append("function copyManifest(){");
        s.append("navigator.clipboard.writeText(JSON.stringify(buildManifest(),null,2))");
        s.append(".then(()=>toast('Copied!',true)).catch(()=>toast('Copy failed',false));}");
        s.append("function downloadManifest(){");
        s.append("const blob=new Blob([JSON.stringify(buildManifest(),null,2)],{type:'application/json'});");
        s.append("const a=document.createElement('a');a.href=URL.createObjectURL(blob);");
        s.append("a.download='manifest.json';a.click();}");
        s.append("async function revealScript(name){");
        s.append("const res=await fetch('/api/scripts/reveal',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({name})});");
        s.append("const d=await res.json();");
        s.append("if(!d.ok)toast('Error: '+(d.error||'?'),false);}");
        s.append("async function importManifest(){");
        s.append("const url=document.getElementById('mf-import-url').value.trim();");
        s.append("if(!url){toast('Enter a manifest URL first',false);return;}");
        s.append("try{");
        s.append("const res=await fetch('/api/manifest/fetch?url='+encodeURIComponent(url));");
        s.append("const m=await res.json();");
        s.append("if(m.error){toast('Import failed: '+m.error,false);return;}");
        s.append("populateManifest(m);toast('Imported!',true);");
        s.append("}catch(e){toast('Import failed: '+e.message,false);}}");
        s.append("function populateManifest(m){");
        s.append("if(m.name)document.getElementById('mf-name').value=m.name;");
        s.append("if(m.source)document.getElementById('mf-source').value=m.source;");
        s.append("if(m.author)document.getElementById('mf-author').value=m.author;");
        s.append("if(m.repos&&m.repos.length)document.getElementById('mf-repos').value=m.repos.join('\\n');");
        s.append("if(m.discordUrl)document.getElementById('mf-discord').value=m.discordUrl;");
        s.append("if(m.donateUrl)document.getElementById('mf-donate').value=m.donateUrl;");
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
        s.append("if(sc.category){e.querySelector('.se-category').value=sc.category;onCategoryChange(e.querySelector('.se-category'));}");
        s.append("if(sc.isExecuteReady)e.querySelector('.se-exec').checked=true;");
        s.append("if(sc.register&&sc.register.type){");
        s.append("const sel=e.querySelector('.se-reg');sel.value=sc.register.type;updateRegFields(sel);");
        s.append("if(sc.register.key)e.querySelector('.se-key').value=sc.register.key;");
        s.append("if(sc.register.event)e.querySelector('.se-event').value=sc.register.event;}");
        s.append("if(sc.jsmacrosVersion)e.querySelector('.se-jsmver').value=sc.jsmacrosVersion;");
        s.append("if(sc.server)e.querySelector('.se-server').value=sc.server;");
        s.append("}}");
        s.append("updatePreview();}");
        s.append("function verifyManifest(){");
        s.append("try{");
        s.append("const m=JSON.parse(document.getElementById('mf-preview').value);");
        s.append("populateManifest(m);");
        s.append("toast('Verified!',true);");
        s.append("}catch(e){toast('Invalid JSON: '+e.message,false);}}");
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
