package com.hellold6.steamhapticssinger;

final class SteamHapticsCommandEncoder {
    static final int NOTE_STOP = -1;
    private static final double STEAM_CONTROLLER_MAGIC_PERIOD_RATIO = 495483.0;
    private static final int CONTROLLER_TIMEOUT_MS = 1000;

    private SteamHapticsCommandEncoder() {
    }

    static ControlTransferCommand encodeOriginal(int channel, int note) {
        byte[] payload = new byte[64];
        int periodCommand = note == NOTE_STOP ? 0 : (int) ((1.0 / frequencyFor(note)) * STEAM_CONTROLLER_MAGIC_PERIOD_RATIO);
        int repeatCommand = note == NOTE_STOP ? 0x0000 : 0x7FFF;

        payload[0] = (byte) 0x8F;
        payload[2] = (byte) channel;
        payload[3] = (byte) (periodCommand % 0xFF);
        payload[4] = (byte) (periodCommand / 0xFF);
        payload[5] = (byte) (periodCommand % 0xFF);
        payload[6] = (byte) (periodCommand / 0xFF);
        payload[7] = (byte) (repeatCommand % 0xFF);
        payload[8] = (byte) (repeatCommand / 0xFF);

        return new ControlTransferCommand(0x21, 9, 0x0300, 2, payload, CONTROLLER_TIMEOUT_MS);
    }

    static ControlTransferCommand encodeJupiter(int channel, int note, int velocity, boolean directVelocity) {
        byte[] payload = new byte[64];
        int duration = note == NOTE_STOP ? 0x0000 : 0x7FFF;
        int frequency = note == NOTE_STOP ? 0 : (int) frequencyFor(note);

        payload[0] = (byte) 0xEA;
        payload[2] = (byte) (channel == 0 ? 1 : 0);
        payload[3] = 0x03;
        payload[5] = directVelocity ? encodeGain(velocity) : 0x00;
        payload[6] = (byte) (frequency % 0xFF);
        payload[7] = (byte) (frequency / 0xFF);
        payload[8] = (byte) (duration % 0xFF);
        payload[9] = (byte) (duration / 0xFF);

        return new ControlTransferCommand(0x21, 9, 0x0300, 2, payload, CONTROLLER_TIMEOUT_MS);
    }

    static byte[] encodeTriton(int channel, int note, int velocity, boolean directVelocity, boolean tritonSwap) {
        byte[] report = new byte[65];
        int mappedChannel;

        if (note == NOTE_STOP) {
            report[0] = (byte) 0x81;
            mappedChannel = tritonSwap
                ? (channel < 2 ? channel : invertBinary(channel - 2) + 3)
                : (channel < 2 ? invertBinary(channel) + 3 : channel - 2);
            report[1] = (byte) mappedChannel;
            return report;
        }

        report[0] = (byte) 0x83;
        mappedChannel = tritonSwap
            ? (channel < 2 ? invertBinary(channel) : invertBinary(channel - 2) + 3)
            : (channel < 2 ? invertBinary(channel) + 3 : invertBinary(channel - 2));
        int frequency = (int) frequencyFor(note);
        report[1] = (byte) mappedChannel;
        report[2] = directVelocity ? encodeGain(velocity) : (byte) 0xFE;
        report[3] = (byte) (frequency % 0xFF);
        report[4] = (byte) (frequency / 0xFF);
        report[5] = (byte) 0xFF;
        report[6] = (byte) 0x7F;
        return report;
    }

    private static byte encodeGain(int velocity) {
        return (byte) (((velocity * 255) / 127) - 128);
    }

    private static int invertBinary(int value) {
        return value == 0 ? 1 : 0;
    }

    private static double frequencyFor(int note) {
        return 440.0 * Math.pow(2.0, (note - 69) / 12.0);
    }

    static final class ControlTransferCommand {
        private final int requestType;
        private final int request;
        private final int value;
        private final int index;
        private final byte[] payload;
        private final int timeoutMillis;

        ControlTransferCommand(int requestType, int request, int value, int index, byte[] payload, int timeoutMillis) {
            this.requestType = requestType;
            this.request = request;
            this.value = value;
            this.index = index;
            this.payload = payload.clone();
            this.timeoutMillis = timeoutMillis;
        }

        int getRequestType() {
            return requestType;
        }

        int getRequest() {
            return request;
        }

        int getValue() {
            return value;
        }

        int getIndex() {
            return index;
        }

        byte[] getPayload() {
            return payload.clone();
        }

        int getTimeoutMillis() {
            return timeoutMillis;
        }
    }
}
