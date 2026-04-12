const settings = {
    user: {
        unbindInstallerJs: true, //Unbind installer.js after install
        deleteInstallerJs: false, //Delete installer.js after install
        overwrite: true
    }
}

const synapmc = {
        url: "https://github.com/Ghostmode65/Synapcmc-Extension/releases/download/v1.0.2/",
    jar: "synapmc-1.0.2.jar"
}

//Installer
const Installer = {};
const configFolder = JsMacros.getConfig().configFolder.getPath();

Installer.downloadJar = (url, jar, dir) => {
try {
    FS.makeDir(dir);
    Java.type("java.nio.file.Files").copy(
        new (Java.type("java.net.URL"))(url + jar).openStream(),
        Java.type("java.nio.file.Paths").get(dir + jar),
        Java.type("java.nio.file.StandardCopyOption").REPLACE_EXISTING
    );
}   catch (error) {
    Chat.log("§dError Downloading" + jar);
    return false
}
    Chat.log("§dFile downloaded successfully to " + dir + jar);
    return true
};

if (settings.user.unbindInstallerJs || settings.user.deleteInstallerJs) {
    const registry = JsMacros.getProfile().getRegistry();
    const triggers = registry.getScriptTriggers();
    if (triggers) {
        const toRemove = [];
        for (const trigger of triggers) {
            if (trigger.scriptFile === "installer.js") {
                toRemove.push(trigger);
            }
        }
        for (const trigger of toRemove) {
            registry.removeScriptTrigger(trigger);
        }
    }
}

if (settings.user.deleteInstallerJs) {
    const installerFile = FS.open(configFolder + "/Macros/installer.js").getFile();
    FS.unlink(installerFile);
}

if (Installer.downloadJar(synapmc.url,synapmc.jar,configFolder + "\\LanguageExtensions\\")) {
    Chat.actionbar("§dGoing to Restart game in 10 seconds, relaunch after exit");
    Client.waitTick(200);
    Client.exitGamePeacefully();
};