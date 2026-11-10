package com.fincore.support;

import com.fincore.observability.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Collections;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration(proxyBeanMethods=false)
@Import(TracingConfiguration.class)
public class ServletTracingConfiguration {
    @Bean ServletTraceFilter servletTraceFilter(Tracing tracing) { return new ServletTraceFilter(tracing); }
    @Order(Ordered.HIGHEST_PRECEDENCE+2)
    public static final class ServletTraceFilter extends OncePerRequestFilter {
        private final Tracing tracing;
        public ServletTraceFilter(Tracing tracing) { this.tracing=tracing; }
        @Override protected boolean shouldNotFilter(HttpServletRequest request) {
            return !tracing.enabled() || (!request.getRequestURI().startsWith("/api/") && !request.getRequestURI().startsWith("/internal/"));
        }
        @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
            var parents=Collections.list(request.getHeaders("traceparent"));
            try(var operation=tracing.server(parents.size()==1?parents.getFirst():null,request.getMethod());var scope=operation.makeCurrent()) {
                if(operation.traceId()!=null)response.setHeader("X-Trace-ID",operation.traceId());
                boolean failed=false;
                try { chain.doFilter(request,response); }
                catch(ServletException|IOException|RuntimeException error) { failed=true;throw error; }
                finally { operation.status(failed?500:response.getStatus()); }
            }
        }
    }
}
