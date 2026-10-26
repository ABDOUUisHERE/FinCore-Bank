package com.fincore.notification;
import com.fincore.messaging.KafkaSettings;
import com.fincore.support.Json;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.*;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.util.backoff.FixedBackOff;
@Configuration
@EnableKafka
@ConditionalOnProperty(name="fincore.messaging.enabled",havingValue="true")
public class MessagingConfiguration {
 public static final String DLT="fincore.notifications.dlt.v1",REPLAY="fincore.notifications.replay.v1";
 @Bean KafkaTemplate<String,String> eventTemplate(Environment env){return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(KafkaSettings.producer(env)));}
 private ConcurrentKafkaListenerContainerFactory<String,String> base(Environment env){
  var p=KafkaSettings.common(env);p.put("key.deserializer",StringDeserializer.class);p.put("value.deserializer",StringDeserializer.class);
  p.put("enable.auto.commit",false);p.put("auto.offset.reset","earliest");p.put("max.poll.records",10);p.put("max.poll.interval.ms",180000);
  var f=new ConcurrentKafkaListenerContainerFactory<String,String>();f.setConsumerFactory(new DefaultKafkaConsumerFactory<>(p));
  f.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);f.getContainerProperties().setSyncCommits(true);return f;
 }
 @Bean ConcurrentKafkaListenerContainerFactory<String,String> notificationKafkaFactory(Environment env,KafkaTemplate<String,String> template){
  var f=base(env);var backoff=new ExponentialBackOffWithMaxRetries(3);backoff.setInitialInterval(500);backoff.setMultiplier(2);backoff.setMaxInterval(5000);
  f.setCommonErrorHandler(new DefaultErrorHandler((record,error)->{
   var value=record.value()==null?"":record.value().toString();
   var topic=record.topic();var key=record.key()==null?"":record.key().toString();
   if(topic.equals(REPLAY)){var wrapper=Json.read(value);topic=wrapper.path("originalTopic").asText();key=wrapper.path("key").asText();value=wrapper.path("value").asText();}
   try{template.send(DLT,key,Json.text(Map.of("originalTopic",topic,"key",key,"value",value))).get(10,TimeUnit.SECONDS);}
   catch(Exception failure){if(failure instanceof InterruptedException)Thread.currentThread().interrupt();throw new IllegalStateException("DEAD_LETTER_PUBLICATION_RETRY");}
  },backoff));return f;
 }
 @Bean ConcurrentKafkaListenerContainerFactory<String,String> deadLetterKafkaFactory(Environment env){
  var f=base(env);f.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1000,FixedBackOff.UNLIMITED_ATTEMPTS)));return f;
 }
 @Bean EventListeners eventListeners(EventProcessor processor,NotificationStore store){return new EventListeners(processor,store);}
 @Bean DeliveryWorker deliveryWorker(org.springframework.jdbc.core.JdbcTemplate jdbc,org.springframework.transaction.PlatformTransactionManager tx,KafkaTemplate<String,String> template){return new DeliveryWorker(jdbc,new org.springframework.transaction.support.TransactionTemplate(tx),template);}
}
