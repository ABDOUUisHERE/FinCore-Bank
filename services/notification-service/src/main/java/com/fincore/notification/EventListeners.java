package com.fincore.notification;
import com.fincore.support.Json;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
public class EventListeners {
 private final EventProcessor processor;private final NotificationStore store;
 public EventListeners(EventProcessor processor,NotificationStore store){this.processor=processor;this.store=store;}
 @KafkaListener(topicPattern="fincore\\.(customer|account|ledger|payment|fraud)\\..*\\.v1",groupId="fincore-notifications-v1",containerFactory="notificationKafkaFactory")
 public void domain(ConsumerRecord<String,String> record){processor.process(record.topic(),record.key(),record.value());}
 @KafkaListener(topics=MessagingConfiguration.REPLAY,groupId="fincore-notifications-replay-v1",containerFactory="notificationKafkaFactory")
 public void replay(ConsumerRecord<String,String> record){var wrapper=Json.read(record.value());processor.process(wrapper.path("originalTopic").asText(),wrapper.path("key").asText(),wrapper.path("value").asText());}
 @KafkaListener(topics=MessagingConfiguration.DLT,groupId="fincore-notifications-dlt-v1",containerFactory="deadLetterKafkaFactory")
 public void deadLetter(ConsumerRecord<String,String> record){var wrapper=Json.read(record.value());store.deadLetter(wrapper.path("originalTopic").asText(),wrapper.path("key").asText(),wrapper.path("value").asText(),record.partition(),record.offset());}
}
