package io.notrack.master;

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
    private ArrayList<String> events = new ArrayList<>();
    private ExecutorService pool = Executors.newCachedThreadPool();
    private Handler h = new Handler();
    private TextView status;
    private ServerSocket serverSocket;
    private boolean running = true;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_master);
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
        status = findViewById(R.id.status);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, events);
        list.setAdapter(a);
        events.add("Master server started on port 9877");
        a.notifyDataSetChanged();

        // Master = also a server. Opens port 9877 on THIS phone. Slave pushes data here over internet.
        pool.execute(() -> {
            try {
                serverSocket = new ServerSocket(9877);
                status("Master listening on port 9877");
                while (running) {
                    Socket client = serverSocket.accept();
                    pool.execute(() -> handleSlave(client));
                }
            } catch (Exception e) { status("Error: " + e.getMessage()); }
        });

        // Master also connects TO the slave (port 9876) to pull data and send commands
        pool.execute(() -> {
            while (running) {
                try {
                    String slaveIp = getSharedPreferences("master", 0).getString("slave_ip", "127.0.0.1");
                    Socket slaveSocket = new Socket(slaveIp, 9876);
                    events.add("Connected to slave");
                    h.post(() -> { ArrayAdapter<String> a2 = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, events); list.setAdapter(a2); });
                    slaveSocket.close();
                } catch (Exception e) {
                    // Slave not reachable yet — keep trying every 5s
                    try { Thread.sleep(5000); } catch (Exception ex) {}
                }
            }
        });
    }

    private void handleSlave(Socket s) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
            PrintWriter out = new PrintWriter(s.getOutputStream(), true);
            String line;
            while ((line = in.readLine()) != null) {
                if (line.equals("BYE")) break;
                events.add("From slave: " + line);
                h.post(() -> {
                    ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, events);
                    list.setAdapter(a);
                });
                out.println("OK");
            }
        } catch (Exception e) {} finally {
            try { s.close(); } catch (Exception e) {}
        }
    }

    private void status(String t) {
        h.post(() -> status.setText(t));
    }
}