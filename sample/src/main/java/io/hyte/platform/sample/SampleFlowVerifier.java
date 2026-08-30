package io.hyte.platform.sample;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Executes and verifies the full alignment flow against a running {@link SampleServer}: posts a
 * root-wrapped request (with a single-string {@code tags}) and asserts every marshaling contract on
 * the response — root wrap, indentation, ISO-8601 dates, primitive min/max/zero renderings, omission
 * of null fields, collection wrap/unwrap, and the HYTE-configured deserialization round trip.
 * Plain {@link AssertionError}s so it is usable from any harness (unit test, integration test, CLI).
 */
public final class SampleFlowVerifier {

    private SampleFlowVerifier() {}

    public static void verify(String baseAddress) throws Exception {
        // root-wrapped request; "tags" is a SINGLE string -> ACCEPT_SINGLE_VALUE_AS_ARRAY
        String requestJson = "{\"SampleRequest\":{\"note\":\"hello\",\"tags\":\"solo\"}}";
        String responseJson = post(baseAddress + "/sample/payload", requestJson);

        // --- serialization features, asserted on the raw JSON text ---
        check(responseJson.replaceAll("\\s", "").startsWith("{\"SamplePayload\":{"),
                "WRAP_ROOT_VALUE: response must be wrapped as SamplePayload, got: " + head(responseJson));
        check(responseJson.contains("\n"), "INDENT_OUTPUT: response must be pretty-printed");
        check(responseJson.contains("\"isoDateTime\" : \"2026-08-30T12:34:56.789-05:00\""),
                "WRITE_DATES_AS_TIMESTAMPS=false: date must be ISO-8601 text");

        // integral min/max/zero exact renderings
        for (String expected : new String[] {
                "\"byteMin\" : -128", "\"byteMax\" : 127", "\"byteZero\" : 0",
                "\"shortMin\" : -32768", "\"shortMax\" : 32767",
                "\"intMin\" : -2147483648", "\"intMax\" : 2147483647",
                "\"longMin\" : -9223372036854775808", "\"longMax\" : 9223372036854775807",
                "\"booleanTrue\" : true", "\"booleanFalse\" : false"}) {
            check(responseJson.contains(expected), "missing expected rendering: " + expected);
        }

        // Include.NON_NULL: every null wrapper field must be ABSENT
        for (String nullField : new String[] {"booleanNull", "byteNull", "shortNull", "intNull",
                "longNull", "floatNull", "doubleNull", "charNull", "isoDateTimeNull"}) {
            check(!responseJson.contains(nullField), "NON_NULL: '" + nullField + "' must be omitted");
        }

        // collection wrap: a real JSON array of three strings
        check(responseJson.replaceAll("\\s", "")
                        .contains("\"stringCollection\":[\"alpha\",\"bravo\",\"charlie\"]"),
                "stringCollection must serialize as a JSON array");

        // --- full round trip: deserialize with the same HYTE configuration (UNWRAP_ROOT_VALUE) ---
        ObjectMapper mapper = HyteJackson.newObjectMapper();
        SamplePayload payload = mapper.readValue(responseJson, SamplePayload.class);
        checkEquals(Byte.MIN_VALUE, payload.getByteMin(), "byteMin");
        checkEquals(Byte.MAX_VALUE, payload.getByteMax(), "byteMax");
        checkEquals(Short.MIN_VALUE, payload.getShortMin(), "shortMin");
        checkEquals(Short.MAX_VALUE, payload.getShortMax(), "shortMax");
        checkEquals(Integer.MIN_VALUE, payload.getIntMin(), "intMin");
        checkEquals(Integer.MAX_VALUE, payload.getIntMax(), "intMax");
        checkEquals(Long.MIN_VALUE, payload.getLongMin(), "longMin");
        checkEquals(Long.MAX_VALUE, payload.getLongMax(), "longMax");
        checkEquals(Float.MIN_VALUE, payload.getFloatMin(), "floatMin");
        checkEquals(Float.MAX_VALUE, payload.getFloatMax(), "floatMax");
        checkEquals(0.0f, payload.getFloatZero(), "floatZero");
        checkEquals(Double.MIN_VALUE, payload.getDoubleMin(), "doubleMin");
        checkEquals(Double.MAX_VALUE, payload.getDoubleMax(), "doubleMax");
        checkEquals(0.0d, payload.getDoubleZero(), "doubleZero");
        checkEquals(Character.MIN_VALUE, payload.getCharMin(), "charMin");
        checkEquals(Character.MAX_VALUE, payload.getCharMax(), "charMax");
        checkEquals('A', payload.getCharValue(), "charValue");
        check(payload.getIntNull() == null, "intNull must deserialize to null");
        check(payload.getIsoDateTimeNull() == null, "isoDateTimeNull must deserialize to null");
        // the raw JSON carried the original -05:00 offset (asserted above); on parse, Jackson's
        // default ADJUST_DATES_TO_CONTEXT_TIME_ZONE (not disabled in the HYTE config) normalizes
        // the offset to the context zone, so the round trip is instant-equal, not offset-equal
        checkEquals(SampleServer.SAMPLE_DATE_TIME.toInstant(), payload.getIsoDateTime().toInstant(), "isoDateTime instant");
        checkEquals(List.of("alpha", "bravo", "charlie"), payload.getStringCollection(), "stringCollection");

        // request data crossed CXF -> Camel -> JMS -> broker -> reply intact;
        // the single-string "tags" arrived as a one-element list (ACCEPT_SINGLE_VALUE_AS_ARRAY)
        checkEquals("hello", payload.getRequestNote(), "requestNote");
        checkEquals(List.of("solo"), payload.getRequestTags(), "requestTags (single value as array)");
    }

    /**
     * Second execution path: POST the note to /sample/xa (which enqueues it for the in-container XA
     * consumer) and assert the root-wrapped receipt. Database/DLQ outcomes are asserted by the caller,
     * which knows the container's paths.
     */
    public static void submitXa(String baseAddress, String note) throws Exception {
        String responseJson = post(baseAddress + "/sample/xa",
                "{\"SampleRequest\":{\"note\":\"" + note + "\"}}");
        String flat = responseJson.replaceAll("\\s", "");
        check(flat.startsWith("{\"XaReceipt\":{"), "XA receipt must be root-wrapped, got: " + head(responseJson));
        check(flat.contains("\"queued\":true"), "XA receipt must confirm queued");
        check(flat.contains("\"content\":\"" + note + "\""), "XA receipt must echo the note");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void checkEquals(Object expected, Object actual, String what) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(what + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static String post(String url, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");
        connection.setDoOutput(true);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        check(connection.getResponseCode() == 200, "expected HTTP 200, got " + connection.getResponseCode());
        check(String.valueOf(connection.getContentType()).startsWith("application/json"),
                "response must be application/json, got " + connection.getContentType());
        try (InputStream in = connection.getInputStream();
             ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, n);
            }
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }

    private static String head(String s) {
        return s == null ? "null" : s.substring(0, Math.min(s.length(), 120));
    }
}
