package com.fincore.support;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.MDC;

/** Outbound infrastructure only. Tokens live in memory and never appear in exceptions. */
public final class Remote {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String tokenEndpoint, client, secret;
    private String cached;
    private Instant validUntil=Instant.EPOCH;
    public Remote(String tokenEndpoint,String client,String secret) { this.tokenEndpoint=tokenEndpoint;this.client=client;this.secret=secret; }
    public synchronized String serviceToken() {
        if (cached==null || Instant.now().isAfter(validUntil)) {
            var result=grant(Map.of("grant_type","client_credentials"));
            cached=result.path("access_token").asText();
            validUntil=Instant.now().plusSeconds(Math.max(1,result.path("expires_in").asLong(30)-20));
        }
        return cached;
    }
    public String exchange(String subjectToken) {
        return grant(Map.of("grant_type","urn:ietf:params:oauth:grant-type:token-exchange","subject_token",subjectToken,
            "subject_token_type","urn:ietf:params:oauth:token-type:access_token","audience","ledger-service")).path("access_token").asText();
    }
    private JsonNode grant(Map<String,String> grant) {
        var data=new java.util.HashMap<>(grant);data.put("client_id",client);data.put("client_secret",secret);
        var body=data.entrySet().stream().map(e->escape(e.getKey())+"="+escape(e.getValue())).collect(Collectors.joining("&"));
        var request=HttpRequest.newBuilder(URI.create(tokenEndpoint)).timeout(Duration.ofSeconds(5)).header("Content-Type","application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return send(request);
    }
    public JsonNode call(String method,String url,String bearer,Object body) {
        var request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).header("Authorization","Bearer "+bearer)
            .header("Content-Type","application/json");
        var correlation=MDC.get("correlationId");
        if(correlation!=null && correlation.matches("[A-Za-z0-9._-]{1,64}")) request.header("X-Correlation-ID",correlation);
        return send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(Json.text(body))).build());
    }
    private JsonNode send(HttpRequest request) {
        try {
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());
            JsonNode result;
            try {result=Json.read(response.body());} catch(IllegalArgumentException ignored){throw new ApiFailure(503,"DEPENDENCY_UNAVAILABLE");}
            if(response.statusCode()>=400) {
                var code=result.path("code").asText("DEPENDENCY_UNAVAILABLE");
                throw new ApiFailure(response.statusCode(),code.matches("[A-Z_]{1,80}")?code:"DEPENDENCY_UNAVAILABLE");
            }
            return result;
        } catch(java.io.IOException error){throw new ApiFailure(503,"DEPENDENCY_UNAVAILABLE");}
        catch(InterruptedException error){Thread.currentThread().interrupt();throw new ApiFailure(503,"DEPENDENCY_UNAVAILABLE");}
    }
    private static String escape(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
}
