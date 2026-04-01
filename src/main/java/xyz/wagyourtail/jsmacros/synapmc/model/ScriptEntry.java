package xyz.wagyourtail.jsmacros.synapmc.model;


public class ScriptEntry {

    public String name;
    public String author;
    public String source;
    public String destination;
    public String version;
    public String description;
    public String imageurl;

    /** Will be changed to a category based system. */
    public boolean isApi;

    public boolean isExecuteReady;
    public String server;
    /** Not reliable enough to enable by default yet */
    public ScriptRegister register;
    public transient String repoName;
}
