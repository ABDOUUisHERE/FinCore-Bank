package com.fincore.customer.adapter.in.web;

import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CurrentSubjectController {
    public record CurrentSubject(String subject, List<String> roles) {}

    @GetMapping("/api/v1/customers/me")
    CurrentSubject currentSubject(@AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        var roles = authentication.getAuthorities().stream()
            .map(authority -> authority.getAuthority())
            .filter(value -> value.startsWith("ROLE_"))
            .map(value -> value.substring(5)).sorted().toList();
        return new CurrentSubject(jwt.getSubject(), roles);
    }
}
