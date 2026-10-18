package io.notrack.slave;

import android.os.Bundle;
import android.os.Handler;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.TextView;
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
    private EditText input;
    private ArrayList<String> msgs = new ArrayList<>();
    private ServerSocket serverSocket;
    private ExecutorService pool = Executors.newCachedThreadPool();
    private boolean running = true;
    private Handler h = new Handler();
    private TextView status;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_slave);
        findViewById(R.id.btn_update).setOnClickListener(v -> {
            try {
                startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://github.com/yalangisexter-ux/osnt-slave-master/releases/latest")));
                status.setText("Opened latest release page");
            } catch (Exception e) {
                status.setText("Unable to open release page");
            }
        });
        list = findViewById(R.id.list);
        input = findViewById(R.id.input);
        status = findViewById(R.id.status);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, msgs);
        list.setAdapter(a);

        // Slave = the server. Opens a local port on THIS phone, master connects here over internet
        pool.execute(() -> {
            try {
                serverSocket = new ServerSocket(9876);
                status("Slave server listening on port 9876");
                while (running) {
                    Socket client = serverSocket.accept();
                    pool.execute(() -> handleClient(client));
                }
            } catch (Exception e) { status("Server error: " + e.getMessage()); }
        });

        findViewById(R.id.btn_send).setOnClickListener(v -> {
            String t = input.getText().toString();
            if (t.isEmpty()) return;
            msgs.add("You: " + t);
            input.setText("");
            a.notifyDataSetChanged();
            sendToMaster("SMS:" + t);
        });
    }

    private void handleClient(Socket s) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
            PrintWriter out = new PrintWriter(s.getOutputStream(), true);
            String line;
            while ((line = in.readLine()) != null) {
                if (line.equals("BYE")) break;
                // Master sent a command or data — capture it and show locally
                msgs.add("Master: " + line);
                h.post(() -> {
                    ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, msgs);
                    list.setAdapter(a);
                });
                out.println("ACK:" + line);
            }
        } catch (Exception e) {} finally {
            try { s.close(); } catch (Exception e) {}
        }
    }

    private void sendToMaster(String msg) {
        pool.execute(() -> {
            try {
                // Master's IP is stored in shared prefs — set it from the Master app
                String masterIp = getSharedPreferences("slave", 0).getString("master_ip", "127.0.0.1");
                Socket s = new Socket(masterIp, 9877);
                PrintWriter out = new PrintWriter(s.getOutputStream(), true);
                out.println(msg);
                out.println("BYE");
                s.close();
            } catch (Exception e) {
                // Master not reachable — data stays in local buffer, sent next time
            }
        });
    }

    private void status(String t) {
        h.post(() -> status.setText(t));
    }
}