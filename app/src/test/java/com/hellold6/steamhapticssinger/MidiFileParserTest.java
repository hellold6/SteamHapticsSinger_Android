package com.hellold6.steamhapticssinger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

class MidiFileParserTest {
    @Test
    void parsesSimpleTempoAwareMidiFile() throws Exception {
        byte[] midi = new byte[] {
            'M', 'T', 'h', 'd', 0, 0, 0, 6, 0, 0, 0, 1, 0x01, (byte) 0xE0,
            'M', 'T', 'r', 'k', 0, 0, 0, 20,
            0x00, (byte) 0xFF, 0x51, 0x03, 0x07, (byte) 0xA1, 0x20,
            0x00, (byte) 0x90, 0x3C, 0x64,
            (byte) 0x83, 0x60, (byte) 0x80, 0x3C, 0x40,
            0x00, (byte) 0xFF, 0x2F, 0x00
        };

        PlaybackPlan plan = MidiFileParser.parse(midi, "simple.mid");
        List<MidiPlaybackEvent> events = plan.getEvents();

        assertEquals(2, events.size());
        assertEquals(0L, events.get(0).getTimeMicros());
        assertEquals(500_000L, events.get(1).getTimeMicros());
        assertEquals(0, events.get(0).getChannel());
        assertFalse(events.get(1).isNoteOn());
    }

    @Test
    void treatsNoteOnWithZeroVelocityAsNoteOff() throws Exception {
        byte[] midi = new byte[] {
            'M', 'T', 'h', 'd', 0, 0, 0, 6, 0, 0, 0, 1, 0x01, (byte) 0xE0,
            'M', 'T', 'r', 'k', 0, 0, 0, 11,
            0x00, (byte) 0x90, 0x3C, 0x40,
            0x00, 0x3C, 0x00,
            0x00, (byte) 0xFF, 0x2F, 0x00
        };

        PlaybackPlan plan = MidiFileParser.parse(midi, "running-status.mid");
        assertEquals(2, plan.getEvents().size());
        assertFalse(plan.getEvents().get(1).isNoteOn());
    }

    @Test
    void rejectsInvalidHeader() {
        assertThrows(IOException.class, () -> MidiFileParser.parse(new byte[] { 0x00, 0x01, 0x02 }, "broken.mid"));
    }
}
