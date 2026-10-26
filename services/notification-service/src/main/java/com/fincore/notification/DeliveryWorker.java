package com.fincore.notification;
import com.fincore.support.Json;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
public final class DeliveryWorker {
 private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final KafkaTemplate<String,String> kafka;
 public DeliveryWorker(JdbcTemplate jdbc,TransactionTemplate tx,KafkaTemplate<String,String> kafka){this.jdbc=jdbc;this.tx=tx;this.kafka=kafka;}
 @Scheduled(fixedDelay=1000) public void tick(){for(int i=0;i<10;i++){emailOne();replayOne();}}
 public void emailOne(){tx.executeWithoutResult(status->{
  var rows=jdbc.query("select id from email_delivery where delivered_at is null and next_attempt<=clock_timestamp() order by next_attempt,id for update skip locked limit 1",(rs,n)->rs.getObject(1,UUID.class));
  if(rows.isEmpty())return;var id=rows.getFirst();
  // A local synthetic sink: no external email provider or invented email address.
  jdbc.update("insert into synthetic_email(delivery_id,subject,title,body) select id,subject,title,body from notification where id=? on conflict do nothing",id);
  jdbc.update("update email_delivery set delivered_at=clock_timestamp(),attempts=attempts+1 where id=?",id);
 });}
 public void replayOne(){
  var rows=jdbc.query("with candidate as (select id from replay_outbox where sent_at is null and next_attempt<=clock_timestamp() and (lease_until is null or lease_until<clock_timestamp()) order by next_attempt,id for update skip locked limit 1) update replay_outbox r set lease_token=gen_random_uuid(),lease_until=clock_timestamp()+interval '30 seconds',attempts=attempts+1 from candidate c where r.id=c.id returning r.id,r.lease_token,r.body::text",(rs,n)->List.of(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3)));
  if(rows.isEmpty())return;var row=rows.getFirst();
  try{kafka.send(MessagingConfiguration.REPLAY,row.get(0).toString(),row.get(2).toString()).get(10,TimeUnit.SECONDS);
   jdbc.update("update replay_outbox set sent_at=clock_timestamp(),lease_token=null,lease_until=null where id=? and lease_token=? and lease_until>clock_timestamp()",row.get(0),row.get(1));
  }catch(Exception error){if(error instanceof InterruptedException)Thread.currentThread().interrupt();jdbc.update("update replay_outbox set lease_token=null,lease_until=null,next_attempt=clock_timestamp()+interval '10 seconds' where id=? and lease_token=?",row.get(0),row.get(1));}
 }
}
