package xyz.wagyourtail.jsmacros.synapmc.library;

import com.synapmc.SynapMcShared;
import com.synapmc.library.FInstall;
import com.synapmc.library.FPort;
import com.synapmc.library.FPrivate;
import com.synapmc.library.FRepo;
import xyz.wagyourtail.jsmacros.core.Core;
import xyz.wagyourtail.jsmacros.core.library.BaseLibrary;
import xyz.wagyourtail.jsmacros.core.library.Library;
import xyz.wagyourtail.jsmacros.synapmc.config.SynapMcConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@Library("synapmc")
public class FSynapMc extends BaseLibrary {

    private final FRepo repoHelper = new FRepo();
    private final FPort portHelper = new FPort();

    public FRepo repo() {
        return repoHelper;
    }

    public FRepo repo(String repoName) {
        return new FRepo(repoName);
    }

    public FPort port() {
        return portHelper;
    }

    public FPrivate getPrivate() {
        return new FPrivate();
    }

    public String roaming() {
        return SynapMcShared.roamingDir.getAbsolutePath();
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
            Path dest = SynapMcShared.roamingDir.toPath().resolve(savePath).resolve(filename);

            if (!overwrite && Files.exists(dest)) {
                return null;
            }

            byte[] bytes = SynapMcShared.core.httpGet(url);
            if (bytes == null || bytes.length == 0) return null;

            Files.createDirectories(dest.getParent());
            Files.write(dest, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            return filename;
        } catch (IOException e) {
            System.err.println("[SynapMc] download failed for " + url + ": " + e.getMessage());
            return null;
        }
    }
}
