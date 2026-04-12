package com.synapmc;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.function.Consumer;


public interface ICoreAccess {


    byte[] httpGet(String url) throws IOException;


    String httpGetText(String url) throws IOException;

    void exec(String lang, String script, File file, Consumer<Throwable> errHandler);
    File getMacroFolder();

    Object getProfile() throws Exception;

    boolean hasExtensionForFile(File f);

    boolean isAutoRegisterTriggers();

    int getTcpPort();

    String getTcpPortPassword();

    Map<String, String> getConfiguredRepos();

    void addConfigRepo(String name, String url);


    void removeConfigRepo(String name);

    void saveConfig();

    String getScriptTriggerClassName();
}
