package com.synapmc.model;

import java.util.List;

public class ScriptEntry {
    public String name;
    public String author;
    public String source;
    public String destination;
    public String version;
    public String description;
    public String imageurl;
    public String category;
    public boolean isExecuteReady;
    public String server;
    public String jsmacrosVersion;
    public List<String> dependencies;
    public ScriptRegister register;
    public transient String repoName;
}
