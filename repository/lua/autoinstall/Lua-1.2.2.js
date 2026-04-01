const luamc = {
                    url: "https://github.com/JsMacros/JsMacros-Lua/releases/download/1.2.2/",
                    jar: "jsmacros-lua-1.2.2.jar"
}

const configFolder = JsMacros.getConfig().configFolder.getPath();

const downloadJar = (url, jar, dir) => {
    FS.makeDir(dir);
    Java.type("java.nio.file.Files").copy(
        new (Java.type("java.net.URL"))(url + jar).openStream(),
        Java.type("java.nio.file.Paths").get(dir + jar),
        Java.type("java.nio.file.StandardCopyOption").REPLACE_EXISTING
    );
    Chat.log("§dFile downloaded successfully to " + dir + jar);
};

try {
    downloadJar(luamc.url,luamc.jar,configFolder + "\\LanguageExtensions\\")
    Chat.actionbar("§dGoing to Restart game in 10 seconds, relaunch after exit");
    Client.waitTick(200);
    Client.exitGamePeacefully();
    }  catch (error) {
    Chat.log("§dError Downloading Lua");
}
