package com.synapmc;

import com.synapmc.server.BridgeServer;

import java.io.File;

public final class SynapMcShared {

    private SynapMcShared() {}
    public static ICoreAccess core;
    public static File roamingDir;
    public static BridgeServer server;
}
