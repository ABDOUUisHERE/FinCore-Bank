package com.fincore.support;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import com.fincore.support.Json;

/** Only explicitly registered bounded synchronous ADMIN reads use response buffering. */
public class AdministrativeReadAuditFilter extends OncePerRequestFilter {
    private static final String ACTOR = AdministrativeReadAuditFilter.class.getName() + ".verifiedActor";
    private final AdministrativeReadAudit audit;
    private final AdministrativeReadAudit.Owner owner;
    public AdministrativeReadAuditFilter(AdministrativeReadAudit audit, AdministrativeReadAudit.Owner owner) { this.audit = audit; this.owner=owner; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path=request.getServletPath();
        if (path.isEmpty()) path=request.getRequestURI().substring(request.getContextPath().length());
        var target = owner.target(path);
        if (target == null) { chain.doFilter(request, response); return; }
        request.removeAttribute(ACTOR);
        var buffered = new BufferedResponse(response);
        buffered.setHeader("Cache-Control", "no-store");
        Exception failure = null;
        try { chain.doFilter(request, buffered); }
        catch (ServletException | IOException | RuntimeException error) { failure = error; }
        String correlation = String.valueOf(request.getAttribute("correlationId"));
        if (!correlation.matches("[A-Za-z0-9._-]{1,64}")) correlation = UUID.randomUUID().toString();
        try {
            audit.retain(target, request.getMethod(), failure == null ? buffered.getStatus() : 500,
                (AdministrativeReadAudit.Actor) request.getAttribute(ACTOR), correlation);
        } catch (DataAccessException unavailable) {
            buffered.resetBuffer();
            buffered.setStatus(503);
            buffered.setHeader("WWW-Authenticate", null);
            buffered.setHeader("Location", null);
            buffered.setContentType("application/problem+json");
            buffered.getOutputStream().write(Json.text(Map.of("type","about:blank","title","Service Unavailable","status",503,
                "code","AUDIT_UNAVAILABLE","detail","The request could not be retained.","correlationId",correlation)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            buffered.copyBodyToResponse();
            return;
        }
        if (failure instanceof ServletException error) throw error;
        if (failure instanceof IOException error) throw error;
        if (failure instanceof RuntimeException error) throw error;
        buffered.copyBodyToResponse();
    }
    private static final class BufferedResponse extends ContentCachingResponseWrapper {
        BufferedResponse(HttpServletResponse response) { super(response); }
        @Override public void sendError(int status) { resetBuffer(); setStatus(status); }
        @Override public void sendError(int status, String ignored) { sendError(status); }
        @Override public void sendRedirect(String location) { resetBuffer(); setStatus(302); setHeader("Location", location); }
    }
    /** Runs after signature/audience/issuer validation, while the SecurityContext is present. */
    public static final class VerifiedActorFilter extends OncePerRequestFilter {
        @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.isAuthenticated() && authentication.getPrincipal() instanceof Jwt jwt
                && jwt.getSubject() != null && jwt.getSubject().matches("[A-Za-z0-9:_-]{1,200}"))
                request.setAttribute(ACTOR, new AdministrativeReadAudit.Actor(jwt.getSubject()));
            chain.doFilter(request, response);
        }
    }
}
