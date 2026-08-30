package io.hyte.platform.sample;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.jms.JmsComponent;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.cxf.Bus;
import org.apache.cxf.BusFactory;
import org.apache.cxf.endpoint.Server;
import org.apache.cxf.management.InstrumentationManager;
import org.apache.cxf.management.jmx.InstrumentationManagerImpl;
import org.apache.cxf.jaxrs.JAXRSServerFactoryBean;

import com.fasterxml.jackson.jaxrs.json.JacksonJsonProvider;

/**
 * Wires the full alignment sample: an embedded ActiveMQ broker (vm://), a Camel context with a
 * camel-jms backend route answering request/response on the sample queue, and a CXF JAX-RS server
 * (Jetty HTTP transport) marshaling with the HYTE-configured Jackson ObjectMapper.
 */
public class SampleServer implements AutoCloseable {

    /** Fixed timestamp so callers can assert the exact ISO-8601 rendering. */
    public static final OffsetDateTime SAMPLE_DATE_TIME = SamplePayload.FIXED_DATE_TIME;

    private final CamelContext camelContext;
    private final ProducerTemplate producerTemplate;
    private final Server cxfServer;
    private final String baseAddress;

    public SampleServer(int httpPort) throws Exception {
        // vm:// transport creates the embedded broker on first connection
        this(httpPort, "vm://sample-align?broker.persistent=false&broker.useJmx=false");
    }

    public SampleServer(int httpPort, String brokerUrl) throws Exception {
        ActiveMQConnectionFactory connectionFactory = new ActiveMQConnectionFactory(brokerUrl);
        connectionFactory.setTrustAllPackages(true); // payload travels as an ObjectMessage

        camelContext = new DefaultCamelContext();
        camelContext.addComponent("jms", JmsComponent.jmsComponentAutoAcknowledge(connectionFactory));
        camelContext.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                // InOut: camel replies to JMSReplyTo with the route's out body
                from(SampleResource.REQUEST_QUEUE)
                        .process(exchange -> {
                            SampleRequest request = exchange.getIn().getBody(SampleRequest.class);
                            exchange.getMessage().setBody(SamplePayload.build(request, SAMPLE_DATE_TIME));
                        });
            }
        });
        camelContext.start();
        producerTemplate = camelContext.createProducerTemplate();

        baseAddress = "http://localhost:" + httpPort + "/api";
        // CXF JMX: register bus/endpoint MBeans in the platform MBeanServer
        Bus bus = BusFactory.newInstance().createBus();
        InstrumentationManagerImpl instrumentationManager = new InstrumentationManagerImpl();
        instrumentationManager.setEnabled(true);
        instrumentationManager.setUsePlatformMBeanServer(true);
        instrumentationManager.setBus(bus);
        instrumentationManager.init();
        bus.setExtension(instrumentationManager, InstrumentationManager.class);
        JAXRSServerFactoryBean factory = new JAXRSServerFactoryBean();
        factory.setBus(bus);
        factory.setAddress(baseAddress);
        factory.setServiceBean(new SampleResource(producerTemplate));
        factory.setProviders(Collections.singletonList(new JacksonJsonProvider(HyteJackson.newObjectMapper())));
        cxfServer = factory.create();
    }

    public String getBaseAddress() {
        return baseAddress;
    }

    @Override
    public void close() throws Exception {
        cxfServer.destroy();
        producerTemplate.stop();
        camelContext.stop();
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        SampleServer server = args.length > 1 ? new SampleServer(port, args[1]) : new SampleServer(port);
        javax.management.MBeanServer mbs = java.lang.management.ManagementFactory.getPlatformMBeanServer();
        System.out.println("JMX: " + mbs.queryNames(new javax.management.ObjectName("org.apache.camel:*"), null).size()
                + " Camel MBeans, " + mbs.queryNames(new javax.management.ObjectName("org.apache.cxf:*"), null).size()
                + " CXF MBeans registered (attach jconsole to this JVM to browse)");
        System.out.println("Sample alignment server: POST " + server.getBaseAddress() + "/sample/payload");
        System.out.println("XA execution path:       POST " + server.getBaseAddress() + "/sample/xa");
        Thread.currentThread().join();
    }
}
