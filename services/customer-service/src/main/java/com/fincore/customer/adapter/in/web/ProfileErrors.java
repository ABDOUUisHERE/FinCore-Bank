package com.fincore.customer.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes=ProfileController.class)
public class ProfileErrors {
    @ExceptionHandler(ResponseStatusException.class) ProblemDetail invalid(ResponseStatusException failure,HttpServletRequest request){
        var code=failure.getReason();return problem(failure.getStatusCode(),code!=null && code.matches("[A-Z_]{1,80}")?code:"INVALID_REQUEST",request);
    }
    @ExceptionHandler(org.springframework.dao.DataAccessException.class) ProblemDetail database(HttpServletRequest request){return problem(HttpStatus.SERVICE_UNAVAILABLE,"STORAGE_UNAVAILABLE",request);}
    private static ProblemDetail problem(HttpStatusCode status,String code,HttpServletRequest request){var result=ProblemDetail.forStatusAndDetail(status,"The profile request could not be completed.");result.setProperty("code",code);result.setProperty("correlationId",request.getAttribute("correlationId"));return result;}
}
