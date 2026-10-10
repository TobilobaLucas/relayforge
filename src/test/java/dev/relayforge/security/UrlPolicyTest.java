package dev.relayforge.security;

import static org.assertj.core.api.Assertions.*;

import dev.relayforge.TestSupport;
import dev.relayforge.common.ApiException;
import java.net.InetAddress;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UrlPolicyTest {
    @ParameterizedTest
    @ValueSource(
            strings = {
                "0.0.0.0",
                "10.1.2.3",
                "127.0.0.1",
                "100.64.0.1",
                "169.254.169.254",
                "172.16.0.1",
                "192.168.1.1",
                "192.0.2.1",
                "198.18.0.1",
                "198.51.100.1",
                "203.0.113.1",
                "224.0.0.1",
                "255.255.255.255",
                "::1",
                "::",
                "fe80::1",
                "fc00::1",
                "ff02::1",
                "2001:db8::1",
                "2002:7f00:1::1",
                "3fff::1",
                "::ffff:127.0.0.1"
            })
    void rejectsNonPublicAddresses(String address) throws Exception {
        assertThat(UrlPolicy.isPublic(InetAddress.getByName(address))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.1.1.1", "8.8.8.8", "2606:4700:4700::1111", "2001:4860:4860::8888"})
    void allowsPublicAddresses(String address) throws Exception {
        assertThat(UrlPolicy.isPublic(InetAddress.getByName(address))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://8.8.8.8/hook",
                "file:///etc/passwd",
                "https://user:password@8.8.8.8/hook",
                "https://8.8.8.8/hook#fragment",
                "https://127.0.0.1/hook",
                "https://[::1]/hook",
                "https://8.8.8.8:70000/hook",
                "not a url"
            })
    void rejectsUnsafeUrls(String url) {
        UrlPolicy policy = new UrlPolicy(TestSupport.properties(Set.of()));
        assertThatThrownBy(() -> policy.validate(url)).isInstanceOf(ApiException.class);
    }

    @Test
    void developmentExceptionIsExplicitAndHostSpecific() {
        UrlPolicy policy = new UrlPolicy(TestSupport.properties(Set.of("localhost")));
        assertThat(policy.validate("http://localhost:9000/hooks").getHost()).isEqualTo("localhost");
        assertThatThrownBy(() -> policy.validate("http://127.0.0.1:9000/hooks"))
                .isInstanceOf(ApiException.class);
    }
}
