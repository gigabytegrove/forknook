package dev.recipebridge.mobile;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Parcelable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SharedItem {
    enum Kind { TEXT, URL, IMAGE, VIDEO }
    final Kind kind;
    final String text;
    final Uri uri;

    SharedItem(Kind kind, String text, Uri uri) {
        this.kind = kind;
        this.text = text == null ? "" : text;
        this.uri = uri;
    }

    static SharedItem fromIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return null;
        String type = intent.getType();
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (type != null && (type.startsWith("video/") || type.startsWith("image/"))) {
            Parcelable stream;
            if (Build.VERSION.SDK_INT >= 33) stream = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
            else stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream instanceof Uri) {
                Uri uri = (Uri) stream;
                if ("content".equals(uri.getScheme())) {
                    return new SharedItem(type.startsWith("video/") ? Kind.VIDEO : Kind.IMAGE, text, uri);
                }
            }
        }
        if (text == null || text.trim().isEmpty()) return null;
        return fromText(text);
    }

    static SharedItem fromText(String text) {
        String url = firstUrl(text);
        return new SharedItem(url != null ? Kind.URL : Kind.TEXT, text, null);
    }

    static String firstUrl(String text) {
        Matcher matcher = Pattern.compile("https?://[^\\s<>\"']+", Pattern.CASE_INSENSITIVE).matcher(text);
        if (!matcher.find()) return null;
        String url = matcher.group();
        while (url.endsWith(".") || url.endsWith(",") || url.endsWith(")") || url.endsWith("!")) {
            url = url.substring(0, url.length() - 1);
        }
        try {
            Uri parsed = Uri.parse(url);
            if (parsed.getHost() == null || !("http".equals(parsed.getScheme()) || "https".equals(parsed.getScheme()))) return null;
            return url;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    static boolean isInstagram(String url) {
        if (url == null) return false;
        String host = Uri.parse(url).getHost();
        return "instagram.com".equalsIgnoreCase(host) || "www.instagram.com".equalsIgnoreCase(host);
    }
}
