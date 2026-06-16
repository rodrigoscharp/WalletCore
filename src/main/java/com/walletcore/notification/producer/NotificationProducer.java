package com.walletcore.notification.producer;

import com.walletcore.notification.dto.TransferNotificationEvent;
import com.walletcore.transaction.entity.Transaction;
import com.walletcore.user.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class NotificationProducer {

    private static final Logger log = LoggerFactory.getLogger(NotificationProducer.class);

    private final RabbitTemplate rabbitTemplate;

    @Value("${walletcore.rabbitmq.exchange}")
    private String exchange;

    @Value("${walletcore.rabbitmq.routing-keys.notification}")
    private String routingKey;

    public NotificationProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publishTransferEvent(Transaction transaction, User user) {
        var event = new TransferNotificationEvent(
                transaction.getId(),
                user.getId(),
                transaction.getSourceAccount().getId(),
                transaction.getTargetAccount().getId(),
                transaction.getAmount(),
                transaction.getCurrency(),
                Instant.now()
        );

        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, event);
            log.info("Notification event published for tx: {}", transaction.getId());
        } catch (Exception e) {
            log.error("Failed to publish notification event for tx: {}", transaction.getId(), e);
        }
    }
}
