package com.hellold6.steamhapticssinger;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SteamHapticsCommandEncoderTest {
    @Test
    void originalStopCommandKeepsPeriodAtZero() {
        SteamHapticsCommandEncoder.ControlTransferCommand command = SteamHapticsCommandEncoder.encodeOriginal(0, SteamHapticsCommandEncoder.NOTE_STOP);
        byte[] payload = command.getPayload();

        assertEquals(0, payload[3]);
        assertEquals(0, payload[4]);
        assertEquals(0, payload[7]);
        assertEquals(0, payload[8]);
    }

    @Test
    void tritonReportUsesSwapAwareChannelMapping() {
        byte[] report = SteamHapticsCommandEncoder.encodeTriton(0, 69, 127, true, false);

        assertEquals(0x83, report[0] & 0xFF);
        assertEquals(4, report[1] & 0xFF);
        assertEquals(127, report[2]);
    }

    @Test
    void noteFormatterMatchesOriginalDisplayOrder() {
        String text = NoteDisplayFormatter.format(new int[] { 69, 60, 71, 72 }, 4);
        assertEquals("LEFT haptic : C-4, RIGHT haptic : A-4, LEFT haptic : C-5, RIGHT haptic : B-4", text);
    }
}
