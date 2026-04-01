package xyz.wagyourtail.jsmacros.synapmc.server;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import xyz.wagyourtail.jsmacros.core.Core;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class BridgeServer {//legacy name, just like webhook named methods.

    private static final Gson GSON = new Gson();

    private final int port;
    private final String password;
    private ServerSocket serverSocket;
    private final Set<PrintWriter> clients = ConcurrentHashMap.newKeySet();

    public BridgeServer(int port, String password) {
        this.port = port;
        this.password = password != null ? password : "";
    }

    private boolean requiresAuth() {
        return !password.isEmpty();
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        Thread t = new Thread(this::acceptLoop, "SynapMc-Webhook-Accept");
        t.setDaemon(true);
        t.start();
        System.out.println("[SynapMc] Webhook listening on port " + port);
    }

    public void stop() {
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {}
    }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try {
                Socket client = serverSocket.accept();
                handleClient(client);
            } catch (IOException e) {
                if (!serverSocket.isClosed()) e.printStackTrace();
            }
        }
    }

    private void handleClient(Socket socket) {
        PrintWriter writer;
        try {
            writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
                if (requiresAuth()) {
                    String line = reader.readLine();
                    if (line == null) return;
                    try {
                        JsonObject msg = new JsonParser().parse(line.trim()).getAsJsonObject();
                        String type = msg.has("type") ? msg.get("type").getAsString() : "";
                        String pass = msg.has("password") ? msg.get("password").getAsString() : "";
                        if (!"auth".equals(type) || !password.equals(pass)) {
                            writer.println(errorJson("Authentication failed", null, -1));
                            return;
                        }
                    } catch (Exception e) {
                        writer.println(errorJson("Authentication failed: invalid message", null, -1));
                        return;
                    }
                    writer.println("{\"type\":\"auth\",\"status\":\"ok\"}");
                }

                clients.add(writer);
                // Acknowledge the connection if no auth was required (auth flow already sends its own response).
                if (!requiresAuth()) {
                    writer.println("{\"type\":\"connected\",\"status\":\"ok\"}");
                }
                System.out.println("[SynapMc] Bridge client connected. (" + clients.size() + " connected)");
                String line;
                while ((line = reader.readLine()) != null) {
                    String msg = line.trim();
                    if (!msg.isEmpty()) handleMessage(writer, msg);
                }
            } catch (IOException ignored) {
            } finally {
                clients.remove(writer);
                System.out.println("[SynapMc] Bridge client disconnected. (" + clients.size() + " connected)");
                try { socket.close(); } catch (IOException ignored) {}
            }
        }, "SynapMc-Webhook-Client");
        t.setDaemon(true);
        t.start();
    }

    private void handleMessage(PrintWriter sender, String json) {
        try {
            JsonObject msg = new JsonParser().parse(json).getAsJsonObject();
            String type = msg.has("type") ? msg.get("type").getAsString() : "";

            switch (type) {
                case "exec": {
                    String lang = msg.has("lang") ? msg.get("lang").getAsString() : "js";
                    String script = msg.has("script") ? msg.get("script").getAsString() : "";
                    File fakeFile = new File(new File("").getAbsoluteFile(), "synapmc-webhook." + lang);
                    Core.getInstance().exec(lang, script, fakeFile, null, null, ex -> {
                        String err = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                        sender.println(errorJson(err, null, -1));
                    });
                    break;
                }
                default: {
                    sender.println(errorJson("Unknown message type: " + type, null, -1));
                    break;
                }
            }
        } catch (Exception e) {
            sender.println(errorJson("Failed to parse message: " + e.getMessage(), null, -1));
        }
    }

    public void broadcast(String json) {
        for (PrintWriter writer : clients) {
            writer.println(json);
        }
    }

    public boolean hasClients() {
        return !clients.isEmpty();
    }

    public static String errorJson(String message, String file, int line) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "error");
        obj.addProperty("message", message != null ? message : "");
        if (file != null) obj.addProperty("file", file);
        if (line > -1) obj.addProperty("line", line);
        return GSON.toJson(obj);
    }
}
