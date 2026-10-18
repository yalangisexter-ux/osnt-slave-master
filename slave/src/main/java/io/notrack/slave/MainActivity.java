package io.notrack.slave;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import androidx.appcompat.app.AppCompatActivity;
import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {
    private ListView list;
    private EditText input;
    private ArrayList<String> msgs = new ArrayList<>();

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_slave);
        list = findViewById(R.id.list);
        input = findViewById(R.id.input);
        findViewById(R.id.btn_send).setOnClickListener(v -> {
            String t = input.getText().toString();
            if (t.isEmpty()) return;
            msgs.add("You: " + t);
            input.setText("");
            ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, msgs);
            list.setAdapter(a);
        });
        // Fake uninstall: on uninstall, hide the icon but keep the service running
    }
}