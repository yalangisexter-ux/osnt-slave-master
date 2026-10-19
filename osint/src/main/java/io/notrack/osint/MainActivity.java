package io.notrack.osint;

import android.content.Intent;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import android.os.Bundle;
import android.provider.Settings;
import android.app.AlertDialog;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private EditText inPhone, inEmail, inUser, inDomain, inIp, inUrl;
    private Spinner engine, mode;
    private WebView web;
    private TextView status;
    private static final String PREFS = "osint_memory";
    private static final String HISTORY = "search_history";

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_main);
        inPhone = findViewById(R.id.in_phone); inEmail = findViewById(R.id.in_email);
        inUser = findViewById(R.id.in_user); inDomain = findViewById(R.id.in_domain);
        inIp = findViewById(R.id.in_ip); inUrl = findViewById(R.id.in_url);
        engine = findViewById(R.id.engine); mode = findViewById(R.id.mode);
        web = findViewById(R.id.web); status = findViewById(R.id.status);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return route(r.getUrl()); }
            @Override public boolean shouldOverrideUrlLoading(WebView v, String u) { return route(Uri.parse(u)); }
        });
        web.setWebChromeClient(new WebChromeClient());
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.loadUrl("https://www.google.com");
        findViewById(R.id.btn_update).setOnClickListener(v -> checkForUpdates());
        findViewById(R.id.btn_search).setOnClickListener(v -> doSearch());
        findViewById(R.id.btn_back).setOnClickListener(v -> {
            if (web.canGoBack()) web.goBack();
            else status.setText("No previous page");
        });
        findViewById(R.id.btn_clear).setOnClickListener(v -> {
            inPhone.setText(""); inEmail.setText(""); inUser.setText("");
            inDomain.setText(""); inIp.setText(""); inUrl.setText("");
            status.setText("Search fields cleared");
        });
        findViewById(R.id.btn_web).setOnClickListener(v -> browse("https://www.google.com"));
        findViewById(R.id.btn_dark).setOnClickListener(v -> openTorBrowser("https://check.torproject.org/"));
        findViewById(R.id.btn_tor).setOnClickListener(v -> openTorBrowser("https://www.torproject.org/download/"));
        findViewById(R.id.btn_vpn).setOnClickListener(v -> openVpnSettings());
        findViewById(R.id.btn_mem).setOnClickListener(v -> showMemory());
    }


    private void openTorBrowser(String url) {
        try {
            Intent tor = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            tor.setPackage("org.torproject.torbrowser");
            startActivity(tor);
            status.setText("Opened in Tor Browser. Confirm Tor is connected before entering sensitive information.");
        } catch (Exception e) {
            try {
                Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.torproject.org/download/"));
                startActivity(browser);
                status.setText("Tor Browser is not detected. Install it, connect to Tor, then retry.");
            } catch (Exception ignored) {
                status.setText("No browser available. Install Tor Browser from the official Tor Project.");
            }
        }
    }

    private boolean isVpnActive() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            for (Network network : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    private void checkForUpdates() {
        status.setText("Checking latest app release…");
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                URL endpoint = new URL("https://api.github.com/repos/yalangisexter-ux/osnt-slave-master/releases/latest");
                connection = (HttpURLConnection) endpoint.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                int code = connection.getResponseCode();
                if (code != 200) throw new Exception("GitHub returned HTTP " + code);
                java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(connection.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder body = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
                reader.close();
                JSONObject release = new JSONObject(body.toString());
                String tag = release.optString("tag_name", "Latest release");
                String page = release.optString("html_url", "https://github.com/yalangisexter-ux/osnt-slave-master/releases/latest");
                String assetUrl = "";
                org.json.JSONArray assets = release.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.optJSONObject(i);
                        if (asset == null) continue;
                        String name = asset.optString("name", "");
                        if (name.toLowerCase(java.util.Locale.ROOT).contains("osint") && name.toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) {
                            assetUrl = asset.optString("browser_download_url", "");
                            break;
                        }
                    }
                }
                final String finalPage = page;
                final String finalAsset = assetUrl;
                new Handler(Looper.getMainLooper()).post(() -> new AlertDialog.Builder(this)
                    .setTitle("Latest release: " + tag)
                    .setMessage(finalAsset.isEmpty()
                        ? "A release was found, but no OSINT APK asset was detected. Open release page to review available downloads."
                        : "A release was found. Open the APK download in your browser; Android may ask you to allow installation from that source.")
                    .setPositiveButton(finalAsset.isEmpty() ? "Open release" : "Download APK", (d, w) -> openExternal(finalAsset.isEmpty() ? finalPage : finalAsset, "Opened release download"))
                    .setNegativeButton("Cancel", null).show());
                new Handler(Looper.getMainLooper()).post(() -> status.setText("Latest release checked: " + tag));
            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    status.setText("Update check failed. Check internet and try again.");
                    new AlertDialog.Builder(this).setTitle("Could not check updates")
                        .setMessage("Unable to reach GitHub releases. You can still open the release page.")
                        .setPositiveButton("Open releases", (d, w) -> openExternal("https://github.com/yalangisexter-ux/osnt-slave-master/releases/latest", "Opened releases"))
                        .setNegativeButton("Close", null).show();
                });
            } finally {
                if (connection != null) connection.disconnect();
            }
        }).start();
    }

    private boolean route(Uri uri) {
        String host = uri.getHost();
        if (host != null && (host.equalsIgnoreCase("accounts.google.com") ||
            host.equalsIgnoreCase("myaccount.google.com") || host.equalsIgnoreCase("consent.google.com"))) {
            openExternal(uri.toString(), "Opened Google sign-in in external browser");
            return true;
        }
        return false;
    }

    private String query() {
        EditText[] fields = {inPhone, inEmail, inUser, inDomain, inIp, inUrl};
        StringBuilder out = new StringBuilder();
        for (EditText f : fields) {
            String value = f.getText().toString().trim();
            if (!value.isEmpty()) { if (out.length() > 0) out.append(' '); out.append(value); }
        }
        return out.toString();
    }

    private void doSearch() {
        String q = query();
        if (q.isEmpty()) { Toast.makeText(this, "Enter at least one search term", Toast.LENGTH_SHORT).show(); return; }
        String selectedEngine = String.valueOf(engine.getSelectedItem());
        String selectedMode = String.valueOf(mode.getSelectedItem());
        String encoded = enc(q);
        String url;
        switch (selectedEngine) {
            case "Bing": url = "https://www.bing.com/search?q=" + encoded; break;
            case "DuckDuckGo": url = "https://duckduckgo.com/?q=" + encoded; break;
            case "Yahoo": url = "https://search.yahoo.com/search?p=" + encoded; break;
            case "Startpage": url = "https://www.startpage.com/sp/search?query=" + encoded; break;
            case "Brave": url = "https://search.brave.com/search?q=" + encoded; break;
            case "Ecosia": url = "https://www.ecosia.org/search?q=" + encoded; break;
            case "Searx": url = "https://searx.be/search?q=" + encoded; break;
            case "HackerNews": url = "https://hn.algolia.com/?q=" + encoded; break;
            case "GitHub": url = "https://github.com/search?q=" + encoded; break;
            case "HIBP": url = "https://haveibeenpwned.com/"; break;
            default: url = "https://www.google.com/search?q=" + encoded;
        }
        remember(selectedEngine + " [" + selectedMode + "]: " + q);
        if ("Tor".equals(selectedMode)) {
            openExternal(url, "Choose Tor Browser or a browser configured for Tor. This app cannot enable Tor routing itself.");
        } else if ("Dark web".equals(selectedMode)) {
            browse("https://www.torproject.org/");
            status.setText("Tor Project opened. .onion addresses require Tor Browser or a configured Tor connection.");
        } else {
            browse(url);
            status.setText("Searching " + selectedEngine + " (" + selectedMode + ")");
            if ("VPN".equals(selectedMode)) status.setText(isVpnActive() ? "VPN network detected; browsing uses Android system routing." : "No active Android VPN detected. Connect a VPN provider in Settings first.");
        }
    }

    private void browse(String url) {
        web.loadUrl(url);
        if (status != null) status.setText("Opening " + Uri.parse(url).getHost());
    }

    private void openExternal(String url, String message) {
        try {
            startActivity(Intent.createChooser(new Intent(Intent.ACTION_VIEW, Uri.parse(url)), "Open in browser"));
            if (status != null) status.setText(message);
        } catch (Exception e) {
            if (status != null) status.setText("No compatible browser is installed");
            Toast.makeText(this, "Install or enable a supported browser", Toast.LENGTH_LONG).show();
        }
    }

    private void openVpnSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_VPN_SETTINGS));
            status.setText("Connect your VPN in Android settings, then return to browse through it.");
        } catch (Exception e) { status.setText("Open Android Settings and search for VPN."); }
    }

    private void remember(String item) {
        String old = getSharedPreferences(PREFS, MODE_PRIVATE).getString(HISTORY, "");
        String[] lines = (item + (old.isEmpty() ? "" : "\n" + old)).split("\\n");
        StringBuilder saved = new StringBuilder();
        for (int i = 0; i < Math.min(lines.length, 30); i++) {
            if (i > 0) saved.append('\n');
            saved.append(lines[i]);
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(HISTORY, saved.toString()).apply();
    }

    private void showMemory() {
        String history = getSharedPreferences(PREFS, MODE_PRIVATE).getString(HISTORY, "");
        if (history.isEmpty()) history = "No saved searches yet.";
        new AlertDialog.Builder(this).setTitle("Search memory").setMessage(history)
            .setPositiveButton("Close", null)
            .setNegativeButton("Clear history", (d, w) ->
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(HISTORY).apply()).show();
    }

    private String enc(String value) {
        try { return java.net.URLEncoder.encode(value, "UTF-8"); }
        catch (Exception e) { return value; }
    }

    @Override protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }
}