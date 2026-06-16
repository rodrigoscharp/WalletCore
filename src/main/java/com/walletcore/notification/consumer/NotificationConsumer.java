package com.walletcore.notification.consumer;

import com.rabbitmq.client.Channel;
import com.walletcore.notification.dto.TransferNotificationEvent;
import com.walletcore.notification.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class NotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

    private final NotificationService notificationService;

    public NotificationConsumer(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @RabbitListener(queues = "${walletcore.rabbitmq.queues.notification}")
    @Retryable(
            retryFor = Exception.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    public void consume(TransferNotificationEvent event,
                        Channel channel,
                        @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        log.debug("Processing notification event for tx: {}", event.transactionId());
        try {
            notificationService.processTransferNotification(event);
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.warn("Error processing notification for tx: {} — will retry", event.transactionId(), e);
            throw e;
        }
    }

    @Recover
    public void recover(Exception e, TransferNotificationEvent event,
                        Channel channel,
                        @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        log.error("All retry attempts exhausted for tx: {}. Sending to DLQ.", event.transactionId(), e);
        notificationService.saveFailedNotification(event, e.getMessage());
        channel.basicNack(deliveryTag, false, false);
    }
}
