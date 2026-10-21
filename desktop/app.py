#!/usr/bin/env python3
"""
Camera Finder - spot possible hidden cameras on the Wi-Fi network you're on.

Defensive / self-protection use only. It:
  * discovers devices on YOUR local subnet (ping sweep + ARP table),
  * identifies the hardware vendor from the MAC address (offline list),
  * checks whether common camera / video-stream ports are open.

It does NOT log into, send commands to, or change anything on other devices.
It only observes, the same way Fing or hidden-camera-detector apps do.

Run:  python app.py      (then the browser opens at http://127.0.0.1:8765)
"""
import concurrent.futures as cf
import ipaddress
import json
import platform
import re
import socket
import subprocess
import threading
import time
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from oui import lookup_vendor

HERE = Path(__file__).resolve().parent
STATIC = HERE / "static"
HOST, PORT = "127.0.0.1", 8765
IS_WINDOWS = platform.system() == "Windows"

# Ports cameras / NVRs commonly expose. "strong" ones are rarely used by
# anything except video devices; "web" ones are generic and only shown as context.
CAMERA_PORTS = {
    554: ("RTSP video stream", "strong"),
    8554: ("RTSP video stream (alt)", "strong"),
    37777: ("Dahua camera/NVR service", "strong"),
    34567: ("Xiongmai DVR/camera service", "strong"),
    8000: ("Hikvision camera SDK", "strong"),
    8899: ("ONVIF camera control", "strong"),
    2020: ("ONVIF camera control (alt)", "strong"),
    80: ("Web admin page (HTTP)", "web"),
    443: ("Web admin page (HTTPS)", "web"),
    8080: ("Web admin page (HTTP alt)", "web"),
}

HOSTNAME_KEYWORDS = (
    "cam", "ipc", "nvr", "dvr", "hik", "dahua", "reolink", "wyze", "tapo",
    "arlo", "eufy", "nest", "ring", "doorbell", "imou", "ezviz", "yi-",
)

_scan_lock = threading.Lock()


# --------------------------------------------------------------------------- #
# Helpers
# --------------------------------------------------------------------------- #
def _run(cmd, timeout=5):
    kwargs = {"capture_output": True, "text": True, "timeout": timeout}
    if IS_WINDOWS:
        kwargs["creationflags"] = 0x08000000  # CREATE_NO_WINDOW: no console flashes
    try:
        return subprocess.run(cmd, **kwargs)
    except (subprocess.TimeoutExpired, OSError):
        return None


def local_ip():
    """IP of the interface used for outbound traffic (no packet is actually sent)."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    except OSError:
        return None
    finally:
        s.close()


def ping(ip):
    cmd = (["ping", "-n", "1", "-w", "500", ip] if IS_WINDOWS
           else ["ping", "-c", "1", "-W", "1", ip])
    r = _run(cmd, timeout=3)
    # On Windows, "Destination host unreachable" can still return 0, so check for TTL.
    return bool(r and r.returncode == 0 and "ttl=" in r.stdout.lower())


ARP_LINE = re.compile(
    r"(\d{1,3}(?:\.\d{1,3}){3})\D+?([0-9a-fA-F]{2}(?:[:-][0-9a-fA-F]{2}){5})")


def read_arp_table():
    r = _run(["arp", "-a"], timeout=8)
    table = {}
    if not r:
        return table
    for ip, mac in ARP_LINE.findall(r.stdout):
        mac = mac.replace("-", ":").upper()
        if mac in ("FF:FF:FF:FF:FF:FF", "00:00:00:00:00:00") or mac.startswith("01:00:5E"):
            continue  # broadcast / multicast entries
        table[ip] = mac
    return table


def is_randomized_mac(mac):
    """Phones/laptops often use private (locally administered) MACs."""
    try:
        return bool(int(mac[1], 16) & 0x2)
    except (ValueError, IndexError):
        return False


def port_open(ip, port, timeout=0.6):
    try:
        with socket.create_connection((ip, port), timeout=timeout):
            return True
    except OSError:
        return False


def reverse_dns(ip):
    try:
        return socket.gethostbyaddr(ip)[0]
    except OSError:
        return None


# --------------------------------------------------------------------------- #
# Scoring
# --------------------------------------------------------------------------- #
def assess(dev):
    score, reasons = 0, []

    if dev["vendor_is_camera"]:
        score += 3
        reasons.append(f"Made by {dev['vendor']}, a camera / smart-home video brand")

    strong = [p for p in dev["open_ports"] if CAMERA_PORTS[p][1] == "strong"]
    if 554 in strong or 8554 in strong:
        score += 3
        reasons.append("RTSP video-streaming port is open - typical of IP cameras")
    other_strong = [p for p in strong if p not in (554, 8554)]
    if other_strong:
        score += 2
        reasons.append("Camera/NVR service port open: "
                       + ", ".join(f"{p} ({CAMERA_PORTS[p][0]})" for p in other_strong))

    name = (dev["hostname"] or "").lower()
    if any(k in name for k in HOSTNAME_KEYWORDS):
        score += 2
        reasons.append(f"Network name '{dev['hostname']}' looks camera-related")

    web = [p for p in dev["open_ports"] if CAMERA_PORTS[p][1] == "web"]
    if web and score > 0:
        reasons.append("Also has a web admin page (" + ", ".join(map(str, web)) + ")")

    if dev["is_self"]:
        level = "self"
    elif dev["is_gateway"] and score < 3:
        level = "router"
    elif score >= 3:
        level = "high"
    elif score >= 1:
        level = "medium"
    else:
        level = "low"

    if not reasons:
        reasons.append("No camera signals found")
    return score, level, reasons


# --------------------------------------------------------------------------- #
# Scan
# --------------------------------------------------------------------------- #
def scan():
    started = time.time()
    my_ip = local_ip()
    if not my_ip or my_ip.startswith("127."):
        return {"error": "Not connected to a network. Join the Wi-Fi first, then scan again."}

    net = ipaddress.ip_network(f"{my_ip}/24", strict=False)
    hosts = [str(h) for h in net.hosts()]
    gateway = str(net.network_address + 1)

    # 1) Ping sweep - also fills the OS ARP table with devices that ignore ping.
    with cf.ThreadPoolExecutor(max_workers=128) as pool:
        alive = {ip for ip, ok in zip(hosts, pool.map(ping, hosts)) if ok}

    # 2) ARP table - catches devices that block ping but still answered ARP.
    arp = {ip: mac for ip, mac in read_arp_table().items()
           if ipaddress.ip_address(ip) in net}

    found = sorted(alive | set(arp) | {my_ip},
                   key=lambda ip: ipaddress.ip_address(ip))

    # 3) Camera-port check + reverse DNS, in parallel.
    with cf.ThreadPoolExecutor(max_workers=128) as pool:
        port_jobs = {(ip, p): pool.submit(port_open, ip, p)
                     for ip in found if ip != my_ip for p in CAMERA_PORTS}
        dns_jobs = {ip: pool.submit(reverse_dns, ip) for ip in found}
        open_ports = {ip: sorted(p for (h, p), f in port_jobs.items()
                                 if h == ip and f.result())
                      for ip in found}
        hostnames = {ip: f.result() for ip, f in dns_jobs.items()}

    devices = []
    for ip in found:
        mac = arp.get(ip, "")
        vendor, cam_vendor = lookup_vendor(mac) if mac else (None, False)
        dev = {
            "ip": ip,
            "mac": mac or ("(this computer)" if ip == my_ip else "unknown"),
            "vendor": vendor,
            "vendor_is_camera": cam_vendor,
            "randomized_mac": bool(mac) and is_randomized_mac(mac),
            "hostname": hostnames.get(ip),
            "open_ports": open_ports.get(ip, []),
            "port_labels": {p: CAMERA_PORTS[p][0] for p in open_ports.get(ip, [])},
            "responds_to_ping": ip in alive,
            "is_self": ip == my_ip,
            "is_gateway": ip == gateway,
        }
        dev["score"], dev["level"], dev["reasons"] = assess(dev)
        devices.append(dev)

    order = {"high": 0, "medium": 1, "low": 2, "router": 3, "self": 4}
    devices.sort(key=lambda d: (order[d["level"]], -d["score"],
                                ipaddress.ip_address(d["ip"])))
    return {
        "network": str(net),
        "your_ip": my_ip,
        "gateway": gateway,
        "duration_s": round(time.time() - started, 1),
        "scanned_at": time.strftime("%Y-%m-%d %H:%M:%S"),
        "devices": devices,
    }


# --------------------------------------------------------------------------- #
# Local web UI (bound to 127.0.0.1 only - not reachable from the network)
# --------------------------------------------------------------------------- #
class Handler(BaseHTTPRequestHandler):
    def _send(self, code, body, ctype):
        data = body if isinstance(body, bytes) else body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path in ("/", "/index.html"):
            self._send(200, (STATIC / "index.html").read_bytes(), "text/html; charset=utf-8")
        elif self.path == "/api/scan":
            if not _scan_lock.acquire(blocking=False):
                self._send(409, json.dumps({"error": "A scan is already running."}),
                           "application/json")
                return
            try:
                result = scan()
            except Exception as exc:  # report instead of crashing the server
                result = {"error": f"Scan failed: {exc}"}
            finally:
                _scan_lock.release()
            self._send(200, json.dumps(result), "application/json")
        else:
            self._send(404, "Not found", "text/plain")

    def log_message(self, *args):
        pass  # keep the console quiet


def main():
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    url = f"http://{HOST}:{PORT}/"
    print(f"Camera Finder running at {url}  (Ctrl+C to stop)")
    threading.Timer(0.8, lambda: webbrowser.open(url)).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nStopped.")


if __name__ == "__main__":
    main()
