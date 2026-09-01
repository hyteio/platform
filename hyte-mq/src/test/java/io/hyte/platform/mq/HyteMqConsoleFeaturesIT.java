package io.hyte.platform.mq;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

import org.junit.Test;

import io.hyte.platform.sample.SampleFlowVerifier;

/**
 * Boots the assembled hyte-mq distribution with the SAME staged karaf feature set the productized
 * hyte-mq (hyte-console) uses -- karaf core in two boot stages, then pax-url-wrap,
 * pax-web-http-war, pax-web-karaf and pax-web-jetty-websockets -- and validates:
 * <ul>
 *   <li>every console feature reports Started over the karaf management JMX FeaturesMBean,</li>
 *   <li>an ActiveMQ broker with a ws:// transport starts and completes a STOMP handshake over a
 *       real WebSocket connection (proving the jetty + jetty-websocket bundles are wired to the
 *       broker's ws transport),</li>
 *   <li>the CXF-over-blueprint sample endpoint answers on this feature set.</li>
 * </ul>
 * NOTE: feature versions matter -- wrap and pax-url-wrap are versioned by pax-url,
 * pax-web-http-war by KARAF (it is a karaf standard wrapper feature), pax-web-karaf by pax-web,
 * and pax-web-jetty-websockets by JETTY.
 */
public class HyteMqConsoleFeaturesIT extends DistroTestSupport {

    @Test
    public void consoleFeatureSetBootsWithWsBroker() throws Exception {
        assumeSupportedJava();
        unpackDistro(Path.of(System.getProperty("hyte.mq.console-workdir")));

        int openwirePort = freePort();
        int wsPort = freePort();
        int rmiRegistryPort = freePort();
        int rmiServerPort = freePort();

        // broker name must stay "hyte-it": the sample blueprint's XA wiring attaches over
        // vm://hyte-it?create=false (XA recovery opens a live connection at build time)
        writeBrokerConfig("hyte-it",
                "            <transportConnector name=\"openwire\" uri=\"tcp://127.0.0.1:" + openwirePort + "\"/>\n"
              + "            <transportConnector name=\"websocket\" uri=\"ws://127.0.0.1:" + wsPort + "\"/>");

        String karaf = System.getProperty("hyte.karaf.version");
        String paxWeb = System.getProperty("hyte.pax-web.version");
        String paxUrl = System.getProperty("hyte.pax-url.version");
        String jetty = System.getProperty("hyte.jetty.version");

        // the hyte-console staged boot (two karaf-core stages, then the pax web/url features),
        // followed by the hyte product features the sample bundle and broker need
        String consoleBoot = String.join("\n",
                "featuresBoot = \\",
                "    (instance/" + karaf + ", \\",
                "    package/" + karaf + ", \\",
                "    log/" + karaf + ", \\",
                "    ssh/" + karaf + ", \\",
                "    framework/" + karaf + ", \\",
                "    system/" + karaf + ", \\",
                "    eventadmin/" + karaf + ", \\",
                "    feature/" + karaf + ", \\",
                "    shell/" + karaf + ", \\",
                "    shell-compat/" + karaf + ", \\",
                "    service/" + karaf + ", \\",
                "    jaas/" + karaf + ", \\",
                "    jndi/" + karaf + "), \\",
                "    (deployer/" + karaf + ", \\",
                "    jaas-deployer/" + karaf + ", \\",
                "    diagnostic/" + karaf + ", \\",
                "    wrap/" + paxUrl + ", \\",
                "    bundle/" + karaf + ", \\",
                "    config/" + karaf + ", \\",
                "    aries-blueprint/" + karaf + ", \\",
                "    jasypt-encryption/" + karaf + ", \\",
                "    scr/" + karaf + ", \\",
                "    management/" + karaf + "), \\",
                "    pax-url-wrap/" + paxUrl + ", \\",
                "    pax-web-http-war/" + karaf + ", \\",
                "    pax-web-karaf/" + paxWeb + ", \\",
                "    pax-web-jetty-websockets/" + jetty + ", \\",
                "    transaction, pax-transx-jdbc, pax-transx-jms, hyte-db, \\",
                "    hyte-javax-api, hyte-model, hyte-activemq-client, hyte-spring-jms, \\",
                "    hyte-camel-jms, hyte-cxf-jaxrs, hyte-cxf-jackson, camel, \\",
                "    hyte-activemq-broker");
        replaceFeaturesBoot(consoleBoot);

        deploySampleBundle();
        startDistro(Map.of(
                "ORG_APACHE_KARAF_MANAGEMENT_RMIREGISTRYPORT", String.valueOf(rmiRegistryPort),
                "ORG_APACHE_KARAF_MANAGEMENT_RMISERVERPORT", String.valueOf(rmiServerPort)));
        waitForOpenWire(openwirePort, 180_000);

        // jetty + websocket wiring: full STOMP handshake over the broker's ws:// transport
        String connectedFrame = stompHandshakeOverWebSocket("ws://127.0.0.1:" + wsPort + "/", 120_000);
        if (!connectedFrame.startsWith("CONNECTED")) {
            throw new AssertionError("STOMP over ws:// did not answer CONNECTED, got:\n" + head(connectedFrame)
                    + "\nkaraf.log tail:\n" + karafLogTail(40));
        }

        // CXF-over-blueprint endpoint on the console feature set (identical marshaling verification)
        waitForInContainerFlow("http://127.0.0.1:8181/api/sample-app/sample/payload", 120_000);
        SampleFlowVerifier.verify("http://127.0.0.1:8181/api/sample-app");

        // every console feature must report Started on the karaf FeaturesMBean
        assertFeaturesStarted(rmiRegistryPort, rmiServerPort, Set.of(
                "instance", "package", "log", "ssh", "framework", "system", "eventadmin", "feature",
                "shell", "shell-compat", "service", "jaas", "jndi",
                "deployer", "jaas-deployer", "diagnostic", "wrap", "bundle", "config",
                "aries-blueprint", "jasypt-encryption", "scr", "management",
                "pax-url-wrap", "pax-web-http-war", "pax-web-karaf", "pax-web-jetty-websockets",
                "hyte-activemq-broker", "hyte-cxf-jaxrs"));
    }

    /** Replaces the distro's whole featuresBoot block (up to the blank line ending it). */
    private void replaceFeaturesBoot(String newBlock) throws Exception {
        Path featuresCfg = distroHome.resolve("etc/org.apache.karaf.features.cfg");
        String cfg = Files.readString(featuresCfg, StandardCharsets.UTF_8);
        int start = cfg.indexOf("featuresBoot = ");
        int end = cfg.indexOf("\n\n", start);
        if (start < 0 || end < 0) {
            throw new AssertionError("could not locate the featuresBoot block in " + featuresCfg);
        }
        Files.writeString(featuresCfg, cfg.substring(0, start) + newBlock + cfg.substring(end),
                StandardCharsets.UTF_8);
    }

    /**
     * Opens a real WebSocket to the broker's ws transport with the STOMP subprotocol, sends a
     * STOMP CONNECT frame, and returns the first frame the broker answers (CONNECTED on success).
     * Retries while the ws connector is still mounting.
     */
    private String stompHandshakeOverWebSocket(String wsUrl, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        Exception last = null;
        HttpClient client = HttpClient.newHttpClient();
        while (System.currentTimeMillis() < deadline) {
            CompletableFuture<String> firstFrame = new CompletableFuture<>();
            try {
                WebSocket webSocket = client.newWebSocketBuilder()
                        .subprotocols("v12.stomp", "stomp")
                        .buildAsync(URI.create(wsUrl), new WebSocket.Listener() {
                            private final StringBuilder buffer = new StringBuilder();

                            @Override
                            public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean lastPart) {
                                buffer.append(data);
                                if (lastPart) {
                                    firstFrame.complete(buffer.toString());
                                }
                                ws.request(1);
                                return null;
                            }
                        })
                        .get(15, TimeUnit.SECONDS);
                try {
                    webSocket.sendText("CONNECT\naccept-version:1.2\nhost:localhost\n\n\0", true)
                            .get(15, TimeUnit.SECONDS);
                    return firstFrame.get(30, TimeUnit.SECONDS);
                } finally {
                    webSocket.abort();
                }
            } catch (Exception e) {
                last = e;
            }
            Thread.sleep(1000);
        }
        throw new AssertionError("broker ws:// transport never completed a STOMP handshake at " + wsUrl
                + "\nkaraf.log tail:\n" + karafLogTail(40), last);
    }

    /** Asserts the given features report Installed=true on the karaf FeaturesMBean. */
    private void assertFeaturesStarted(int rmiRegistryPort, int rmiServerPort, Set<String> required)
            throws Exception {
        JMXServiceURL serviceUrl = new JMXServiceURL("service:jmx:rmi://127.0.0.1:" + rmiServerPort
                + "/jndi/rmi://127.0.0.1:" + rmiRegistryPort + "/karaf-root");
        Map<String, Object> env = Map.of(JMXConnector.CREDENTIALS, new String[] {"admin", "admin"});
        long deadline = System.currentTimeMillis() + 120_000;
        Exception last = null;
        while (System.currentTimeMillis() < deadline) {
            try (JMXConnector connector = JMXConnectorFactory.connect(serviceUrl, env)) {
                MBeanServerConnection connection = connector.getMBeanServerConnection();
                TabularData features = (TabularData) connection.getAttribute(
                        new ObjectName("org.apache.karaf:type=feature,name=root"), "Features");
                java.util.Set<String> installed = new java.util.HashSet<>();
                for (Object row : features.values()) {
                    CompositeData feature = (CompositeData) row;
                    if (Boolean.TRUE.equals(feature.get("Installed"))) {
                        installed.add(String.valueOf(feature.get("Name")));
                    }
                }
                java.util.Set<String> missing = new java.util.TreeSet<>(required);
                missing.removeAll(installed);
                if (missing.isEmpty()) {
                    return;
                }
                throw new AssertionError("console features not Started: " + missing
                        + "\nkaraf.log tail:\n" + karafLogTail(40));
            } catch (AssertionError e) {
                throw e;
            } catch (Exception e) {
                last = e; // JMX connector may mount slightly after the broker -- keep polling
            }
            Thread.sleep(2000);
        }
        throw new AssertionError("karaf JMX FeaturesMBean never became reachable at " + serviceUrl, last);
    }
}
