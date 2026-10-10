package dev.relayforge.delivery;

import java.util.UUID;

public record DeliveryJob(
        UUID id,
        UUID eventId,
        UUID endpointId,
        UUID leaseToken,
        int attemptNumber,
        int cycleAttempts,
        String url,
        String encryptedSecret,
        String envelope) {}
