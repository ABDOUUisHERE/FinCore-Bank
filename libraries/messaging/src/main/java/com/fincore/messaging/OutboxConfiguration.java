package com.fincore.messaging;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
@Configuration
@EnableScheduling
@ConditionalOnProperty(name="fincore.messaging.enabled",havingValue="true")
public class OutboxConfiguration {
    @Bean(destroyMethod="close") OutboxPublisher outboxPublisher(JdbcTemplate jdbc,PlatformTransactionManager tx,Environment env,MeterRegistry metrics){
        return new OutboxPublisher(jdbc,new TransactionTemplate(tx),new KafkaProducer<>(KafkaSettings.producer(env)),metrics,()->{});
    }
}
