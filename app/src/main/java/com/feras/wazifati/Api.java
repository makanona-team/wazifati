package com.feras.wazifati;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** الاتصال بخادم وظيفتي على Cloudflare. */
public final class Api {
    private Api() { }

    public static String base() {
        String u = BuildConfig.API_URL;
        return u == null ? "" : u.replaceAll("/+$", "");
    }

    public static String enc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); } catch (Exception e) { return ""; }
    }

    static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[16384];
        int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    public static JSONObject get(String path) throws Exception {
        return call("GET", path, null);
    }

    public static JSONObject post(String path, JSONObject body) throws Exception {
        return call("POST", path, body);
    }

    static JSONObject call(String method, String path, JSONObject body) throws Exception {
        if (base().isEmpty()) throw new Exception("عنوان الخادم غير مضبوط في هذه النسخة");
        HttpURLConnection con = (HttpURLConnection) new URL(base() + path).openConnection();
        con.setConnectTimeout(20_000);
        con.setReadTimeout(60_000);
        con.setRequestMethod(method);
        con.setRequestProperty("User-Agent", "Wazifati-Android");
        con.setRequestProperty("Accept", "application/json");
        if (body != null) {
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream o = con.getOutputStream()) { o.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        }
        int code = con.getResponseCode();
        InputStream in = code >= 400 ? con.getErrorStream() : con.getInputStream();
        String t = in == null ? "" : readAll(in);
        JSONObject j;
        try { j = new JSONObject(t); } catch (Exception e) { throw new Exception("HTTP " + code); }
        if (code >= 400) throw new Exception(j.optString("error", "HTTP " + code));
        return j;
    }
}
