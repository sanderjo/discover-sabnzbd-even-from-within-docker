#!/usr/bin/env bash
# Discover SABnzbd instances on localhost, the Docker host and the LAN.
# Works on a plain host and from inside a Docker container (bridge network).
#
# Usage: ./discover-sabnzbd.sh
# Env overrides:
#   PORTS="8080 9090"     ports to probe
#   LAN=192.168.1         /24 prefix to sweep (skip autodetection)
#   PARALLEL=64           concurrent probes
#   TIMEOUT=2             per-request timeout in seconds
#   VERBOSE=1             print detection steps to stderr

set -u

PORTS=${PORTS:-"8080 9090"}
PARALLEL=${PARALLEL:-64}
TIMEOUT=${TIMEOUT:-2}
VERBOSE=${VERBOSE:-0}

log() { [ "$VERBOSE" = 1 ] && echo "[*] $*" >&2; return 0; }

command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }
HAVE_PING=0; command -v ping >/dev/null && HAVE_PING=1

# --- helpers ---------------------------------------------------------------

in_docker() {
  [ -f /.dockerenv ] && return 0
  grep -qaE 'docker|containerd|kubepods' /proc/1/cgroup 2>/dev/null && return 0
  return 1
}

# Default gateway from /proc/net/route (no dependency on `ip`)
default_gw() {
  awk '$2=="00000000" && $3!="00000000" {print $3; exit}' /proc/net/route 2>/dev/null |
    sed -E 's/(..)(..)(..)(..)/0x\4 0x\3 0x\2 0x\1/' |
    xargs -r printf '%d.%d.%d.%d\n'
}

# IP of the router at hop N (TTL trick)
hop() {
  [ "$HAVE_PING" = 1 ] || return
  ping -n -c1 -W1 -t "$1" 1.1.1.1 2>/dev/null |
    awk '/[Tt]ime to live exceeded/ {sub(/:$/,"",$2); print $2; exit}'
}

is_docker_bridge_ip() { [[ $1 =~ ^172\.(1[6-9]|2[0-9]|3[01])\. ]]; }

prefix24() { echo "${1%.*}"; }

# Probe one host:port; print a result line if it is SABnzbd
probe() {
  local hostport=$1 scheme url body version
  for scheme in http https; do
    url="$scheme://$hostport"
    body=$(curl -sk -L -i --connect-timeout 1 --max-time "$TIMEOUT" "$url/" 2>/dev/null) || continue
    if grep -qi sabnzbd <<<"$body"; then
      # mode=version needs no API key
      version=$(curl -sk --max-time "$TIMEOUT" "$url/api?mode=version&output=json" 2>/dev/null |
        sed -nE 's/.*"version"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/p')
      if [ -n "$version" ]; then
        echo "$url  (SABnzbd $version)"
      elif grep -qi 'access denied' <<<"$body"; then
        echo "$url  (SABnzbd, access denied from this IP)"
      else
        echo "$url  (SABnzbd)"
      fi
      return
    fi
  done
}
export -f probe
export TIMEOUT

# --- network discovery -----------------------------------------------------

targets=(127.0.0.1)
GW=$(default_gw)
log "default gateway: ${GW:-unknown}"

if in_docker; then
  log "running inside a container"
  # Docker host reachable via the bridge gateway / host.docker.internal
  [ -n "$GW" ] && targets+=("$GW")
  getent hosts host.docker.internal >/dev/null 2>&1 && targets+=(host.docker.internal)

  if [ -z "${LAN:-}" ] && [ -n "$GW" ] && is_docker_bridge_ip "$GW"; then
    # Bridge network: the LAN router is the 2nd hop
    LANGW=$(hop 2)
    log "LAN gateway (hop 2): ${LANGW:-unknown}"
  else
    LANGW=$GW   # e.g. --network host
  fi
else
  log "running on a plain host"
  LANGW=$GW
fi

[ -z "${LAN:-}" ] && [ -n "${LANGW:-}" ] && LAN=$(prefix24 "$LANGW")

if [ -n "${LAN:-}" ]; then
  log "LAN: $LAN.0/24"
  # Broadcast-ping hack: the host answers 'Destination Host Unreachable'
  # from its own LAN address, revealing it from inside the container
  if [ "$HAVE_PING" = 1 ] && in_docker; then
    HOSTIP=$(ping -n -c5 -w6 -b "$LAN.0" 2>&1 |
      awk '/Unreachable/ {sub(/:$/,"",$2); print $2; exit}')
    log "Docker host LAN IP: ${HOSTIP:-unknown}"
  fi
  for i in $(seq 1 254); do targets+=("$LAN.$i"); done
else
  echo "Could not determine LAN; only probing local targets (set LAN=a.b.c)" >&2
fi

# --- scan ------------------------------------------------------------------

log "probing ${#targets[@]} hosts on ports: $PORTS"
for t in "${targets[@]}"; do
  for p in $PORTS; do echo "$t:$p"; done
done | awk '!seen[$0]++' |
  xargs -r -P "$PARALLEL" -I{} bash -c 'probe "$1"' _ {} |
  sort -V |
  while read -r line; do
    host=${line#*://}; host=${host%%:*}
    note=""
    [ "${HOSTIP:-}" = "$host" ] && note="  [docker host]"
    [ "${GW:-}" = "$host" ] && in_docker && note="  [docker host via bridge]"
    echo "$line$note"
  done
