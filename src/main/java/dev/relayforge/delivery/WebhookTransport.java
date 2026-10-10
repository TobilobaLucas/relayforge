package dev.relayforge.delivery;

public interface WebhookTransport {
    DeliveryResult send(DeliveryJob job);
}
