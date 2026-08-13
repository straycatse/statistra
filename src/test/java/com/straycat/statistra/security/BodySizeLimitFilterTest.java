package com.straycat.statistra.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.straycat.statistra.config.StatistraProperties;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the declared-Content-Length path specifically.
 *
 * <p>Worth its own test because it behaves differently from the streaming path
 * and is easy to miss: an exception thrown from a filter unwinds through the
 * servlet container rather than DispatcherServlet, so the controller advice
 * never runs and the client would see a 500 instead of a 413. The end-to-end
 * test does not exercise this, because its HTTP client sends the body chunked
 * and so never declares a length at all.
 */
class BodySizeLimitFilterTest {

    private static final long LIMIT = 1024L;

    private BodySizeLimitFilter filter;

    @BeforeEach
    void setUp() {
        StatistraProperties properties = new StatistraProperties();
        properties.getIngest().setMaxBodyBytes(LIMIT);
        filter = new BodySizeLimitFilter(properties, new ObjectMapper());
    }

    @Test
    void rejectsADeclaredOversizedBodyWithA413AndNeverCallsTheChain() throws Exception {
        MockHttpServletRequest request = post("x".repeat(2000));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("payload_too_large");
        // The chain must not run: the point is to reject before doing work.
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void allowsBodiesWithinTheLimit() throws Exception {
        MockHttpServletRequest request = post("x".repeat(100));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void capsAnUnderstatedBodyWhileItIsRead() throws Exception {
        MockHttpServletRequest request = post("x".repeat(2000));
        // A client claiming to send far less than it actually does. The header
        // check passes, so only the read-time cap can catch this.
        request.setContentType("application/json");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain readingChain = (req, res) -> req.getInputStream().readAllBytes();

        assertThatThrownBy(() -> filter.doFilter(wrapWithoutLength(request), response, readingChain))
                .isInstanceOf(PayloadTooLargeException.class);
    }

    @Test
    void skipsNonApiRequests() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    private MockHttpServletRequest post(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/events");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        request.setContentType("application/json");
        return request;
    }

    /** Mirrors a chunked request, which declares no Content-Length. */
    private MockHttpServletRequest wrapWithoutLength(MockHttpServletRequest original) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/events") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContent(original.getContentAsByteArray());
        request.setContentType("application/json");
        return request;
    }
}
