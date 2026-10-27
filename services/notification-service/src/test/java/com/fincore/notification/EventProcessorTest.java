package com.fincore.notification;
import com.fincore.support.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
class EventProcessorTest {
 final NotificationStore store=mock(NotificationStore.class);final Remote remote=mock(Remote.class);final EventProcessor processor=new EventProcessor(store,remote,"http://accounts");
 String event(UUID id,String type,String journal){return Json.text(Map.of("eventId",UUID.randomUUID(),"eventType",type,"schemaVersion",1,"aggregateId",id,"aggregateVersion",3,"correlationId",UUID.randomUUID().toString(),"occurredAt","2026-10-26T10:00:00Z","payload",Map.of("paymentId",id,"status","COMPLETED","version",3,"kind","TRANSFER","actorSubject","sender-subject","destinationAccountId",UUID.randomUUID(),"amount","10.00","currency","MAD","journalId",journal)));}
 @Test void completedNotificationRequiresConfirmedJournalAndMatchingTopic(){
  var id=UUID.randomUUID();
  assertThatThrownBy(()->processor.process("fincore.payment.completed.v1",id.toString(),event(id,"payment.completed",""))).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->processor.process("fincore.customer.created.v1",id.toString(),event(id,"payment.completed",UUID.randomUUID().toString()))).hasMessage("EVENT_TOPIC_OR_KEY_MISMATCH");
  verifyNoInteractions(store,remote);
 }
 @Test void onlyVerifiedAccountOwnershipRoutesRecipientNotification(){
  var id=UUID.randomUUID();when(remote.serviceToken()).thenReturn("memory-only");when(remote.call(eq("GET"),anyString(),eq("memory-only"),isNull())).thenReturn(Json.read("{\"ownerSubject\":\"recipient-subject\"}"));
  processor.process("fincore.payment.completed.v1",id.toString(),event(id,"payment.completed",UUID.randomUUID().toString()));
  verify(store).accept(any(),anyString(),argThat(notices->notices.size()==2 && notices.get(0).subject().equals("sender-subject") && notices.get(1).subject().equals("recipient-subject")));
 }
 @Test void ledgerFactsAloneNeverCreatePaymentSuccessMessages(){
  var id=UUID.randomUUID();var raw=Json.text(Map.of("eventId",UUID.randomUUID(),"eventType","ledger.journal.posted","schemaVersion",1,"aggregateId",id,"aggregateVersion",1,"correlationId",id.toString(),"occurredAt","2026-10-26T10:00:00Z","payload",Map.of("journalId",UUID.randomUUID())));
  processor.process("fincore.ledger.journal.posted.v1",id.toString(),raw);verify(store).accept(any(),eq(raw),eq(List.of()));verifyNoInteractions(remote);
 }
 @Test void fraudAlertIsAnOwnedSecurityNoticeAndNeverAPaymentSuccess(){
  var id=UUID.randomUUID();var raw=Json.text(Map.of("eventId",UUID.randomUUID(),"eventType","fraud.alert.created","schemaVersion",1,"aggregateId",id,"aggregateVersion",1,"correlationId",id.toString(),"occurredAt","2026-10-26T10:00:00Z","payload",Map.of("paymentId",id,"caseId",UUID.randomUUID(),"alertId",UUID.randomUUID(),"actorSubject","verified-owner","verdict","REVIEW")));
  processor.process("fincore.fraud.alert.created.v1",id.toString(),raw);
  verify(store).accept(any(),eq(raw),argThat(n->n.size()==1 && n.getFirst().subject().equals("verified-owner") && n.getFirst().category().equals("SECURITY") && !n.getFirst().body().contains("completed")));verifyNoInteractions(remote);
 }
}
