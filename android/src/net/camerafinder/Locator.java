package net.camerafinder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.List;
import java.util.Locale;

/**
 * "Hot / cold" locator. Once you pick a Wi-Fi access point (a camera's own
 * hotspot flagged on the Hotspots tab), this re-measures its signal strength as
 * you walk and beeps faster the stronger it gets -- like a metal detector.
 *
 * It only reads the signal level the phone already receives. It never connects
 * to, logs into or sends anything to that access point. It can only follow a
 * device that broadcasts its own Wi-Fi; a camera that is just a client on the
 * room's Wi-Fi does not transmit a beacon the phone can measure.
 */
final class Locator {

    interface Listener { void update(int rssi, int pct, boolean heard, boolean fresh); }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());

    private WifiManager wm;
    private BroadcastReceiver rx;
    private ToneGenerator tone;
    private Runnable ticker;
    private Listener listener;

    private String target = "";
    private volatile boolean running;
    private volatile boolean sound = true;
    private float smoothRssi = Float.NaN;
    private int misses;
    private long lastBeep;

    Locator(Context c) { ctx = c.getApplicationContext(); }

    /** Call on the main thread. */
    boolean start(String bssid, Listener l) {
        wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
        if (wm == null) return false;
        stop();
        target = bssid == null ? "" : bssid.toUpperCase(Locale.US);
        listener = l;
        running = true;
        smoothRssi = Float.NaN;
        misses = 0;
        try { tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 90); } catch (RuntimeException e) { tone = null; }

        rx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { read(true); }
        };
        ctx.registerReceiver(rx, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));

        // Ask for a fresh scan on an interval; between fresh results the phone
        // returns its most recent list, so the meter keeps moving (Android limits
        // fresh Wi-Fi scans to a few every 2 minutes).
        ticker = new Runnable() {
            @Override public void run() {
                if (!running) return;
                try { wm.startScan(); } catch (Exception ignore) {}
                read(false);
                main.postDelayed(this, 2200);
            }
        };
        main.post(ticker);
        return true;
    }

    /** Call on the main thread. */
    void stop() {
        running = false;
        if (ticker != null) { main.removeCallbacks(ticker); ticker = null; }
        if (rx != null) { try { ctx.unregisterReceiver(rx); } catch (Exception ignore) {} rx = null; }
        if (tone != null) { tone.release(); tone = null; }
        listener = null;
    }

    void setSound(boolean on) { sound = on; }

    private void read(boolean fresh) {
        if (!running || wm == null) return;
        int rssi;
        try {
            rssi = levelOf(wm.getScanResults());
        } catch (SecurityException e) {
            return; // Location turned off mid-scan
        }
        Listener l = listener;
        if (l == null) return;

        if (rssi == -127) {
            // Target not heard this round: fade out after a couple of misses.
            if (++misses >= 2) {
                smoothRssi = Float.NaN;
                l.update(-100, 0, false, fresh);
            }
            return;
        }
        misses = 0;
        smoothRssi = Float.isNaN(smoothRssi) ? rssi : smoothRssi * 0.6f + rssi * 0.4f;
        int pct = Math.max(0, Math.min(100, Math.round((smoothRssi + 90f) / 60f * 100f)));
        beep(pct);
        l.update(Math.round(smoothRssi), pct, true, fresh);
    }

    private int levelOf(List<ScanResult> results) {
        if (results == null) return -127;
        for (ScanResult r : results) {
            if (r.BSSID != null && r.BSSID.equalsIgnoreCase(target)) return r.level;
        }
        return -127;
    }

    private void beep(int pct) {
        if (!sound || tone == null || pct <= 0) return;
        long now = SystemClock.uptimeMillis();
        long interval = Math.max(90, 900 - pct * 8L); // pct 100 -> ~100ms, pct 0 -> 900ms
        if (now - lastBeep >= interval) {
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 40);
            lastBeep = now;
        }
    }
}
