package com.synapmc.library;

public class FPrivate {

    public FSupabase supabase(String supabaseUrl, String anonKey) {
        return new FSupabase(supabaseUrl, anonKey);
    }
}
