package com.straycat.statistra.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.straycat.statistra.config.StatistraProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Instant;
import java.util.Map;

/**
 * Caps the size of ingest request bodies.
 *
 * <p>The ingest endpoint accepts an arbitrary JSON metadata object, so without a
 * ceiling a single request can write an unbounded amount of storage. Spring MVC
 * applies no default limit to JSON bodies.
 *
 * <p>The check is applied to the bytes actually read, not to the declared
 * {@code Content-Length}. Trusting the header is not enough: a chunked request
 * does not send one at all, and a client is free to understate it. An earlier
 * version checked only the header and let a 1.2 MB body through to the broker,
 * where it failed as an opaque 500.
 *
 * <p>The header is still consulted as a fast path, so an honest oversized
 * request is rejected before any of it is read.
 */
@Component
@Order(Ordered.BODY_SIZE)
public class BodySizeLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(BodySizeLimitFilter.class);

    private final StatistraProperties properties;
    private final ObjectMapper objectMapper;

    public BodySizeLimitFilter(StatistraProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        long maxBytes = properties.getIngest().getMaxBodyBytes();

        // Fast path: reject a truthfully declared oversized body before reading
        // any of it. The response is written here rather than thrown, because an
        // exception raised in a filter unwinds through the servlet container
        // rather than through DispatcherServlet, so @RestControllerAdvice never
        // sees it and the client gets Tomcat's 500 error page.
        if (request.getContentLengthLong() > maxBytes) {
            log.warn("Rejected declared {} byte body to {} (limit {})",
                    request.getContentLengthLong(), request.getRequestURI(), maxBytes);
            writeTooLarge(response, maxBytes);
            return;
        }

        // Slow path: a chunked or understated body is capped as it is read. That
        // throws from inside the message converter, which is within MVC, so the
        // exception handler turns it into a 413.
        filterChain.doFilter(new LimitedRequest(request, maxBytes), response);
    }

    private void writeTooLarge(HttpServletResponse response, long maxBytes) throws IOException {
        response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), Map.of(
                "error", "payload_too_large",
                "message", "Request body exceeds " + maxBytes + " bytes",
                "timestamp", Instant.now().toString()));
    }

    /** Wraps the request so the body cannot be read past the limit. */
    private static final class LimitedRequest extends HttpServletRequestWrapper {

        private final long maxBytes;

        private LimitedRequest(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new LimitedInputStream(super.getInputStream(), maxBytes);
        }

        @Override
        public BufferedReader getReader() throws IOException {
            return new BufferedReader(new InputStreamReader(getInputStream(), getCharacterEncodingOrDefault()));
        }

        private String getCharacterEncodingOrDefault() {
            String encoding = getCharacterEncoding();
            return encoding == null ? "UTF-8" : encoding;
        }
    }

    /**
     * Counts bytes as they are consumed and fails once the limit is passed.
     *
     * <p>Failing during the read rather than after it means an oversized body is
     * abandoned partway instead of being buffered in full first.
     */
    private static final class LimitedInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long maxBytes;
        private long read;

        private LimitedInputStream(ServletInputStream delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value != -1) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int count = delegate.read(b, off, len);
            if (count > 0) {
                count(count);
            }
            return count;
        }

        private void count(int increment) {
            read += increment;
            if (read > maxBytes) {
                throw new PayloadTooLargeException(maxBytes);
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }
    }
}
