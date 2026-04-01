package xyz.wagyourtail.jsmacros.synapmc.model;

/**
 * how should be a script be registered as a JsMacros trigger.
 */
public class ScriptRegister {

    /** "keydown", "keyup", "event", "service" */
    public String type;

    /** Key code used when type is keydown or keyup. */
    public String key;

    /** Event name used when type is event. unless key and service */
    public String event;
}
