package com.hellold6.steamhapticssinger;

import java.util.Collections;
import java.util.List;

final class PlaybackPlan {
    private final String sourceName;
    private final List<MidiPlaybackEvent> events;
    private final long durationMicros;

    PlaybackPlan(String sourceName, List<MidiPlaybackEvent> events, long durationMicros) {
        this.sourceName = sourceName;
        this.events = List.copyOf(events);
        this.durationMicros = durationMicros;
    }

    String getSourceName() {
        return sourceName;
    }

    List<MidiPlaybackEvent> getEvents() {
        return Collections.unmodifiableList(events);
    }

    long getDurationMicros() {
        return durationMicros;
    }
}
