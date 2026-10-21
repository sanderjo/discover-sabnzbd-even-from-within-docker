# discover-sabnzbd-even-from-within-docker

Bash script that finds SABnzbd instances on localhost, the Docker host and the LAN (/24),
also when run from inside a Docker container.

A Python 3 version (`discover-sabnzbd.py`, standard library only) does the same with the same
options and output; see [Python version](#python-version).

## Run it from a fresh container

```bash
docker run --rm debian:stable-slim bash -c 'apt-get -qq update >/dev/null && apt-get -qq install -y curl iputils-ping >/dev/null 2>&1; curl -fsSL https://raw.githubusercontent.com/sanderjo/discover-sabnzbd-even-from-within-docker/main/discover-sabnzbd.sh | bash'
```

## Run your local copy in a fresh container

From the repo directory (mounts it at `/workdir` inside the container):

```bash
docker run --rm -v "$PWD":/workdir debian:stable-slim bash -c 'apt-get -qq update >/dev/null && apt-get -qq install -y curl iputils-ping >/dev/null 2>&1; /workdir/discover-sabnzbd.sh'
```

## Run it on a plain host

```bash
curl -fsSL https://raw.githubusercontent.com/sanderjo/discover-sabnzbd-even-from-within-docker/main/discover-sabnzbd.sh | bash
```

## Python version

```bash
./discover-sabnzbd.py
```

From a fresh container (only `ping` needs installing):

```bash
docker run --rm -v "$PWD":/workdir python:3-slim bash -c 'apt-get -qq update >/dev/null && apt-get -qq install -y iputils-ping >/dev/null 2>&1; /workdir/discover-sabnzbd.py'
```

Or straight from GitHub:

```bash
curl -fsSL https://raw.githubusercontent.com/sanderjo/discover-sabnzbd-even-from-within-docker/main/discover-sabnzbd.py | python3
```

## Example output

```
LAN IP: 192.168.1.235 (Docker host)
http://172.17.0.1:8080  (SABnzbd 5.2.0Beta2)  <<< this host (via Docker bridge)
http://192.168.1.109:8080  (SABnzbd 5.2.0Beta2)
http://192.168.1.139:8080  (SABnzbd 5.1.3)
http://192.168.1.235:8080  (SABnzbd 5.2.0Beta2)  <<< this host
http://192.168.1.244:8080  (SABnzbd, access denied from this IP)
```

## How it works

- Detects whether it runs in a container (`/.dockerenv`, `/proc/1/cgroup`).
- On a Docker bridge network, finds the LAN router as the 2nd hop (`ping -t 2 1.1.1.1`)
  and the Docker host's LAN IP via a broadcast ping (`ping -b <lan>.0`).
- Probes 127.0.0.1, the Docker host and every address in the LAN /24 on ports 8080 and 9090,
  over http and https, in parallel. A hit is a page mentioning "sabnzbd";
  the version comes from `/api?mode=version` (no API key needed).

Requirements: `bash`, `curl`, and `ping` (`iputils-ping`) for the Docker tricks.

## Options

Set as environment variables, e.g. `PORTS="8080 9090" LAN=192.168.1 VERBOSE=1 ./discover-sabnzbd.sh`

| Variable   | Default     | Meaning                                  |
|------------|-------------|------------------------------------------|
| `PORTS`    | `8080 9090` | Ports to probe                           |
| `LAN`      | autodetect  | /24 prefix to sweep, e.g. `192.168.1`    |
| `PARALLEL` | `64`        | Concurrent probes                        |
| `TIMEOUT`  | `2`         | Per-request timeout in seconds           |
| `VERBOSE`  | `0`         | `1` prints detection steps to stderr     |

With `curl | bash`, pass options as `... | VERBOSE=1 bash`.
