package dev.recipebridge.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Android native ACTION_SEND recipient: receive > extract > edit preview > confirm > save. */
public final class MainActivity extends Activity {
    private static final int INK = Color.rgb(23, 41, 57);
    private static final int MUTED = Color.rgb(83, 104, 121);
    private static final int BLUE = Color.rgb(29, 105, 193);
    private static final int BG = Color.rgb(244, 247, 251);
    private static final int BORDER = Color.rgb(216, 226, 235);
    private static final long IMAGE_MAX = 10L * 1024 * 1024;
    private static final long VIDEO_MAX = 80L * 1024 * 1024;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Integer> householdIds = new ArrayList<>();
    private final List<String> householdNames = new ArrayList<>();
    private SharedPreferences preferences;
    private SecureStore secure;
    private SharedItem pendingShare;
    private JSONObject parsedRecipe;
    private String sourceKind = "text";
    private boolean busy = false;
    private boolean pendingAutoparse = false;

    private LinearLayout root, configPanel, previewPanel;
    private ScrollView scroll;
    private EditText serverInput, loginInput, passwordInput, videoServerInput, videoTokenInput, textInput;
    private EditText titleEdit, descEdit, ingredientsEdit, stepsEdit, sourceEdit, servesEdit, prepEdit, cookEdit, totalEdit, notesEdit;
    private Spinner householdSpinner;
    private TextView status, previewInfo, sharedInfo;
    private Button parseButton, saveButton, settingsButton;
    private ProgressBar progress;
    private String cookie = "";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("recipe_bridge_config", MODE_PRIVATE);
        secure = new SecureStore(this);
        cookie = secure.get("pellet_cookie");
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        buildUi();
        pendingShare = SharedItem.fromIntent(getIntent());
        if (pendingShare != null) setIncoming(pendingShare, true);
        if (hasLogin()) reloadHouseholds();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        SharedItem incoming = SharedItem.fromIntent(intent);
        if (incoming == null) return;
        if (parsedRecipe != null) {
            new AlertDialog.Builder(this).setTitle("Replace current preview?")
                .setMessage("Unsaved recipe changes will be discarded.")
                .setNegativeButton("Keep current", null)
                .setPositiveButton("Replace", (dialog, which) -> setIncoming(incoming, true)).show();
        } else setIncoming(incoming, true);
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private int dp(float value) { return Math.round(getResources().getDisplayMetrics().density * value); }

    private GradientDrawable background(int color, int radius, int outline) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        if (outline != 0) shape.setStroke(dp(1), outline);
        return shape;
    }

    private LinearLayout column() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        return box;
    }

    private void space(LinearLayout parent, int points) {
        View spacer = new View(this);
        parent.addView(spacer, new LinearLayout.LayoutParams(1, dp(points)));
    }

    private void add(LinearLayout parent, View view) {
        parent.addView(view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private TextView label(String text, int size, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setFontFeatureSettings("kern");
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private void sectionLabel(LinearLayout parent, String value) {
        space(parent, 9);
        add(parent, label(value, 13, true, MUTED));
        space(parent, 4);
    }

    private EditText input(String hint, boolean multiline) {
        EditText field = new EditText(this);
        field.setTextColor(INK);
        field.setHintTextColor(MUTED);
        field.setTextSize(15);
        field.setHint(hint);
        field.setSingleLine(!multiline);
        if (multiline) {
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            field.setMinLines(3);
            field.setGravity(Gravity.TOP | Gravity.START);
        } else field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setPadding(dp(12), dp(12), dp(12), dp(12));
        field.setBackground(background(Color.WHITE, 12, BORDER));
        return field;
    }

    private Button button(String text, boolean filled) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextSize(14);
        b.setTextColor(filled ? Color.WHITE : BLUE);
        b.setBackgroundTintList(null);
        b.setBackground(background(filled ? BLUE : Color.WHITE, 12, filled ? 0 : BORDER));
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        b.setMinHeight(dp(48));
        return b;
    }

    private LinearLayout card() {
        LinearLayout body = column();
        body.setPadding(dp(17), dp(17), dp(17), dp(18));
        body.setBackground(background(Color.WHITE, 17, BORDER));
        return body;
    }

    private void cardToRoot(LinearLayout view) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(14);
        root.addView(view, p);
    }

    private String pref(String key) { return preferences.getString(key, ""); }

    private void buildUi() {
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        root = column();
        root.setPadding(dp(17), dp(27), dp(17), dp(32));
        scroll.addView(root);
        setContentView(scroll);

        add(root, label("RECIPE BRIDGE", 12, true, BLUE));
        space(root, 7);
        add(root, label("Save recipes in a snap.", 25, true, INK));
        space(root, 6);
        add(root, label("Share a recipe, check the details, then choose what gets saved to KitchenOwl.", 14, false, MUTED));
        space(root, 13);
        settingsButton = button("Settings & connection  ⚙", false);
        add(root, settingsButton);
        settingsButton.setOnClickListener(v -> {
            configPanel.setVisibility(configPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        });
        space(root, 9);

        configPanel = card();
        cardToRoot(configPanel);
        add(configPanel, label("KO-Pellet connection", 20, true, INK));
        sectionLabel(configPanel, "KO-Pellet server URL");
        serverInput = input("https://pellet.your-domain.com", false);
        serverInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        serverInput.setText(pref("pellet_url"));
        add(configPanel, serverInput);
        sectionLabel(configPanel, "Username");
        loginInput = input("KitchenOwl username", false);
        loginInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL);
        loginInput.setText(pref("username"));
        add(configPanel, loginInput);
        sectionLabel(configPanel, "Password");
        passwordInput = input("KitchenOwl password", false);
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        add(configPanel, passwordInput);
        space(configPanel, 10);
        Button loginButton = button("Connect with KitchenOwl login", true);
        add(configPanel, loginButton);
        loginButton.setOnClickListener(v -> login());
        space(configPanel, 8);
        Button webLogin = button("Sign in using KO-Pellet web page", false);
        add(configPanel, webLogin);
        webLogin.setOnClickListener(v -> browserLogin());
        space(configPanel, 7);
        Button logout = button("Disconnect this phone", false);
        add(configPanel, logout);
        logout.setOnClickListener(v -> logout());
        space(configPanel, 12);
        add(configPanel, label("Optional: Instagram and video extraction", 17, true, INK));
        space(configPanel, 4);
        add(configPanel, label("Install the included Docker extractor on your network to read video speech and on-screen text. This is not required for regular recipe links, images, or pasted text.", 12, false, MUTED));
        sectionLabel(configPanel, "Video extractor URL");
        videoServerInput = input("http://your-server:8999", false);
        videoServerInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        videoServerInput.setText(pref("video_url"));
        add(configPanel, videoServerInput);
        sectionLabel(configPanel, "Video extractor token");
        videoTokenInput = input("Token from Docker .env", false);
        videoTokenInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        videoTokenInput.setText(secure.get("video_token"));
        add(configPanel, videoTokenInput);
        space(configPanel, 8);
        Button saveSettings = button("Save connection settings", false);
        add(configPanel, saveSettings);
        saveSettings.setOnClickListener(v -> saveSettings());
        space(configPanel, 8);
        add(configPanel, label("Privacy: HTTPS is recommended. Plain HTTP is supported for an isolated trusted LAN but sends credentials without transport encryption.", 12, false, MUTED));

        LinearLayout statusCard = card();
        cardToRoot(statusCard);
        status = label(hasLogin() ? "Checking KitchenOwl connection…" : "Not connected. Open Settings to sign in.", 13, false, MUTED);
        add(statusCard, status);
        space(statusCard, 8);
        add(statusCard, label("Save to household", 13, true, INK));
        space(statusCard, 5);
        householdSpinner = new Spinner(this);
        add(statusCard, householdSpinner);
        updateHouseholds();
        Button refresh = button("Refresh households", false);
        add(statusCard, refresh);
        refresh.setOnClickListener(v -> reloadHouseholds());

        LinearLayout importCard = card();
        cardToRoot(importCard);
        add(importCard, label("Import a recipe", 20, true, INK));
        space(importCard, 5);
        add(importCard, label("Use Share in Instagram, your browser or Gallery. You can also paste text or a link below.", 13, false, MUTED));
        space(importCard, 12);
        sharedInfo = label("No item shared yet", 13, true, BLUE);
        add(importCard, sharedInfo);
        space(importCard, 9);
        textInput = input("Paste a recipe URL, Reel link, or recipe text...", true);
        add(importCard, textInput);
        space(importCard, 12);
        parseButton = button("Extract recipe & show preview", true);
        add(importCard, parseButton);
        parseButton.setOnClickListener(v -> {
            SharedItem item = pendingShare;
            if (item == null || (item.kind != SharedItem.Kind.IMAGE && item.kind != SharedItem.Kind.VIDEO)) item = SharedItem.fromText(textInput.getText().toString());
            parse(item);
        });
        space(importCard, 9);
        Button clear = button("Clear shared item", false);
        add(importCard, clear);
        clear.setOnClickListener(v -> {
            pendingShare = null;
            parsedRecipe = null;
            textInput.setText("");
            previewPanel.setVisibility(View.GONE);
            sharedInfo.setText("No item shared yet");
        });

        LinearLayout busyCard = card();
        cardToRoot(busyCard);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        add(busyCard, progress);
        progress.setVisibility(View.GONE);
        previewInfo = label("Ready", 13, false, MUTED);
        space(busyCard, 7);
        add(busyCard, previewInfo);

        previewPanel = card();
        cardToRoot(previewPanel);
        add(previewPanel, label("Recipe preview", 21, true, INK));
        space(previewPanel, 5);
        add(previewPanel, label("Nothing is saved until you tap the final button. Every field can be edited.", 13, false, MUTED));
        titleEdit = field(previewPanel, "Recipe name", "Recipe name", false);
        descEdit = field(previewPanel, "Description", "Short description (optional)", true);
        servesEdit = field(previewPanel, "Servings", "4 servings", false);
        prepEdit = field(previewPanel, "Prep time (minutes)", "15", false);
        cookEdit = field(previewPanel, "Cook time (minutes)", "30", false);
        totalEdit = field(previewPanel, "Total time (minutes)", "45", false);
        ingredientsEdit = field(previewPanel, "Ingredients — one per line", "2 cups flour\n1 tsp salt", true);
        ingredientsEdit.setMinLines(5);
        stepsEdit = field(previewPanel, "Directions — one step per line", "Preheat oven...\nMix ingredients...", true);
        stepsEdit.setMinLines(5);
        notesEdit = field(previewPanel, "Notes", "Personal notes (optional)", true);
        sourceEdit = field(previewPanel, "Source URL", "https://...", false);
        sourceEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        space(previewPanel, 15);
        saveButton = button("Add to KitchenOwl ✓", true);
        add(previewPanel, saveButton);
        saveButton.setOnClickListener(v -> prepareSave());
        previewPanel.setVisibility(View.GONE);
        configPanel.setVisibility(hasLogin() ? View.GONE : View.VISIBLE);
    }

    private EditText field(LinearLayout parent, String name, String hint, boolean multiline) {
        sectionLabel(parent, name);
        EditText edit = input(hint, multiline);
        add(parent, edit);
        return edit;
    }

    private void updateHouseholds() {
        List<String> names = new ArrayList<>(householdNames);
        if (names.isEmpty()) names.add("Connect to load households");
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        householdSpinner.setAdapter(adapter);
        int last = preferences.getInt("household", -1);
        int idx = householdIds.indexOf(last);
        if (idx >= 0) householdSpinner.setSelection(idx);
    }

    private boolean hasLogin() { return !cookie.isEmpty() && !pref("pellet_url").isEmpty(); }
    private String server() { return pref("pellet_url"); }
    private void setStatus(String value) { status.setText(value); }
    private void info(String value) { previewInfo.setText(value); }
    private void warn(String value) { new AlertDialog.Builder(this).setTitle("Recipe Bridge").setMessage(value).setPositiveButton("OK", null).show(); }

    private void setBusy(boolean working, String message) {
        busy = working;
        progress.setVisibility(working ? View.VISIBLE : View.GONE);
        parseButton.setEnabled(!working);
        saveButton.setEnabled(!working);
        if (message != null) info(message);
    }

    private interface Work { void run() throws Exception; }
    private void job(String description, Work work) {
        if (busy) return;
        setBusy(true, description);
        worker.execute(() -> {
            try { work.run(); }
            catch (Exception e) {
                String message = e.getMessage();
                if (message == null || message.trim().isEmpty()) message = e.getClass().getSimpleName();
                final String failure = message;
                runOnUiThread(() -> {
                    if (e instanceof PelletApi.ApiException && ((PelletApi.ApiException) e).statusCode == 401) setStatus("Session expired. Reconnect in Settings.");
                    info("Import failed: " + failure);
                    warn(failure);
                });
            } finally { runOnUiThread(() -> {
                setBusy(false, null);
                if (pendingAutoparse && pendingShare != null && hasLogin() && !householdIds.isEmpty()) {
                    pendingAutoparse = false;
                    scroll.post(() -> parse(pendingShare));
                }
            }); }
        });
    }

    private void storeCookie(String next) throws Exception {
        secure.put("pellet_cookie", next);
        cookie = next;
    }

    private void saveSettings() {
        try {
            String base = PelletApi.normalizeBase(serverInput.getText().toString());
            String video = videoServerInput.getText().toString().trim();
            if (!video.isEmpty()) video = PelletApi.normalizeBase(video);
            String old = server();
            if (!old.isEmpty() && !old.equals(base)) {
                storeCookie("");
                householdIds.clear(); householdNames.clear(); updateHouseholds();
            }
            preferences.edit().putString("pellet_url", base).putString("video_url", video)
                    .putString("username", loginInput.getText().toString().trim()).apply();
            secure.put("video_token", videoTokenInput.getText().toString().trim());
            setStatus(hasLogin() ? "Settings saved" : "Server saved. Sign in to continue.");
            info("Settings saved");
        } catch (Exception e) { warn(e.getMessage()); }
    }

    private void login() {
        saveSettings();
        final String user = loginInput.getText().toString().trim();
        final String pass = passwordInput.getText().toString();
        if (server().isEmpty() || user.isEmpty() || pass.isEmpty()) { warn("Enter a server URL, username, and password."); return; }
        job("Signing in to KO-Pellet…", () -> {
            JSONObject request = new JSONObject().put("username", user).put("password", pass);
            PelletApi.Result result = PelletApi.json(server(), "/api/auth/kitchenowl", "POST", request, "", "", 30000);
            if (!result.json.optBoolean("success") || result.cookie == null) throw new Exception("Login did not return a KO-Pellet session. Confirm that native KitchenOwl login is enabled.");
            storeCookie(result.cookie);
            runOnUiThread(() -> {
                passwordInput.setText("");
                setStatus("Signed in. Loading KitchenOwl households…");
                configPanel.setVisibility(View.GONE);
            });
            loadHouseholdsInternal();
        });
    }

    private void browserLogin() {
        saveSettings();
        if (server().isEmpty()) { warn("Enter the KO-Pellet server URL first."); return; }
        WebView web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true); // KO-Pellet's login page needs JS.
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptCookie(true);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Sign in to KO-Pellet")
            .setView(web).setNegativeButton("Cancel", (d, w) -> web.destroy()).create();
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                String current = CookieManager.getInstance().getCookie(server());
                if (current == null || !current.contains("ko_pellet_auth=")) return;
                // Only a KO-Pellet origin cookie may be persisted; not IdP origin cookies.
                job("Verifying browser login…", () -> {
                    try {
                        JSONObject check = PelletApi.json(server(), "/api/auth/status", "GET", null, current, "", 30000).json;
                        if (!check.optBoolean("authenticated")) return;
                        storeCookie(current);
                        runOnUiThread(() -> {
                            dialog.dismiss(); web.destroy();
                            setStatus("Signed in using KO-Pellet");
                            configPanel.setVisibility(View.GONE);
                        });
                        loadHouseholdsInternal();
                    } catch (Exception ignored) { /* The user may still be finishing an IdP redirect. */ }
                });
            }
        });
        dialog.show();
        web.loadUrl(server());
    }

    private void logout() {
        if (hasLogin()) job("Disconnecting…", () -> {
            try { PelletApi.json(server(), "/api/auth/logout", "POST", new JSONObject(), cookie, "", 30000); }
            catch (Exception ignored) { /* Always erase device credentials locally. */ }
            storeCookie("");
            runOnUiThread(() -> {
                householdIds.clear(); householdNames.clear(); updateHouseholds();
                setStatus("Disconnected"); configPanel.setVisibility(View.VISIBLE);
                info("Session removed from this phone");
            });
        });
    }

    private void reloadHouseholds() {
        if (!hasLogin()) { setStatus("Not connected. Sign in under Settings."); configPanel.setVisibility(View.VISIBLE); return; }
        job("Loading KitchenOwl households…", this::loadHouseholdsInternal);
    }

    private void loadHouseholdsInternal() throws Exception {
        PelletApi.Result result = PelletApi.json(server(), "/api/kitchenowl/households", "GET", null, cookie, "", 30000);
        JSONArray arr = result.json.optJSONArray("results");
        if (arr == null) throw new Exception("KitchenOwl did not return a household list");
        List<Integer> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.optJSONObject(i);
            if (obj == null || !obj.has("id")) continue;
            ids.add(obj.getInt("id"));
            names.add(obj.optString("name", "Household " + obj.getInt("id")));
        }
        runOnUiThread(() -> {
            householdIds.clear(); householdIds.addAll(ids);
            householdNames.clear(); householdNames.addAll(names);
            updateHouseholds();
            setStatus(ids.isEmpty() ? "Connected, but no households available" : "Connected • " + ids.size() + " household" + (ids.size() == 1 ? "" : "s"));
            if (pendingAutoparse && pendingShare != null) {
                pendingAutoparse = false;
                scroll.post(() -> parse(pendingShare));
            }
        });
    }

    private void setIncoming(SharedItem item, boolean autoParse) {
        pendingShare = item;
        parsedRecipe = null;
        previewPanel.setVisibility(View.GONE);
        if (item.kind == SharedItem.Kind.IMAGE || item.kind == SharedItem.Kind.VIDEO) {
            sharedInfo.setText(item.kind == SharedItem.Kind.IMAGE ? "Image shared from another app" : "Video shared from another app");
            textInput.setText(item.text);
        } else {
            sharedInfo.setText(item.kind == SharedItem.Kind.URL ? "Link received from another app" : "Recipe text received from another app");
            textInput.setText(item.text);
        }
        if (autoParse) {
            if (hasLogin() && !householdIds.isEmpty() && !busy) parse(item);
            else pendingAutoparse = true;
        }
    }

    private void parse(SharedItem item) {
        if (busy) { info("Another operation is in progress"); return; }
        if (!hasLogin()) { warn("Sign in to KO-Pellet first."); configPanel.setVisibility(View.VISIBLE); return; }
        if (item == null || (item.kind == SharedItem.Kind.TEXT && item.text.trim().isEmpty())) { warn("Share an item or paste a link or recipe text."); return; }
        String videoBase = pref("video_url");
        String videoToken = secure.get("video_token");
        job("Extracting recipe…", () -> {
            JSONObject parsed;
            String source = "";
            String category;
            if (item.kind == SharedItem.Kind.IMAGE) {
                category = "image";
                parsed = PelletApi.media(server(), "/api/parse/image", getContentResolver(), item.uri,
                        mimeOf(item.uri, "image/jpeg"), cookie, "", 120000, IMAGE_MAX).json;
            } else if (item.kind == SharedItem.Kind.VIDEO || (item.kind == SharedItem.Kind.URL && SharedItem.isInstagram(SharedItem.firstUrl(item.text)))) {
                if (videoBase.isEmpty() || videoToken.isEmpty())
                    throw new Exception("Instagram/video import needs the included Docker extractor. Configure its URL and token in Settings. You can also paste the recipe caption as text.");
                JSONObject extracted;
                if (item.kind == SharedItem.Kind.VIDEO) {
                    extracted = PelletApi.media(videoBase, "/extract/video", getContentResolver(), item.uri,
                            mimeOf(item.uri, "video/mp4"), "", videoToken, 900000, VIDEO_MAX).json;
                } else {
                    source = SharedItem.firstUrl(item.text);
                    extracted = PelletApi.json(videoBase, "/extract/instagram", "POST",
                            new JSONObject().put("url", source), "", videoToken, 900000).json;
                }
                String captured = extracted.optString("text", "");
                if (captured.trim().isEmpty()) throw new Exception("No usable recipe text was extracted from this video.");
                if (captured.length() > 47000) captured = captured.substring(0, 47000);
                parsed = PelletApi.json(server(), "/api/parse/text", "POST",
                        new JSONObject().put("text", captured), cookie, "", 120000).json;
                category = item.kind == SharedItem.Kind.VIDEO ? "video" : "instagram";
            } else if (item.kind == SharedItem.Kind.URL) {
                category = "url";
                source = SharedItem.firstUrl(item.text);
                parsed = PelletApi.json(server(), "/api/parse/url", "POST",
                        new JSONObject().put("url", source), cookie, "", 90000).json;
            } else {
                category = "text";
                parsed = PelletApi.json(server(), "/api/parse/text", "POST",
                        new JSONObject().put("text", item.text), cookie, "", 120000).json;
            }
            if (!source.isEmpty() && parsed.optString("source_url").isEmpty()) parsed.put("source_url", source);
            parsed.put("source_type", category.equals("instagram") || category.equals("video") ? "text" : category);
            final JSONObject complete = parsed;
            runOnUiThread(() -> showPreview(complete, category));
        });
    }

    private String mimeOf(Uri uri, String fallback) {
        String type = getContentResolver().getType(uri);
        if (type != null && (type.startsWith("image/") || type.startsWith("video/"))) return type;
        return fallback;
    }

    private String prettyIngredient(JSONObject ingredient) {
        List<String> parts = new ArrayList<>();
        String quantity = ingredient.optString("quantity", "");
        String unit = ingredient.optString("unit", "");
        String name = ingredient.optString("name", "");
        String note = ingredient.optString("note", "");
        if (!quantity.isEmpty()) parts.add(quantity);
        if (!unit.isEmpty()) parts.add(unit);
        if (!name.isEmpty()) parts.add(name);
        if (!note.isEmpty()) parts.add("(" + note + ")");
        return String.join(" ", parts);
    }

    private void showPreview(JSONObject json, String category) {
        parsedRecipe = json;
        sourceKind = category;
        titleEdit.setText(json.optString("title", ""));
        descEdit.setText(json.optString("description", ""));
        servesEdit.setText(json.optString("servings", ""));
        prepEdit.setText(json.isNull("prep_time") ? "" : json.optString("prep_time", ""));
        cookEdit.setText(json.isNull("cook_time") ? "" : json.optString("cook_time", ""));
        totalEdit.setText(json.isNull("total_time") ? "" : json.optString("total_time", ""));
        notesEdit.setText(json.optString("notes", ""));
        sourceEdit.setText(json.optString("source_url", ""));
        JSONArray ingredients = json.optJSONArray("ingredients");
        List<String> ingredientLines = new ArrayList<>();
        if (ingredients != null) for (int i = 0; i < ingredients.length(); i++) {
            JSONObject ing = ingredients.optJSONObject(i);
            if (ing != null) ingredientLines.add(prettyIngredient(ing));
        }
        ingredientsEdit.setText(String.join("\n", ingredientLines));
        JSONArray instructions = json.optJSONArray("instructions");
        List<String> stepLines = new ArrayList<>();
        if (instructions != null) for (int i = 0; i < instructions.length(); i++) {
            String step = instructions.optString(i).trim();
            if (!step.isEmpty()) stepLines.add(step.replace('\n', ' '));
        }
        stepsEdit.setText(String.join("\n", stepLines));
        String confidence = json.optString("confidence", "low").toUpperCase(Locale.US);
        String warning = ("instagram".equals(category) || "video".equals(category))
                ? "Video extraction is approximate. Carefully verify quantities and cooking instructions."
                : ("LOW".equals(confidence) ? "This parser marked the result low-confidence; carefully review the fields." : "Review the fields before saving.");
        info("Preview ready • " + confidence + " confidence. " + warning);
        previewPanel.setVisibility(View.VISIBLE);
        scroll.post(() -> scroll.smoothScrollTo(0, previewPanel.getTop()));
    }

    private static List<String> lines(String value) {
        List<String> result = new ArrayList<>();
        for (String line : value.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    private static Integer positiveInt(String value, String field) throws Exception {
        if (value.trim().isEmpty()) return null;
        try {
            int number = Integer.parseInt(value.trim());
            if (number < 0 || number > 100000) throw new NumberFormatException("out of range");
            return number;
        } catch (NumberFormatException e) { throw new Exception(field + " must be a number of minutes between 0 and 100000"); }
    }

    private JSONObject recipeToSave() throws Exception {
        String title = titleEdit.getText().toString().trim();
        if (title.isEmpty() || title.length() > 300 || (title.equalsIgnoreCase("Untitled Recipe") || title.equalsIgnoreCase("Review this video recipe"))) throw new Exception("Enter a descriptive recipe name (up to 300 characters)");
        List<String> ingredients = lines(ingredientsEdit.getText().toString());
        List<String> steps = lines(stepsEdit.getText().toString());
        if (ingredients.isEmpty() && steps.isEmpty()) throw new Exception("Enter at least one ingredient or one instruction before saving");
        JSONObject result = new JSONObject();
        result.put("title", title);
        result.put("description", descEdit.getText().toString().trim());
        result.put("servings", servesEdit.getText().toString().trim());
        result.put("notes", notesEdit.getText().toString().trim());
        String source = sourceEdit.getText().toString().trim();
        if (!source.isEmpty()) {
            String parsedUrl = SharedItem.firstUrl(source);
            if (parsedUrl == null || !parsedUrl.equals(source)) throw new Exception("Source must be a valid http or https URL");
            result.put("source_url", source);
        }
        Integer prep = positiveInt(prepEdit.getText().toString(), "Prep time");
        Integer cook = positiveInt(cookEdit.getText().toString(), "Cook time");
        Integer total = positiveInt(totalEdit.getText().toString(), "Total time");
        if (prep != null) result.put("prep_time", prep);
        if (cook != null) result.put("cook_time", cook);
        if (total != null) result.put("total_time", total);
        else if (prep != null || cook != null) result.put("total_time", (prep == null ? 0 : prep) + (cook == null ? 0 : cook));
        JSONArray originals = parsedRecipe == null ? null : parsedRecipe.optJSONArray("ingredients");
        JSONArray ingArray = new JSONArray();
        for (int i = 0; i < ingredients.size(); i++) {
            String line = ingredients.get(i);
            JSONObject existing = originals != null ? originals.optJSONObject(i) : null;
            // Preserve exact parser structure for unchanged rows; let the user edit freely.
            JSONObject ing = existing != null && prettyIngredient(existing).equals(line)
                    && !(existing.optString("quantity", "").isEmpty() && line.matches("^[0-9¼½¾⅓⅔⅛⅜⅝⅞].*"))
                ? new JSONObject(existing.toString()) : IngredientCodec.parse(line);
            ingArray.put(ing);
        }
        result.put("ingredients", ingArray);
        JSONArray instructionArray = new JSONArray();
        for (String step : steps) instructionArray.put(step);
        result.put("instructions", instructionArray);
        result.put("tags", parsedRecipe == null ? new JSONArray() : parsedRecipe.optJSONArray("tags") == null ? new JSONArray() : parsedRecipe.getJSONArray("tags"));
        if (parsedRecipe != null && !parsedRecipe.isNull("image_url") && !parsedRecipe.optString("image_url").isEmpty()) result.put("image_url", parsedRecipe.getString("image_url"));
        result.put("confidence", parsedRecipe == null ? "low" : parsedRecipe.optString("confidence", "low"));
        result.put("source_type", sourceKind.equals("video") || sourceKind.equals("instagram") ? "text" : sourceKind);
        return result;
    }

    private void prepareSave() {
        if (householdIds.isEmpty() || householdSpinner.getSelectedItemPosition() < 0) { warn("Choose a KitchenOwl household before saving."); return; }
        final JSONObject recipe;
        try { recipe = recipeToSave(); }
        catch (Exception e) { warn(e.getMessage()); return; }
        int householdId = householdIds.get(householdSpinner.getSelectedItemPosition());
        preferences.edit().putInt("household", householdId).apply();
        String title = recipe.optString("title", "");
        job("Checking for duplicate recipes…", () -> {
            String encoded = java.net.URLEncoder.encode(title, "UTF-8");
            JSONObject duplicates = PelletApi.json(server(), "/api/kitchenowl/check-duplicate/" + householdId + "?title=" + encoded,
                    "GET", null, cookie, "", 30000).json;
            runOnUiThread(() -> {
                if (duplicates.optBoolean("has_duplicate")) {
                    new AlertDialog.Builder(this)
                        .setTitle("Possible duplicate")
                        .setMessage("A recipe with a similar name already exists. Add another copy anyway?")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Add anyway", (d, which) -> saveRecipe(householdId, recipe)).show();
                } else scroll.post(() -> saveRecipe(householdId, recipe));
            });
        });
    }

    private void saveRecipe(int householdId, JSONObject recipe) {
        job("Saving recipe to KitchenOwl…", () -> {
            JSONObject payload = new JSONObject().put("recipe", recipe);
            PelletApi.json(server(), "/api/kitchenowl/recipe/" + householdId, "POST", payload, cookie, "", 60000);
            runOnUiThread(() -> {
                parsedRecipe = null;
                pendingShare = null;
                previewPanel.setVisibility(View.GONE);
                sharedInfo.setText("Saved successfully. Share another recipe to start again.");
                textInput.setText("");
                info("✓ Recipe added to KitchenOwl successfully");
                new AlertDialog.Builder(this).setTitle("Saved to KitchenOwl")
                    .setMessage("Your recipe is now in the selected household.")
                    .setPositiveButton("Done", null).show();
            });
        });
    }
}
