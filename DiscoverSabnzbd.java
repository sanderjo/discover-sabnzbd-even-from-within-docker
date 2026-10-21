// Discover SABnzbd instances on localhost, the Docker host and the LAN.
// Works on a plain host and from inside a Docker container (bridge network).
// Java 11+ standard library only; `ping` (iputils-ping) is used for the Docker tricks.
//
// Usage: java -jar discover-sabnzbd.jar   (or: java DiscoverSabnzbd.java)
// Env overrides:
//   PORTS="8080 9090"     ports to probe
//   LAN=192.168.1         /24 prefix to sweep (skip autodetection)
//   PARALLEL=256          concurrent probes
//   TIMEOUT=2             per-request timeout in seconds
//   VERBOSE=1             print detection steps to stderr

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class DiscoverSabnzbd {

    static final List<Integer> PORTS = new ArrayList<>();
    static final int PARALLEL = Integer.parseInt(env("PARALLEL", "256"));
    static final int TIMEOUT_MS = (int) (Double.parseDouble(env("TIMEOUT", "2")) * 1000);
    static final boolean VERBOSE = env("VERBOSE", "0").equals("1");
    static final Pattern VERSION = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"");

    static {
        for (String p : env("PORTS", "8080 9090").trim().split("\\s+")) {
            PORTS.add(Integer.parseInt(p));
        }
    }

    static String env(String name, String dflt) {
        String v = System.getenv(name);
        return v == null || v.isEmpty() ? dflt : v;
    }

    static void log(String msg) {
        if (VERBOSE) {
            System.err.println("[*] " + msg);
        }
    }

    // --- helpers -----------------------------------------------------------

    static boolean inDocker() {
        if (Files.exists(Path.of("/.dockerenv"))) {
            return true;
        }
        try {
            return Pattern.compile("docker|containerd|kubepods")
                    .matcher(Files.readString(Path.of("/proc/1/cgroup"))).find();
        } catch (IOException e) {
            return false;
        }
    }

    /** Default gateway from /proc/net/route (no dependency on `ip`). */
    static String defaultGw() {
        try {
            List<String> lines = Files.readAllLines(Path.of("/proc/net/route"));
            for (String line : lines.subList(1, lines.size())) {
                String[] f = line.trim().split("\\s+");
                if (f[1].equals("00000000") && !f[2].equals("00000000")) {
                    long gw = Long.parseLong(f[2], 16); // little-endian
                    return (gw & 0xff) + "." + (gw >> 8 & 0xff) + "." + (gw >> 16 & 0xff) + "." + (gw >> 24 & 0xff);
                }
            }
        } catch (IOException | RuntimeException e) {
            // no /proc/net/route
        }
        return null;
    }

    /** Run ping and return the address from the first 'From <ip> ... <pattern>' line. */
    static String pingFrom(String pattern, String... args) {
        List<String> cmd = new ArrayList<>(List.of("ping", "-n"));
        cmd.addAll(List.of(args));
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor(10, TimeUnit.SECONDS);
            Matcher m = Pattern.compile("From ([0-9.]+).*" + pattern, Pattern.CASE_INSENSITIVE).matcher(out);
            return m.find() ? m.group(1) : null;
        } catch (IOException e) {
            return null; // no ping installed
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** IP of the router at hop N (TTL trick). */
    static String hop(int n) {
        return pingFrom("time to live exceeded", "-c1", "-W1", "-t", String.valueOf(n), "1.1.1.1");
    }

    static boolean isDockerBridgeIp(String ip) {
        return ip.matches("172\\.(1[6-9]|2[0-9]|3[01])\\..*");
    }

    static String prefix24(String ip) {
        return ip.substring(0, ip.lastIndexOf('.'));
    }

    /** Our own IPv4 address on the LAN with prefix `lan` (UDP connect, no packets sent). */
    static String ownIp(String lan) {
        try (DatagramSocket s = new DatagramSocket()) {
            s.connect(InetAddress.getByName(lan + ".1"), 9);
            return s.getLocalAddress().getHostAddress();
        } catch (IOException e) {
            return null;
        }
    }

    static void trustAllCerts() throws Exception {
        TrustManager[] trustAll = {new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }};
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, trustAll, null);
        HttpsURLConnection.setDefaultSSLSocketFactory(ctx.getSocketFactory());
        HttpsURLConnection.setDefaultHostnameVerifier((host, session) -> true);
    }

    /** Return headers + body as text, also for HTTP error responses; null if unreachable. */
    static String[] fetch(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            int status = c.getResponseCode();
            StringBuilder headers = new StringBuilder();
            for (Map.Entry<String, List<String>> h : c.getHeaderFields().entrySet()) {
                headers.append(h.getKey()).append(": ").append(h.getValue()).append('\n');
            }
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            c.disconnect();
            return new String[] {headers.toString(), body};
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Probe one host:port; return {url, info} if it is SABnzbd. */
    static String[] probe(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 1000);
        } catch (IOException e) {
            return null;
        }
        for (String scheme : new String[] {"http", "https"}) {
            String url = scheme + "://" + host + ":" + port;
            String[] page = fetch(url + "/");
            if (page == null) {
                continue;
            }
            String text = (page[0] + page[1]).toLowerCase(Locale.ROOT);
            if (!text.contains("sabnzbd")) {
                continue;
            }
            // mode=version needs no API key
            String[] api = fetch(url + "/api?mode=version&output=json");
            Matcher m = VERSION.matcher(api == null ? "" : api[1]);
            if (m.find()) {
                return new String[] {url, "(SABnzbd " + m.group(1) + ")"};
            }
            if (text.contains("access denied")) {
                return new String[] {url, "(SABnzbd, access denied from this IP)"};
            }
            return new String[] {url, "(SABnzbd)"};
        }
        return null;
    }

    interface Hit {
        void print(String host, String url, String info);
    }

    /** Probe targets on all PORTS in parallel; report hits as they come in. */
    static void scan(Set<String> targets, String label, Hit hit) throws InterruptedException {
        log("probing " + targets.size() + " " + label + " hosts on ports: "
                + PORTS.toString().replaceAll("[\\[\\],]", ""));
        ExecutorService pool = Executors.newFixedThreadPool(PARALLEL);
        CompletionService<String[]> done = new ExecutorCompletionService<>(pool);
        int jobs = 0;
        for (String t : targets) {
            for (int p : PORTS) {
                done.submit(() -> {
                    String[] r = probe(t, p);
                    return r == null ? null : new String[] {t, r[0], r[1]};
                });
                jobs++;
            }
        }
        for (int i = 0; i < jobs; i++) {
            try {
                String[] r = done.take().get();
                if (r != null) {
                    hit.print(r[0], r[1], r[2]);
                }
            } catch (java.util.concurrent.ExecutionException e) {
                // probe failed: treat as no hit
            }
        }
        pool.shutdown();
    }

    // --- network discovery -------------------------------------------------

    public static void main(String[] args) throws Exception {
        trustAllCerts();
        Set<String> local = new LinkedHashSet<>(List.of("127.0.0.1"));
        String gw = defaultGw();
        log("default gateway: " + (gw == null ? "unknown" : gw));
        String lan = System.getenv("LAN");
        boolean docker = inDocker();
        boolean bridged = docker && gw != null && isDockerBridgeIp(gw);

        if (docker) {
            log("running inside a container");
            // Docker host reachable via the bridge gateway / host.docker.internal
            if (bridged) {
                local.add(gw);
            }
            try {
                InetAddress.getByName("host.docker.internal");
                local.add("host.docker.internal");
            } catch (IOException e) {
                // not resolvable
            }
        } else {
            log("running on a plain host");
        }

        // --- local scan first: fast, so print it before the slow LAN detection ---

        scan(local, "local", (host, url, info) -> {
            String note;
            if (host.equals("127.0.0.1")) {
                note = bridged ? "  <<< this container" : "  <<< this host";
            } else {
                note = bridged ? "  <<< this host (via Docker bridge)" : "";
            }
            System.out.println(url + "  " + info + note);
        });

        // --- LAN detection -------------------------------------------------

        String lanGw;
        if ((lan == null || lan.isEmpty()) && bridged) {
            // Bridge network: the LAN router is the 2nd hop
            lanGw = hop(2);
            log("LAN gateway (hop 2): " + (lanGw == null ? "unknown" : lanGw));
        } else {
            lanGw = gw; // plain host or e.g. --network host
        }

        if ((lan == null || lan.isEmpty()) && lanGw != null) {
            lan = prefix24(lanGw);
        }

        if (lan == null || lan.isEmpty()) {
            System.err.println("Could not determine LAN; only probed local targets (set LAN=a.b.c)");
            return;
        }

        log("LAN: " + lan + ".0/24");
        String hostIp;
        if (bridged) {
            // Broadcast-ping hack: the host answers 'Destination Host Unreachable'
            // from its own LAN address, revealing it from inside the container
            hostIp = pingFrom("unreachable", "-c5", "-w6", "-b", lan + ".0");
            System.out.println("LAN IP: " + (hostIp == null ? "unknown" : hostIp) + " (Docker host)");
        } else {
            // Plain host or --network host: our own address is the host's
            hostIp = ownIp(lan);
            System.out.println("LAN IP: " + (hostIp == null ? "unknown" : hostIp));
        }

        // --- LAN scan ------------------------------------------------------

        Set<String> lanTargets = new LinkedHashSet<>();
        for (int i = 1; i < 255; i++) {
            String t = lan + "." + i;
            if (!local.contains(t)) {
                lanTargets.add(t);
            }
        }
        scan(lanTargets, "LAN", (host, url, info) ->
                System.out.println(url + "  " + info + (host.equals(hostIp) ? "  <<< this host" : "")));
    }
}
