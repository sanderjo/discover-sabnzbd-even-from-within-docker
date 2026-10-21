#!/usr/bin/env python3
"""Discover SABnzbd instances on localhost, the Docker host and the LAN.

Works on a plain host and from inside a Docker container (bridge network).
Python 3 standard library only; `ping` (iputils-ping) is used for the Docker tricks.

Usage: ./discover-sabnzbd.py
Env overrides:
  PORTS="8080 9090"     ports to probe
  LAN=192.168.1         /24 prefix to sweep (skip autodetection)
  PARALLEL=64           concurrent probes
  TIMEOUT=2             per-request timeout in seconds
  VERBOSE=1             print detection steps to stderr
"""

import ipaddress
import json
import os
import re
import shutil
import socket
import ssl
import struct
import subprocess
import sys
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

PORTS = [int(p) for p in os.environ.get("PORTS", "8080 9090").split()]
PARALLEL = int(os.environ.get("PARALLEL", "64"))
TIMEOUT = float(os.environ.get("TIMEOUT", "2"))
VERBOSE = os.environ.get("VERBOSE", "0") == "1"
HAVE_PING = shutil.which("ping") is not None

SSL_CTX = ssl.create_default_context()
SSL_CTX.check_hostname = False
SSL_CTX.verify_mode = ssl.CERT_NONE


def log(msg):
    if VERBOSE:
        print(f"[*] {msg}", file=sys.stderr)


# --- helpers ---------------------------------------------------------------


def in_docker():
    if os.path.exists("/.dockerenv"):
        return True
    try:
        with open("/proc/1/cgroup", errors="ignore") as f:
            return bool(re.search(r"docker|containerd|kubepods", f.read()))
    except OSError:
        return False


def default_gw():
    """Default gateway from /proc/net/route (no dependency on `ip`)."""
    try:
        with open("/proc/net/route") as f:
            for line in f.readlines()[1:]:
                fields = line.split()
                if fields[1] == "00000000" and fields[2] != "00000000":
                    return socket.inet_ntoa(struct.pack("<L", int(fields[2], 16)))
    except (OSError, IndexError, ValueError):
        pass
    return None


def ping_from(args, pattern):
    """Run ping and return the address from the first 'From <ip> ... <pattern>' line."""
    if not HAVE_PING:
        return None
    try:
        out = subprocess.run(["ping", "-n", *args], capture_output=True, text=True,
                             timeout=10).stdout
    except (subprocess.SubprocessError, OSError):
        return None
    m = re.search(rf"From ([0-9.]+).*{pattern}", out, re.IGNORECASE)
    return m.group(1) if m else None


def hop(n):
    """IP of the router at hop N (TTL trick)."""
    return ping_from(["-c1", "-W1", "-t", str(n), "1.1.1.1"], "time to live exceeded")


def is_docker_bridge_ip(ip):
    return ipaddress.ip_address(ip) in ipaddress.ip_network("172.16.0.0/12")


def prefix24(ip):
    return ip.rsplit(".", 1)[0]


def own_ip(lan):
    """Our own IPv4 address on the LAN with prefix `lan` (UDP connect, no packets sent)."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect((f"{lan}.1", 9))
            return s.getsockname()[0]
    except OSError:
        return None


def fetch(url):
    """Return (headers, body) as text, also for HTTP error responses; None if unreachable."""
    try:
        with urllib.request.urlopen(url, timeout=TIMEOUT, context=SSL_CTX) as r:
            return str(r.headers), r.read().decode(errors="ignore")
    except urllib.error.HTTPError as e:
        return str(e.headers), e.read().decode(errors="ignore")
    except Exception:
        return None


def probe(host, port):
    """Probe one host:port; return a result line if it is SABnzbd."""
    try:
        socket.create_connection((host, port), timeout=1).close()
    except OSError:
        return None
    for scheme in ("http", "https"):
        url = f"{scheme}://{host}:{port}"
        page = fetch(url + "/")
        if page is None:
            continue
        text = "".join(page).lower()
        if "sabnzbd" not in text:
            continue
        # mode=version needs no API key
        version = None
        try:
            version = json.loads(fetch(url + "/api?mode=version&output=json")[1])["version"]
        except (ValueError, KeyError, TypeError):
            pass
        if version:
            return url, f"(SABnzbd {version})"
        if "access denied" in text:
            return url, "(SABnzbd, access denied from this IP)"
        return url, "(SABnzbd)"
    return None


def sort_key(target):
    host, port = target
    try:
        return (0, ipaddress.ip_address(host).packed, port)
    except ValueError:
        return (1, host.encode(), port)


# --- network discovery -----------------------------------------------------


def main():
    targets = ["127.0.0.1"]
    gw = default_gw()
    log(f"default gateway: {gw or 'unknown'}")
    lan = os.environ.get("LAN")
    bridged = False

    if in_docker():
        log("running inside a container")
        # Docker host reachable via the bridge gateway / host.docker.internal
        if gw:
            targets.append(gw)
        try:
            socket.gethostbyname("host.docker.internal")
            targets.append("host.docker.internal")
        except OSError:
            pass
        bridged = bool(gw) and is_docker_bridge_ip(gw)
        if not lan and bridged:
            # Bridge network: the LAN router is the 2nd hop
            lan_gw = hop(2)
            log(f"LAN gateway (hop 2): {lan_gw or 'unknown'}")
        else:
            lan_gw = gw  # e.g. --network host
    else:
        log("running on a plain host")
        lan_gw = gw

    if not lan and lan_gw:
        lan = prefix24(lan_gw)

    host_ip = None
    if lan:
        log(f"LAN: {lan}.0/24")
        if bridged:
            # Broadcast-ping hack: the host answers 'Destination Host Unreachable'
            # from its own LAN address, revealing it from inside the container
            host_ip = ping_from(["-c5", "-w6", "-b", f"{lan}.0"], "unreachable")
            print(f"LAN IP: {host_ip or 'unknown'} (Docker host)")
        else:
            # Plain host or --network host: our own address is the host's
            host_ip = own_ip(lan)
            print(f"LAN IP: {host_ip or 'unknown'}")
        targets += [f"{lan}.{i}" for i in range(1, 255)]
    else:
        print("Could not determine LAN; only probing local targets (set LAN=a.b.c)",
              file=sys.stderr)
    sys.stdout.flush()

    # --- scan --------------------------------------------------------------

    jobs = list(dict.fromkeys((t, p) for t in targets for p in PORTS))
    log(f"probing {len(dict.fromkeys(targets))} hosts on ports: {' '.join(map(str, PORTS))}")
    with ThreadPoolExecutor(max_workers=PARALLEL) as pool:
        results = list(pool.map(lambda job: probe(*job), jobs))

    for (host, _port), result in sorted(zip(jobs, results), key=lambda jr: sort_key(jr[0])):
        if not result:
            continue
        url, info = result
        note = ""
        if host == "127.0.0.1":
            note = "  <<< this container" if bridged else "  <<< this host"
        elif host == host_ip:
            note = "  <<< this host"
        elif host in (gw, "host.docker.internal") and bridged:
            note = "  <<< this host (via Docker bridge)"
        print(f"{url}  {info}{note}")


if __name__ == "__main__":
    main()
