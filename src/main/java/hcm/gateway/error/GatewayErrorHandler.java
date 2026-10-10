package hcm.gateway.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;

/**
 * Gateway-wide safety net for everything that fails before (or instead of)
 * a downstream response — unknown routes, services missing from discovery
 * ("Unable to find instance for ..."), refused connections and timeouts.
 *
 * <p>Spring's default handler serializes the raw exception message, which
 * leaks internal topology (service ids, hosts, ports) to browsers on the
 * tenant, platform, marketing and career entry points. This handler runs
 * first ({@code @Order(-2)}, ahead of Boot's default at -1) and always
 * answers with the platform's standard {@code {status,message,data}}
 * envelope and a user-safe message. Full details stay in the server log.
 *
 * <p>Successful proxied responses — including downstream 4xx/5xx bodies —
 * never reach this handler, so each service's own friendly messages pass
 * through untouched.
 */
@Component
@Order(-2)
public class GatewayErrorHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

    private static final String UNAVAILABLE =
            "Service temporarily unavailable. Please try again in a moment.";
    private static final String TIMEOUT =
            "The service is taking too long to respond. Please try again.";
    private static final String NOT_FOUND =
            "The requested page was not found.";
    private static final String BAD_REQUEST =
            "The request could not be completed. Please try again.";
    private static final String SERVER_ERROR =
            "Something went wrong on our side. Please try again in a moment.";

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }

        HttpStatus status = resolveStatus(ex);
        String message = messageFor(status);

        // Server log keeps the diagnosis (route + root cause); the client
        // only ever sees the sanitized message above.
        Object route = exchange.getAttribute(
                org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        String routeId = route instanceof org.springframework.cloud.gateway.route.Route r
                ? r.getId() : "unknown-route";
        log.warn("Gateway error route={} status={} error={}: {}",
                routeId, status.value(), ex.getClass().getName(), rootMessage(ex));

        String body = "{\"status\":\"ERROR\",\"message\":\"" + message + "\",\"data\":null}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    private static HttpStatus resolveStatus(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof NotFoundException) {
                return HttpStatus.NOT_FOUND;
            }
            if (current instanceof ResponseStatusException rse) {
                HttpStatusCode code = rse.getStatusCode();
                if (code instanceof HttpStatus httpStatus) {
                    return httpStatus;
                }
                return HttpStatus.BAD_GATEWAY;
            }
            if (isTimeout(current)) {
                return HttpStatus.GATEWAY_TIMEOUT;
            }
            if (isConnectivityFailure(current)) {
                return HttpStatus.SERVICE_UNAVAILABLE;
            }
            current = current.getCause();
        }
        return HttpStatus.BAD_GATEWAY;
    }

    private static boolean isConnectivityFailure(Throwable ex) {
        return ex instanceof ConnectException
                || ex instanceof UnknownHostException
                || ex instanceof java.net.NoRouteToHostException
                || ex instanceof io.netty.channel.ConnectTimeoutException
                || ex instanceof org.springframework.cloud.gateway.support.ServiceUnavailableException
                || nameContains(ex, "AnnotatedConnectException", "UnableToFindInstance",
                        "NoServersAvailable", "ServiceUnavailable");
    }

    private static boolean isTimeout(Throwable ex) {
        return ex instanceof TimeoutException
                || ex instanceof io.netty.handler.timeout.ReadTimeoutException
                || ex instanceof io.netty.handler.timeout.WriteTimeoutException
                || ex instanceof reactor.netty.http.client.PrematureCloseException
                || nameContains(ex, "ReadTimeout", "ResponseTimeout", "TimeoutException");
    }

    private static boolean nameContains(Throwable ex, String... fragments) {
        String name = ex.getClass().getName();
        for (String fragment : fragments) {
            if (name.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static String messageFor(HttpStatus status) {
        if (status == HttpStatus.SERVICE_UNAVAILABLE) return UNAVAILABLE;
        if (status == HttpStatus.GATEWAY_TIMEOUT) return TIMEOUT;
        if (status == HttpStatus.NOT_FOUND) return NOT_FOUND;
        if (status.is4xxClientError()) return BAD_REQUEST;
        return SERVER_ERROR;
    }

    private static String rootMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
