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
        var target = owner.target(path,request.getMethod());
        if (target == null) { chain.doFilter(request, response); return; }
        request.removeAttribute(ACTOR);
        var buffered = new BufferedResponse(response,owner==AdministrativeReadAudit.Owner.REPORTING?4*1024*1024:Integer.MAX_VALUE);
        buffered.setHeader("Cache-Control", "no-store");
        Exception failure = null;
        try { chain.doFilter(request, buffered); buffered.finishWriter(); }
        catch (ServletException | IOException | RuntimeException error) { failure = error; }
        String correlation = String.valueOf(request.getAttribute("correlationId"));
        if (!correlation.matches("[A-Za-z0-9._-]{1,64}")) correlation = UUID.randomUUID().toString();
        if (buffered.limitExceeded) {
            problem(buffered,"RESPONSE_TOO_LARGE","The prepared response exceeds the disclosure limit.",correlation);
            failure=null;
        }
        try {
            audit.retain(target, request.getMethod(), failure == null ? buffered.getStatus() : 500,
                (AdministrativeReadAudit.Actor) request.getAttribute(ACTOR), correlation);
        } catch (DataAccessException | org.springframework.transaction.TransactionException unavailable) {
            problem(buffered,"AUDIT_UNAVAILABLE","The request could not be retained.",correlation);
            buffered.copyBodyToResponse();
            return;
        }
        if (failure instanceof ServletException error) throw error;
        if (failure instanceof IOException error) throw error;
        if (failure instanceof RuntimeException error) throw error;
        buffered.copyBodyToResponse();
    }
    private static void problem(BufferedResponse response,String code,String detail,String correlation) throws IOException {
        response.resetBuffer();response.setStatus(503);
        for(String header:java.util.List.of("WWW-Authenticate","Location","Content-Disposition"))response.setHeader(header,null);
        response.setContentType("application/problem+json");response.setHeader("Cache-Control","no-store");
        response.getOutputStream().write(Json.text(Map.of("type","about:blank","title","Service Unavailable","status",503,
            "code",code,"detail",detail,"correlationId",correlation)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private static final class BufferedResponse extends ContentCachingResponseWrapper {
        private final int limit;
        private boolean limitExceeded;
        private ServletOutputStream bounded;
        private java.io.PrintWriter writer;
        BufferedResponse(HttpServletResponse response,int limit) { super(response);this.limit=limit; }
        private void checkLength(long length){if(length>limit-getContentSize()){limitExceeded=true;throw new IllegalStateException("PREPARED_RESPONSE_TOO_LARGE");}}
        @Override public void setContentLength(int length){checkLength(length);super.setContentLength(length);}
        @Override public void setContentLengthLong(long length){checkLength(length);super.setContentLengthLong(length);}
        @Override public ServletOutputStream getOutputStream() throws IOException {
            if(bounded==null){var delegate=super.getOutputStream();bounded=new ServletOutputStream(){
                @Override public void write(int value)throws IOException{checkLength(1);delegate.write(value);}
                @Override public void write(byte[] bytes,int offset,int length)throws IOException{checkLength(length);delegate.write(bytes,offset,length);}
                @Override public boolean isReady(){return delegate.isReady();}
                @Override public void setWriteListener(WriteListener listener){delegate.setWriteListener(listener);}
            };}return bounded;
        }
        @Override public java.io.PrintWriter getWriter() throws IOException {
            if(writer==null)writer=new java.io.PrintWriter(new java.io.OutputStreamWriter(getOutputStream(),getCharacterEncoding()));
            return writer;
        }
        void finishWriter(){if(writer!=null)writer.flush();}
        @Override public void resetBuffer(){writer=null;super.resetBuffer();}
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
