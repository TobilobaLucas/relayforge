package dev.relayforge.security;

import dev.relayforge.common.ApiException;
import dev.relayforge.config.RelayProperties;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class UrlPolicy {
    private final Set<String> developmentHosts;

    public UrlPolicy(RelayProperties properties) {
        this.developmentHosts = properties.developmentHosts();
    }

    public URI validate(String url) {
        try {
            return validateForDelivery(url);
        } catch (UnknownHostException e) {
            throw invalid("The endpoint must resolve exclusively to permitted public addresses.");
        }
    }

    // A DNS lookup failure is a transient transport problem when delivering to
    // an already registered endpoint. Keep it distinct from a blocked address.
    public URI validateForDelivery(String url) throws UnknownHostException {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw invalid("Supply an absolute HTTPS endpoint URL.");
        }
        String host = uri.getHost();
        if (host == null
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || uri.getPort() == 0
                || uri.getPort() > 65535
                || (!"https".equalsIgnoreCase(uri.getScheme())
                        && !("http".equalsIgnoreCase(uri.getScheme())
                                && isDevelopmentHost(host)))) {
            throw invalid(
                    "Use HTTPS without user info or a fragment. HTTP is allowed only for explicitly configured development hosts.");
        }
        resolve(host);
        return uri;
    }

    // The transport uses these exact vetted addresses for the connection, keeping
    // validation and connection DNS resolution together even after registration.
    public InetAddress[] resolve(String host) throws UnknownHostException {
        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (!isDevelopmentHost(host)) {
            for (InetAddress address : addresses) {
                if (!isPublic(address)) throw new UnsafeAddressException();
            }
        }
        if (addresses.length == 0) throw new UnknownHostException("No destination addresses");
        return addresses;
    }

    private boolean isDevelopmentHost(String host) {
        return developmentHosts.contains(host.toLowerCase(Locale.ROOT));
    }

    public static boolean isPublic(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int a = Byte.toUnsignedInt(bytes[0]),
                    b = Byte.toUnsignedInt(bytes[1]),
                    c = Byte.toUnsignedInt(bytes[2]);
            return !(a == 0
                    || a == 10
                    || a == 127
                    || a >= 224
                    || (a == 100 && b >= 64 && b <= 127)
                    || (a == 169 && b == 254)
                    || (a == 172 && b >= 16 && b <= 31)
                    || (a == 192
                            && (b == 168 || (b == 0 && (c == 0 || c == 2)) || (b == 88 && c == 99)))
                    || (a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100)))
                    || (a == 203 && b == 0 && c == 113));
        }
        // Accept IPv6 global unicast only; exclude special-purpose tunneling and documentation
        // blocks.
        int a = Byte.toUnsignedInt(bytes[0]),
                b = Byte.toUnsignedInt(bytes[1]),
                c = Byte.toUnsignedInt(bytes[2]),
                d = Byte.toUnsignedInt(bytes[3]);
        return (a & 0xe0) == 0x20
                && !(a == 0x20 && b == 0x01 && (c < 2 || (c == 0x0d && d == 0xb8)))
                && !(a == 0x20 && b == 0x02)
                && !(a == 0x3f && b == 0xff && (c & 0xf0) == 0);
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "unsafe_endpoint_url", message);
    }

    public static final class UnsafeAddressException extends UnknownHostException {
        public UnsafeAddressException() {
            super("Destination address is not permitted");
        }
    }
}
