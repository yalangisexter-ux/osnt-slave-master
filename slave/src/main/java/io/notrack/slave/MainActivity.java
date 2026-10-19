package io.notrack.slave;

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
    private EditText input, peerIp;
    private final ArrayList<String> messages = new ArrayList<>();
    private volatile boolean running = true;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private volatile ServerSocket serverSocket;
    private ArrayAdapter<String> adapter;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_slave);
        list = findViewById(R.id.list); input = findViewById(R.id.input);
        peerIp = findViewById(R.id.peer_ip); status = findViewById(R.id.status);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, messages);
        list.setAdapter(adapter);
        peerIp.setText(getSharedPreferences("slave", 0).getString("master_ip", ""));
        findViewById(R.id.btn_update).setOnClickListener(v -> openUpdates());
        findViewById(R.id.btn_send).setOnClickListener(v -> sendMessage());
        pool.execute(() -> {
            try (ServerSocket listener = new ServerSocket(9876)) {
                serverSocket = listener;
                status("Listening on local TCP port 9876. Remote networks may block direct TCP.");
                while (running) {
                    Socket client = listener.accept();
                    pool.execute(() -> handleClient(client));
                }
            } catch (Exception e) {
                if (running) status("Listener unavailable. Check whether port 9876 is already in use.");
            }
        });
    }

    private void sendMessage() {
        String text = input.getText().toString().trim();
        String host = peerIp.getText().toString().trim();
        if (text.isEmpty()) { input.setError("Enter a message"); return; }
        if (host.isEmpty()) { peerIp.setError("Enter the Master IP or hostname"); return; }
        getSharedPreferences("slave", 0).edit().putString("master_ip", host).apply();
        input.setText("");
        String pending = "You: " + text + " (sending…)";
        addMessage(pending);
        status("Sending to " + host + "…");
        pool.execute(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new java.net.InetSocketAddress(host, 9877), 5000);
                socket.setSoTimeout(5000);
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                out.println("MESSAGE:" + text);
                String response = in.readLine();
                if ("OK".equals(response)) {
                    replaceMessage(pending, "You: " + text + " ✓");
                    status("Message delivered to Master");
                } else {
                    replaceMessage(pending, "You: " + text + " (not confirmed)");
                    status("Master did not confirm delivery");
                }
                out.println("BYE");
            } catch (Exception e) {
                replaceMessage(pending, "You: " + text + " (failed)");
                status("Send failed. Check Master address, network and firewall.");
            }
        });
    }

    private void handleClient(Socket socket) {
        try (Socket s = socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
             PrintWriter out = new PrintWriter(s.getOutputStream(), true)) {
            s.setSoTimeout(30000);
            String line;
            while (running && (line = in.readLine()) != null) {
                if ("BYE".equals(line)) break;
                if ("PING".equals(line)) { out.println("ACK:PING"); continue; }
                addMessage("Master: " + line);
                out.println("OK");
            }
        } catch (Exception e) { if (running) status("Peer disconnected or timed out."); }
    }

    private void addMessage(String message) {
        handler.post(() -> {
            messages.add(message);
            if (messages.size() > 200) messages.remove(0);
            adapter.notifyDataSetChanged();
            list.setSelection(messages.size() - 1);
        });
    }

    private void replaceMessage(String oldMessage, String newMessage) {
        handler.post(() -> {
            int index = messages.indexOf(oldMessage);
            if (index >= 0) messages.set(index, newMessage);
            adapter.notifyDataSetChanged();
        });
    }

    private void status(String value) { handler.post(() -> status.setText(value)); }

    private void openUpdates() {
        AppUpdater.check(this, "chat-slave.apk", status);
    }

    @Override protected void onDestroy() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        pool.shutdownNow();
        super.onDestroy();
    }
}