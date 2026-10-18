package io.notrack.osint;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

public class MainActivity extends AppCompatActivity {
    private EditText inPhone, inEmail, inUser, inDomain, inIp, inUrl;
    private Spinner engine, mode;
    private WebView web;
    private TextView status;
    private OkHttpClient torClient, vpnClient, normalClient;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_main);

        inPhone = findViewById(R.id.in_phone);
        inEmail = findViewById(R.id.in_email);
        inUser = findViewById(R.id.in_user);
        inDomain = findViewById(R.id.in_domain);
        inIp = findViewById(R.id.in_ip);
        inUrl = findViewById(R.id.in_url);
        engine = findViewById(R.id.engine);
        mode = findViewById(R.id.mode);
        web = findViewById(R.id.web);
        status = findViewById(R.id.status);

        normalClient = new OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).build();
        torClient = new OkHttpClient.Builder()
            .proxy(new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", 9050)))
            .connectTimeout(40, TimeUnit.SECONDS).build();
        vpnClient = new OkHttpClient.Builder().build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return routeUrl(request.getUrl().toString());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return routeUrl(url);
            }
        });
        web.setWebChromeClient(new WebChromeClient());
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.loadUrl("https://www.google.com");

        findViewById(R.id.btn_update).setOnClickListener(v -> openUpdates());
        findViewById(R.id.btn_search).setOnClickListener(v -> doSearch());
        findViewById(R.id.btn_web).setOnClickListener(v -> browse("https://www.google.com"));
        findViewById(R.id.btn_dark).setOnClickListener(v -> browse("https://www.torproject.org"));
        findViewById(R.id.btn_tor).setOnClickListener(v -> toggleTor());
        findViewById(R.id.btn_vpn).setOnClickListener(v -> toggleVpn());
        findViewById(R.id.btn_mem).setOnClickListener(v -> showMemory());
    }

    // Google blocks account sign-in inside embedded WebViews. Keep browsing in-app,
    // but hand authentication pages to the user's installed supported browser.
    private boolean routeUrl(String url) {
        Uri uri = Uri.parse(url);
        String host = uri.getHost();
        if (host != null && isGoogleAuthHost(host)) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                startActivity(intent);
                status.setText("Opened Google sign-in in your browser");
            } catch (Exception e) {
                status.setText("No supported browser found for sign-in");
                Toast.makeText(this, "Install or enable a supported browser to sign in", Toast.LENGTH_LONG).show();
            }
            return true;
        }
        return false;
    }

    private boolean isGoogleAuthHost(String host) {
        return host.equalsIgnoreCase("accounts.google.com")
            || host.equalsIgnoreCase("myaccount.google.com")
            || host.equalsIgnoreCase("consent.google.com");
    }

    private String q() {
        String t = "";
        if (inPhone.getText().length() > 0) t += inPhone.getText().toString() + " ";
        if (inEmail.getText().length() > 0) t += inEmail.getText().toString() + " ";
        if (inUser.getText().length() > 0) t += inUser.getText().toString() + " ";
        if (inDomain.getText().length() > 0) t += inDomain.getText().toString() + " ";
        if (inIp.getText().length() > 0) t += inIp.getText().toString() + " ";
        if (inUrl.getText().length() > 0) t += inUrl.getText().toString();
        return t.trim();
    }

    private void doSearch() {
        String query = q();
        if (query.isEmpty()) { Toast.makeText(this, "Type something first", Toast.LENGTH_SHORT).show(); return; }
        String eng = engine.getSelectedItem().toString();
        String url;
        switch (eng) {
            case "Google": url = "https://www.google.com/search?q=" + enc(query); break;
            case "Bing": url = "https://www.bing.com/search?q=" + enc(query); break;
            case "DuckDuckGo": url = "https://duckduckgo.com/?q=" + enc(query); break;
            case "Yahoo": url = "https://search.yahoo.com/search?p=" + enc(query); break;
            case "Startpage": url = "https://www.startpage.com/sp/search?query=" + enc(query); break;
            case "Brave": url = "https://search.brave.com/search?q=" + enc(query); break;
            case "Ecosia": url = "https://www.ecosia.org/search?q=" + enc(query); break;
            case "Searx": url = "https://searx.be/search?q=" + enc(query); break;
            case "HackerNews": url = "https://hn.algolia.com/?q=" + enc(query); break;
            case "GitHub": url = "https://github.com/search?q=" + enc(query); break;
            case "HIBP": url = "https://haveibeenpwned.com/?s=" + enc(query); break;
            default: url = "https://www.google.com/search?q=" + enc(query);
        }
        status.setText("Searching via " + eng + " ...");
        browse(url);
    }

    private void browse(String url) {
        web.loadUrl(url);
        status.setText("Browsing: " + url);
    }

    private void toggleTor() {
        status.setText("Tor routing is not configured by this button. Install and configure a Tor service first.");
        Toast.makeText(this, "This button does not enable Tor by itself", Toast.LENGTH_LONG).show();
    }

    private void toggleVpn() {
        status.setText("VPN uses Android system VPN settings; this button cannot enable a VPN by itself.");
        Toast.makeText(this, "Connect a VPN in Android Settings first", Toast.LENGTH_LONG).show();
    }

    private void showMemory() {
        String mem = "Local OSINT memory store:\n\n" +
            "Current search fields:\n" + q() + "\n\n" +
            "Current page: " + web.getUrl() + "\n\n" +
            "This screen does not persist search history.";
        Toast.makeText(this, mem, Toast.LENGTH_LONG).show();
    }

    private void openUpdates() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/yalangisexter-ux/osnt-slave-master/releases/latest"));
            startActivity(intent);
            status.setText("Opened latest release page");
        } catch (Exception e) {
            Toast.makeText(this, "Unable to open release page", Toast.LENGTH_LONG).show();
        }
    }

    private String enc(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }
}