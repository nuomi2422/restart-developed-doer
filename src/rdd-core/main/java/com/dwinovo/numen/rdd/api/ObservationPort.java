package com.dwinovo.numen.rdd.api;

/** Environment observation boundary; no Minecraft types cross this interface. */
public interface ObservationPort {
    Observation observe(ObservationRequest request);

    record ObservationRequest(String observationId, String type, String environmentId) {
        public ObservationRequest {
            if (observationId == null || observationId.isBlank()) throw new IllegalArgumentException("observationId required");
            if (type == null || type.isBlank()) throw new IllegalArgumentException("observation type required");
            if (environmentId == null || environmentId.isBlank()) throw new IllegalArgumentException("environmentId required");
        }
    }
}
