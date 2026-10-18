package io.notrack.master;

import android.os.Bundle;
import android.os.Handler;
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
    private final Handler handler = new Handler();
    private TextView status;
    private volatile boolean running = true;
    private ServerSocket serverSocket;
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
        addEvent("Master started. Listening on TCP port 9877.");
        pool.execute(() -> {
            try {
                ServerSocket listener = new ServerSocket(9877);
                serverSocket = listener;
                status("Listening on port 9877");
                while (running) {
                    Socket client = listener.accept();
                    pool.execute(() -> handleSlave(client));
                }
            } catch (Exception e) {
                if (running) status("Server error: " + e.getMessage());
            }
        });
    }

    private void connectToSlave() {
        String host = peerIp.getText().toString().trim();
        if (host.isEmpty()) { peerIp.setError("Enter the Slave IP or hostname"); return; }
        getSharedPreferences("master", 0).edit().putString("slave_ip", host).apply();
        pool.execute(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new java.net.InetSocketAddress(host, 9876), 5000);
                socket.setSoTimeout(5000);
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                out.println("PING");
                String response = in.readLine();
                if ("ACK:PING".equals(response)) {
                    addEvent("Connected to Slave at " + host);
                    status("Connection test successful: " + host);
                } else status("Connected, but unexpected response from " + host);
            } catch (Exception e) { status("Cannot reach " + host + ": " + e.getMessage()); }
        });
    }

    private void handleSlave(Socket socket) {
        try (Socket s = socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
             PrintWriter out = new PrintWriter(s.getOutputStream(), true)) {
            String line;
            while (running && (line = in.readLine()) != null) {
                if ("BYE".equals(line)) break;
                addEvent("Slave: " + line);
                out.println("OK");
            }
        } catch (Exception e) { if (running) status("Peer connection ended: " + e.getMessage()); }
    }

    private void addEvent(String event) {
        handler.post(() -> { events.add(event); adapter.notifyDataSetChanged(); });
    }
    private void status(String value) { handler.post(() -> status.setText(value)); }
    private void openUpdates() {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/yalangisexter-ux/osnt-slave-master/releases/latest"))); }
        catch (Exception e) { Toast.makeText(this, "Unable to open release page", Toast.LENGTH_LONG).show(); }
    }
    @Override protected void onDestroy() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        pool.shutdownNow();
        super.onDestroy();
    }
}