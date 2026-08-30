package io.hyte.platform.sample;

import static org.junit.Assert.assertEquals;

import java.net.ServerSocket;

import org.junit.Test;

/**
 * End-to-end validation of the platform's core dependency alignment:
 * HTTP -> CXF JAX-RS (Jetty transport) -> Camel -> camel-jms -> ActiveMQ vm:// broker queue
 * (request/response) -> payload -> Jackson (HYTE configuration) -> caller.
 * The flow assertions live in {@link SampleFlowVerifier} so the hyte-mq integration test can run
 * the identical verification against the assembled distribution's broker.
 */
public class EndToEndAlignmentTest {

    /** The loaded library versions must equal the platform pins (passed in by surefire). */
    @Test
    public void loadedVersionsMatchPlatformPins() throws Exception {
        assertEquals("jackson", System.getProperty("hyte.jackson.version"),
                com.fasterxml.jackson.databind.cfg.PackageVersion.VERSION.toString());
        assertEquals("cxf", System.getProperty("hyte.cxf.version"),
                org.apache.cxf.version.Version.getCurrentVersion());
        assertEquals("jetty", System.getProperty("hyte.jetty.version"),
                org.eclipse.jetty.util.Jetty.VERSION);
        assertEquals("activemq", System.getProperty("hyte.activemq.version"),
                org.apache.activemq.ActiveMQConnectionMetaData.PROVIDER_VERSION);
        try (org.apache.camel.impl.DefaultCamelContext ctx = new org.apache.camel.impl.DefaultCamelContext()) {
            assertEquals("camel", System.getProperty("hyte.camel.version"), ctx.getVersion());
        }
    }

    @Test
    public void requestResponseThroughFullStack() throws Exception {
        int port = freePort();
        try (SampleServer server = new SampleServer(port)) {
            SampleFlowVerifier.verify(server.getBaseAddress());
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
