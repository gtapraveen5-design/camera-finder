package net.camerafinder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Lists the Wi-Fi networks the phone can hear -- the same list the Wi-Fi settings
 * screen shows -- and flags ones that look like a camera broadcasting its own
 * hotspot, or a portable 4G router a camera could be hiding behind. Passive: it
 * never joins or sends anything to these networks.
 */
final class HotspotScanner {

    interface Done { void result(String json); }

    // Names cheap and branded cameras use for their own setup / direct-connect hotspot.
    private static final Pattern CAM_SSID = Pattern.compile(
            "(hd-?wi-?fi-?cam|ip-?cam|ipc[-_ ]|wi-?fi-?cam|webcam|spy-?cam|look-?cam|v380|^mv\\d{4,}"
            + "|gw_ap|yoosee|jxl-?cam|chuangmi|isa-camera|tapo_cam|wyze|reolink|ezviz|imou|dahua"
            + "|hikvision|^hik|eufy|arlo|^blink|xiaovv|camera|nanny|doorbell|dash-?cam|70mai|ddpai"
            + "|viofo|gopro|(^|[^a-z])cam([^a-z]|$))");
    // Portable / mobile routers: a camera can be connected to one instead of the room's Wi-Fi.
    private static final Pattern MOBILE_ROUTER = Pattern.compile(
            "(mifi|jiofi|(^|[^.0-9])4g|lte|ufi|huawei-e5|zte_mf|^mf\\d{3}|pocket-?wi-?fi|portable)");

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());

    HotspotScanner(Context c) { ctx = c.getApplicationContext(); }

    /** Must be called on the main thread. Calls back on the main thread. */
    void scan(final Done done) {
        final WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
        if (wm == null) { done.result(error("Wi-Fi is not available on this device.")); return; }
        if (!wm.isWifiEnabled()) {
            done.result(error("Turn Wi-Fi on (you don't need to connect to anything), then scan again."));
            return;
        }

        final boolean[] finished = {false};
        final BroadcastReceiver[] rx = new BroadcastReceiver[1];
        final boolean[] throttled = {false};

        final Runnable finish = new Runnable() {
            @Override public void run() {
                if (finished[0]) return;
                finished[0] = true;
                try { ctx.unregisterReceiver(rx[0]); } catch (Exception ignore) {}
                done.result(build(wm, throttled[0]));
            }
        };
        rx[0] = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { finish.run(); }
        };
        ctx.registerReceiver(rx[0], new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));

        boolean started;
        try {
            started = wm.startScan();
        } catch (SecurityException e) {
            started = false;
        }
        throttled[0] = !started;
        // Android allows only a few fresh scans every 2 minutes; when refused, the
        // results the phone collected most recently are still returned.
        main.postDelayed(finish, started ? 8000 : 300);
    }

    private String build(WifiManager wm, boolean throttled) {
        try {
            List<ScanResult> results;
            try {
                results = wm.getScanResults();
            } catch (SecurityException e) {
                return error("Location permission is needed to list nearby Wi-Fi networks.");
            }
            String connected = null;
            try {
                WifiInfo info = wm.getConnectionInfo();
                if (info != null && info.getBSSID() != null) connected = info.getBSSID().toUpperCase(Locale.US);
            } catch (Exception ignore) {}

            // Keep the strongest entry per suspicious access point, and collapse
            // ordinary networks (one name, many access points) into one row.
            Map<String, Net> byKey = new LinkedHashMap<>();
            if (results != null) {
                for (ScanResult r : results) {
                    Net n = new Net();
                    n.ssid = clean(r.SSID);
                    n.bssid = r.BSSID == null ? "" : r.BSSID.toUpperCase(Locale.US);
                    n.rssi = r.level;
                    n.freq = r.frequency;
                    n.caps = r.capabilities == null ? "" : r.capabilities;
                    n.connected = n.bssid.equals(connected);
                    assess(n);
                    String key = (n.score > 0 || n.ssid.isEmpty()) ? n.bssid : "ssid:" + n.ssid;
                    Net prev = byKey.get(key);
                    if (prev == null || n.rssi > prev.rssi || n.connected) {
                        if (prev != null && prev.connected) n.connected = true;
                        byKey.put(key, n);
                    }
                }
            }
            List<Net> nets = new ArrayList<>(byKey.values());
            Collections.sort(nets, (a, b) -> {
                int la = rank(a.level), lb = rank(b.level);
                if (la != lb) return la - lb;
                return b.rssi - a.rssi;
            });

            JSONArray arr = new JSONArray();
            for (Net n : nets) arr.put(n.toJson());
            JSONObject out = new JSONObject();
            out.put("networks", arr);
            out.put("throttled", throttled);
            out.put("scanned_at", new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()));
            return out.toString();
        } catch (Exception e) {
            return error("Hotspot scan failed: " + e);
        }
    }

    private static void assess(Net n) {
        int score = 0;
        List<String> reasons = new ArrayList<>();
        String s = n.ssid.toLowerCase(Locale.US);

        if (!s.isEmpty() && CAM_SSID.matcher(s).find()) {
            score += 3;
            reasons.add("Network name \"" + n.ssid + "\" looks like a camera's own hotspot");
        }
        String[] v = Oui.lookup(n.bssid);
        if (v[0] != null) {
            n.vendor = v[0];
            score += 3;
            reasons.add("Broadcast by " + article(v[0]) + " " + v[0] + " device (a camera brand)");
        } else {
            String chip = Oui.chip(n.bssid);
            if (chip != null) {
                n.vendor = chip + " Wi-Fi chip";
                score += 1;
                reasons.add("Uses " + article(chip) + " " + chip + " Wi-Fi chip, common in cheap mini cameras (and other smart gadgets)");
            }
        }
        if (!s.isEmpty() && MOBILE_ROUTER.matcher(s).find() && n.rssi >= -65) {
            score += 1;
            reasons.add("Portable 4G / mobile router close by - a camera could be connected to it instead of the room's Wi-Fi");
        }
        if (s.isEmpty() && n.rssi >= -60) {
            score += 1;
            reasons.add("Hidden network (no name) with a strong signal - something very close is broadcasting");
        }
        boolean open = !(n.caps.contains("WPA") || n.caps.contains("WEP") || n.caps.contains("SAE")
                || n.caps.contains("RSN") || n.caps.contains("EAP"));
        n.open = open;
        if (open && score > 0) reasons.add("No password - camera setup hotspots are usually open");
        if (score > 0 && n.rssi >= -55) reasons.add("Very strong signal - the device is probably within a few metres");

        if (score >= 3) n.level = "high";
        else if (score >= 1) n.level = "medium";
        else n.level = "low";
        if (reasons.isEmpty()) reasons.add("Looks like an ordinary network");
        n.score = score;
        n.reasons = reasons;
    }

    private static String article(String word) {
        return !word.isEmpty() && "AEIOUaeiou".indexOf(word.charAt(0)) >= 0 ? "an" : "a";
    }

    private static int rank(String level) {
        return "high".equals(level) ? 0 : "medium".equals(level) ? 1 : 2;
    }

    private static String clean(String ssid) {
        if (ssid == null) return "";
        String t = ssid.replace("\u0000", "").trim();
        return "<unknown ssid>".equalsIgnoreCase(t) ? "" : t;
    }

    private static String error(String msg) {
        try { return new JSONObject().put("error", msg).toString(); }
        catch (Exception e) { return "{\"error\":\"Hotspot scan failed\"}"; }
    }

    private static final class Net {
        String ssid, bssid, caps, vendor, level = "low";
        int rssi, freq, score;
        boolean open, connected;
        List<String> reasons = new ArrayList<>();

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("ssid", ssid);
            o.put("bssid", bssid);
            o.put("vendor", vendor);
            o.put("rssi", rssi);
            o.put("proximity", rssi >= -50 ? "Very close (likely this room)"
                    : rssi >= -65 ? "Nearby" : rssi >= -78 ? "Farther away" : "Far / weak");
            o.put("band", freq >= 5925 ? "6 GHz" : freq >= 4900 ? "5 GHz" : "2.4 GHz");
            o.put("open", open);
            o.put("security", open ? "Open (no password)" : caps.replaceAll("\\[ESS\\]|\\[WPS\\]", "").trim());
            o.put("connected", connected);
            o.put("score", score);
            o.put("level", level);
            JSONArray rs = new JSONArray();
            for (String r : reasons) rs.put(r);
            o.put("reasons", rs);
            return o;
        }
    }
}
