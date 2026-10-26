package com.fincore.messaging;
import java.util.*;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.core.env.Environment;
public final class KafkaSettings {
    private KafkaSettings(){}
    public static Map<String,Object> common(Environment env) {
        var p=new HashMap<String,Object>();p.put("bootstrap.servers",env.getRequiredProperty("KAFKA_BOOTSTRAP_SERVERS"));
        p.put("security.protocol",env.getProperty("KAFKA_SECURITY_PROTOCOL","SASL_PLAINTEXT"));
        if(!p.get("security.protocol").equals("PLAINTEXT")) {
            var user=env.getRequiredProperty("KAFKA_USER");var secret=env.getRequiredProperty("KAFKA_PASSWORD");
            if(!user.matches("[a-z-]+") || !secret.matches("[a-f0-9]{48}"))throw new IllegalStateException("Invalid Kafka credential configuration");
            p.put("sasl.mechanism","PLAIN");p.put("sasl.jaas.config","org.apache.kafka.common.security.plain.PlainLoginModule required username=\""+user+"\" password=\""+secret+"\";");
        }
        return p;
    }
    public static Map<String,Object> producer(Environment env) {
        var p=common(env);p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,StringSerializer.class);p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,StringSerializer.class);
        p.put("acks","all");p.put("enable.idempotence",true);p.put("max.in.flight.requests.per.connection",5);
        p.put("delivery.timeout.ms",8000);p.put("request.timeout.ms",3000);p.put("max.block.ms",3000);return p;
    }
}
