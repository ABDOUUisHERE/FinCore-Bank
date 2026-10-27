package com.fincore.fraud;

import com.fincore.support.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FraudCardContextTest {
 final Remote remote=mock(Remote.class);final FraudContextClient context=new FraudContextClient(remote,"http://payments","http://customers","http://beneficiaries","http://cards");
 @Test void cardContextComesFromCardsAndTheDeclaredCountryFromCustomer(){var id=UUID.randomUUID();when(remote.serviceToken()).thenReturn("memory-only");when(remote.call(eq("GET"),eq("http://cards/internal/v1/cards/"+id+"/risk-context"),anyString(),isNull())).thenReturn(Json.read(Json.text(Map.of("paymentId",id,"fingerprint","f".repeat(64),"actor","owner","kind","MERCHANT_PAYMENT","currency","MAD","amount","6000.00","destinationAccountId",UUID.randomUUID()))));when(remote.call(eq("POST"),eq("http://customers/internal/v1/customers/risk-context"),anyString(),any())).thenReturn(Json.read("{\"countryCode\":\"MA\"}"));var facts=context.cardContext(id);assertThat(facts.path("countryCode").asText()).isEqualTo("MA");assertThat(facts.path("kind").asText()).isEqualTo("MERCHANT_PAYMENT");verify(remote,never()).call(anyString(),startsWith("http://payments"),anyString(),any());verify(remote,never()).call(anyString(),startsWith("http://beneficiaries"),anyString(),any());}
 @Test void unavailableCardOwnerDoesNotFallBackToAnotherDomain(){var id=UUID.randomUUID();when(remote.serviceToken()).thenReturn("memory-only");when(remote.call(eq("GET"),startsWith("http://cards"),anyString(),isNull())).thenThrow(new ApiFailure(503,"DEPENDENCY_UNAVAILABLE"));assertThatThrownBy(()->context.cardContext(id)).isInstanceOf(ApiFailure.class);verify(remote,never()).call(anyString(),startsWith("http://payments"),anyString(),any());}
}
