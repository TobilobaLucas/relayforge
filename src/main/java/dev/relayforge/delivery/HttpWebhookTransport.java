package dev.relayforge.delivery;

import dev.relayforge.config.RelayProperties;
import dev.relayforge.security.SecretVault;
import dev.relayforge.security.UrlPolicy;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

@Component
public class HttpWebhookTransport implements WebhookTransport {
    private final UrlPolicy urls;
    private final SecretVault vault;
    private final RelayProperties properties;
    private final Clock clock;

    public HttpWebhookTransport(
            UrlPolicy urls, SecretVault vault, RelayProperties properties, Clock clock) {
        this.urls = urls;
        this.vault = vault;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public DeliveryResult send(DeliveryJob job) {
        long started = System.nanoTime();
        Timeout timeout = Timeout.ofMilliseconds(properties.requestTimeout().toMillis());
        var manager =
                PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(
                                new DnsResolver() {
                                    @Override
                                    public InetAddress[] resolve(String host)
                                            throws UnknownHostException {
                                        return urls.resolve(host);
                                    }

                                    @Override
                                    public String resolveCanonicalHostname(String host) {
                                        return host;
                                    }
                                })
                        .setDefaultConnectionConfig(
                                ConnectionConfig.custom()
                                        .setConnectTimeout(timeout)
                                        .setSocketTimeout(timeout)
                                        .build())
                        .setDefaultSocketConfig(SocketConfig.custom().setSoTimeout(timeout).build())
                        .build();
        try (var client =
                HttpClients.custom()
                        .setConnectionManager(manager)
                        .setDefaultRequestConfig(
                                RequestConfig.custom()
                                        .setResponseTimeout(timeout)
                                        .setConnectionRequestTimeout(timeout)
                                        .build())
                        .disableRedirectHandling()
                        .disableAutomaticRetries()
                        .disableCookieManagement()
                        .build()) {
            HttpPost request = new HttpPost(urls.validateForDelivery(job.url()));
            long timestamp = clock.instant().getEpochSecond();
            request.setHeader("Webhook-Id", job.id().toString());
            request.setHeader("Webhook-Event-Id", job.eventId().toString());
            request.setHeader("Webhook-Attempt", Integer.toString(job.attemptNumber()));
            request.setHeader(
                    "Webhook-Signature",
                    WebhookSigner.sign(
                            vault.decrypt(job.encryptedSecret(), job.endpointId()),
                            timestamp,
                            job.id(),
                            job.envelope()));
            request.setHeader("User-Agent", "RelayForge/1.0");
            request.setEntity(new StringEntity(job.envelope(), ContentType.APPLICATION_JSON));
            // Never buffer or log untrusted response bodies. Close the per-attempt connection.
            try (var response = client.executeOpen(null, request, HttpClientContext.create())) {
                int status = response.getCode();
                Header retryAfter = response.getFirstHeader("Retry-After");
                return new DeliveryResult(
                        status,
                        status >= 200 && status < 300 ? null : "http_" + status,
                        RetryPolicy.retryable(status),
                        RetryPolicy.parseRetryAfter(
                                retryAfter == null ? null : retryAfter.getValue(), clock.instant()),
                        elapsed(started));
            }
        } catch (UrlPolicy.UnsafeAddressException e) {
            return DeliveryResult.failure("unsafe_destination", false, elapsed(started));
        } catch (dev.relayforge.common.ApiException e) {
            return DeliveryResult.failure("unsafe_destination", false, elapsed(started));
        } catch (IOException e) {
            return DeliveryResult.failure("transport_error", true, elapsed(started));
        } catch (RuntimeException e) {
            return DeliveryResult.failure("delivery_configuration_error", false, elapsed(started));
        }
    }

    private static long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
