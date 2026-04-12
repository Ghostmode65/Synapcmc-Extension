package com.synapmc.library;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.synapmc.SynapMcShared;

/**
 * Provides scripting access to the TCP bridge server (formerly called "webhook").
 * Connect external programs to the bridge server to receive broadcasts and execute scripts.
 */
public class FPort {

    private static final Gson GSON = new Gson();

    public void send(String type, String message) {
        if (SynapMcShared.server == null || !SynapMcShared.server.hasClients()) return;
        JsonObject obj = new JsonObject();
        obj.addProperty("type", type);
        obj.addProperty("message", message);
        SynapMcShared.server.broadcast(GSON.toJson(obj));
    }

    public void log(String message) {
        if (SynapMcShared.server == null || !SynapMcShared.server.hasClients()) return;
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "log");
        obj.addProperty("message", message);
        SynapMcShared.server.broadcast(GSON.toJson(obj));
    }

    public void sendRaw(String json) {
        if (SynapMcShared.server == null || !SynapMcShared.server.hasClients()) return;
        SynapMcShared.server.broadcast(json);
    }

    public boolean isConnected() {
        return SynapMcShared.server != null && SynapMcShared.server.hasClients();
    }
}
