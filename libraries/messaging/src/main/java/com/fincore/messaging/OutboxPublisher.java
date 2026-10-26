package com.fincore.messaging;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.kafka.clients.producer.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

/** The fact is immutable; only a separate delivery row is leased/acknowledged. */
public final class OutboxPublisher implements AutoCloseable {
    private final JdbcTemplate jdbc; private final TransactionTemplate tx; private final Producer<String,String> producer;
    private final Runnable afterAck; private final MeterRegistry metrics;
    private volatile long retryAfterMillis;
    public record Delivery(UUID id,UUID lease,int attempts,String body){}
    public OutboxPublisher(JdbcTemplate jdbc,TransactionTemplate tx,Producer<String,String> producer,MeterRegistry metrics,Runnable afterAck) {
        this.jdbc=jdbc;this.tx=tx;this.producer=producer;this.metrics=metrics;this.afterAck=afterAck;
        metrics.gauge("fincore.outbox.pending",this,p->p.pending());
        metrics.gauge("fincore.outbox.circuit.open",this,p->System.currentTimeMillis()<p.retryAfterMillis?1.0:0.0);
        metrics.gauge("fincore.outbox.oldest.seconds",this,p->p.oldest());
    }
    public double pending(){return jdbc.queryForObject("select count(*) from fincore_event_source s left join event_delivery d on d.event_id=s.event_id where d.sent_at is null",Long.class);}
    public double oldest(){return jdbc.queryForObject("select coalesce(extract(epoch from clock_timestamp()-min(s.occurred_at)),0) from fincore_event_source s left join event_delivery d on d.event_id=s.event_id where d.sent_at is null",Double.class);}
    public Delivery claim() {return tx.execute(status->{
        jdbc.update("insert into event_delivery(event_id) select s.event_id from fincore_event_source s left join event_delivery d on d.event_id=s.event_id where d.event_id is null order by s.occurred_at,s.event_id limit 100 on conflict do nothing");
        var rows=jdbc.query("with candidate as (select event_id from event_delivery where sent_at is null and next_attempt<=clock_timestamp() and (lease_until is null or lease_until<clock_timestamp()) order by next_attempt,event_id for update skip locked limit 1), claimed as (update event_delivery d set lease_token=gen_random_uuid(),lease_until=clock_timestamp()+interval '30 seconds',attempts=attempts+1 from candidate c where d.event_id=c.event_id returning d.*) select c.event_id,c.lease_token,c.attempts,s.envelope::text from claimed c join fincore_event_source s on s.event_id=c.event_id",
            (rs,n)->new Delivery(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getInt(3),rs.getString(4)));
        return rows.isEmpty()?null:rows.getFirst();
    });}
    public boolean publishOne() {
        if(System.currentTimeMillis()<retryAfterMillis)return false;
        var row=claim();if(row==null)return false;
        try {
            var event=EventEnvelope.parse(row.body());
            producer.send(new ProducerRecord<>(event.topic(),event.aggregateId().toString(),row.body())).get(10,TimeUnit.SECONDS);
            retryAfterMillis=0;
            afterAck.run(); // test seam: a process crash here MUST leave a replayable row.
            int updated=jdbc.update("update event_delivery set sent_at=clock_timestamp(),lease_token=null,lease_until=null,last_code=null where event_id=? and lease_token=? and lease_until>clock_timestamp()",row.id(),row.lease());
            metrics.counter("fincore.outbox.acknowledged").increment(updated);
        } catch(Exception error) {
            if(error instanceof InterruptedException)Thread.currentThread().interrupt();
            long delay=Math.min(60,1L<<Math.min(6,row.attempts()))+ThreadLocalRandom.current().nextLong(3);
            retryAfterMillis=System.currentTimeMillis()+delay*1000;
            jdbc.update("update event_delivery set lease_token=null,lease_until=null,next_attempt=clock_timestamp()+(? * interval '1 second'),last_code='PUBLISH_RETRY' where event_id=? and lease_token=?",delay,row.id(),row.lease());
            metrics.counter("fincore.outbox.retries").increment();
        }
        return true;
    }
    @Scheduled(fixedDelayString="${fincore.messaging.poll-ms:1000}") public void tick(){for(int i=0;i<8;i++)if(!publishOne())break;}
    @Override public void close(){producer.close(java.time.Duration.ofSeconds(3));}
}
