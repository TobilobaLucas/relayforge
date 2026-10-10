package dev.relayforge.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.relayforge.config.RelayProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestFilter.class);
    private final ObjectMapper mapper;
    private final int bodyLimit;

    public RequestFilter(ObjectMapper mapper, RelayProperties properties) {
        this.mapper = mapper;
        this.bodyLimit = properties.maxPayloadBytes() + 4096;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader("X-Request-Id");
        String id =
                supplied != null && supplied.matches("[a-zA-Z0-9._-]{1,64}")
                        ? supplied
                        : UUID.randomUUID().toString();
        MDC.put("requestId", id);
        response.setHeader("X-Request-Id", id);
        long start = System.nanoTime();
        try {
            if (request.getRequestURI().startsWith("/api/")
                    && (request.getMethod().equals("POST")
                            || request.getMethod().equals("PATCH"))) {
                if (request.getContentLengthLong() > bodyLimit) {
                    tooLarge(response);
                    return;
                }
                byte[] body = request.getInputStream().readNBytes(bodyLimit + 1);
                if (body.length > bodyLimit) {
                    tooLarge(response);
                    return;
                }
                chain.doFilter(new BodyRequest(request, body), response);
            } else {
                chain.doFilter(request, response);
            }
        } finally {
            log.atInfo()
                    .addKeyValue("method", request.getMethod())
                    .addKeyValue("path", request.getRequestURI())
                    .addKeyValue("status", response.getStatus())
                    .addKeyValue("durationMs", (System.nanoTime() - start) / 1_000_000)
                    .log("HTTP request completed");
            MDC.remove("requestId");
        }
    }

    private void tooLarge(HttpServletResponse response) throws IOException {
        Problems.write(
                response,
                mapper,
                HttpStatus.PAYLOAD_TOO_LARGE,
                "payload_too_large",
                "The request body exceeds the size limit.");
    }

    private static final class BodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        BodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return input.read();
                }

                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("Synchronous request body");
                }
            };
        }
    }
}
