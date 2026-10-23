package com.fincore.payment;

import com.fincore.support.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaymentIdempotencyTest {
    @Test void replayPrecedesMutableAccountEligibilityAndChangedPayloadStillConflicts(){
        var remote=mock(Remote.class);var store=mock(PaymentStore.class);String actor="verified-customer",key="stable-key";UUID source=UUID.randomUUID(),destination=UUID.randomUUID(),id=Identifiers.stable("payment",actor,"TRANSFER",key);
        var metadata=new LinkedHashMap<String,Object>();metadata.put("sourceAccountId",source);metadata.put("destinationAccountId",destination);metadata.put("amount","10.00");metadata.put("currency","MAD");metadata.put("reference","");metadata.put("originalPaymentId",null);
        var receipt=Json.read(Json.text(Map.of("actor",actor,"kind","TRANSFER","metadata",metadata)));
        var saved=Json.read(Json.text(Map.of("instruction",receipt,"id",id,"status","COMPLETED")));
        when(store.exists(id)).thenReturn(true);when(store.get(id)).thenReturn(saved);when(store.importInstruction(receipt)).thenReturn(saved);
        var coordinator=new PaymentCoordinator(remote,store,"http://ledger","http://account");
        assertThat(coordinator.transfer(actor,"new-valid-customer-token",key,new PaymentCoordinator.Transfer(source,destination,"10","MAD",null))).isEqualTo(saved);
        assertThatThrownBy(()->coordinator.transfer(actor,"new-valid-customer-token",key,new PaymentCoordinator.Transfer(source,destination,"11","MAD",null))).hasMessage("IDEMPOTENCY_CONFLICT");
        verifyNoInteractions(remote);
    }
}
