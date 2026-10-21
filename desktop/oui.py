"""
Best-effort MAC-address (OUI) vendor hints, focused on makers of cameras and
IoT recording devices.

IMPORTANT: this is a small, hand-curated list, NOT the full IEEE OUI registry.
A match is a *hint*, not proof, and a miss does NOT mean a device is safe --
plenty of cameras use generic or re-badged chipsets. The scan also uses open
network ports (especially RTSP 554) and hostnames, which are more reliable
signals than the vendor alone.
"""

# Vendor names that commonly make network cameras / NVRs / video doorbells.
CAMERA_VENDORS = {
    "Hikvision", "Dahua", "Axis", "Ubiquiti", "Google Nest", "Amazon / Ring",
    "Wyze", "Reolink", "Amcrest", "TP-Link (Tapo/Kasa)", "Xiaomi", "Eufy / Anker",
    "Netgear / Arlo", "Vivotek", "Mobotix", "Lorex",
}

# MAC prefix (first 3 octets, "AA:BB:CC") -> vendor name.
# Curated from commonly documented prefixes; treat as approximate.
VENDOR_PREFIXES = {
    # Hikvision
    "44:19:B6": "Hikvision", "4C:BD:8F": "Hikvision", "58:50:AB": "Hikvision",
    "C0:56:E3": "Hikvision", "BC:AD:28": "Hikvision", "28:57:BE": "Hikvision",
    "24:28:FD": "Hikvision", "E0:CA:3C": "Hikvision", "A4:14:37": "Hikvision",
    "54:C4:15": "Hikvision", "18:68:CB": "Hikvision", "8C:E7:48": "Hikvision",
    "DC:07:F8": "Hikvision", "68:35:C9": "Hikvision",
    # Dahua (also OEMs Amcrest, Lorex, many others)
    "3C:EF:8C": "Dahua", "90:02:A9": "Dahua", "14:A7:8B": "Dahua",
    "08:ED:ED": "Dahua", "4C:11:BF": "Dahua", "24:52:6A": "Dahua",
    "E0:50:8B": "Dahua", "38:AF:29": "Dahua", "9C:14:63": "Dahua",
    "F4:B1:C2": "Dahua",
    # Axis Communications
    "00:40:8C": "Axis", "AC:CC:8E": "Axis", "B8:A4:4F": "Axis",
    "E8:27:25": "Axis",
    # Ubiquiti (UniFi Protect cameras)
    "24:A4:3C": "Ubiquiti", "44:D9:E7": "Ubiquiti", "78:8A:20": "Ubiquiti",
    "FC:EC:DA": "Ubiquiti", "68:D7:9A": "Ubiquiti", "04:18:D6": "Ubiquiti",
    "DC:9F:DB": "Ubiquiti", "B4:FB:E4": "Ubiquiti", "F0:9F:C2": "Ubiquiti",
    "74:83:C2": "Ubiquiti", "18:E8:29": "Ubiquiti",
    # Google / Nest
    "18:B4:30": "Google Nest", "64:16:66": "Google Nest", "6C:AD:F8": "Google Nest",
    "F4:F5:D8": "Google Nest", "54:60:09": "Google Nest", "A4:77:33": "Google Nest",
    "38:8B:59": "Google Nest", "D8:6C:63": "Google Nest", "CC:F4:11": "Google Nest",
    # Amazon / Ring
    "34:D2:70": "Amazon / Ring", "44:00:49": "Amazon / Ring", "F0:81:73": "Amazon / Ring",
    "FC:A6:67": "Amazon / Ring", "68:37:E9": "Amazon / Ring", "74:C2:46": "Amazon / Ring",
    "B0:FC:0D": "Amazon / Ring", "50:DC:E7": "Amazon / Ring", "0C:47:C9": "Amazon / Ring",
    "AC:63:BE": "Amazon / Ring", "00:FC:8B": "Amazon / Ring",
    # Wyze
    "2C:AA:8E": "Wyze", "D0:3F:27": "Wyze", "7C:78:B2": "Wyze",
    # TP-Link (Tapo / Kasa cameras)
    "50:C7:BF": "TP-Link (Tapo/Kasa)", "1C:3B:F3": "TP-Link (Tapo/Kasa)",
    "98:DA:C4": "TP-Link (Tapo/Kasa)", "AC:84:C6": "TP-Link (Tapo/Kasa)",
    "54:AF:97": "TP-Link (Tapo/Kasa)", "60:A4:B7": "TP-Link (Tapo/Kasa)",
    "B0:95:75": "TP-Link (Tapo/Kasa)", "3C:52:A1": "TP-Link (Tapo/Kasa)",
    "5C:A6:E6": "TP-Link (Tapo/Kasa)",
    # Xiaomi (Mi / Xiaovv cameras)
    "28:6C:07": "Xiaomi", "34:CE:00": "Xiaomi", "50:8F:4C": "Xiaomi",
    "64:09:80": "Xiaomi", "78:11:DC": "Xiaomi", "04:CF:8C": "Xiaomi",
    "F0:B4:29": "Xiaomi", "8C:BE:BE": "Xiaomi",
    # Netgear / Arlo
    "00:62:6E": "Netgear / Arlo", "A0:40:A0": "Netgear / Arlo", "CC:40:D0": "Netgear / Arlo",
    # Others
    "00:02:D1": "Vivotek", "00:03:C5": "Mobotix",
}


def _normalize(mac: str) -> str:
    """Return MAC as 'AA:BB:CC:DD:EE:FF' uppercase, or '' if unparseable."""
    hexonly = "".join(c for c in mac if c in "0123456789abcdefABCDEF").upper()
    if len(hexonly) < 12:
        return ""
    hexonly = hexonly[:12]
    return ":".join(hexonly[i:i + 2] for i in range(0, 12, 2))


def lookup_vendor(mac: str):
    """Return (vendor_name_or_None, is_camera_vendor_bool)."""
    norm = _normalize(mac)
    if not norm:
        return None, False
    prefix = norm[:8]  # 'AA:BB:CC'
    vendor = VENDOR_PREFIXES.get(prefix)
    if vendor is None:
        return None, False
    return vendor, vendor in CAMERA_VENDORS
