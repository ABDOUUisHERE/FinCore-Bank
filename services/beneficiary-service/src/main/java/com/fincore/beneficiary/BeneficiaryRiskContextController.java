package com.fincore.beneficiary;

import com.fincore.support.*;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
public class BeneficiaryRiskContextController {
 private final BeneficiaryStore store;
 public BeneficiaryRiskContextController(BeneficiaryStore store){this.store=store;}
 public record Context(String subject,UUID destinationAccountId){}
 @PostMapping("/internal/v1/beneficiaries/risk-context") public Object context(@RequestBody Context body){
  if(body.subject()==null || body.subject().isBlank() || body.subject().length()>200 || body.destinationAccountId()==null)throw new ApiFailure(400,"INVALID_RISK_CONTEXT");
  return store.riskContext(body.subject(),body.destinationAccountId());
 }
}
