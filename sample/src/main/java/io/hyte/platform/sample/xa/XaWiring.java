package io.hyte.platform.sample.xa;

import javax.jms.ConnectionFactory;
import javax.resource.spi.TransactionSupport.TransactionSupportLevel;
import javax.sql.DataSource;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.ActiveMQXAConnectionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.ops4j.pax.transx.jdbc.ManagedDataSourceBuilder;
import org.ops4j.pax.transx.jms.ManagedConnectionFactoryBuilder;
import org.ops4j.pax.transx.tm.TransactionManager;
import org.osgi.framework.BundleContext;

/**
 * Blueprint factory methods building the XA-enlisting {@link DataSource} and
 * {@link ConnectionFactory} from the platform-shipped pieces: H2's XADataSource and ActiveMQ's
 * XAConnectionFactory wrapped by pax-transx, enlisted with the pax-transx
 * {@link TransactionManager} service registered by the karaf {@code transaction} feature
 * (transaction-manager-geronimo). The consumer bean never sees these types — it is injected with
 * the plain JDBC/JMS interfaces.
 */
public final class XaWiring {

    private XaWiring() {}

    public static DataSource dataSource(TransactionManager transactionManager, String jdbcUrl) throws Exception {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL(jdbcUrl);
        return ManagedDataSourceBuilder.builder()
                .name("sample-xa-h2")
                .dataSource(h2)
                .transaction(TransactionSupportLevel.XATransaction)
                .transactionManager(transactionManager)
                .build();
    }

    /**
     * Must only be invoked once the broker is FULLY STARTED (the blueprint gates this bean on the
     * BrokerService OSGi service, which activemq-osgi registers only after start() +
     * waitUntilStarted()). Attaching over vm:// while the broker is still starting races ActiveMQ's
     * unsynchronized lazy {@code BrokerService.getBroker()} (duplicate addInterceptors -> Health
     * MBean collision when useJmx=true), and pax-transx XA-recovery registration needs a live
     * connection at build time.
     */
    public static ConnectionFactory connectionFactory(TransactionManager transactionManager, BundleContext bundleContext, String brokerUrl) throws Exception {
        waitForStartedBroker(bundleContext, 120_000);
        return ManagedConnectionFactoryBuilder.builder()
                .name("sample-xa-amq")
                .connectionFactory(new ActiveMQConnectionFactory(brokerUrl), new ActiveMQXAConnectionFactory(brokerUrl))
                .transaction(TransactionSupportLevel.XATransaction)
                .transactionManager(transactionManager)
                .build();
    }

    /** activemq-osgi registers the BrokerService OSGi service only after start() + waitUntilStarted(). */
    private static void waitForStartedBroker(BundleContext bundleContext, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (bundleContext.getServiceReference("org.apache.activemq.broker.BrokerService") != null) {
                return;
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("BrokerService was not registered within " + timeoutMillis + "ms");
    }
}
