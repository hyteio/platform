package io.hyte.platform.sample;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import org.apache.camel.ProducerTemplate;

/**
 * CXF JAX-RS endpoint: accepts the request, sends it over Camel JMS (request/response, InOut) to the
 * broker-backed queue, and returns the payload for Jackson to marshal back to the caller.
 */
@Path("/sample")
public class SampleResource {

    /** Consumer-side endpoint: responder routes consume the plain request queue. */
    public static final String REQUEST_QUEUE = "jms:queue:sample.payload";

    private final ProducerTemplate producerTemplate;
    private final String requestEndpoint;
    private final javax.sql.DataSource dataSource;

    /**
     * @param replyQueue fixed reply queue for this requester -- camel's temporary-replyTo-queue
     *        machinery is disabled in favor of a hard-coded queue with an Exclusive reply
     *        consumer. Each requester context needs its OWN reply queue (Exclusive assumes a
     *        single consumer); the responder still replies via the standard JMSReplyTo header,
     *        which is what routes each reply back to the requester that asked.
     */
    public SampleResource(ProducerTemplate producerTemplate, String replyQueue) {
        this(producerTemplate, replyQueue, null);
    }

    /**
     * @param dataSource the container-managed DataSource for the {@code /sample/db} diagnostic
     *        endpoint; null when no in-container database is wired (JVM-side SampleServer).
     */
    public SampleResource(ProducerTemplate producerTemplate, String replyQueue, javax.sql.DataSource dataSource) {
        this.producerTemplate = producerTemplate;
        this.requestEndpoint = REQUEST_QUEUE + "?replyTo=" + replyQueue + "&replyToType=Exclusive";
        this.dataSource = dataSource;
    }

    @POST
    @Path("/payload")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public SamplePayload payload(SampleRequest request) {
        return producerTemplate.requestBody(requestEndpoint, request, SamplePayload.class);
    }

    /**
     * Second execution path: enqueue the note (fire-and-forget) for the IN-CONTAINER XA consumer
     * ({@code io.hyte.platform.sample.xa.XaQueueToDatabase}), which inserts it into the on-disk H2
     * database inside one container-managed XA transaction.
     */
    @POST
    @Path("/xa")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public XaReceipt xa(SampleRequest request) {
        producerTemplate.sendBody(XA_QUEUE, request.getNote());
        return new XaReceipt(true, request.getNote());
    }

    public static final String XA_QUEUE = "jms:queue:sample.xa";

    /**
     * Third execution path: database diagnostics through the injected container-managed
     * DataSource -- reports the H2 version actually loaded IN-CONTAINER (the wrapped h2 bundle),
     * so integration tests can pin it against the platform's h2 version property.
     */
    @POST
    @Path("/db")
    @Produces(MediaType.APPLICATION_JSON)
    public DbInfo db() {
        if (dataSource == null) {
            throw new javax.ws.rs.WebApplicationException("no in-container DataSource wired", 503);
        }
        try (java.sql.Connection connection = dataSource.getConnection();
             java.sql.Statement statement = connection.createStatement();
             java.sql.ResultSet resultSet = statement.executeQuery("SELECT H2VERSION()")) {
            resultSet.next();
            return new DbInfo(resultSet.getString(1));
        } catch (java.sql.SQLException e) {
            throw new javax.ws.rs.WebApplicationException(e, 500);
        }
    }

    /**
     * Bulk-loads {@code rows} padded records into a dedicated table through the container's h2 --
     * fixture for the compaction-on-shutdown validation (grow the store, purge, then assert the
     * file shrinks across a clean close).
     */
    @POST
    @Path("/db/load")
    @Produces(MediaType.APPLICATION_JSON)
    public DbInfo dbLoad(@javax.ws.rs.QueryParam("rows") @javax.ws.rs.DefaultValue("10000") int rows) {
        requireDataSource();
        char[] pad = new char[4000];
        java.util.Arrays.fill(pad, 'x');
        String padding = new String(pad);
        try (java.sql.Connection connection = dataSource.getConnection()) {
            try (java.sql.Statement ddl = connection.createStatement()) {
                ddl.execute("CREATE TABLE IF NOT EXISTS BULK_DATA(ID IDENTITY PRIMARY KEY, CONTENT VARCHAR(4096))");
            }
            try (java.sql.PreparedStatement insert =
                         connection.prepareStatement("INSERT INTO BULK_DATA(CONTENT) VALUES (?)")) {
                for (int i = 1; i <= rows; i++) {
                    insert.setString(1, "bulk-" + i + "-" + padding);
                    insert.addBatch();
                    if (i % 1000 == 0) {
                        insert.executeBatch();
                    }
                }
                insert.executeBatch();
            }
            return new DbInfo(null, rows);
        } catch (java.sql.SQLException e) {
            throw new javax.ws.rs.WebApplicationException(e, 500);
        }
    }

    /** Deletes every bulk-loaded record -- the space is reclaimed by h2's compaction on close. */
    @POST
    @Path("/db/purge")
    @Produces(MediaType.APPLICATION_JSON)
    public DbInfo dbPurge() {
        requireDataSource();
        try (java.sql.Connection connection = dataSource.getConnection();
             java.sql.Statement statement = connection.createStatement()) {
            long deleted = statement.executeUpdate("DELETE FROM BULK_DATA");
            return new DbInfo(null, deleted);
        } catch (java.sql.SQLException e) {
            throw new javax.ws.rs.WebApplicationException(e, 500);
        }
    }

    /**
     * Compacts the database via {@code SHUTDOWN COMPACT} -- the h2 2.x mechanism that actually
     * reclaims deleted space (the MVStore is append-only: bulk deletes GROW the file, and a
     * normal close does not meaningfully compact). h2 reopens transparently on the next
     * connection.
     */
    @POST
    @Path("/db/compact")
    @Produces(MediaType.APPLICATION_JSON)
    public DbInfo dbCompact() {
        requireDataSource();
        try (java.sql.Connection connection = dataSource.getConnection();
             java.sql.Statement statement = connection.createStatement()) {
            statement.execute("SHUTDOWN COMPACT");
            return new DbInfo(null, 0);
        } catch (java.sql.SQLException e) {
            throw new javax.ws.rs.WebApplicationException(e, 500);
        }
    }

    private void requireDataSource() {
        if (dataSource == null) {
            throw new javax.ws.rs.WebApplicationException("no in-container DataSource wired", 503);
        }
    }
}
