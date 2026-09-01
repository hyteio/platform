package io.hyte.platform.mq;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
public class HyteMqDistroSampleIT extends DistroTestSupport {

    @Test
    public void sampleFlowAgainstAssembledDistribution() throws Exception {
        assumeSupportedJava();
        unpackDistro(Path.of(System.getProperty("hyte.mq.workdir")));

        int brokerPort = freePort();
        int httpPort = freePort();

        writeBrokerConfig("hyte-it",
                "            <transportConnector name=\"openwire\" uri=\"tcp://127.0.0.1:" + brokerPort + "\"/>");

        // in-container XA path: boot the transaction feature (Geronimo TM), pax-transx enlistment,
        // and the h2 feature; hot-deploy the sample bundle (its blueprint wires the XA consumer)
        Path featuresCfg = distroHome.resolve("etc/org.apache.karaf.features.cfg");
        Files.writeString(featuresCfg, Files.readString(featuresCfg, StandardCharsets.UTF_8)
                .replace("featuresBoot = ", "featuresBoot = transaction, pax-transx-jdbc, pax-transx-jms, hyte-db, hyte-cxf-jackson, camel, "),
                StandardCharsets.UTF_8);
        deploySampleBundle();

        // start the distribution and wait for the broker's OpenWire transport
        startDistro(java.util.Map.of());
        waitForOpenWire(brokerPort, 180_000);

        String brokerUrl = "tcp://127.0.0.1:" + brokerPort + "?wireFormat.maxInactivityDuration=0";
        String jdbcUrl = "jdbc:h2:file:" + distroHome.resolve("data/it-xa/sampledb") + ";AUTO_SERVER=TRUE";

        // IN-CONTAINER endpoint: the deployed bundle's blueprint runs the CXF JAX-RS endpoint and
        // the Camel JMS route inside Karaf (visible to cxf:list-endpoints / camel:route-list); the
        // identical verification runs against it via the CXF servlet (context /api per org.apache.cxf.osgi.cfg).
        // The blueprint mounts after the broker gate and the Camel/JMS leg starts after the CXF
        // servlet, so wait until the WHOLE in-container flow answers (a real POST returning 200)
        // before running the strict verification -- a slow CI can otherwise catch the gap between
        // servlet mount and route readiness (observed as a 500).
        waitForInContainerFlow("http://127.0.0.1:8181/api/sample-app/sample/payload", 120_000);
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
}
