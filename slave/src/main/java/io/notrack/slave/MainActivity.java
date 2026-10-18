package io.notrack.slave;

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
    private EditText input, peerIp;
    private final ArrayList<String> messages = new ArrayList<>();
    private volatile boolean running = true;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Handler handler = new Handler();
    private TextView status;
    private ServerSocket serverSocket;
    private ArrayAdapter<String> adapter;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_slave);
        list = findViewById(R.id.list);
        input = findViewById(R.id.input);
        peerIp = findViewById(R.id.peer_ip);
        status = findViewById(R.id.status);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, messages);
        list.setAdapter(adapter);
        peerIp.setText(getSharedPreferences("slave", 0).getString("master_ip", ""));
        findViewById(R.id.btn_update).setOnClickListener(v -> openUpdates());
        findViewById(R.id.btn_send).setOnClickListener(v -> sendMessage());
        pool.execute(() -> {
            try {
                ServerSocket listener = new ServerSocket(9876);
                serverSocket = listener;
                status("Listening on port 9876");
                while (running) {
                    Socket client = listener.accept();
                    pool.execute(() -> handleClient(client));
                }
            } catch (Exception e) { if (running) status("Server error: " + e.getMessage()); }
        });
    }

    private void sendMessage() {
        String text = input.getText().toString().trim();
        String host = peerIp.getText().toString().trim();
        if (text.isEmpty()) { input.setError("Enter a message"); return; }
        if (host.isEmpty()) { peerIp.setError("Enter the Master IP or hostname"); return; }
        getSharedPreferences("slave", 0).edit().putString("master_ip", host).apply();
        input.setText("");
        addMessage("You: " + text + " (sending...)");
        pool.execute(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new java.net.InetSocketAddress(host, 9877), 5000);
                socket.setSoTimeout(5000);
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                out.println("MESSAGE:" + text);
                String response = in.readLine();
                if ("OK".equals(response)) status("Message delivered to Master");
                else status("Connected, but delivery was not acknowledged");
                out.println("BYE");
            } catch (Exception e) { status("Send failed to " + host + ": " + e.getMessage()); }
        });
    }

    private void handleClient(Socket socket) {
        try (Socket s = socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
             PrintWriter out = new PrintWriter(s.getOutputStream(), true)) {
            String line;
            while (running && (line = in.readLine()) != null) {
                if ("BYE".equals(line)) break;
                addMessage("Master: " + line);
                out.println("ACK:" + line);
            }
        } catch (Exception e) { if (running) status("Peer connection ended: " + e.getMessage()); }
    }

    private void addMessage(String message) { handler.post(() -> { messages.add(message); adapter.notifyDataSetChanged(); }); }
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