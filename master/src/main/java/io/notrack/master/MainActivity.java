package io.notrack.master;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;
import android.content.Intent;
import android.net.Uri;
import androidx.appcompat.app.AppCompatActivity;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private ListView list;
    private EditText peerIp;
    private final ArrayList<String> events = new ArrayList<>();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private volatile boolean running = true;
    private volatile ServerSocket serverSocket;
    private ArrayAdapter<String> adapter;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_master);
        list = findViewById(R.id.list);
        peerIp = findViewById(R.id.peer_ip);
        status = findViewById(R.id.status);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, events);
        list.setAdapter(adapter);
        peerIp.setText(getSharedPreferences("master", 0).getString("slave_ip", ""));
        findViewById(R.id.btn_update).setOnClickListener(v -> openUpdates());
        findViewById(R.id.btn_connect).setOnClickListener(v -> connectToSlave());
        addEvent("Master started.");
        pool.execute(() -> {
            try (ServerSocket listener = new ServerSocket(9877)) {
                serverSocket = listener;
                status("Listening on local TCP port 9877. Remote networks may block direct TCP.");
                while (running) {
                    Socket client = listener.accept();
                    pool.execute(() -> handleSlave(client));
                }
            } catch (Exception e) {
                if (running) status("Listener unavailable. Check whether port 9877 is already in use.");
            }
        });
    }

    private void connectToSlave() {
        String host = peerIp.getText().toString().trim();
        if (host.isEmpty()) { peerIp.setError("Enter the Slave IP or hostname"); return; }
        getSharedPreferences("master", 0).edit().putString("slave_ip", host).apply();
        status("Testing connection to " + host + "…");
        pool.execute(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new java.net.InetSocketAddress(host, 9876), 5000);
                socket.setSoTimeout(5000);
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                out.println("PING");
                if ("ACK:PING".equals(in.readLine())) {
                    addEvent("Connection test succeeded: " + host);
                    status("Connection test passed for " + host);
                } else status("Unexpected reply from " + host);
            } catch (Exception e) {
                status("Cannot reach peer. Check address, network, and firewall.");
            }
        });
    }

    private void handleSlave(Socket socket) {
        try (Socket s = socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
             PrintWriter out = new PrintWriter(s.getOutputStream(), true)) {
            s.setSoTimeout(30000);
            String line;
            while (running && (line = in.readLine()) != null) {
                if ("BYE".equals(line)) break;
                if ("PING".equals(line)) {
                    out.println("ACK:PING");
                    continue;
                }
                String display = line.startsWith("MESSAGE:") ? line.substring(8) : line;
                addEvent("Slave: " + display);
                out.println("OK");
            }
        } catch (Exception e) {
            if (running) status("Peer disconnected or timed out.");
        }
    }

    private void addEvent(String event) {
        handler.post(() -> {
            events.add(event);
            if (events.size() > 200) events.remove(0);
            adapter.notifyDataSetChanged();
            list.setSelection(events.size() - 1);
        });
    }

    private void status(String value) { handler.post(() -> status.setText(value)); }

    private void openUpdates() {
        AppUpdater.check(this, "master-control.apk", status);
    }

    @Override protected void onDestroy() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        pool.shutdownNow();
        super.onDestroy();
    }
}