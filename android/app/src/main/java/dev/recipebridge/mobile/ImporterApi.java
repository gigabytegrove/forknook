package dev.recipebridge.mobile;

import android.content.ContentResolver;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Network code: no external dependencies, no TLS bypass. Use from background threads. */
final class ImporterApi {
    private ImporterApi() {}

    static String normalizeBase(String raw) throws IOException {
        String base = raw == null ? "" : raw.trim();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        URL url;
        try { url = new URL(base); }
        catch (Exception e) { throw new IOException("Enter a full server URL starting with http:// or https://"); }
        URI parsed;
        try { parsed = new URI(base); }
        catch (Exception e) { throw new IOException("Invalid server URL"); }
        if (!("http".equalsIgnoreCase(url.getProtocol()) || "https".equalsIgnoreCase(url.getProtocol())) || url.getHost().isEmpty() || parsed.getUserInfo() != null || url.getQuery() != null || url.getRef() != null) {
            throw new IOException("Invalid server URL");
        }
        return base;
    }

    static final class Result {
        final JSONObject json;
        Result(JSONObject json) { this.json = json; }
    }

    static Result json(String base, String route, String method, JSONObject payload, String token, int timeoutMs) throws Exception {
        HttpURLConnection connection = open(base, route, method, token, timeoutMs);
        try {
            if (payload != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] data = payload.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(data.length);
                try (OutputStream out = connection.getOutputStream()) { out.write(data); }
            }
            return finish(connection);
        } finally { connection.disconnect(); }
    }

    static Result media(String base, String route, ContentResolver resolver, Uri uri, String mime, String token, int timeoutMs, long maxBytes) throws Exception {
        String boundary = "RecipeImporter_" + UUID.randomUUID();
        HttpURLConnection connection = open(base, route, "POST", token, timeoutMs);
        try {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            connection.setChunkedStreamingMode(64 * 1024);
            try (OutputStream out = connection.getOutputStream(); InputStream stream = resolver.openInputStream(uri)) {
                if (stream == null) throw new IOException("Could not read shared media");
                String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"shared-media\"\r\nContent-Type: " + mime + "\r\n\r\n";
                out.write(head.getBytes(StandardCharsets.UTF_8));
                byte[] buffer = new byte[65536];
                long count = 0;
                int n;
                while ((n = stream.read(buffer)) != -1) {
                    count += n;
                    if (count > maxBytes) throw new IOException("Media file is too large (limit " + (maxBytes / 1024 / 1024) + " MB)");
                    out.write(buffer, 0, n);
                }
                out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            }
            return finish(connection);
        } finally { connection.disconnect(); }
    }

    private static HttpURLConnection open(String base, String route, String method, String token, int timeout) throws Exception {
        URL url = new URL(normalizeBase(base) + route);
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setInstanceFollowRedirects(false);
        con.setRequestMethod(method);
        con.setConnectTimeout(15000);
        con.setReadTimeout(timeout);
        con.setRequestProperty("Accept", "application/json");
        con.setRequestProperty("User-Agent", "RecipeImporterAndroid/1.1.0");
        
        if (token != null && !token.isEmpty()) con.setRequestProperty("Authorization", "Bearer " + token);
        return con;
    }

    private static Result finish(HttpURLConnection connection) throws Exception {
        int code = connection.getResponseCode();
        if (code >= 300 && code < 400) throw new IOException("Unexpected server redirect. Check the server URL or authentication.");
        InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String body = readLimited(stream, 2 * 1024 * 1024);
        if (code < 200 || code >= 300) {
            String detail = "Server returned HTTP " + code;
            try { detail = new JSONObject(body).optString("detail", detail); } catch (JSONException ignored) {}
            if (detail.length() > 500) detail = detail.substring(0, 500);
            throw new ApiException(code, detail);
        }
        JSONObject parsed = new JSONObject();
        if (!body.trim().isEmpty()) {
            if (body.trim().startsWith("[")) parsed.put("results", new JSONArray(body));
            else parsed = new JSONObject(body);
        }
        return new Result(parsed);
    }

    private static String readLimited(InputStream stream, int limit) throws IOException {
        if (stream == null) return "";
        try (InputStream in = new BufferedInputStream(stream); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (out.size() + n > limit) throw new IOException("Server response exceeded safe size limit");
                out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        }
    }

    static class ApiException extends IOException {
        final int statusCode;
        ApiException(int status, String detail) { super(detail); statusCode = status; }
    }
}
