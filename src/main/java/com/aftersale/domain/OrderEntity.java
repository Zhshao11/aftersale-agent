package com.aftersale.domain;

import com.aftersale.enums.OrderStatus;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "orders")
public class OrderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false, unique = true, length = 32)
    private String orderNo;

    @Column(name = "user_id", nullable = false, length = 32)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OrderStatus status;

    @Column(name = "item_name", nullable = false, length = 128)
    private String itemName;

    @Column(name = "amount_cents", nullable = false)
    private Long amountCents;

    @Column(nullable = false, length = 8)
    private String currency = "USD";

    @Column(name = "logistics_status", length = 32)
    private String logisticsStatus;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "shipped_at")
    private LocalDateTime shippedAt;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    public boolean ownedBy(String userId) {
        return this.userId != null && this.userId.equals(userId);
    }

    public Long getId() { return id; }
    public String getOrderNo() { return orderNo; }
    public String getUserId() { return userId; }
    public OrderStatus getStatus() { return status; }
    public String getItemName() { return itemName; }
    public Long getAmountCents() { return amountCents; }
    public String getCurrency() { return currency; }
    public String getLogisticsStatus() { return logisticsStatus; }
    public LocalDateTime getPaidAt() { return paidAt; }
    public LocalDateTime getShippedAt() { return shippedAt; }
    public LocalDateTime getDeliveredAt() { return deliveredAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public void setStatus(OrderStatus status) { this.status = status; }
    protected void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    protected void setUserId(String userId) { this.userId = userId; }
    protected void setItemName(String itemName) { this.itemName = itemName; }
    protected void setAmountCents(Long amountCents) { this.amountCents = amountCents; }
    protected void setLogisticsStatus(String logisticsStatus) { this.logisticsStatus = logisticsStatus; }
    protected void setPaidAt(LocalDateTime paidAt) { this.paidAt = paidAt; }
    protected void setShippedAt(LocalDateTime shippedAt) { this.shippedAt = shippedAt; }
    protected void setDeliveredAt(LocalDateTime deliveredAt) { this.deliveredAt = deliveredAt; }
}
