package io.hyte.platform.sample;

/**
 * Camel bean for the in-container blueprint route: answers the {@code sample.payload}
 * request/response queue with the full alignment payload (same responder logic as the JVM-side
 * {@link SampleServer} route).
 */
public class SampleResponder {

    public SamplePayload respond(SampleRequest request) {
        return SamplePayload.build(request, SamplePayload.FIXED_DATE_TIME);
    }
}
