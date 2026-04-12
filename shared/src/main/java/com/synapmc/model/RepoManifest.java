package com.synapmc.model;

import java.util.ArrayList;
import java.util.List;

public class RepoManifest {

    public String name;
    public String source;
    public String author;
    public List<ScriptEntry> scripts = new ArrayList<ScriptEntry>();

    public List<String> repos;
    public String discordUrl;
    public String donateUrl;
    public transient String manifestUrl;
}
