package io.hyte.platform.mq;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.After;

/**
 * Shared plumbing for integration tests that boot the ASSEMBLED hyte-mq distribution: tarball
 * unpack, broker provisioning (the post-install xbean + server-factory cfg mechanism the HYTE
 * console uses), sample-bundle hot deploy (with the h2 test fixture the XA blueprint needs),
 * start/stop lifecycle with a pkill fallback, and the readiness/diagnostic helpers.
 */
abstract class DistroTestSupport {

    protected Path distroHome;

    /** Karaf 4.4.x supports up to JDK 21 (JDK 23+ removed Subject.getSubject, JEP 486). */
    protected static final int MAX_SUPPORTED_JAVA = 21;

    protected void assumeSupportedJava() {
        int javaMajor = javaMajorVersion();
        if (javaMajor > MAX_SUPPORTED_JAVA) {
            System.err.println("WARNING: skipping " + getClass().getSimpleName() + " -- the build JVM is Java "
                    + javaMajor + ", but the Karaf runtime supports at most Java " + MAX_SUPPORTED_JAVA
                    + ". Run the build with JAVA_HOME set to a JDK 11-21 to execute the distro integration test.");
        }
        org.junit.Assume.assumeTrue("build JVM Java " + javaMajor + " > " + MAX_SUPPORTED_JAVA
                + " (unsupported by the Karaf runtime)", javaMajor <= MAX_SUPPORTED_JAVA);
    }

    protected void unpackDistro(Path workDir) throws Exception {
        Path tarball = Path.of(System.getProperty("hyte.mq.tarball"));
        if (!Files.isRegularFile(tarball)) {
            throw new AssertionError("distribution tarball not found (run after package): " + tarball);
        }
        deleteRecursively(workDir);
        Files.createDirectories(workDir);
        run(workDir.toFile(), Map.of(), "tar", "-xzf", tarball.toString());
        try (var dirs = Files.list(workDir)) {
            distroHome = dirs.filter(Files::isDirectory).findFirst()
                    .orElseThrow(() -> new AssertionError("no distribution directory unpacked"));
        }
    }

    /** Provisions a broker post-install: xbean XML + org.apache.activemq.server-*.cfg factory config. */
    protected void writeBrokerConfig(String brokerName, String transportConnectors) throws IOException {
        String brokerXml = String.join("\n",
                "<beans xmlns=\"http://www.springframework.org/schema/beans\"",
                "       xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"",
                "       xsi:schemaLocation=\"http://www.springframework.org/schema/beans http://www.springframework.org/schema/beans/spring-beans.xsd",
                "       http://activemq.apache.org/schema/core http://activemq.apache.org/schema/core/activemq-core.xsd\">",
                "    <broker xmlns=\"http://activemq.apache.org/schema/core\" brokerName=\"" + brokerName
                        + "\" persistent=\"false\" useJmx=\"false\">",
                "        <transportConnectors>",
                transportConnectors,
                "        </transportConnectors>",
                "    </broker>",
                "</beans>", "");
        Path brokerXmlPath = distroHome.resolve("etc/activemq-it.xml");
        Files.writeString(brokerXmlPath, brokerXml, StandardCharsets.UTF_8);
        Files.writeString(distroHome.resolve("etc/org.apache.activemq.server-it.cfg"),
                "broker-name=" + brokerName + "\nconfig=" + brokerXmlPath.toAbsolutePath() + "\n",
                StandardCharsets.UTF_8);
    }

    /**
     * Hot-deploys the sample bundle plus what its blueprint needs beyond the product feature set:
     * the h2 bundle as a local-repo test fixture (the hyte-db/h2 feature is not part of the
     * hyte-mq product) and a clean XA dataset.
     */
    protected void deploySampleBundle() throws Exception {
        Files.copy(Path.of(System.getProperty("hyte.samplexa.jar")),
                Files.createDirectories(distroHome.resolve("deploy")).resolve("sample.jar"));
        String h2Version = System.getProperty("hyte.h2.version");
        Path h2Dir = Files.createDirectories(distroHome.resolve("local-repo/com/h2database/h2/" + h2Version));
        Files.copy(Path.of(System.getProperty("hyte.h2.jar")), h2Dir.resolve("h2-" + h2Version + ".jar"));
        deleteRecursively(distroHome.resolve("data/it-xa"));
    }

    protected void startDistro(Map<String, String> extraEnv) throws Exception {
        run(distroHome.toFile(), extraEnv, distroHome.resolve("bin/start").toString());
    }

    /** OpenWire brokers send a WireFormatInfo frame immediately on connect. */
    protected static void waitForOpenWire(int port, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        Exception last = null;
        while (System.currentTimeMillis() < deadline) {
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(5000);
                if (socket.getInputStream().read() >= 0) {
                    return;
                }
            } catch (Exception e) {
                last = e;
            }
            Thread.sleep(1000);
        }
        throw new AssertionError("broker OpenWire transport never came up on port " + port, last);
    }

    /**
     * Polls until the full in-container flow (CXF servlet + Camel route + JMS + broker) answers
     * 200. Each probe is bounded (a not-yet-started route makes the CXF resource block on the
     * camel-jms request timeout, ~20s server-side), and the status transition history plus the
     * karaf.log ERROR lines ride in the failure for CI diagnosis.
     */
    protected void waitForInContainerFlow(String url, long timeoutMillis) throws Exception {
        long start = System.currentTimeMillis();
        long deadline = start + timeoutMillis;
        int lastCode = Integer.MIN_VALUE;
        String lastBody = "";
        StringBuilder history = new StringBuilder();
        while (System.currentTimeMillis() < deadline) {
            int code;
            try {
                java.net.HttpURLConnection connection =
                        (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(30_000);
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);
                try (java.io.OutputStream out = connection.getOutputStream()) {
                    out.write("{\"SampleRequest\":{\"note\":\"readiness-probe\"}}".getBytes(StandardCharsets.UTF_8));
                }
                code = connection.getResponseCode();
                if (code == 200) {
                    return;
                }
                java.io.InputStream err = connection.getErrorStream();
                lastBody = err == null ? "" : new String(err.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                code = -1;
                lastBody = String.valueOf(e);
            }
            if (code != lastCode) {
                history.append('+').append((System.currentTimeMillis() - start) / 1000).append("s=")
                        .append(code == -1 ? "io-error" : String.valueOf(code)).append(' ');
                lastCode = code;
            }
            Thread.sleep(1000);
        }
        throw new AssertionError("in-container flow never became ready at " + url
                + "\nprobe status history: " + history
                + "\nlast status: " + lastCode + "\nlast body: " + head(lastBody)
                + "\nkaraf.log ERRORs:\n" + karafLogErrors(15)
                + "\nkaraf.log JMS/listener recovery WARNs:\n"
                + karafLogMatching(15, "trying to recover", "Could not refresh", "Setup of JMS message listener",
                        "temporary destination", "recovery")
                + "\nkaraf JVM threads (JMS/broker/camel):\n" + karafThreadDump()
                + "\nkaraf.log tail:\n" + karafLogTail(60));
    }

    /**
     * Thread-dumps the karaf JVM (jstack) at failure time: a reply consumer that hangs silently --
     * no listener-recovery WARNs -- can only be diagnosed from its thread stack. The full dump is
     * saved next to karaf.log (for CI artifact archiving); the assertion carries just the
     * JMS/broker/camel-related thread stacks.
     */
    protected String karafThreadDump() {
        try {
            String pid = karafPid();
            if (pid.isEmpty()) {
                Process pgrep = new ProcessBuilder("pgrep", "-f", distroHome.toString()).start();
                pid = new String(pgrep.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim()
                        .lines().findFirst().orElse("");
                pgrep.waitFor();
            }
            if (pid.isEmpty()) {
                return "(karaf process not found for jstack)";
            }
            Path jstack = Path.of(System.getProperty("java.home"), "bin", "jstack");
            ProcessBuilder builder = new ProcessBuilder(jstack.toString(), "-l", pid);
            builder.redirectErrorStream(true);
            Process dumper = builder.start();
            String dump = new String(dumper.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (dumper.waitFor() != 0) {
                return "(jstack failed for pid " + pid + ": " + dump.substring(0, Math.min(dump.length(), 500)) + ")";
            }
            Path dumpFile = distroHome.resolve("data/log/threaddump-" + System.currentTimeMillis() + ".txt");
            Files.writeString(dumpFile, dump, StandardCharsets.UTF_8);

            // pull out only the threads that can explain a stuck JMS reply path
            StringBuilder relevant = new StringBuilder("(full dump: " + dumpFile + ")\n");
            for (String block : dump.split("\n\n")) {
                String header = block.lines().findFirst().orElse("");
                if (header.startsWith("\"") && (block.contains("JmsConsumer") || block.contains("ReplyManager")
                        || block.contains("activemq") || block.contains("ActiveMQ") || block.contains("camel")
                        || block.contains("VMTransport") || block.contains("listenerContainer")
                        || block.contains("DefaultMessageListenerContainer"))) {
                    relevant.append(block).append("\n\n");
                    if (relevant.length() > 12_000) {
                        relevant.append("(truncated; see full dump file)");
                        break;
                    }
                }
            }
            return relevant.toString();
        } catch (Exception e) {
            return "(thread dump unavailable: " + e + ")";
        }
    }

    /** The first karaf.log lines containing any of the needles (case-sensitive), for diagnostics. */
    protected String karafLogMatching(int maxLines, String... needles) {
        try {
            java.util.List<String> hits = new java.util.ArrayList<>();
            for (String line : Files.readAllLines(distroHome.resolve("data/log/karaf.log"))) {
                for (String needle : needles) {
                    if (line.contains(needle)) {
                        hits.add(line);
                        break;
                    }
                }
                if (hits.size() >= maxLines) {
                    break;
                }
            }
            return hits.isEmpty() ? "(none)" : String.join("\n", hits);
        } catch (Exception e) {
            return "(karaf.log unreadable: " + e + ")";
        }
    }

    /** The first ERROR lines from karaf.log (stack frames excluded), for failure diagnostics. */
    protected String karafLogErrors(int maxLines) {
        try {
            java.util.List<String> errors = new java.util.ArrayList<>();
            for (String line : Files.readAllLines(distroHome.resolve("data/log/karaf.log"))) {
                if (line.contains("| ERROR |") && errors.size() < maxLines) {
                    errors.add(line);
                }
            }
            return errors.isEmpty() ? "(none)" : String.join("\n", errors);
        } catch (Exception e) {
            return "(karaf.log unreadable: " + e + ")";
        }
    }

    protected String karafLogTail(int lines) {
        try {
            java.util.List<String> all = Files.readAllLines(distroHome.resolve("data/log/karaf.log"));
            return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
        } catch (Exception e) {
            return "(karaf.log unreadable: " + e + ")";
        }
    }

    protected static String head(String s) {
        return s == null ? "" : s.substring(0, Math.min(s.length(), 500));
    }

    @After
    public void stopDistro() {
        if (distroHome == null) {
            return;
        }
        try {
            run(distroHome.toFile(), Map.of(), distroHome.resolve("bin/stop").toString());
            for (int i = 0; i < 30 && isKarafRunning(); i++) {
                Thread.sleep(1000);
            }
        } catch (Exception e) {
            // fall through to the hard kill below
        }
        if (isKarafRunning()) {
            try {
                String pid = karafPid();
                if (!pid.isEmpty()) {
                    new ProcessBuilder("kill", "-9", pid).start().waitFor();
                } else {
                    new ProcessBuilder("pkill", "-9", "-f", distroHome.toString()).start().waitFor();
                }
            } catch (Exception ignored) {
                // best effort
            }
        }
        try {
            deleteRecursively(distroHome.resolve("data/it-xa")); // clean XA dataset at shutdown too
        } catch (IOException ignored) {
            // best effort
        }
    }

    /**
     * Process liveness via karaf.pid + kill -0: pgrep/pkill -f does not reliably match karaf's
     * very long java command line on macOS, which let consecutive ITs overlap their containers.
     */
    protected boolean isKarafRunning() {
        try {
            String pid = karafPid();
            if (!pid.isEmpty()) {
                return new ProcessBuilder("kill", "-0", pid).start().waitFor() == 0;
            }
            Process p = new ProcessBuilder("pgrep", "-f", distroHome.toString()).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    protected String karafPid() {
        try {
            Path pidFile = distroHome.resolve("karaf.pid");
            return Files.isRegularFile(pidFile)
                    ? Files.readString(pidFile, StandardCharsets.UTF_8).trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    protected static void run(File workingDir, Map<String, String> extraEnv, String... command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).directory(workingDir).inheritIO();
        builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        builder.environment().putAll(extraEnv);
        int exit = builder.start().waitFor();
        if (exit != 0) {
            throw new AssertionError("command failed (" + exit + "): " + String.join(" ", command));
        }
    }

    /** Parses java.specification.version ("11", "21", "25", legacy "1.8") to the major version. */
    protected static int javaMajorVersion() {
        String spec = System.getProperty("java.specification.version", "11");
        return Integer.parseInt(spec.startsWith("1.") ? spec.substring(2) : spec);
    }

    protected static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    protected static void deleteRecursively(Path path) throws IOException {
        if (Files.exists(path)) {
            try (var walk = Files.walk(path)) {
                walk.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
    }
}
