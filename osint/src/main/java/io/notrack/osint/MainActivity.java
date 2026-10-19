package io.notrack.osint;

import android.content.Intent;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.webkit.WebResourceResponse;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import org.torproject.jni.TorService;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
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
    private Spinner engine, mode, dorkTemplate;
    private WebView web;
    private TextView status;
    private static final String PREFS = "osint_memory";
    private static final String HISTORY = "search_history";
    private volatile boolean torActive = false;
    private volatile boolean torStarting = false;
    private boolean torBound = false;
    private boolean torModeEnabled = false;
    private String pendingTorUrl = "https://check.torproject.org/";
    private static final int TOR_SOCKS_PORT = 9050;
    private static final int VPN_CONFIG_REQUEST = 4012;

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_main);
        inPhone = findViewById(R.id.in_phone); inEmail = findViewById(R.id.in_email);
        inUser = findViewById(R.id.in_user); inDomain = findViewById(R.id.in_domain);
        inIp = findViewById(R.id.in_ip); inUrl = findViewById(R.id.in_url);
        engine = findViewById(R.id.engine); mode = findViewById(R.id.mode); dorkTemplate = findViewById(R.id.dork_template);
        web = findViewById(R.id.web); status = findViewById(R.id.status);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri uri = r.getUrl();
                if (torModeEnabled && "http".equalsIgnoreCase(uri.getScheme()) && !isLocalHost(uri.getHost())) {
                    status.setText("Tor mode blocks insecure HTTP navigation; use HTTPS where available.");
                }
                return route(uri);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, String u) { return route(Uri.parse(u)); }
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                if (!torModeEnabled) return super.shouldInterceptRequest(v, r);
                return fetchThroughTor(r.getUrl().toString(), r.getMethod(), r.getRequestHeaders());
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, String url) {
                if (!torModeEnabled) return super.shouldInterceptRequest(v, url);
                return fetchThroughTor(url, "GET", java.util.Collections.emptyMap());
            }
        });
        web.setWebChromeClient(new WebChromeClient());
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.setVisibility(android.view.View.GONE);
        findViewById(R.id.btn_update).setOnClickListener(v -> checkForUpdates());
        findViewById(R.id.btn_search).setOnClickListener(v -> doSearch());
        findViewById(R.id.btn_dork).setOnClickListener(v -> doDorkSearch());
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
        findViewById(R.id.btn_dark).setOnClickListener(v -> openDarkWeb());
        findViewById(R.id.btn_tor).setOnClickListener(v -> startTorAndBrowse("https://check.torproject.org/"));
        findViewById(R.id.btn_vpn).setOnClickListener(v -> chooseVpnConfig());
        findViewById(R.id.btn_mem).setOnClickListener(v -> showMemory());
    }


    private void startTorAndBrowse(String url) {
        pendingTorUrl = url;
        torModeEnabled = true;
        if (torActive) {
            web.setVisibility(android.view.View.VISIBLE);
            web.loadUrl(url);
            status.setText("In-app Tor browser active. Verify connection at check.torproject.org.");
            return;
        }
        if (torStarting) {
            status.setText("Tor is starting; please wait…");
            return;
        }
        torStarting = true;
        status.setText("Starting the built-in Tor service…");
        try {
            torBound = bindService(new Intent(this, TorService.class), torConnection, BIND_AUTO_CREATE);
            if (!torBound) {
                torStarting = false;
                torModeEnabled = false;
                status.setText("Could not bind the built-in Tor service.");
            }
        } catch (Exception e) {
            torStarting = false;
            torModeEnabled = false;
            status.setText("Unable to start Tor service: " + e.getClass().getSimpleName());
        }
    }

    private final ServiceConnection torConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            new Thread(() -> {
                long deadline = System.currentTimeMillis() + 90000L;
                boolean ready = false;
                while (System.currentTimeMillis() < deadline && !isFinishing()) {
                    try (Socket socket = new Socket()) {
                        socket.connect(new InetSocketAddress("127.0.0.1", TOR_SOCKS_PORT), 1500);
                        socket.setSoTimeout(1500);
                        socket.getOutputStream().write(new byte[] {0x05, 0x01, 0x00});
                        byte[] handshake = new byte[2];
                        int read = socket.getInputStream().read(handshake);
                        ready = read == 2 && handshake[0] == 0x05 && handshake[1] == 0x00;
                        if (ready) break;
                        throw new java.io.IOException("SOCKS5 handshake not ready");
                    } catch (Exception ignored) {
                        try { Thread.sleep(500); } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
                final boolean connected = ready;
                new Handler(Looper.getMainLooper()).post(() -> {
                    torStarting = false;
                    torActive = connected;
                    if (connected) {
                        status.setText("Tor SOCKS5 proxy is responding. Checking Tor network…");
                        web.setVisibility(android.view.View.VISIBLE);
                        web.loadUrl(pendingTorUrl);
                    } else {
                        torModeEnabled = false;
                        status.setText("Tor SOCKS proxy did not become ready. Check internet access, wait, then retry. No direct-network fallback was used.");
                        Toast.makeText(MainActivity.this, "Tor did not become ready", Toast.LENGTH_LONG).show();
                    }
                });
            }).start();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            torActive = false;
            torModeEnabled = false;
            status.setText("Tor service disconnected; Tor browsing stopped.");
        }
    };

    private void openDarkWeb() {
        String candidate = inUrl.getText().toString().trim();
        if (candidate.matches("(?i)^https?://[^/]+\\.onion(?:/.*)?$")) {
            startTorAndBrowse(candidate);
        } else {
            new AlertDialog.Builder(this)
                .setTitle("In-app Tor browser")
                .setMessage("Enter a full .onion URL in the URL field to open it through Tor, or start with the Tor connectivity check.")
                .setPositiveButton("Start Tor", (d, w) -> startTorAndBrowse("https://check.torproject.org/"))
                .setNegativeButton("Cancel", null).show();
        }
    }

    private boolean isLocalHost(String host) {
        return host == null || host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("::1");
    }

    private WebResourceResponse fetchThroughTor(String url, String method, Map<String, String> headers) {
        if (!torActive) return errorResponse(503, "Tor is not connected. Request blocked to prevent direct-network leakage.");
        HttpURLConnection connection = null;
        try {
            String scheme = Uri.parse(url).getScheme();
            if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
                return errorResponse(403, "Only HTTP and HTTPS are allowed in Tor mode.");
            }
            if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
                return errorResponse(405, "Tor browser currently supports GET/HEAD requests only.");
            }
            Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", TOR_SOCKS_PORT));
            connection = (HttpURLConnection) new URL(url).openConnection(proxy);
            connection.setConnectTimeout(60000);
            connection.setReadTimeout(60000);
            connection.setRequestProperty("User-Agent", "OSINT-Workspace/1.0");
            connection.setInstanceFollowRedirects(true);
            connection.setRequestMethod(method);
            for (Map.Entry<String, String> header : headers.entrySet()) {
                String key = header.getKey();
                if (key != null && !key.equalsIgnoreCase("Host") && !key.equalsIgnoreCase("Connection")) {
                    connection.setRequestProperty(key, header.getValue());
                }
            }
            int code = connection.getResponseCode();
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream == null) stream = new ByteArrayInputStream(new byte[0]);
            // Buffer the response before disconnecting HttpURLConnection; otherwise WebView may read a closed stream.
            java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            int total = 0;
            while ((count = stream.read(buffer)) != -1) {
                total += count;
                if (total > 20 * 1024 * 1024) return errorResponse(502, "Response exceeded the 20 MB in-app browser limit.");
                body.write(buffer, 0, count);
            }
            stream.close();
            String contentType = connection.getContentType();
            String mime = "text/plain";
            String encoding = "UTF-8";
            if (contentType != null) {
                String[] parts = contentType.split(";");
                mime = parts[0].trim();
                for (String part : parts) {
                    String p = part.trim();
                    if (p.toLowerCase(java.util.Locale.ROOT).startsWith("charset=")) encoding = p.substring(8).replace("\"", "");
                }
            }
            Map<String, String> responseHeaders = new HashMap<>();
            for (Map.Entry<String, java.util.List<String>> entry : connection.getHeaderFields().entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null && !entry.getValue().isEmpty()) {
                    if (!entry.getKey().equalsIgnoreCase("Content-Length") && !entry.getKey().equalsIgnoreCase("Content-Encoding") && !entry.getKey().equalsIgnoreCase("Transfer-Encoding")) {
                        responseHeaders.put(entry.getKey(), String.join(", ", entry.getValue()));
                    }
                }
            }
            return new WebResourceResponse(mime, encoding, code, connection.getResponseMessage(), responseHeaders, new ByteArrayInputStream(body.toByteArray()));
        } catch (Exception e) {
            String detail = e instanceof java.net.SocketTimeoutException
                ? "Tor network timed out while connecting. The local proxy exists, but the Tor circuit may not be ready or the site may be slow."
                : "Tor request failed: " + e.getClass().getSimpleName();
            MAIN.post(() -> {
                if (torModeEnabled && !isFinishing()) status.setText(detail);
            });
            return errorResponse(502, detail);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private WebResourceResponse errorResponse(int code, String message) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Cache-Control", "no-store");
        String reason = code == 503 ? "Tor Unavailable" : (code == 403 ? "Blocked" : "Request Failed");
        return new WebResourceResponse("text/plain", "UTF-8", code, reason, headers,
            new ByteArrayInputStream(message.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private void chooseVpnConfig() {
        new AlertDialog.Builder(this)
            .setTitle("VPN configuration")
            .setMessage("Import a WireGuard .conf file to open it in the official WireGuard app. A valid configuration from your VPN provider is required; this app does not operate a VPN server.")
            .setPositiveButton("Import .conf", (d, w) -> {
                Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("*/*");
                startActivityForResult(pick, VPN_CONFIG_REQUEST);
            })
            .setNeutralButton("Android VPN settings", (d, w) -> openVpnSettings())
            .setNegativeButton("Cancel", null).show();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != VPN_CONFIG_REQUEST || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri configUri = data.getData();
        try {
            String config;
            try (InputStream input = getContentResolver().openInputStream(configUri);
                 java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
                if (input == null) throw new java.io.IOException("Unable to read selected file");
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                config = output.toString("UTF-8");
            }
            if (!config.contains("[Interface]") || !config.contains("[Peer]")) {
                status.setText("Selected file is not a WireGuard .conf file.");
                return;
            }
            Intent open = new Intent(Intent.ACTION_VIEW);
            open.setDataAndType(configUri, "application/x-wireguard-profile");
            open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(open, "Import into WireGuard"));
            status.setText("Configuration sent to WireGuard. Review it and explicitly activate the tunnel there.");
        } catch (Exception e) {
            status.setText("WireGuard is not installed or could not open the configuration.");
            new AlertDialog.Builder(this).setTitle("WireGuard required")
                .setMessage("Install the official WireGuard app, then import your VPN provider's configuration.")
                .setPositiveButton("Open WireGuard website", (d, w) -> openExternal("https://www.wireguard.com/install/", "Opened WireGuard installation page"))
                .setNegativeButton("Close", null).show();
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
        AppUpdater.check(this, "osint-master.apk", status);
    }

    private boolean route(Uri uri) {
        // In Tor mode, never hand requests to an external browser: that could leak the destination outside Tor.
        if (torModeEnabled) return false;
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
            startTorAndBrowse(url);
        } else if ("Dark web".equals(selectedMode)) {
            String onionUrl = inUrl.getText().toString().trim();
            if (onionUrl.matches("(?i)^https?://[^/]+\\.onion(?:/.*)?$")) startTorAndBrowse(onionUrl);
            else openDarkWeb();
        } else {
            browse(url);
            status.setText("Searching " + selectedEngine + " (" + selectedMode + ")");
            if ("VPN".equals(selectedMode)) status.setText(isVpnActive() ? "VPN network detected; browsing uses Android system routing." : "No active Android VPN detected. Connect a VPN provider in Settings first.");
        }
    }


    private void doDorkSearch() {
        String terms = query().trim();
        if (terms.isEmpty()) {
            Toast.makeText(this, "Enter a public search term first", Toast.LENGTH_SHORT).show();
            return;
        }
        String domain = inDomain.getText().toString().trim();
        if (domain.startsWith("https://")) domain = domain.substring(8);
        if (domain.startsWith("http://")) domain = domain.substring(7);
        if (domain.contains("/")) domain = domain.substring(0, domain.indexOf('/'));
        String template = String.valueOf(dorkTemplate.getSelectedItem());
        String dork;
        switch (template) {
            case "Exact phrase":
                dork = "\"" + terms.replace("\"", "") + "\"";
                break;
            case "Limit to domain":
                dork = domain.isEmpty() ? terms : "site:" + domain + " " + terms;
                break;
            case "PDF documents":
                dork = terms + " filetype:pdf";
                break;
            case "Word documents":
                dork = terms + " (filetype:doc OR filetype:docx)";
                break;
            case "Title matches":
                dork = "intitle:" + terms;
                break;
            case "URL contains":
                dork = "inurl:" + terms;
                break;
            case "Public profiles":
                dork = "\"" + terms.replace("\"", "") + "\" (site:github.com OR site:linkedin.com OR site:x.com)";
                break;
            case "Public document types":
                dork = terms + " (filetype:pdf OR filetype:docx OR filetype:xlsx OR filetype:pptx)";
                break;
            default:
                dork = terms;
                break;
        }
        String url = "https://www.google.com/search?q=" + enc(dork);
        remember("Google Dork [" + template + "]: " + dork);
        if ("Tor".equals(String.valueOf(mode.getSelectedItem()))) {
            startTorAndBrowse(url);
        } else {
            browse(url);
            status.setText("Google dork search: " + template);
        }
    }

    private void browse(String url) {
        web.setVisibility(android.view.View.VISIBLE);
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
        if (torBound) { try { unbindService(torConnection); } catch (Exception ignored) { } }
        if (web != null) web.destroy();
        super.onDestroy();
    }
}