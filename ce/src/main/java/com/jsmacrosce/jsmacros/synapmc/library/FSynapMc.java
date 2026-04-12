package com.jsmacrosce.jsmacros.synapmc.library;

import com.jsmacrosce.jsmacros.core.Core;
import com.jsmacrosce.jsmacros.core.library.BaseLibrary;
import com.jsmacrosce.jsmacros.core.library.Library;
import com.synapmc.SynapMcShared;
import com.synapmc.library.FPort;
import com.synapmc.library.FPrivate;
import com.synapmc.library.FRepo;
import com.jsmacrosce.jsmacros.synapmc.config.SynapMcConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@Library("synapmc")
public class FSynapMc extends BaseLibrary {

    // CE: BaseLibrary requires Core<?,?> in its constructor.
    private final Core<?, ?> coreInstance;

    private final FRepo repoHelper = new FRepo();
    private final FPort portHelper = new FPort();

    public FSynapMc(Core<?, ?> core) {
        super(core);
        this.coreInstance = core;
    }

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
        return coreInstance.config.getOptions(SynapMcConfig.class);
    }

    public void saveConfig() {
        coreInstance.config.saveConfig();
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
