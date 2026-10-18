package io.notrack.master;

import android.os.Bundle;
import android.widget.ListView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import androidx.appcompat.app.AppCompatActivity;
import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {
    private ListView list;
    private ArrayList<String> events = new ArrayList<>();

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_master);
        list = findViewById(R.id.list);
        events.add("Slave connected");
        events.add("SMS received: 'Hello there'");
        events.add("WhatsApp message captured");
        events.add("Call recorded (3m 22s)");
        events.add("Media viewed: photo.jpg");
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, events);
        list.setAdapter(a);
    }
}