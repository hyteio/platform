package io.hyte.platform.sample.xa;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.sql.DataSource;
import javax.transaction.UserTransaction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * XA consumer: receives from the {@value #QUEUE} queue and inserts into the XA_MESSAGES table, both
 * inside one container-managed XA transaction. Operates ONLY on the injected {@link DataSource},
 * {@link ConnectionFactory} and {@link UserTransaction} — the enlisting XA wiring (pax-transx over
 * the Geronimo transaction manager from the karaf {@code transaction} feature) is supplied entirely
 * by the blueprint container.
 * <p>
 * A message whose text starts with {@value #POISON_PREFIX} is received and inserted, then the
 * transaction is ROLLED BACK — proving atomicity: the row must vanish and the broker must redeliver
 * (eventually dead-lettering to ActiveMQ.DLQ), while committed messages land exactly once.
 */
public class XaQueueToDatabase {

    public static final String QUEUE = "sample.xa";
    public static final String POISON_PREFIX = "POISON";

    private static final Logger logger = LoggerFactory.getLogger(XaQueueToDatabase.class);

    private final DataSource dataSource;
    private final ConnectionFactory connectionFactory;
    private final UserTransaction userTransaction;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread worker;

    public XaQueueToDatabase(DataSource dataSource, ConnectionFactory connectionFactory, UserTransaction userTransaction) {
        this.dataSource = dataSource;
        this.connectionFactory = connectionFactory;
        this.userTransaction = userTransaction;
    }

    public void start() {
        running.set(true);
        worker = new Thread(this::run, "sample-xa-consumer");
        worker.setDaemon(true);
        worker.start();
        logger.info("sample-xa consumer started for queue {}", QUEUE);
    }

    public void stop() {
        running.set(false);
        if (worker != null) {
            worker.interrupt();
        }
        logger.info("sample-xa consumer stopped");
    }

    private void run() {
        boolean ddlDone = false;
        while (running.get()) {
            try {
                if (!ddlDone) {
                    createTable();
                    ddlDone = true;
                }
                processOneMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                logger.warn("sample-xa iteration failed (will retry): {}", e.toString());
                sleepQuietly();
            }
        }
    }

    /** DDL runs outside the XA transaction, retried until the DataSource is ready. */
    private void createTable() throws Exception {
        try (java.sql.Connection jdbc = dataSource.getConnection();
             Statement statement = jdbc.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS XA_MESSAGES (ID IDENTITY PRIMARY KEY, CONTENT VARCHAR(255))");
        }
        logger.info("sample-xa XA_MESSAGES table ready");
    }

    private void processOneMessage() throws Exception {
        userTransaction.begin();
        boolean rollback = false;
        try (Connection jms = connectionFactory.createConnection()) {
            jms.start();
            try (Session session = jms.createSession(false, Session.AUTO_ACKNOWLEDGE);
                 MessageConsumer consumer = session.createConsumer(session.createQueue(QUEUE))) {
                Message message = consumer.receive(1000);
                if (message instanceof TextMessage) {
                    String content = ((TextMessage) message).getText();
                    try (java.sql.Connection jdbc = dataSource.getConnection();
                         PreparedStatement insert = jdbc.prepareStatement("INSERT INTO XA_MESSAGES (CONTENT) VALUES (?)")) {
                        insert.setString(1, content);
                        insert.executeUpdate();
                    }
                    if (content.startsWith(POISON_PREFIX)) {
                        rollback = true; // atomicity proof: row AND message consumption must both revert
                        logger.info("sample-xa rolling back poison message: {}", content);
                    } else {
                        logger.info("sample-xa committing message: {}", content);
                    }
                }
            }
        } catch (Exception e) {
            rollback = true;
            throw e;
        } finally {
            if (rollback) {
                userTransaction.rollback();
            } else {
                userTransaction.commit();
            }
        }
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
