package com.fincore.payment;

import java.util.UUID;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

@RestController
public class PaymentSupportController {
    private final PaymentStore payments;
    public PaymentSupportController(PaymentStore payments) {this.payments=payments;}
    @GetMapping("/internal/v1/payments/{id}/support-status")
    public Object inspect(@PathVariable UUID id,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        return payments.supportStatus(id);
    }
}
