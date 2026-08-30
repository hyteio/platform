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

    public static final String REQUEST_QUEUE = "jms:queue:sample.payload";

    private final ProducerTemplate producerTemplate;

    public SampleResource(ProducerTemplate producerTemplate) {
        this.producerTemplate = producerTemplate;
    }

    @POST
    @Path("/payload")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public SamplePayload payload(SampleRequest request) {
        return producerTemplate.requestBody(REQUEST_QUEUE, request, SamplePayload.class);
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
}
