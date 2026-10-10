package hcm.gateway.filter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Gateway resolves tenant from the Host header for both domain families
 * (env-driven TENANT_BASE_DOMAIN and CAREERS_BASE_DOMAIN) and forwards via
 * trusted headers X-Tenant-Subdomain / X-Entry-Point. Downstream services
 * re-validate against the tenant_domains registry instead of trusting
 * any client-supplied tenant id.
 */
@Component
public class TenantResolutionGatewayFilter implements GlobalFilter, Ordered {

    @Value("${tenant.base-domain:localhost}")
    private String baseDomain;

    /** Env-driven (CAREERS_BASE_DOMAIN); fallback is local-dev only. */
    @Value("${tenant.careers-base-domain:jobs.localhost}")
    private String careersBaseDomain;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String host = exchange.getRequest().getHeaders().getFirst(HttpHeaders.HOST);
        if (host == null) {
            host = exchange.getRequest().getURI().getHost();
        }
        if (host != null) {
            // Strip port if present (e.g., localhost:3000)
            int colonIdx = host.indexOf(':');
            if (colonIdx != -1) {
                host = host.substring(0, colonIdx);
            }
            host = host.toLowerCase();
        }

        ServerHttpRequest.Builder requestBuilder = exchange.getRequest().mutate();
        boolean tenantHeaderAdded = false;

        // FR-9.4/9.5: Frontend (api.ts) already sends X-Tenant-Subdomain derived from window.location.hostname (abc.localhost)
        // when api baseUrl is localhost:8070, Host header is localhost (not abc.localhost), so Host-based resolution would incorrectly classify as MARKETING.
        // Respect existing header from frontend/Gateway upstream trust.
        String existingSubdomain = exchange.getRequest().getHeaders().getFirst("X-Tenant-Subdomain");
        if (existingSubdomain != null && !existingSubdomain.isBlank()) {
            // Preserve frontend subdomain, ensure Entry-Point is TENANT (not MARKETING) for downstream tenant resolution
            // Use headers() with set() to replace any existing MARKETING value, avoiding duplicate header values.
            String existingEntry = exchange.getRequest().getHeaders().getFirst("X-Entry-Point");
            if (existingEntry == null || existingEntry.isBlank() || "MARKETING".equalsIgnoreCase(existingEntry.trim())) {
                requestBuilder.headers(headers -> headers.set("X-Entry-Point", "TENANT"));
            } else if ("CAREERS".equalsIgnoreCase(existingEntry) || "TENANT".equalsIgnoreCase(existingEntry)) {
                // already correct, keep as-is
            } else {
                requestBuilder.headers(headers -> headers.set("X-Entry-Point", "TENANT"));
            }
            tenantHeaderAdded = true;
        }

        if (host != null && !tenantHeaderAdded) {
            // Platform: superadmin.baseDomain
            if (host.equals("superadmin." + baseDomain)) {
                requestBuilder.headers(headers -> headers.set("X-Entry-Point", "PLATFORM"));
            }
            // Internal workspace: tenant.baseDomain (e.g., acme.localhost -> tenant acme)
            else if (baseDomain != null && host.endsWith("." + baseDomain)) {
                String subdomain = host.substring(0, host.length() - baseDomain.length() - 1);
                if (!subdomain.isBlank() && !subdomain.contains(".")) {
                    String finalSubdomain = subdomain;
                    requestBuilder.headers(headers -> {
                        headers.set("X-Tenant-Subdomain", finalSubdomain);
                        headers.set("X-Entry-Point", "TENANT");
                    });
                    tenantHeaderAdded = true;
                } else if (!subdomain.isBlank()) {
                    // Handle careers subdomain on same baseDomain for dev (acme.localhost:3000/careers)
                    // subdomain may be "acme" still, so treat as tenant
                    String firstLabel = subdomain.split("\\.")[0];
                    requestBuilder.headers(headers -> {
                        headers.set("X-Tenant-Subdomain", firstLabel);
                        headers.set("X-Entry-Point", "TENANT");
                    });
                    tenantHeaderAdded = true;
                }
            }
            // Careers domain: tenant.careersBaseDomain
            else if (careersBaseDomain != null && host.endsWith("." + careersBaseDomain)) {
                String subdomain = host.substring(0, host.length() - careersBaseDomain.length() - 1);
                if (!subdomain.isBlank()) {
                    String finalSubdomain = subdomain;
                    requestBuilder.headers(headers -> {
                        headers.set("X-Tenant-Subdomain", finalSubdomain);
                        headers.set("X-Entry-Point", "CAREERS");
                    });
                    tenantHeaderAdded = true;
                }
            }
            // Marketing root or unknown: no tenant header, mark as MARKETING
            else if (host.equals(baseDomain) || host.equals("www." + baseDomain)) {
                requestBuilder.headers(headers -> headers.set("X-Entry-Point", "MARKETING"));
            }
        }

        ServerHttpRequest mutatedRequest = requestBuilder.build();
        ServerWebExchange mutatedExchange = exchange.mutate().request(mutatedRequest).build();
        return chain.filter(mutatedExchange);
    }

    @Override
    public int getOrder() {
        return -1000; // Run early, before routing
    }
}
