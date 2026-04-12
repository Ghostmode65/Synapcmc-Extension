package com.synapmc.library;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;


public class FSupabase {

    private static final Gson GSON = new Gson();

    private final String baseUrl;
    private final String anonKey;
    private String table;
    private final List<String> filters = new ArrayList<String>();

    FSupabase(String baseUrl, String anonKey) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.anonKey = anonKey;
    }

    public FSupabase from(String table) {
        this.table = table;
        return this;
    }

    public FSupabase eq(String column, String value) {
        filters.add(encode(column) + "=eq." + encode(value));
        return this;
    }

    public FSupabase neq(String column, String value) {
        filters.add(encode(column) + "=neq." + encode(value));
        return this;
    }


    public List<Map<String, Object>> execute() {
        String json = fetch();
        if (json == null) return Collections.emptyList();
        try {
            List<Map<String, Object>> result = GSON.fromJson(
                    json, new TypeToken<List<Map<String, Object>>>() {}.getType());
            return result != null ? result : Collections.<Map<String, Object>>emptyList();
        } catch (JsonSyntaxException e) {
            return Collections.emptyList();
        }
    }

    public Map<String, Object> single() {
        List<Map<String, Object>> rows = execute();
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String fetch() {
        if (table == null || table.isEmpty()) return null;

        StringBuilder urlStr = new StringBuilder(baseUrl)
                .append("/rest/v1/")
                .append(table);

        if (!filters.isEmpty()) {
            urlStr.append('?');
            for (int i = 0; i < filters.size(); i++) {
                if (i > 0) urlStr.append('&');
                urlStr.append(filters.get(i));
            }
        }

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr.toString()).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("apikey", anonKey);
            conn.setRequestProperty("Authorization", "Bearer " + anonKey);
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);

            int status = conn.getResponseCode();
            InputStream stream = (status >= 200 && status < 300)
                    ? conn.getInputStream()
                    : conn.getErrorStream();

            if (stream == null) return null;

            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = stream.read(chunk)) != -1) buf.write(chunk, 0, n);
            stream.close();

            if (status < 200 || status >= 300) return null;

            return buf.toString("UTF-8");
        } catch (IOException e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
