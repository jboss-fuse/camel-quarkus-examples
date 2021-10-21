package org.apache.camel.example;

import java.util.Collections;
import java.util.Map;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.strimzi.StrimziKafkaContainer;

public class StrimziTestResource implements QuarkusTestResourceLifecycleManager {

    private static final StrimziKafkaContainer kafka = new StrimziKafkaContainer();

    public static String getBootstrapServers() {
        return kafka.getBootstrapServers();
    }

    @Override
    public Map<String, String> start() {
        kafka.start();
        return Collections.singletonMap("camel.component.kafka.brokers", kafka.getBootstrapServers());
    }

    @Override
    public void stop() {
        kafka.close();
    }
}
