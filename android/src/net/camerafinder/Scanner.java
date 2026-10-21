package net.camerafinder;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Observes the local Wi-Fi: discovers devices on this /24, checks the ports
 * cameras commonly expose, and scores each device. It never logs into or sends
 * commands to any device -- it only opens-and-closes TCP connections to see
 * whether a port answers, the same thing any port scanner does.
 */
final class Scanner {

    private final Context ctx;
    Scanner(Context c) { this.ctx = c.getApplicationContext(); }

    // Ports that are almost exclusively video devices.
    private static final int[] STRONG_PORTS = {554, 8554, 37777, 34567, 8000, 8899, 2020};
    // Generic web-admin ports -- shown as context only, not scored on their own.
    private static final int[] WEB_PORTS = {80, 443, 8080};
    private static final int[] ALL_PORTS =
            {554, 8554, 37777, 34567, 8000, 8899, 2020, 80, 443, 8080};

    private static final Map<Integer, String> PORT_LABELS = new HashMap<>();
    static {
        PORT_LABELS.put(554, "RTSP video stream");
        PORT_LABELS.put(8554, "RTSP video stream (alt)");
        PORT_LABELS.put(37777, "Dahua camera/NVR service");
        PORT_LABELS.put(34567, "Xiongmai DVR/camera service");
        PORT_LABELS.put(8000, "Hikvision camera SDK");
        PORT_LABELS.put(8899, "ONVIF camera control");
        PORT_LABELS.put(2020, "ONVIF camera control (alt)");
        PORT_LABELS.put(80, "Web admin page (HTTP)");
        PORT_LABELS.put(443, "Web admin page (HTTPS)");
        PORT_LABELS.put(8080, "Web admin page (HTTP alt)");
    }
    private static final String[] HOST_KEYWORDS = {"cam", "ipc", "nvr", "dvr", "hik",
            "dahua", "reolink", "wyze", "tapo", "arlo", "eufy", "nest", "ring",
            "doorbell", "imou", "ezviz"};

    private static final Map<String, Integer> LEVEL_ORDER = new HashMap<>();
    static {
        LEVEL_ORDER.put("high", 0); LEVEL_ORDER.put("medium", 1); LEVEL_ORDER.put("low", 2);
        LEVEL_ORDER.put("router", 3); LEVEL_ORDER.put("self", 4);
    }

    /** Runs the scan and returns a JSON string (never throws). */
    String scanJson() {
        try {
            return scan().toString();
        } catch (Exception e) {
            try {
                return new JSONObject().put("error", "Scan failed: " + e).toString();
            } catch (Exception ignore) {
                return "{\"error\":\"Scan failed\"}";
            }
        }
    }

    private JSONObject scan() throws Exception {
        long t0 = System.currentTimeMillis();

        Inet4Address myAddr = localIp();
        if (myAddr == null) {
            return new JSONObject().put("error",
                    "Not connected to Wi-Fi. Join the room's Wi-Fi, then scan again.");
        }
        final String myIp = myAddr.getHostAddress();
        final String base = myIp.substring(0, myIp.lastIndexOf('.') + 1);
        final String gateway = gatewayIp(base);
        final Map<String, String> arp = readArp();

        // --- Discovery + port probe across the whole /24, in parallel ---
        ExecutorService pool = Executors.newFixedThreadPool(128);
        List<Future<Host>> jobs = new ArrayList<>();
        for (int i = 1; i <= 254; i++) {
            final String ip = base + i;
            final boolean self = ip.equals(myIp);
            final boolean inArp = arp.containsKey(ip);
            jobs.add(pool.submit(() -> probe(ip, self, inArp)));
        }
        List<Host> hosts = new ArrayList<>();
        for (Future<Host> f : jobs) {
            Host h = f.get();
            if (h != null) hosts.add(h);
        }
        pool.shutdownNow();

        boolean hasSelf = false;
        for (Host h : hosts) if (h.ip.equals(myIp)) hasSelf = true;
        if (!hasSelf) {
            Host h = new Host(); h.ip = myIp; h.self = true; h.reachable = true;
            hosts.add(h);
        }

        // --- Reverse DNS for the (few) devices we kept ---
        ExecutorService dns = Executors.newFixedThreadPool(32);
        List<Future<?>> dnsJobs = new ArrayList<>();
        for (final Host h : hosts) dnsJobs.add(dns.submit(() -> h.hostname = reverseDns(h.ip)));
        for (Future<?> f : dnsJobs) {
            try { f.get(1500, TimeUnit.MILLISECONDS); } catch (Exception ignore) {}
        }
        dns.shutdownNow();

        // --- Vendor + scoring ---
        for (Host h : hosts) {
            h.mac = arp.get(h.ip);
            String[] v = Oui.lookup(h.mac);
            h.vendor = v[0];
            h.vendorIsCamera = "1".equals(v[1]);
            h.gateway = h.ip.equals(gateway);
            assess(h);
        }

        Collections.sort(hosts, (a, b) -> {
            int la = LEVEL_ORDER.get(a.level), lb = LEVEL_ORDER.get(b.level);
            if (la != lb) return la - lb;
            if (a.score != b.score) return b.score - a.score;
            return lastOctet(a.ip) - lastOctet(b.ip);
        });

        JSONArray devices = new JSONArray();
        for (Host h : hosts) devices.put(h.toJson());

        JSONObject out = new JSONObject();
        out.put("network", base + "0/24");
        out.put("your_ip", myIp);
        out.put("gateway", gateway);
        out.put("duration_s", Math.round((System.currentTimeMillis() - t0) / 100.0) / 10.0);
        out.put("scanned_at", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        out.put("devices", devices);
        return out;
    }

    private Host probe(String ip, boolean self, boolean inArp) {
        Host h = new Host();
        h.ip = ip;
        h.self = self;
        boolean reachable = false;
        try { reachable = InetAddress.getByName(ip).isReachable(250); } catch (Exception ignore) {}
        h.reachable = reachable;

        // Live hosts get the full port list; silent hosts get only the strong
        // camera ports, so a camera that drops pings is still caught cheaply.
        int[] ports = reachable ? ALL_PORTS : STRONG_PORTS;
        List<Integer> open = new ArrayList<>();
        for (int p : ports) if (portOpen(ip, p, 300)) open.add(p);
        h.openPorts = open;

        if (self || reachable || inArp || !open.isEmpty()) return h;
        return null;
    }

    private boolean portOpen(String ip, int port, int timeoutMs) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(ip, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try { s.close(); } catch (Exception ignore) {}
        }
    }

    private void assess(Host h) {
        int score = 0;
        List<String> reasons = new ArrayList<>();

        if (h.vendorIsCamera) {
            score += 3;
            reasons.add("Made by " + h.vendor + ", a camera / smart-home video brand");
        }
        if (h.openPorts.contains(554) || h.openPorts.contains(8554)) {
            score += 3;
            reasons.add("RTSP video-streaming port is open - typical of IP cameras");
        }
        List<Integer> otherStrong = new ArrayList<>();
        for (int p : new int[]{37777, 34567, 8000, 8899, 2020})
            if (h.openPorts.contains(p)) otherStrong.add(p);
        if (!otherStrong.isEmpty()) {
            score += 2;
            StringBuilder sb = new StringBuilder("Camera/NVR service port open: ");
            for (int i = 0; i < otherStrong.size(); i++) {
                int p = otherStrong.get(i);
                if (i > 0) sb.append(", ");
                sb.append(p).append(" (").append(PORT_LABELS.get(p)).append(")");
            }
            reasons.add(sb.toString());
        }
        String name = h.hostname == null ? "" : h.hostname.toLowerCase(Locale.US);
        for (String k : HOST_KEYWORDS) {
            if (name.contains(k)) {
                score += 2;
                reasons.add("Network name '" + h.hostname + "' looks camera-related");
                break;
            }
        }
        List<Integer> web = new ArrayList<>();
        for (int p : WEB_PORTS) if (h.openPorts.contains(p)) web.add(p);
        if (!web.isEmpty() && score > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < web.size(); i++) { if (i > 0) sb.append(", "); sb.append(web.get(i)); }
            reasons.add("Also has a web admin page (" + sb + ")");
        }

        if (h.self) h.level = "self";
        else if (h.gateway && score < 3) h.level = "router";
        else if (score >= 3) h.level = "high";
        else if (score >= 1) h.level = "medium";
        else h.level = "low";

        if (reasons.isEmpty()) reasons.add("No camera signals found");
        h.score = score;
        h.reasons = reasons;
    }

    // --- Network helpers ---

    private Inet4Address localIp() {
        try {
            Inet4Address fallback = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        String n = ni.getName() == null ? "" : ni.getName().toLowerCase(Locale.US);
                        if (n.startsWith("wlan") || n.startsWith("ap") || n.contains("wifi"))
                            return (Inet4Address) a;
                        if (fallback == null) fallback = (Inet4Address) a;
                    }
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }

    private String gatewayIp(String base) {
        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                DhcpInfo d = wm.getDhcpInfo();
                if (d != null && d.gateway != 0) return intToIp(d.gateway);
            }
        } catch (Exception ignore) {}
        return base + "1";
    }

    private static String intToIp(int i) {
        return (i & 0xff) + "." + ((i >> 8) & 0xff) + "." + ((i >> 16) & 0xff) + "." + ((i >> 24) & 0xff);
    }

    private Map<String, String> readArp() {
        Map<String, String> m = new HashMap<>();
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/proc/net/arp"));
            String line;
            boolean first = true;
            while ((line = r.readLine()) != null) {
                if (first) { first = false; continue; }
                String[] p = line.trim().split("\\s+");
                if (p.length >= 4) {
                    String ip = p[0];
                    String mac = p[3].toUpperCase(Locale.US);
                    if (!mac.equals("00:00:00:00:00:00")) m.put(ip, mac);
                }
            }
        } catch (Exception ignore) {
        } finally {
            if (r != null) try { r.close(); } catch (Exception ignore) {}
        }
        return m;
    }

    private String reverseDns(String ip) {
        try {
            String h = InetAddress.getByName(ip).getCanonicalHostName();
            if (h != null && !h.equals(ip)) return h;
        } catch (Exception ignore) {}
        return null;
    }

    private static int lastOctet(String ip) {
        try { return Integer.parseInt(ip.substring(ip.lastIndexOf('.') + 1)); }
        catch (Exception e) { return 0; }
    }

    /** One discovered device. */
    private static final class Host {
        String ip;
        String mac;
        String vendor;
        boolean vendorIsCamera;
        String hostname;
        List<Integer> openPorts = new ArrayList<>();
        boolean reachable;
        boolean self;
        boolean gateway;
        int score;
        String level = "low";
        List<String> reasons = new ArrayList<>();

        boolean randomizedMac() {
            if (mac == null || mac.length() < 2) return false;
            try { return (Integer.parseInt(mac.substring(0, 2), 16) & 0x2) != 0; }
            catch (Exception e) { return false; }
        }

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("ip", ip);
            o.put("mac", mac != null ? mac : (self ? "(this phone)" : "unknown"));
            o.put("vendor", vendor);
            o.put("vendor_is_camera", vendorIsCamera);
            o.put("randomized_mac", mac != null && randomizedMac());
            o.put("hostname", hostname);
            JSONArray ports = new JSONArray();
            JSONObject labels = new JSONObject();
            for (int p : openPorts) { ports.put(p); labels.put(String.valueOf(p), PORT_LABELS.get(p)); }
            o.put("open_ports", ports);
            o.put("port_labels", labels);
            o.put("responds_to_ping", reachable);
            o.put("is_self", self);
            o.put("is_gateway", gateway);
            o.put("score", score);
            o.put("level", level);
            JSONArray rs = new JSONArray();
            for (String r : reasons) rs.put(r);
            o.put("reasons", rs);
            return o;
        }
    }
}
