package com.hellold6.steamhapticssinger;

import android.app.PendingIntent;
import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class UsbSteamDeviceManager {
    private static final int VALVE_VENDOR_ID = 0x28DE;
    private static final int STEAM_CONTROLLER_2015 = 0x1101;
    private static final int STEAM_CONTROLLER_2015_WIRED = 0x1102;
    private static final int STEAM_DONGLE = 0x1142;
    private static final int STEAM_DECK = 0x1205;
    private static final int STEAM_CONTROLLER_2026 = 0x1302;
    private static final int STEAM_PUCK = 0x1304;
    private static final int DEFAULT_TIMEOUT_MS = 1000;
    private static final int USB_RECIP_INTERFACE = 0x01;

    private final UsbManager usbManager;

    UsbSteamDeviceManager(UsbManager usbManager) {
        this.usbManager = usbManager;
    }

    SupportedDevice findBestSupportedDevice() {
        List<SupportedDevice> devices = getSupportedDevices();
        if (devices.isEmpty()) {
            return null;
        }
        devices.sort(Comparator.comparingInt(device -> device.priority));
        return devices.get(0);
    }

    List<SupportedDevice> getSupportedDevices() {
        List<SupportedDevice> devices = new ArrayList<>();
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            SupportedDevice supportedDevice = recognize(device);
            if (supportedDevice != null) {
                devices.add(supportedDevice);
            }
        }
        return devices;
    }

    boolean hasPermission(SupportedDevice device) {
        return usbManager.hasPermission(device.usbDevice);
    }

    void requestPermission(Context context, SupportedDevice device, String action) {
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
            context,
            device.usbDevice.getDeviceId(),
            new android.content.Intent(action).setPackage(context.getPackageName()),
            PendingIntent.FLAG_IMMUTABLE
        );
        usbManager.requestPermission(device.usbDevice, pendingIntent);
    }

    SteamNoteOutput open(SupportedDevice device) throws IOException {
        UsbDeviceConnection connection = usbManager.openDevice(device.usbDevice);
        if (connection == null) {
            throw new IOException("Unable to open USB device. Check USB permission and cable connection.");
        }

        try {
            if (device.type == SteamControllerType.ORIGINAL || device.type == SteamControllerType.JUPITER) {
                UsbInterface usbInterface = requireInterface(device.usbDevice, device.interfaceNumber);
                if (!connection.claimInterface(usbInterface, true)) {
                    throw new IOException("Unable to claim controller interface " + device.interfaceNumber + ".");
                }
                return new ControlTransferNoteOutput(connection, usbInterface, device.type);
            }

            UsbInterface usbInterface = selectHidInterface(device.usbDevice);
            if (!connection.claimInterface(usbInterface, true)) {
                throw new IOException("Unable to claim HID interface " + usbInterface.getId() + ".");
            }
            UsbEndpoint outEndpoint = findOutEndpoint(usbInterface);
            return new HidNoteOutput(connection, usbInterface, outEndpoint, device.type);
        } catch (IOException error) {
            connection.close();
            throw error;
        }
    }

    private SupportedDevice recognize(UsbDevice device) {
        if (device.getVendorId() != VALVE_VENDOR_ID) {
            return null;
        }

        return switch (device.getProductId()) {
            case STEAM_CONTROLLER_2015 -> new SupportedDevice(device, SteamControllerType.ORIGINAL, "Steam Controller", 2, 0);
            case STEAM_CONTROLLER_2015_WIRED -> new SupportedDevice(device, SteamControllerType.ORIGINAL, "Steam Controller (wired)", 2, 1);
            case STEAM_DONGLE -> new SupportedDevice(device, SteamControllerType.ORIGINAL, "Steam Dongle", 1, 2);
            case STEAM_CONTROLLER_2026 -> new SupportedDevice(device, SteamControllerType.TRITON, "Steam Controller (2026)", -1, 3);
            case STEAM_PUCK -> new SupportedDevice(device, SteamControllerType.GALILEO, "Steam Puck", -1, 4);
            case STEAM_DECK -> new SupportedDevice(device, SteamControllerType.JUPITER, "Steam Deck", 2, 5);
            default -> null;
        };
    }

    private UsbInterface requireInterface(UsbDevice device, int interfaceNumber) throws IOException {
        for (int index = 0; index < device.getInterfaceCount(); index++) {
            UsbInterface usbInterface = device.getInterface(index);
            if (usbInterface.getId() == interfaceNumber || index == interfaceNumber) {
                return usbInterface;
            }
        }
        throw new IOException("Required interface " + interfaceNumber + " was not found on the device.");
    }

    private UsbInterface selectHidInterface(UsbDevice device) throws IOException {
        UsbInterface fallback = null;
        for (int index = 0; index < device.getInterfaceCount(); index++) {
            UsbInterface usbInterface = device.getInterface(index);
            if (usbInterface.getInterfaceClass() == UsbConstants.USB_CLASS_HID) {
                if (findOutEndpoint(usbInterface) != null) {
                    return usbInterface;
                }
                if (fallback == null) {
                    fallback = usbInterface;
                }
            } else if (fallback == null && findOutEndpoint(usbInterface) != null) {
                fallback = usbInterface;
            }
        }

        if (fallback == null && device.getInterfaceCount() > 0) {
            fallback = device.getInterface(0);
        }
        if (fallback == null) {
            throw new IOException("No usable USB interface was found for the controller.");
        }
        return fallback;
    }

    private UsbEndpoint findOutEndpoint(UsbInterface usbInterface) {
        for (int index = 0; index < usbInterface.getEndpointCount(); index++) {
            UsbEndpoint endpoint = usbInterface.getEndpoint(index);
            if (endpoint.getDirection() == UsbConstants.USB_DIR_OUT) {
                return endpoint;
            }
        }
        return null;
    }

    static final class SupportedDevice {
        private final UsbDevice usbDevice;
        private final SteamControllerType type;
        private final String label;
        private final int interfaceNumber;
        private final int priority;

        SupportedDevice(UsbDevice usbDevice, SteamControllerType type, String label, int interfaceNumber, int priority) {
            this.usbDevice = usbDevice;
            this.type = type;
            this.label = label;
            this.interfaceNumber = interfaceNumber;
            this.priority = priority;
        }

        SteamControllerType getType() {
            return type;
        }

        String describe() {
            return String.format(
                Locale.US,
                "%s (%04X:%04X)",
                label,
                usbDevice.getVendorId(),
                usbDevice.getProductId()
            );
        }
    }

    private static final class ControlTransferNoteOutput implements SteamNoteOutput {
        private final UsbDeviceConnection connection;
        private final UsbInterface usbInterface;
        private final SteamControllerType type;

        private ControlTransferNoteOutput(UsbDeviceConnection connection, UsbInterface usbInterface, SteamControllerType type) {
            this.connection = connection;
            this.usbInterface = usbInterface;
            this.type = type;
        }

        @Override
        public SteamControllerType getType() {
            return type;
        }

        @Override
        public void sendNote(int channel, int note, int velocity, PlaybackOptions options) throws IOException {
            SteamHapticsCommandEncoder.ControlTransferCommand command = type == SteamControllerType.JUPITER
                ? SteamHapticsCommandEncoder.encodeJupiter(channel, note, velocity, options.isDirectVelocity())
                : SteamHapticsCommandEncoder.encodeOriginal(channel, note);
            int result = connection.controlTransfer(
                command.getRequestType(),
                command.getRequest(),
                command.getValue(),
                usbInterface.getId(),
                command.getPayload(),
                command.getPayload().length,
                command.getTimeoutMillis()
            );
            if (result < 0) {
                throw new IOException("USB control transfer failed.");
            }
        }

        @Override
        public void close() {
            connection.releaseInterface(usbInterface);
            connection.close();
        }
    }

    private static final class HidNoteOutput implements SteamNoteOutput {
        private final UsbDeviceConnection connection;
        private final UsbInterface usbInterface;
        private final UsbEndpoint outEndpoint;
        private final SteamControllerType type;

        private HidNoteOutput(UsbDeviceConnection connection, UsbInterface usbInterface, UsbEndpoint outEndpoint, SteamControllerType type) {
            this.connection = connection;
            this.usbInterface = usbInterface;
            this.outEndpoint = outEndpoint;
            this.type = type;
        }

        @Override
        public SteamControllerType getType() {
            return type;
        }

        @Override
        public void sendNote(int channel, int note, int velocity, PlaybackOptions options) throws IOException {
            byte[] report = SteamHapticsCommandEncoder.encodeTriton(
                channel,
                note,
                velocity,
                options.isDirectVelocity(),
                options.isTritonSwap()
            );

            int result = -1;
            if (outEndpoint != null) {
                result = connection.bulkTransfer(outEndpoint, report, report.length, DEFAULT_TIMEOUT_MS);
            }
            if (result < 0) {
                byte[] payload = Arrays.copyOfRange(report, 1, report.length);
                result = connection.controlTransfer(
                    UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_CLASS | USB_RECIP_INTERFACE,
                    0x09,
                    (0x02 << 8) | (report[0] & 0xFF),
                    usbInterface.getId(),
                    payload,
                    payload.length,
                    DEFAULT_TIMEOUT_MS
                );
            }
            if (result < 0) {
                throw new IOException("USB HID report transfer failed.");
            }
        }

        @Override
        public void close() {
            connection.releaseInterface(usbInterface);
            connection.close();
        }
    }
}
