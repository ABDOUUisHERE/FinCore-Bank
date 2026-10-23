package com.fincore.customer.configuration;

import java.io.IOException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class BodyLimitFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        if ((request.getMethod().equals("POST") || request.getMethod().equals("PUT") || request.getMethod().equals("PATCH")) && request.getRequestURI().startsWith("/api/")) {
            int status = request.getContentLengthLong() > 16384 ? 413
                : request.getContentLengthLong() < 0 || request.getHeader("Transfer-Encoding") != null ? 411 : 0;
            if (status != 0) {
                response.setStatus(status);
                response.setContentType("application/problem+json");
                response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Invalid request body size\",\"status\":" + status + "}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
