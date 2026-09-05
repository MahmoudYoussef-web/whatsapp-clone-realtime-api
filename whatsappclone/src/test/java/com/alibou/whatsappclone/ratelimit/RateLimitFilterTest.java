package com.alibou.whatsappclone.ratelimit;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {

    private final RateLimitFilter filter = new RateLimitFilter();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void allowsRequestsUnderLimit() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("alice", null, "USER"));
        FilterChain chain = (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(200);

        for (int i = 0; i < RateLimitFilter.MAX_REQUESTS_PER_MINUTE; i++) {
            MockHttpServletRequest request = post("/api/v1/conversations/x/messages");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    @Test
    void rejectsRequestOverLimitWith429ProblemDetail() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("bob", null, "USER"));
        FilterChain chain = (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(200);

        MockHttpServletResponse last = new MockHttpServletResponse();
        for (int i = 0; i <= RateLimitFilter.MAX_REQUESTS_PER_MINUTE; i++) {
            last = new MockHttpServletResponse();
            filter.doFilter(post("/api/v1/conversations/x/messages"), last, chain);
        }
        assertThat(last.getStatus()).isEqualTo(429);
        assertThat(last.getContentType()).contains("problem");
        assertThat(last.getContentAsString()).contains("Rate limit exceeded");
    }

    @Test
    void skipsReadOnlyRequests() throws Exception {
        FilterChain chain = (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(200);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/conversations");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    private MockHttpServletRequest post(String uri) {
        return new MockHttpServletRequest("POST", uri);
    }
}
