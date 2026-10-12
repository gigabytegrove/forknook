package dev.recipebridge.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Forknook native Android app: touch-first RMS shell + system share sheet.
 *  The web/PWA is served by the user's own Forknook server. Credentials only
 *  go to that server; KitchenOwl accounts are not required or contacted.
 */
public final class ForknookActivity extends Activity {
    private static final int PICK_FILE = 4108;
    private WebView web;
    private SharedPreferences prefs;
    private ValueCallback<Uri[]> fileCallback;
    private Uri pendingMedia;
    private String pendingText;
    private String origin = "";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("forknook", MODE_PRIVATE);
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        final LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(20, 8, 14, 8);
        TextView title = new TextView(this);
        title.setText("Forknook"); title.setTextSize(21);
        title.setTypeface(null, 1);title.setTextColor(Color.rgb(27, 67, 46));
        header.addView(title,new LinearLayout.LayoutParams(0,60,1));
        Button refresh = new Button(this);refresh.setText("Reload");
        header.addView(refresh);
        Button configure = new Button(this);configure.setText("Server");
        header.addView(configure);
        root.addView(header);
        web = new WebView(this);
        web.setBackgroundColor(Color.WHITE);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(true);
        web.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri address = request.getUrl();
                if (origin.equals(address.getScheme() + "://" + address.getAuthority())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, address)); } catch (ActivityNotFoundException ignored) {}
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                                         FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                if (pendingMedia != null) {
                    Uri source = pendingMedia;
                    pendingMedia = null;
                    fileCallback = null;
                    callback.onReceiveValue(new Uri[]{source});
                    return true;
                }
                try {
                    Intent selection = new Intent(Intent.ACTION_GET_CONTENT);
                    selection.addCategory(Intent.CATEGORY_OPENABLE);
                    selection.setType("*/*");
                    selection.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/jpeg", "image/png", "image/webp", "video/*"});
                    startActivityForResult(Intent.createChooser(selection,"Choose a recipe photo or video"), PICK_FILE);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    callback.onReceiveValue(null);
                }
                return true;
            }
        });
        root.addView(web,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
        refresh.setOnClickListener(v -> web.reload());
        configure.setOnClickListener(v -> promptServer());
        receiveShare(getIntent());
        try { loadServer(); } catch (IllegalArgumentException e) { promptServer(); }
    }

    private static String checkedOrigin(String input) {
        try {
            URI value = new URI(input.trim());
            String scheme=value.getScheme(),host=value.getHost();
            if (!("https".equals(scheme) || "http".equals(scheme)) || host==null || host.isEmpty() ||
                value.getUserInfo()!=null || value.getQuery()!=null || value.getFragment()!=null ||
                !(value.getPath().isEmpty() || value.getPath().equals("/")))
                throw new IllegalArgumentException("Enter the Forknook server base URL, without any path.");
            // HTTP is allowed only for private LAN deployments; never silently bypass TLS errors.
            if ("http".equals(scheme) && !(host.equals("localhost") || host.matches("(?:10|127|192\\.168)\\..*") ||
                    host.matches("172\\.(?:1[6-9]|2[0-9]|3[01])\\..*") || host.endsWith(".local")))
                throw new IllegalArgumentException("Use HTTPS for servers outside your private LAN.");
            return scheme + "://" + host + (value.getPort() == -1 ? "" : ":" + value.getPort());
        } catch (Exception e) { throw new IllegalArgumentException("Enter a valid Forknook server URL (https://host or private LAN http://IP:port)"); }
    }
    private void promptServer() {
        final EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        field.setText(prefs.getString("server", ""));
        field.setHint("https://forknook.example.com");
        new AlertDialog.Builder(this).setTitle("Connect to Forknook")
            .setMessage("Enter your Forknook server URL. You sign in with Forknook accounts, not KitchenOwl.")
            .setView(field).setCancelable(false)
            .setPositiveButton("Connect", (d,w) -> {
                try {
                    origin = checkedOrigin(field.getText().toString());
                    prefs.edit().putString("server", origin).apply();
                    loadServer();
                } catch (IllegalArgumentException e) {
                    new AlertDialog.Builder(this).setMessage(e.getMessage()).setPositiveButton("OK", (a,b)->promptServer()).show();
                }
            })
            .setNegativeButton("Cancel", (d,w) -> { if (origin.isEmpty()) finish(); }).show();
    }
    private void loadServer() {
        origin = checkedOrigin(prefs.getString("server", ""));
        String url = origin + "/app/";
        if (pendingText != null && !pendingText.isEmpty()) {
            url += "?share=" + Uri.encode(pendingText);
            pendingText = null;
        } else if (pendingMedia != null) url += "?shared_file=1";
        web.loadUrl(url);
    }
    private void receiveShare(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        String type=intent.getType();
        if (type != null && (type.startsWith("image/") || type.startsWith("video/"))) {
            pendingMedia = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        } else {
            CharSequence sent=intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (sent!=null)pendingText=sent.toString().trim();
        }
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);setIntent(intent);receiveShare(intent);
        if (!origin.isEmpty()) loadServer();
    }
    @Override protected void onActivityResult(int code,int result,Intent data) {
        super.onActivityResult(code,result,data);
        if (code==PICK_FILE && fileCallback!=null) {
            Uri[] chosen=result==RESULT_OK && data!=null && data.getData()!=null?new Uri[]{data.getData()}:null;
            fileCallback.onReceiveValue(chosen);fileCallback=null;
        }
    }
    @Override public void onBackPressed() { if (web!=null&&web.canGoBack())web.goBack();else super.onBackPressed(); }
    @Override protected void onDestroy() {
        if (fileCallback!=null){fileCallback.onReceiveValue(null);fileCallback=null;}
        if (web!=null) web.destroy();
        super.onDestroy();
    }
}
