package com.fincore.customer.adapter.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/v1/customers/me")
public class ProfileController {
    private final Profiles profiles;
    public ProfileController(Profiles profiles){this.profiles=profiles;}
    public record Onboarding(String displayName,String countryCode) {}
    public record Update(String displayName,String countryCode,Long expectedVersion) {}
    @PostMapping("/onboarding") public JsonNode onboard(@RequestBody Onboarding body,@AuthenticationPrincipal Jwt jwt){validate(body.displayName(),body.countryCode());return profiles.create(jwt.getSubject(),body);}
    @GetMapping("/profile") public JsonNode profile(@AuthenticationPrincipal Jwt jwt){return profiles.get(jwt.getSubject());}
    @PatchMapping("/profile") public JsonNode update(@RequestBody Update body,@AuthenticationPrincipal Jwt jwt){
        validate(body.displayName(),body.countryCode());if(body.expectedVersion()==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return profiles.update(jwt.getSubject(),body);
    }
    private static void validate(String name,String country){if(name==null || name.isBlank() || name.length()>80 || name.chars().anyMatch(Character::isISOControl) || country==null || !country.matches("[A-Z]{2}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST);}
    @Service public static class Profiles {
        private final JdbcTemplate jdbc;
        private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();
        public Profiles(JdbcTemplate jdbc){this.jdbc=jdbc;}
        public JsonNode get(String subject){
            var rows=jdbc.query("select jsonb_build_object('customerId',id,'subject',subject,'displayName',display_name,'countryCode',country_code,'status',status,'version',version)::text from customer_profile where subject=?",(rs,n)->{try{return JSON.readTree(rs.getString(1));}catch(java.io.IOException e){throw new IllegalStateException(e);}},subject);
            if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"ONBOARDING_REQUIRED");return rows.getFirst();
        }
        @Transactional public JsonNode create(String subject,Onboarding body){
            int inserted=jdbc.update("insert into customer_profile(id,subject,display_name,country_code) values(?,?,?,?) on conflict(subject) do nothing",UUID.randomUUID(),subject,body.displayName().strip(),body.countryCode());
            var result=get(subject);
            if(!result.path("displayName").asText().equals(body.displayName().strip()) || !result.path("countryCode").asText().equals(body.countryCode()))throw new ResponseStatusException(HttpStatus.CONFLICT,"ALREADY_ONBOARDED");
            if(inserted==1)event(result,"customer.created");return result;
        }
        @Transactional public JsonNode update(String subject,Update body){
            int changed=jdbc.update("update customer_profile set display_name=?,country_code=?,version=version+1 where subject=? and version=?",body.displayName().strip(),body.countryCode(),subject,body.expectedVersion());
            if(changed!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"PROFILE_VERSION_CONFLICT");var result=get(subject);event(result,"customer.updated");return result;
        }
        private void event(JsonNode profile,String type){jdbc.update("insert into customer_outbox(id,customer_id,aggregate_version,event_type) values(?,?,?,?)",UUID.randomUUID(),UUID.fromString(profile.path("customerId").asText()),profile.path("version").asLong(),type);}
    }
}
