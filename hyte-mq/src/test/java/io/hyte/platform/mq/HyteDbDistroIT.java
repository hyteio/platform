package io.hyte.platform.mq;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import io.hyte.platform.sample.DbInfo;
import io.hyte.platform.sample.HyteJackson;
import io.hyte.platform.sample.SampleFlowVerifier;

/**
 * Boots the ASSEMBLED hyte-db distribution and validates the H2 database it ships (the hyte-db
 * feature: the wrapped h2 bundle, versioned by the h2 release, with org.h2.server exported):
 * <ol>
 *   <li><b>version pin</b>: {@code SELECT H2VERSION()} through the injected container-managed
 *       DataSource must equal the platform's {@code hyte.h2.version} (catches BOM-drift bugs
 *       where the loaded bundle silently diverges from the pin),</li>
 *   <li><b>in-container JDBC + XA write</b>: rows committed via the sample's 2PC consumer,</li>
 *   <li><b>AUTO_SERVER cross-JVM handoff</b>: this test JVM queries the file database over
 *       AUTO_SERVER while the container holds it -- the server side runs the CONTAINER's h2,</li>
 *   <li><b>restart persistence</b>: clean stop + start, committed rows survive,</li>
 *   <li><b>crash recovery</b>: kill -9 + start, the MVStore recovers and committed rows are
 *       intact exactly once,</li>
 *   <li><b>feature state</b>: hyte-db (and the supporting stack) report Installed over JMX,</li>
 *   <li><b>compaction</b>: bulk-load ~40MB, purge, then {@code SHUTDOWN COMPACT} in-container
 *       -- the mv.db file must shrink to under half (h2 2.x MVStore is append-only: deletes
 *       GROW the file, and only SHUTDOWN COMPACT reclaims the space).</li>
 * </ol>
 * Unlike the hyte-mq ITs, NO h2 local-repo fixture is provisioned -- the hyte-db distribution
 * ships h2 natively, and this test must prove that.
 */
public class HyteDbDistroIT extends DistroTestSupport {

    private static final String BASE = "http://127.0.0.1:8181/api/sample-app";

    @Test
    public void h2DatabaseLifecycleOnAssembledDbDistribution() throws Exception {
        assumeSupportedJava();
        unpackDistro(Path.of(System.getProperty("hyte.db.workdir")), "hyte.db.tarball");

        int brokerPort = freePort();
        int rmiRegistryPort = freePort();
        int rmiServerPort = freePort();
        Map<String, String> jmxPorts = Map.of(
                "ORG_APACHE_KARAF_MANAGEMENT_RMIREGISTRYPORT", String.valueOf(rmiRegistryPort),
                "ORG_APACHE_KARAF_MANAGEMENT_RMISERVERPORT", String.valueOf(rmiServerPort));

        // the sample blueprint needs a broker named hyte-it (vm://hyte-it?create=false); the
        // hyte-db distro boots only the activemq CLIENT by default, so add the broker + XA +
        // cxf/camel features -- all resolved from the distribution's own shipped repos
        writeBrokerConfig("hyte-it",
                "            <transportConnector name=\"openwire\" uri=\"tcp://127.0.0.1:" + brokerPort + "\"/>");
        Path featuresCfg = distroHome.resolve("etc/org.apache.karaf.features.cfg");
        Files.writeString(featuresCfg, Files.readString(featuresCfg, StandardCharsets.UTF_8)
                .replace("featuresBoot = ",
                        "featuresBoot = transaction, pax-transx-jdbc, pax-transx-jms, hyte-cxf-jackson, camel, hyte-activemq-broker, "),
                StandardCharsets.UTF_8);

        // no h2 fixture: hyte-db ships h2 natively (that is the point of this test)
        deploySampleBundle(false);

        assertNoConflictingInstance();
        startDistro(jmxPorts);
        waitForOpenWire(brokerPort, 180_000);
        waitForInContainerFlow(BASE + "/sample/payload", 300_000);

        String pinnedH2 = System.getProperty("hyte.h2.version");
        String jdbcUrl = "jdbc:h2:file:" + distroHome.resolve("data/it-xa/sampledb") + ";AUTO_SERVER=TRUE";

        // --- 1. version pin: the h2 loaded IN-CONTAINER must be the platform pin ---------------
        String inContainerVersion = fetchInContainerH2Version();
        if (!pinnedH2.equals(inContainerVersion)) {
            throw new AssertionError("in-container H2 version " + inContainerVersion
                    + " != platform pin " + pinnedH2);
        }

        // --- 2. in-container JDBC + XA write ---------------------------------------------------
        SampleFlowVerifier.submitXa(BASE, "db-persist-1");
        waitForRow(jdbcUrl, "db-persist-1", 120_000);

        // --- 3. AUTO_SERVER cross-JVM handoff: server side is the CONTAINER's h2 bundle --------
        // (the container opened the database first, so it runs the auto-server; this JVM attaches
        // as a client -- do NOT query while the container is down or this JVM becomes the server)
        String autoServerVersion = queryH2Version(jdbcUrl);
        if (!pinnedH2.equals(autoServerVersion)) {
            throw new AssertionError("H2VERSION() over AUTO_SERVER returned " + autoServerVersion
                    + " != platform pin " + pinnedH2);
        }

        // --- 4. restart persistence: clean stop, start, committed rows survive -----------------
        shutdownContainer();
        startDistro(jmxPorts);
        waitForOpenWire(brokerPort, 180_000);
        waitForInContainerFlow(BASE + "/sample/payload", 300_000);
        if (countRows(jdbcUrl, "db-persist-1") != 1) {
            throw new AssertionError("committed row lost across clean container restart");
        }

        // --- 5. crash recovery: kill -9, start, MVStore recovers with rows intact once ---------
        SampleFlowVerifier.submitXa(BASE, "db-persist-2");
        waitForRow(jdbcUrl, "db-persist-2", 120_000);
        killContainer();
        startDistro(jmxPorts);
        waitForOpenWire(brokerPort, 180_000);
        waitForInContainerFlow(BASE + "/sample/payload", 300_000);
        if (countRows(jdbcUrl, "db-persist-1") != 1 || countRows(jdbcUrl, "db-persist-2") != 1) {
            throw new AssertionError("H2 crash recovery lost or duplicated committed rows"
                    + "\nkaraf.log tail:\n" + karafLogTail(40));
        }

        // --- 6. feature state over JMX ---------------------------------------------------------
        assertFeaturesStarted(rmiRegistryPort, rmiServerPort, Set.of(
                "hyte-db", "transaction", "pax-transx-jdbc", "pax-transx-jms",
                "hyte-activemq-broker", "hyte-cxf-jaxrs"));

        // --- 7. compaction: grow the store, purge, SHUTDOWN COMPACT, file must SHRINK ----------
        // h2 2.x MVStore is append-only: the bulk DELETE alone GROWS the file (observed 40MB ->
        // 283MB), and a normal close does not reclaim it -- SHUTDOWN COMPACT is the h2 2.x
        // mechanism that actually compacts, so that is what this phase validates in-container
        long loaded = dbOp("/sample/db/load?rows=10000");
        if (loaded != 10_000) {
            throw new AssertionError("bulk load reported " + loaded + " rows, expected 10000");
        }
        shutdownContainer();
        Path dbFile = distroHome.resolve("data/it-xa/sampledb.mv.db");
        long sizeLoaded = Files.size(dbFile);
        if (sizeLoaded < 20_000_000L) {
            throw new AssertionError("bulk load produced only " + sizeLoaded
                    + " bytes -- fixture too small to prove compaction (expected >20MB)");
        }

        startDistro(jmxPorts);
        waitForOpenWire(brokerPort, 180_000);
        waitForInContainerFlow(BASE + "/sample/payload", 300_000);
        long purged = dbOp("/sample/db/purge");
        if (purged != 10_000) {
            throw new AssertionError("purge deleted " + purged + " rows, expected 10000");
        }
        dbOp("/sample/db/compact"); // SHUTDOWN COMPACT in-container
        shutdownContainer();
        long sizePurged = Files.size(dbFile);
        System.out.println("h2 compaction: " + sizeLoaded + " bytes loaded -> " + sizePurged
                + " bytes after purge + SHUTDOWN COMPACT");
        if (sizePurged > sizeLoaded / 2) {
            throw new AssertionError("h2 SHUTDOWN COMPACT did not reclaim space: " + sizeLoaded
                    + " bytes loaded -> " + sizePurged + " bytes after purge + compact (expected < half)");
        }
    }

    /** POSTs a /sample/db operation and returns the reported row count. */
    private long dbOp(String pathAndQuery) throws Exception {
        java.net.HttpURLConnection connection =
                (java.net.HttpURLConnection) new java.net.URL(BASE + pathAndQuery).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(120_000); // bulk load runs tens of thousands of inserts
        connection.setRequestMethod("POST");
        int code = connection.getResponseCode();
        if (code != 200) {
            java.io.InputStream err = connection.getErrorStream();
            throw new AssertionError(pathAndQuery + " answered " + code + ": "
                    + head(err == null ? "" : new String(err.readAllBytes(), StandardCharsets.UTF_8)));
        }
        try (java.io.InputStream in = connection.getInputStream()) {
            Long rows = HyteJackson.newObjectMapper().readValue(in, DbInfo.class).getRows();
            return rows == null ? -1 : rows;
        }
    }

    /** POSTs the sample's /sample/db diagnostic endpoint and unmarshals the HYTE-wrapped DbInfo. */
    private String fetchInContainerH2Version() throws Exception {
        java.net.HttpURLConnection connection =
                (java.net.HttpURLConnection) new java.net.URL(BASE + "/sample/db").openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(30_000);
        connection.setRequestMethod("POST");
        int code = connection.getResponseCode();
        if (code != 200) {
            java.io.InputStream err = connection.getErrorStream();
            throw new AssertionError("/sample/db answered " + code + ": "
                    + head(err == null ? "" : new String(err.readAllBytes(), StandardCharsets.UTF_8)));
        }
        try (java.io.InputStream in = connection.getInputStream()) {
            return HyteJackson.newObjectMapper().readValue(in, DbInfo.class).getH2Version();
        }
    }

    private static String queryH2Version(String jdbcUrl) throws Exception {
        try (java.sql.Connection jdbc = java.sql.DriverManager.getConnection(jdbcUrl);
             java.sql.Statement statement = jdbc.createStatement();
             java.sql.ResultSet resultSet = statement.executeQuery("SELECT H2VERSION()")) {
            resultSet.next();
            return resultSet.getString(1);
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
}
