package com.walletcore.notification.entity;

import com.walletcore.transaction.entity.Transaction;
import com.walletcore.user.entity.User;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id")
    private Transaction transaction;

    @Column(nullable = false)
    private String type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationStatus status = NotificationStatus.PENDING;

    @Column(columnDefinition = "TEXT")
    private String payload;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "sent_at")
    private Instant sentAt;

    public Notification() {}

    public Notification(User user, Transaction transaction, String type, String payload) {
        this.user = user;
        this.transaction = transaction;
        this.type = type;
        this.payload = payload;
    }

    public void markSent() {
        this.status = NotificationStatus.SENT;
        this.sentAt = Instant.now();
    }

    public void markFailed(String errorMessage) {
        this.status = NotificationStatus.FAILED;
        this.errorMessage = errorMessage;
    }

    public void incrementAttempts() { this.attempts++; }

    public UUID getId() { return id; }
    public User getUser() { return user; }
    public Transaction getTransaction() { return transaction; }
    public String getType() { return type; }
    public NotificationStatus getStatus() { return status; }
    public String getPayload() { return payload; }
    public String getErrorMessage() { return errorMessage; }
    public int getAttempts() { return attempts; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSentAt() { return sentAt; }

    public enum NotificationStatus { PENDING, SENT, FAILED }
}
