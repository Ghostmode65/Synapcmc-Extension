package xyz.wagyourtail.jsmacros.synapmc.model;

import java.util.ArrayList;
import java.util.List;

/**
 * manifestUrl is populated at runtime once the manifest has been fetched.
 */
public class RepoManifest {

    public String name;
    public String source;
    public String author;
    public List<ScriptEntry> scripts = new ArrayList<ScriptEntry>();

    // Populated at runtime, not serialised from JSON.
    public transient String manifestUrl;
}
