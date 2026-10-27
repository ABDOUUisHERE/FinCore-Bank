package com.fincore.notification;
import com.fincore.messaging.EventEnvelope;
import com.fincore.support.Remote;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class EventProcessor {
    private final NotificationStore store;private final Remote account;private final String accountUrl;
    public EventProcessor(NotificationStore store,Remote account,@Value("${ACCOUNT_URL:http://localhost:8083}")String url){this.store=store;this.account=account;this.accountUrl=url;}
    public void process(String topic,String key,String body){
        var e=EventEnvelope.parse(body);
        if(!e.topic().equals(topic) || !e.aggregateId().toString().equals(key))throw new IllegalArgumentException("EVENT_TOPIC_OR_KEY_MISMATCH");
        var p=e.payload();var notices=new ArrayList<NotificationStore.Notice>();
        if(e.eventType().startsWith("payment.")){
            if(!e.aggregateId().toString().equals(p.path("paymentId").asText()) || e.aggregateVersion()!=p.path("version").asLong(-1) || !e.eventType().equals("payment."+p.path("status").asText().toLowerCase(java.util.Locale.ROOT)) && !e.eventType().equals("payment.initiated"))throw new IllegalArgumentException("INVALID_PAYMENT_IDENTITY");
            if(Set.of("payment.completed","payment.reversed","payment.failed").contains(e.eventType())){
                var kind=p.path("kind").asText();
                // Historical pre-Phase-5 payloads lack immutable routing metadata: retained/consumed, never guessed.
                if(!kind.isEmpty() && !kind.equals("REVERSAL")){
                    if(!Set.of("TRANSFER","DEPOSIT").contains(kind))throw new IllegalArgumentException("INVALID_PAYMENT_KIND");
                    if(!e.eventType().equals("payment.failed"))UUID.fromString(p.path("journalId").asText());
                    var amount=p.path("amount").asText();var currency=p.path("currency").asText();
                    if(!amount.matches("[0-9]{1,18}\\.[0-9]{2}") || !Set.of("MAD","EUR","USD").contains(currency))throw new IllegalArgumentException("INVALID_PAYMENT_MONEY");
                    String status=e.eventType().substring(8);
                    if(kind.equals("TRANSFER"))add(notices,e,p.path("actorSubject").asText(),"Transfer "+status,amount+" "+currency+". See the recorded transfer for details.","sender");
                    if(!e.eventType().equals("payment.failed")){
                        var destination=UUID.fromString(p.path("destinationAccountId").asText());
                        var target=account.call("GET",accountUrl+"/internal/v1/accounts/"+destination,account.serviceToken(),null);
                        String owner=target.path("ownerSubject").asText();
                        add(notices,e,owner,status.equals("reversed")?"Incoming payment reversed":"Payment received",amount+" "+currency+". A confirmed journal records this payment.","recipient");
                    }
                }
            }
        }else if(e.eventType().equals("fraud.alert.created")){
            if(!e.aggregateId().toString().equals(p.path("paymentId").asText()) || !Set.of("REVIEW","BLOCK").contains(p.path("verdict").asText()))throw new IllegalArgumentException("INVALID_FRAUD_ALERT");
            UUID.fromString(p.path("caseId").asText());UUID.fromString(p.path("alertId").asText());String owner=p.path("actorSubject").asText();
            if(owner.isBlank() || owner.length()>200)throw new IllegalArgumentException("INVALID_NOTIFICATION_OWNER");
            notices.add(new NotificationStore.Notice(owner,"SECURITY","Transfer security check","A security check requires attention. Open the recorded transfer for its current outcome.","fraud:"+e.aggregateId()));
        }else if(e.eventType().equals("account.restriction.applied")){
            String owner=p.path("ownerSubject").asText();
            if(!owner.isEmpty())notices.add(new NotificationStore.Notice(owner,"SECURITY","Account restriction updated","Your account restriction has been confirmed. Open Accounts for its current status.",e.eventId().toString()));
        }
        store.accept(e,body,notices);
    }
    private static void add(List<NotificationStore.Notice> list,EventEnvelope e,String owner,String title,String body,String direction){
        if(owner.isBlank() || owner.length()>200)throw new IllegalArgumentException("INVALID_NOTIFICATION_OWNER");
        list.add(new NotificationStore.Notice(owner,"PAYMENTS",title,body,e.aggregateId()+":"+e.eventType()+":"+direction));
    }
}
