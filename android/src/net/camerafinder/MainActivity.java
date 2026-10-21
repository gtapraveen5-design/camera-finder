package net.camerafinder;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Hosts the UI (a local HTML page in a WebView) and exposes a small bridge the
 * page calls into: network scan, nearby-hotspot scan, lens/IR finder, and the
 * magnetic sweep. No remote content is ever loaded.
 */
public class MainActivity extends Activity {

    private static final int REQ_LOCATION = 1;

    private WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Magnet magnet;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        magnet = new Magnet(this);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.addJavascriptInterface(new Bridge(), "Native");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onPause() {
        magnet.stop();
        js("window.onMagStopped && window.onMagStopped()");
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    private void js(final String code) {
        main.post(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    private static String errorJson(String msg, boolean needLocation) {
        try { return new JSONObject().put("error", msg).put("need_location", needLocation).toString(); }
        catch (Exception e) { return "{\"error\":\"failed\"}"; }
    }

    // ---------------------------------------------------------------- hotspots

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean locationOn() {
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            return lm != null && lm.isLocationEnabled();
        } catch (Exception e) {
            return true;
        }
    }

    private void runHotspotScan() {
        if (!locationOn()) {
            js("window.onHotspotResult(" + errorJson(
                    "Turn on Location in quick settings. Android hides nearby Wi-Fi networks from apps "
                    + "while Location is off. This app never reads where you are.", true) + ")");
            return;
        }
        new HotspotScanner(this).scan(json -> js("window.onHotspotResult(" + json + ")"));
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != REQ_LOCATION) return;
        if (hasLocationPermission()) {
            runHotspotScan();
        } else {
            js("window.onHotspotResult(" + errorJson(
                    "Permission needed: Android only lists nearby Wi-Fi networks for apps with the "
                    + "Location permission. This app never reads or stores your location.", false) + ")");
        }
    }

    // ---------------------------------------------------------------- bridge

    /** Methods here are callable from JavaScript as window.Native.* */
    final class Bridge {

        @JavascriptInterface
        public void startScan() {
            new Thread(() -> {
                String json = new Scanner(getApplicationContext()).scanJson();
                js("window.onScanResult(" + json + ")");
            }, "network-scan").start();
        }

        @JavascriptInterface
        public void scanHotspots() {
            main.post(() -> {
                if (!hasLocationPermission()) {
                    requestPermissions(new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
                } else {
                    runHotspotScan();
                }
            });
        }

        @JavascriptInterface
        public void openLocationSettings() {
            main.post(() -> {
                try { startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); }
                catch (Exception ignore) {}
            });
        }

        @JavascriptInterface
        public void openLens(final String mode) {
            main.post(() -> startActivity(new Intent(MainActivity.this, LensActivity.class)
                    .putExtra(LensActivity.EXTRA_MODE, "ir".equals(mode) ? "ir" : "lens")));
        }

        @JavascriptInterface
        public boolean startMag() {
            if (!magnet.available()) return false;
            main.post(() -> magnet.start((value, baseline, calibrating) -> js(String.format(Locale.US,
                    "window.onMag(%.1f,%.1f,%b)", value, baseline, calibrating))));
            return true;
        }

        @JavascriptInterface
        public void stopMag() { main.post(() -> magnet.stop()); }

        @JavascriptInterface
        public void calibrateMag() { main.post(() -> magnet.calibrate()); }

        @JavascriptInterface
        public void setMagSound(boolean on) { magnet.setSound(on); }
    }
}
