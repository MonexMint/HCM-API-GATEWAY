package hcm.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.reactor.netty.NettyReactiveWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/**
 * Raises Reactor Netty's inbound header cap to match
 * {@code server.max-http-header-size} (default 32KB).
 *
 * <p>Bearer tokens carry the full tenant permission catalog (300+
 * MODULE:ACTION claims ≈ 13KB). Netty's 8KB default answers those requests
 * with 431 before any route runs — applied here explicitly from
 * {@code server.max-http-request-header-size}.</p>
 */
@Configuration
public class NettyHeaderSizeConfig {

    @Bean
    WebServerFactoryCustomizer<NettyReactiveWebServerFactory> nettyHeaderSizeCustomizer(
            @Value("${server.max-http-request-header-size:32KB}") DataSize maxHttpHeaderSize) {
        int maxBytes = Math.toIntExact(maxHttpHeaderSize.toBytes());
        return factory -> factory.addServerCustomizers(
                httpServer -> httpServer.httpRequestDecoder(
                        spec -> spec.maxHeaderSize(maxBytes)));
    }
}
