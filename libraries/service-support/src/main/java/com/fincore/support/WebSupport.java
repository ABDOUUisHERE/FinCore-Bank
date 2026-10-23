package com.fincore.support;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.filter.OncePerRequestFilter;

@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class WebSupport extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        var incoming=request.getHeader("X-Correlation-ID");
        var id=incoming!=null && incoming.matches("[A-Za-z0-9._-]{1,64}")?incoming:UUID.randomUUID().toString();
        response.setHeader("X-Correlation-ID",id);request.setAttribute("correlationId",id);MDC.put("correlationId",id);
        try {
            if(SetMethods.mutating(request.getMethod())) {
                var length=request.getContentLengthLong();
                if(length>16384 || length<0 || request.getHeader("Transfer-Encoding")!=null) {
                    response.setStatus(length>16384?413:411);response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                    response.getWriter().write(Json.text(java.util.Map.of("type","about:blank","status",response.getStatus(),"title","Invalid request body size","code",length>16384?"BODY_TOO_LARGE":"CONTENT_LENGTH_REQUIRED","detail","Supply a bounded JSON body.","instance",request.getRequestURI(),"correlationId",id)));return;
                }
            }
            chain.doFilter(request,response);
        } finally {MDC.remove("correlationId");}
    }
    @ExceptionHandler(ApiFailure.class) ProblemDetail failure(ApiFailure error,HttpServletRequest request){return problem(error.status,error.code,request);}
    @ExceptionHandler(IllegalArgumentException.class) ProblemDetail invalid(HttpServletRequest request){return problem(400,"INVALID_REQUEST",request);}
    @ExceptionHandler(DataAccessException.class) ProblemDetail database(DataAccessException error,HttpServletRequest request){return problem(503,"STORAGE_UNAVAILABLE",request);}
    public static ProblemDetail problem(int status,String code,HttpServletRequest request) {
        var result=ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status),"The request could not be completed.");
        result.setInstance(URI.create(request.getRequestURI()));result.setProperty("code",code);result.setProperty("correlationId",request.getAttribute("correlationId"));return result;
    }
    public static void denied(HttpServletRequest request,HttpServletResponse response,int status) throws IOException {
        response.setStatus(status);response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        if(status==401)response.setHeader("WWW-Authenticate","Bearer");
        response.getWriter().write(Json.text(java.util.Map.of("type","about:blank","status",status,"title",status==401?"Unauthorized":"Forbidden","code",status==401?"UNAUTHORIZED":"FORBIDDEN","detail","The request is not authorized.","instance",request.getRequestURI(),"correlationId",response.getHeader("X-Correlation-ID")==null?"unavailable":response.getHeader("X-Correlation-ID"))));
    }
    private static class SetMethods {static boolean mutating(String method){return java.util.Set.of("POST","PUT","PATCH").contains(method);}}
}
