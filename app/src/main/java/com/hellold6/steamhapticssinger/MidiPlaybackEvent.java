package com.hellold6.steamhapticssinger;

final class MidiPlaybackEvent {
    private final long tick;
    private final long timeMicros;
    private final int channel;
    private final int note;
    private final int velocity;
    private final boolean noteOn;

    MidiPlaybackEvent(long tick, long timeMicros, int channel, int note, int velocity, boolean noteOn) {
        this.tick = tick;
        this.timeMicros = timeMicros;
        this.channel = channel;
        this.note = note;
        this.velocity = velocity;
        this.noteOn = noteOn;
    }

    long getTick() {
        return tick;
    }

    long getTimeMicros() {
        return timeMicros;
    }

    int getChannel() {
        return channel;
    }

    int getNote() {
        return note;
    }

    int getVelocity() {
        return velocity;
    }

    boolean isNoteOn() {
        return noteOn;
    }
}
