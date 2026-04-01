package xyz.wagyourtail.jsmacros.synapmc.library;

import xyz.wagyourtail.jsmacros.core.Core;
import xyz.wagyourtail.jsmacros.core.library.BaseLibrary;
import xyz.wagyourtail.jsmacros.core.library.Library;
import xyz.wagyourtail.jsmacros.core.library.impl.classes.HTTPRequest;
import xyz.wagyourtail.jsmacros.synapmc.SynapMcExtension;
import xyz.wagyourtail.jsmacros.synapmc.config.SynapMcConfig;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@Library("synapmc")
public class FSynapMc extends BaseLibrary {

    private final FRepo repoHelper = new FRepo();
    private final FWebhook webhookHelper = new FWebhook();

    public FRepo repo() {
        return repoHelper;
    }

    public FRepo repo(String repoName) {
        return new FRepo(repoName);
    }

    public FWebhook webhook() {
        return webhookHelper;
    }

    public String roaming() {
        return SynapMcExtension.roamingDir.getAbsolutePath();
    }

    public SynapMcConfig getConfig() {
        return Core.getInstance().config.getOptions(SynapMcConfig.class);
    }

    public void saveConfig() {
        Core.getInstance().config.saveConfig();
    }

    public String download(String url, String savePath, boolean overwrite) {
        try {
            String filename = url.substring(url.lastIndexOf('/') + 1);

            Path dest = SynapMcExtension.roamingDir.toPath().resolve(savePath).resolve(filename);

            if (!overwrite && Files.exists(dest)) {
                return null;
            }

            byte[] bytes = new HTTPRequest(url).get().byteArray();
            if (bytes == null) return null;

            Files.createDirectories(dest.getParent());
            Files.write(dest, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            return filename;
        } catch (Exception e) {
            System.err.println("[SynapMc] download failed for " + url + ": " + e.getMessage());
            return null;
        }
    }
}
