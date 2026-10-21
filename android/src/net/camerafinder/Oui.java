package net.camerafinder;

import java.util.HashMap;
import java.util.Map;

/**
 * Best-effort MAC-address (OUI) vendor hints, focused on makers of cameras and
 * IoT recording devices. NOT the full IEEE registry -- a match is a hint, a miss
 * is not proof a device is safe. On modern non-rooted Android, other devices'
 * MAC addresses are often hidden from apps, so this frequently returns Unknown;
 * the open-port check (especially RTSP 554) is the stronger signal.
 */
final class Oui {

    private static final Map<String, String> PREFIX = new HashMap<>();
    // Wi-Fi chip makers used in most cheap "mini" / hotspot spy cameras (and in
    // many other smart gadgets, so this is a weak hint, not a camera match).
    private static final Map<String, String> CHIP = new HashMap<>();

    /** @return chip maker name, or null. */
    static String chip(String mac) {
        if (mac == null) return null;
        String norm = normalize(mac);
        return norm.length() < 8 ? null : CHIP.get(norm.substring(0, 8));
    }

    /** @return {vendorName|null, "1" if a camera vendor else "0"}. */
    static String[] lookup(String mac) {
        if (mac == null) return new String[]{null, "0"};
        String norm = normalize(mac);
        if (norm.length() < 8) return new String[]{null, "0"};
        String vendor = PREFIX.get(norm.substring(0, 8)); // "AA:BB:CC"
        if (vendor == null) return new String[]{null, "0"};
        return new String[]{vendor, "1"}; // every vendor in this list is a camera brand
    }

    private static String normalize(String mac) {
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < mac.length() && hex.length() < 12; i++) {
            char c = mac.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))
                hex.append(Character.toUpperCase(c));
        }
        if (hex.length() < 12) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 12; i += 2) {
            if (i > 0) out.append(':');
            out.append(hex, i, i + 2);
        }
        return out.toString();
    }

    private static void p(String prefix, String vendor) { PREFIX.put(prefix, vendor); }

    static {
        // Hikvision
        for (String s : new String[]{"44:19:B6","4C:BD:8F","58:50:AB","C0:56:E3","BC:AD:28",
                "28:57:BE","24:28:FD","E0:CA:3C","A4:14:37","54:C4:15","18:68:CB","8C:E7:48",
                "DC:07:F8","68:35:C9"}) p(s, "Hikvision");
        // Dahua (also OEMs Amcrest, Lorex and others)
        for (String s : new String[]{"3C:EF:8C","90:02:A9","14:A7:8B","08:ED:ED","4C:11:BF",
                "24:52:6A","E0:50:8B","38:AF:29","9C:14:63","F4:B1:C2"}) p(s, "Dahua");
        // Axis
        for (String s : new String[]{"00:40:8C","AC:CC:8E","B8:A4:4F","E8:27:25"}) p(s, "Axis");
        // Ubiquiti (UniFi Protect)
        for (String s : new String[]{"24:A4:3C","44:D9:E7","78:8A:20","FC:EC:DA","68:D7:9A",
                "04:18:D6","DC:9F:DB","B4:FB:E4","F0:9F:C2","74:83:C2","18:E8:29"}) p(s, "Ubiquiti");
        // Google / Nest
        for (String s : new String[]{"18:B4:30","64:16:66","6C:AD:F8","F4:F5:D8","54:60:09",
                "A4:77:33","38:8B:59","D8:6C:63","CC:F4:11"}) p(s, "Google Nest");
        // Amazon / Ring
        for (String s : new String[]{"34:D2:70","44:00:49","F0:81:73","FC:A6:67","68:37:E9",
                "74:C2:46","B0:FC:0D","50:DC:E7","0C:47:C9","AC:63:BE","00:FC:8B"}) p(s, "Amazon / Ring");
        // Wyze
        for (String s : new String[]{"2C:AA:8E","D0:3F:27","7C:78:B2"}) p(s, "Wyze");
        // TP-Link (Tapo / Kasa)
        for (String s : new String[]{"50:C7:BF","1C:3B:F3","98:DA:C4","AC:84:C6","54:AF:97",
                "60:A4:B7","B0:95:75","3C:52:A1","5C:A6:E6"}) p(s, "TP-Link (Tapo/Kasa)");
        // Xiaomi (Mi / Xiaovv cameras)
        for (String s : new String[]{"28:6C:07","34:CE:00","50:8F:4C","64:09:80","78:11:DC",
                "04:CF:8C","F0:B4:29","8C:BE:BE"}) p(s, "Xiaomi");
        // Netgear / Arlo
        for (String s : new String[]{"00:62:6E","A0:40:A0","CC:40:D0"}) p(s, "Netgear / Arlo");
        // Others
        p("00:02:D1", "Vivotek");
        p("00:03:C5", "Mobotix");

        // Espressif (ESP8266 / ESP32 Wi-Fi modules)
        for (String s : new String[]{"24:0A:C4","24:62:AB","24:6F:28","30:AE:A4","3C:71:BF",
                "84:CC:A8","84:F3:EB","A4:CF:12","AC:67:B2","B4:E6:2D","BC:DD:C2","C4:4F:33",
                "CC:50:E3","DC:4F:22","EC:FA:BC","48:3F:DA","8C:AA:B5","98:F4:AB","7C:9E:BD",
                "10:52:1C","E8:DB:84","08:3A:F2","34:94:54","40:F5:20","C8:2B:96","24:A1:60",
                "2C:F4:32","50:02:91","5C:CF:7F","60:01:94","68:C6:3A","80:7D:3A","94:B9:7E",
                "A0:20:A6","BC:FF:4D","D8:BF:C0","E0:98:06","F4:CF:A2"}) CHIP.put(s, "Espressif");
        CHIP.put("00:E0:4C", "Realtek");
    }

    private Oui() {}
}
