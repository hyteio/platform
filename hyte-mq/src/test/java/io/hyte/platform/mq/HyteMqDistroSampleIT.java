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

        // fail fast on a leftover instance holding the fixed ports, before we try to start ours
        assertNoConflictingInstance();

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
        String inContainer = "http://127.0.0.1:8181/api/sample-app";
        waitForInContainerFlow(inContainer + "/sample/payload", 300_000);
        SampleFlowVerifier.verify(inContainer);

        // --- in-container XA runs FIRST, with only the in-container consumers active ------------
        // The whole XA leg is in-container (endpoint + consumer), matching real usage and the
        // passing HyteDbDistroIT. It must NOT overlap the external SampleServer below: that server
        // registers a SECOND, competing consumer on sample.payload against the same broker, and
        // its extra broker connections perturb the in-container XA consumer enough (on JDK 21, with
        // pax-transx's connection pool) to intermittently swallow the sample.xa delivery.
        // --- commit case ---
        SampleFlowVerifier.submitXa(inContainer, "xa-commit-1");
        waitForRow(jdbcUrl, "xa-commit-1", 120_000);
        // --- rollback/atomicity case: the consumer INSERTS then rolls back, so the row must never
        // persist, and after the redelivery policy is exhausted the message dead-letters to ActiveMQ.DLQ
        SampleFlowVerifier.submitXa(inContainer, "POISON-1");
        waitForDlqDepth(brokerUrl, 1, 120_000);
        if (countRows(jdbcUrl, "POISON-1") != 0) {
            throw new AssertionError("XA atomicity violated: rolled-back insert persisted");
        }
        if (countRows(jdbcUrl, "xa-commit-1") != 1) {
            throw new AssertionError("committed XA row must persist exactly once");
        }

        // run the identical payload verification against the JVM-side stack (SampleServer), proving
        // the aligned libraries work outside karaf too (payload marshaling only -- no XA)
        try (SampleServer server = new SampleServer(httpPort, brokerUrl)) {
            SampleFlowVerifier.verify(server.getBaseAddress());
        }
    }

    private void waitForRow(String jdbcUrl, String content, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        Exception lastError = null;
        int lastCount = -1;
        while (System.currentTimeMillis() < deadline) {
            try {
                lastCount = countRows(jdbcUrl, content);
                lastError = null;
                if (lastCount == 1) {
                    return;
                }
            } catch (Exception e) {
                // table/db not queryable yet (still starting, or a real fault) -- keep polling but
                // remember WHY, so a persistent failure is diagnosable instead of a bare timeout
                lastError = e;
            }
            Thread.sleep(1000);
        }
        throw new AssertionError("XA-committed row '" + content + "' never appeared in " + jdbcUrl
                + (lastError != null ? "\nlast query error: " + lastError : "\nlast row count: " + lastCount)
                + "\nkaraf JVM threads:\n" + karafThreadDump()
                + "\nkaraf.log tail:\n" + karafLogTail(40));
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
