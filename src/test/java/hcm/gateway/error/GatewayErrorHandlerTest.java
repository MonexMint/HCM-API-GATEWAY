package hcm.gateway.error;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.support.ServiceUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.net.ConnectException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gateway must never forward infrastructure detail (service ids,
 * hosts, stack text) to browsers on any entry point — every failure
 * becomes the standard envelope with a user-safe message.
 */
class GatewayErrorHandlerTest {

    private final GatewayErrorHandler handler = new GatewayErrorHandler();

    private MockServerWebExchange handle(Throwable ex) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/platform/roles/all").build());
        handler.handle(exchange, ex).block();
        return exchange;
    }

    private static String bodyOf(MockServerWebExchange exchange) {
        String body = exchange.getResponse().getBodyAsString().block();
        return body == null ? "" : body;
    }

    @Test
    void missingServiceInstanceIsSanitized503() {
        MockServerWebExchange exchange = handle(
                new ServiceUnavailableException("Unable to find instance for HCMAUTHSERVICE"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
        String body = bodyOf(exchange);
        assertTrue(body.contains("\"status\":\"ERROR\""));
        assertTrue(body.contains("Service temporarily unavailable"));
        assertFalse(body.contains("HCMAUTHSERVICE"), "service id leaked: " + body);
        assertFalse(body.contains("Unable to find instance"), "discovery text leaked: " + body);
    }

    @Test
    void refusedConnectionIsSanitized503() {
        MockServerWebExchange exchange = handle(
                new ConnectException("Connection refused: localhost/127.0.0.1:8071"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
        String body = bodyOf(exchange);
        assertTrue(body.contains("Service temporarily unavailable"));
        assertFalse(body.contains("127.0.0.1"), "host leaked: " + body);
        assertFalse(body.contains("8071"), "port leaked: " + body);
    }

    @Test
    void unknownFailureIsGeneric502() {
        MockServerWebExchange exchange = handle(
                new RuntimeException("secret-topology-detail"));

        assertEquals(HttpStatus.BAD_GATEWAY, exchange.getResponse().getStatusCode());
        String body = bodyOf(exchange);
        assertTrue(body.contains("Something went wrong on our side"));
        assertFalse(body.contains("secret-topology-detail"), "cause leaked: " + body);
    }
}
