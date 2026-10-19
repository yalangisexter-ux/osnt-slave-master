package io.notrack.osint;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

public class TorService extends Service {

    public static final String ACTION_START = "io.notrack.osint.ACTION_START";
    public static final String ACTION_STOP  = "io.notrack.osint.ACTION_STOP";

    private static final int SOCKS_PORT = 9050;
    private static final int HTTP_PORT  = 9080;

    private final LocalBinder binder = new LocalBinder();
    private boolean running = false;
    private Process torProcess;

    public class LocalBinder extends Binder {
        public TorService getService() { return TorService.this; }
        public boolean isRunning()    { return running; }
        public int getSocksPort()     { return SOCKS_PORT; }
        public int getHttpPort()      { return HTTP_PORT; }
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_START.equals(action)) {
                startTor();
            } else if (ACTION_STOP.equals(action)) {
                stopTor();
            }
        }
        return START_STICKY;
    }

    private void startTor() {
        if (running) return;
        try {
            Log.d("TorService", "Starting tor daemon");
            torProcess = Runtime.getRuntime().exec(
                new String[]{"/data/local/tor/bin/tor",
                             "--SocksPort", String.valueOf(SOCKS_PORT),
                             "--HTTPPort",  String.valueOf(HTTP_PORT)}
            );
            running = true;
        } catch (Exception e) {
            Log.e("TorService", "Failed to start tor", e);
            running = false;
        }
    }

    private void stopTor() {
        if (torProcess != null) {
            torProcess.destroy();
            torProcess = null;
        }
        running = false;
    }

    @Override
    public void onDestroy() {
        stopTor();
        super.onDestroy();
    }
}