package io.hyte.platform.mq;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.After;
import org.junit.Test;

import io.hyte.platform.sample.SampleFlowVerifier;
import io.hyte.platform.sample.SampleServer;

/**
 * Boots the ASSEMBLED hyte-mq distribution, provisions a minimal broker configuration into it (the
 * same post-install mechanism the HYTE console uses: an activemq xbean XML plus an
 * {@code org.apache.activemq.server-*.cfg} factory config), waits for the broker's OpenWire
 * transport, then runs the sample alignment flow (CXF -> Camel -> JMS request/response -> Jackson)
 * with the JMS leg pointed at the distribution's broker over tcp://.
 */
public class HyteMqDistroSampleIT {

    private Path distroHome;

    /** Karaf 4.4.x supports up to JDK 21 (JDK 23+ removed Subject.getSubject, JEP 486). */
    private static final int MAX_SUPPORTED_JAVA = 21;

    @Test
    public void sampleFlowAgainstAssembledDistribution() throws Exception {
        int javaMajor = javaMajorVersion();
        if (javaMajor > MAX_SUPPORTED_JAVA) {
            System.err.println("WARNING: skipping " + getClass().getSimpleName() + " -- the build JVM is Java "
                    + javaMajor + ", but the Karaf runtime supports at most Java " + MAX_SUPPORTED_JAVA
                    + ". Run the build with JAVA_HOME set to a JDK 11-21 to execute the distro integration test.");
        }
        org.junit.Assume.assumeTrue("build JVM Java " + javaMajor + " > " + MAX_SUPPORTED_JAVA
                + " (unsupported by the Karaf runtime)", javaMajor <= MAX_SUPPORTED_JAVA);

        Path tarball = Path.of(System.getProperty("hyte.mq.tarball"));
        Path workDir = Path.of(System.getProperty("hyte.mq.workdir"));
        if (!Files.isRegularFile(tarball)) {
            throw new AssertionError("distribution tarball not found (run after package): " + tarball);
        }

        int brokerPort = freePort();
        int httpPort = freePort();

        // unpack
        deleteRecursively(workDir);
        Files.createDirectories(workDir);
        run(workDir.toFile(), "tar", "-xzf", tarball.toString());
        try (var dirs = Files.list(workDir)) {
            distroHome = dirs.filter(Files::isDirectory).findFirst()
                    .orElseThrow(() -> new AssertionError("no distribution directory unpacked"));
        }

        // provision the broker: xbean config + server factory cfg (post-install mechanism)
        String brokerXml = String.join("\n",
                "<beans xmlns=\"http://www.springframework.org/schema/beans\"",
                "       xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"",
                "       xsi:schemaLocation=\"http://www.springframework.org/schema/beans http://www.springframework.org/schema/beans/spring-beans.xsd",
                "       http://activemq.apache.org/schema/core http://activemq.apache.org/schema/core/activemq-core.xsd\">",
                "    <broker xmlns=\"http://activemq.apache.org/schema/core\" brokerName=\"hyte-it\" persistent=\"false\" useJmx=\"false\">",
                "        <transportConnectors>",
                "            <transportConnector name=\"openwire\" uri=\"tcp://127.0.0.1:" + brokerPort + "\"/>",
                "        </transportConnectors>",
                "    </broker>",
                "</beans>", "");
        Path brokerXmlPath = distroHome.resolve("etc/activemq-it.xml");
        Files.writeString(brokerXmlPath, brokerXml, StandardCharsets.UTF_8);
        Files.writeString(distroHome.resolve("etc/org.apache.activemq.server-it.cfg"),
                "broker-name=hyte-it\nconfig=" + brokerXmlPath.toAbsolutePath() + "\n", StandardCharsets.UTF_8);

        // in-container XA path: boot the transaction feature (Geronimo TM), pax-transx enlistment,
        // and the h2 feature; hot-deploy the sample bundle (its blueprint wires the XA consumer)
        Path featuresCfg = distroHome.resolve("etc/org.apache.karaf.features.cfg");
        Files.writeString(featuresCfg, Files.readString(featuresCfg, StandardCharsets.UTF_8)
                .replace("featuresBoot = ", "featuresBoot = transaction, pax-transx-jdbc, pax-transx-jms, hyte-db, hyte-cxf-jackson, camel, "),
                StandardCharsets.UTF_8);
        Files.copy(Path.of(System.getProperty("hyte.samplexa.jar")),
                Files.createDirectories(distroHome.resolve("deploy")).resolve("sample.jar"));

        // test fixture: the hyte-db feature (h2) is not part of the hyte-mq product -- provision the
        // h2 bundle into the unpacked distro's local-repo just for this scenario (h2 stays test-scoped)
        String h2Version = System.getProperty("hyte.h2.version");
        Path h2Dir = Files.createDirectories(distroHome.resolve("local-repo/com/h2database/h2/" + h2Version));
        Files.copy(Path.of(System.getProperty("hyte.h2.jar")), h2Dir.resolve("h2-" + h2Version + ".jar"));

        // clean XA dataset at startup (and again at shutdown in stopDistro)
        deleteRecursively(distroHome.resolve("data/it-xa"));

        // start the distribution and wait for the broker's OpenWire transport
        run(distroHome.toFile(), distroHome.resolve("bin/start").toString());
        waitForOpenWire(brokerPort, 180_000);

        String brokerUrl = "tcp://127.0.0.1:" + brokerPort + "?wireFormat.maxInactivityDuration=0";
        String jdbcUrl = "jdbc:h2:file:" + distroHome.resolve("data/it-xa/sampledb") + ";AUTO_SERVER=TRUE";

        // IN-CONTAINER endpoint: the deployed bundle's blueprint runs the CXF JAX-RS endpoint and
        // the Camel JMS route inside Karaf (visible to cxf:list-endpoints / camel:route-list); the
        // identical verification runs against it via the CXF servlet (context /api per org.apache.cxf.osgi.cfg).
        // The blueprint mounts after the broker gate, so wait for the endpoint before verifying.
        waitForEndpoint("http://127.0.0.1:8181/api/sample-app/sample/payload", 120_000);
        SampleFlowVerifier.verify("http://127.0.0.1:8181/api/sample-app");

        // run the identical sample verification, JMS leg on the DISTRO broker
        try (SampleServer server = new SampleServer(httpPort, brokerUrl)) {
            SampleFlowVerifier.verify(server.getBaseAddress());

            // --- in-container XA: commit case -------------------------------------------------
            SampleFlowVerifier.submitXa(server.getBaseAddress(), "xa-commit-1");
            waitForRow(jdbcUrl, "xa-commit-1", 120_000);

            // --- in-container XA: rollback/atomicity case -------------------------------------
            // the consumer INSERTS then rolls back: the row must never persist, and after the
            // redelivery policy is exhausted the message must dead-letter to ActiveMQ.DLQ
            SampleFlowVerifier.submitXa(server.getBaseAddress(), "POISON-1");
            waitForDlqDepth(brokerUrl, 1, 120_000);
            if (countRows(jdbcUrl, "POISON-1") != 0) {
                throw new AssertionError("XA atomicity violated: rolled-back insert persisted");
            }
            if (countRows(jdbcUrl, "xa-commit-1") != 1) {
                throw new AssertionError("committed XA row must persist exactly once");
            }
        }
    }

    /** Polls until the in-container JAX-RS endpoint is mounted (anything but 404). */
    private static void waitForEndpoint(String url, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        int last = -1;
        while (System.currentTimeMillis() < deadline) {
            try {
                java.net.HttpURLConnection connection =
                        (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                connection.setRequestMethod("GET");
                last = connection.getResponseCode();
                if (last != 404) {
                    return;
                }
            } catch (Exception e) {
                // http layer not up yet -- keep polling
            }
            Thread.sleep(1000);
        }
        throw new AssertionError("in-container endpoint never mounted at " + url + " (last=" + last + ")");
    }

    private static void waitForRow(String jdbcUrl, String content, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (countRows(jdbcUrl, content) == 1) {
                    return;
                }
            } catch (Exception e) {
                // table/db not created yet -- keep polling
            }
            Thread.sleep(1000);
        }
        throw new AssertionError("XA-committed row '" + content + "' never appeared in " + jdbcUrl);
    }

    private static int countRows(String jdbcUrl, String content) throws Exception {
        try (java.sql.Connection jdbc = java.sql.DriverManager.getConnection(jdbcUrl);
             java.sql.PreparedStatement select = jdbc.prepareStatement(
                     "SELECT COUNT(*) FROM XA_MESSAGES WHERE CONTENT = ?")) {
            select.setString(1, content);
            try (java.sql.ResultSet resultSet = select.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private static void waitForDlqDepth(String brokerUrl, int expected, long timeoutMillis) throws Exception {
        org.apache.activemq.ActiveMQConnectionFactory factory =
                new org.apache.activemq.ActiveMQConnectionFactory(brokerUrl);
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            try (javax.jms.Connection connection = factory.createConnection()) {
                connection.start();
                javax.jms.Session session = connection.createSession(false, javax.jms.Session.AUTO_ACKNOWLEDGE);
                try (javax.jms.QueueBrowser browser = session.createBrowser(session.createQueue("ActiveMQ.DLQ"))) {
                    int depth = 0;
                    for (var e = browser.getEnumeration(); e.hasMoreElements(); e.nextElement()) {
                        depth++;
                    }
                    if (depth >= expected) {
                        return;
                    }
                }
            }
            Thread.sleep(2000);
        }
        throw new AssertionError("ActiveMQ.DLQ never reached depth " + expected
                + " (poison message not dead-lettered after XA rollbacks)");
    }

    @After
    public void stopDistro() {
        if (distroHome == null) {
            return;
        }
        try {
            run(distroHome.toFile(), distroHome.resolve("bin/stop").toString());
            for (int i = 0; i < 30 && isKarafRunning(); i++) {
                Thread.sleep(1000);
            }
        } catch (Exception e) {
            // fall through to the hard kill below
        }
        if (isKarafRunning()) {
            try {
                new ProcessBuilder("pkill", "-9", "-f", distroHome.toString()).start().waitFor();
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

    private boolean isKarafRunning() {
        try {
            Process p = new ProcessBuilder("pgrep", "-f", distroHome.toString()).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** OpenWire brokers send a WireFormatInfo frame immediately on connect. */
    private static void waitForOpenWire(int port, long timeoutMillis) throws Exception {
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

    private static void run(File workingDir, String... command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).directory(workingDir).inheritIO();
        builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        int exit = builder.start().waitFor();
        if (exit != 0) {
            throw new AssertionError("command failed (" + exit + "): " + String.join(" ", command));
        }
    }

    /** Parses java.specification.version ("11", "21", "25", legacy "1.8") to the major version. */
    private static int javaMajorVersion() {
        String spec = System.getProperty("java.specification.version", "11");
        return Integer.parseInt(spec.startsWith("1.") ? spec.substring(2) : spec);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (Files.exists(path)) {
            try (var walk = Files.walk(path)) {
                walk.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
    }
}
