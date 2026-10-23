package com.fincore.ledger;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class LedgerErrors {
    @ExceptionHandler(IllegalArgumentException.class) ProblemDetail invalid(IllegalArgumentException ignored, HttpServletRequest request) {
        return problem(400, "INVALID_COMMAND", request);
    }
    @ExceptionHandler(LedgerStore.MissingRecord.class) ProblemDetail missing(HttpServletRequest request) { return problem(404, "NOT_FOUND", request); }
    @ExceptionHandler(DataAccessException.class) ProblemDetail database(DataAccessException error, HttpServletRequest request) {
        var cause = error.getMostSpecificCause();
        var message = cause.getMessage() == null ? "" : cause.getMessage();
        var conflicts = Set.of("IDEMPOTENCY_CONFLICT", "BUSINESS_REFERENCE_CONFLICT", "ACCOUNT_REFERENCE_CONFLICT", "GUARD_CONFLICT", "ACCOUNT_NOT_EMPTY", "ASSESSMENT_EXPIRED");
        for (var code : conflicts) if (message.contains(code)) return problem(409, code, request);
        if (cause instanceof java.sql.SQLException sql && Set.of("55P03", "40P01", "57014", "40001").contains(sql.getSQLState())) {
            return problem(503, "RETRY_SAME_IDENTITY", request);
        }
        if (cause instanceof java.sql.SQLException sql && ("P0001".equals(sql.getSQLState()) || "22P02".equals(sql.getSQLState()))) return problem(400, "INVALID_COMMAND", request);
        return problem(500, "LEDGER_UNAVAILABLE", request);
    }
    private static ProblemDetail problem(int status, String code, HttpServletRequest request) {
        var result = ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatusCode.valueOf(status), code.equals("RETRY_SAME_IDENTITY") ? "Retry or look up the original business reference with the same identity." : "The ledger could not accept this request.");
        result.setProperty("code", code);
        result.setProperty("correlationId", request.getAttribute("correlationId") == null ? "unavailable" : request.getAttribute("correlationId"));
        return result;
    }
}
