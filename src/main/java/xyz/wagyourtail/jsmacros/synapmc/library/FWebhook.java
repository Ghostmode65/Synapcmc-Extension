package xyz.wagyourtail.jsmacros.synapmc.library;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import xyz.wagyourtail.jsmacros.synapmc.SynapMcExtension;

public class FWebhook {

    private static final Gson GSON = new Gson();

    public void send(String type, String message) {
        if (SynapMcExtension.server == null || !SynapMcExtension.server.hasClients()) return;
        JsonObject obj = new JsonObject();
        obj.addProperty("type", type);
        obj.addProperty("message", message);
        SynapMcExtension.server.broadcast(GSON.toJson(obj));
    }

    public void log(String message) {
        if (SynapMcExtension.server == null || !SynapMcExtension.server.hasClients()) return;
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "log");
        obj.addProperty("message", message);
        SynapMcExtension.server.broadcast(GSON.toJson(obj));
    }

    public void sendRaw(String json) {
        if (SynapMcExtension.server == null || !SynapMcExtension.server.hasClients()) return;
        SynapMcExtension.server.broadcast(json);
    }

    public boolean isConnected() {
        return SynapMcExtension.server != null && SynapMcExtension.server.hasClients();
    }
}
