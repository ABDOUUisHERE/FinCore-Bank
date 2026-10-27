package com.fincore.customer.adapter.in.web;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class CustomerRiskContextController {
 private final ProfileController.Profiles profiles;
 public CustomerRiskContextController(ProfileController.Profiles profiles){this.profiles=profiles;}
 public record Subject(String subject){}
 @PostMapping("/internal/v1/customers/risk-context") public Object context(@RequestBody Subject body){
  if(body.subject()==null || body.subject().isBlank() || body.subject().length()>200)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
  var p=profiles.get(body.subject());return Map.of("countryCode",p.path("countryCode").asText(),"profileVersion",p.path("version").asLong());
 }
}
