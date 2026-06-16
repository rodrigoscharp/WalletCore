package com.walletcore.notification.service;

import com.walletcore.notification.dto.TransferNotificationEvent;
import com.walletcore.notification.entity.Notification;
import com.walletcore.notification.repository.NotificationRepository;
import com.walletcore.transaction.repository.TransactionRepository;
import com.walletcore.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;

    public NotificationService(NotificationRepository notificationRepository,
                                UserRepository userRepository,
                                TransactionRepository transactionRepository) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.transactionRepository = transactionRepository;
    }

    @Transactional
    public void processTransferNotification(TransferNotificationEvent event) {
        var user = userRepository.findById(event.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        var transaction = transactionRepository.findById(event.transactionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction not found"));

        var payload = String.format(
                "Transfer of %s %s from account %s to account %s completed.",
                event.amount(), event.currency(), event.sourceAccountId(), event.targetAccountId());

        var notification = new Notification(user, transaction, "TRANSFER_COMPLETED", payload);
        notification.incrementAttempts();
        notification.markSent();

        notificationRepository.save(notification);
        log.info("Notification saved for user: {} tx: {}", user.getId(), transaction.getId());
    }

    @Transactional
    public void saveFailedNotification(TransferNotificationEvent event, String errorMessage) {
        userRepository.findById(event.userId()).ifPresent(user ->
                transactionRepository.findById(event.transactionId()).ifPresent(transaction -> {
                    var notification = new Notification(user, transaction, "TRANSFER_COMPLETED", null);
                    notification.incrementAttempts();
                    notification.markFailed(errorMessage);
                    notificationRepository.save(notification);
                })
        );
    }
}
