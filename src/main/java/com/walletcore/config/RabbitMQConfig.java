package com.walletcore.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    @Value("${walletcore.rabbitmq.exchange}")
    private String exchangeName;

    @Value("${walletcore.rabbitmq.queues.notification}")
    private String notificationQueue;

    @Value("${walletcore.rabbitmq.queues.notification-dlq}")
    private String notificationDlq;

    @Value("${walletcore.rabbitmq.routing-keys.notification}")
    private String notificationRoutingKey;

    @Bean
    DirectExchange walletcoreExchange() {
        return new DirectExchange(exchangeName, true, false);
    }

    @Bean
    Queue notificationQueue() {
        return QueueBuilder.durable(notificationQueue)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", notificationDlq)
                .build();
    }

    @Bean
    Queue notificationDlq() {
        return QueueBuilder.durable(notificationDlq).build();
    }

    @Bean
    Binding notificationBinding(Queue notificationQueue, DirectExchange walletcoreExchange) {
        return BindingBuilder.bind(notificationQueue)
                .to(walletcoreExchange)
                .with(notificationRoutingKey);
    }

    @Bean
    Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        var template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter());
        return template;
    }

    @Bean
    SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(ConnectionFactory connectionFactory) {
        var factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter());
        return factory;
    }
}
