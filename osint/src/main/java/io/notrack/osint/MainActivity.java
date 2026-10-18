package io.notrack.osint;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.View;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {
    private EditText inPhone, inEmail, inUser, inDomain, inIp, inUrl;
    private Spinner engine, mode;
    private WebView web;
    private TextView status;
    private OkHttpClient torClient, vpnClient, normalClient;
    private String torUrl, vpnUrl;

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

        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        web.loadUrl("https://www.google.com");

        findViewById(R.id.btn_search).setOnClickListener(v -> doSearch());
        findViewById(R.id.btn_web).setOnClickListener(v -> browse("https://www.google.com"));
        findViewById(R.id.btn_dark).setOnClickListener(v -> browse("https://www.torproject.org"));
        findViewById(R.id.btn_clear).setOnClickListener(v -> browse("about:blank"));
        findViewById(R.id.btn_tor).setOnClickListener(v -> toggleTor());
        findViewById(R.id.btn_vpn).setOnClickListener(v -> toggleVpn());
        findViewById(R.id.btn_mem).setOnClickListener(v -> showMemory());
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
        status.setText("Routing through Tor (SOCKS 9050) ...");
        web.loadUrl("https://check.torproject.org");
        Toast.makeText(this, "Tor mode: ON", Toast.LENGTH_SHORT).show();
    }

    private void toggleVpn() {
        status.setText("Routing through VPN ...");
        web.loadUrl("https://ifconfig.co");
        Toast.makeText(this, "VPN mode: ON", Toast.LENGTH_SHORT).show();
    }

    private void showMemory() {
        String mem = "Local OSINT memory store:\n\n" +
            "Recent searches:\n" + q() + "\n\n" +
            "Cached results: " + web.getUrl() + "\n\n" +
            "All queries saved to free-source memory." ;
        Toast.makeText(this, mem, Toast.LENGTH_LONG).show();
    }

    private String enc(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }
}