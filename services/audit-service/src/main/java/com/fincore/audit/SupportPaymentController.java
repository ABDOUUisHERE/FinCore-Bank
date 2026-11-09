package com.fincore.audit;

import com.fincore.support.ServiceSecurity;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/incidents/{id}")
public class SupportPaymentController {
    private final SupportAccessStore grants;
    private final SupportPaymentAccess access;
    public SupportPaymentController(SupportAccessStore grants,SupportPaymentAccess access) {this.grants=grants;this.access=access;}
    private static String correlation(HttpServletRequest request,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");return Objects.toString(request.getAttribute("correlationId"));
    }
    @GetMapping("/payment-access") public Object current(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt,HttpServletRequest request,HttpServletResponse response) {
        correlation(request,response);
        boolean admin=ServiceSecurity.authorities(jwt,"audit-service").stream().anyMatch(a->a.getAuthority().equals("ROLE_ADMIN"));
        return grants.current(id,jwt.getSubject(),admin,SupportAccessStore.Resource.PAYMENT);
    }
    @PutMapping("/payment-access") public Object command(@PathVariable UUID id,@RequestHeader("Idempotency-Key")String key,@RequestBody SupportAccessStore.Command body,
        @AuthenticationPrincipal Jwt jwt,HttpServletRequest request,HttpServletResponse response) {
        return grants.command(id,jwt.getSubject(),key,correlation(request,response),body,SupportAccessStore.Resource.PAYMENT);
    }
    @PostMapping("/payment-status") public Object inspect(@PathVariable UUID id,@RequestBody SupportAccessController.Inspect body,@AuthenticationPrincipal Jwt jwt,HttpServletRequest request,HttpServletResponse response) {
        return access.inspect(id,jwt.getSubject(),correlation(request,response));
    }
}
