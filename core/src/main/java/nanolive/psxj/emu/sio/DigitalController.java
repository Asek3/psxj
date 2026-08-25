package nanolive.psxj.emu.sio;

import java.util.Arrays;

/**
 * SCPH-1200 compatible controller. It powers up in digital mode (ID 41h),
 * supports the DualShock configuration protocol, analog mode (ID 73h), and
 * both rumble actuators.
 */
public final class DigitalController implements ControllerDevice {

    public static final String TYPE = "sony-dualshock";

    private static final int CONFIG_ID = 0xF3;
    private static final int RESPONSE_MARKER = 0x5A;
    private static final int[] EMPTY_RUMBLE_MAP = {
        0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF
    };

    @FunctionalInterface
    public interface RumbleHandler {
        void update(int largeMotor, boolean smallMotor);
    }

    private int buttonsLow = 0xFF;
    private int buttonsHigh = 0xFF;
    private int leftX = 0x80;
    private int leftY = 0x80;
    private int rightX = 0x80;
    private int rightY = 0x80;
    private boolean analogMode;
    private boolean configMode;
    private boolean commandStartedInConfig;
    private int currentCommand = -1;
    private int responseIndex;
    private int responseLength;
    private int selector;
    private int largeMotor;
    private boolean smallMotor;
    private int[] rumbleMap = EMPTY_RUMBLE_MAP.clone();
    private int[] rumbleMapReply = EMPTY_RUMBLE_MAP.clone();
    private transient RumbleHandler rumbleHandler;

    public void setButtonState(int mask, boolean pressed) {
        if (mask < 0x100) {
            buttonsLow = pressed ? (buttonsLow & ~mask) : (buttonsLow | mask);
        } else {
            int hiMask = (mask >>> 8) & 0xFF;
            buttonsHigh = pressed ? (buttonsHigh & ~hiMask) : (buttonsHigh | hiMask);
        }
    }

    public void setControllerState(int pressedMask, int leftX, int leftY, int rightX, int rightY) {
        buttonsLow = ~(pressedMask & 0xFF) & 0xFF;
        buttonsHigh = ~((pressedMask >>> 8) & 0xFF) & 0xFF;
        this.leftX = Math.clamp(leftX, 0, 255);
        this.leftY = Math.clamp(leftY, 0, 255);
        this.rightX = Math.clamp(rightX, 0, 255);
        this.rightY = Math.clamp(rightY, 0, 255);
    }

    public void setRumbleHandler(RumbleHandler rumbleHandler) {
        this.rumbleHandler = rumbleHandler;
        dispatchRumble();
    }

    @Override
    public void cancelTransaction() {
        resetTransaction();
    }

    @Override
    public String typeId() {
        return TYPE;
    }

    @Override
    public CommandResult beginCommand(int command) {
        command &= 0xFF;
        if (!supportsCommand(command)) {
            resetTransaction();
            return CommandResult.last(0xFF);
        }
        currentCommand = command;
        commandStartedInConfig = configMode;
        responseIndex = -1;
        selector = 0;
        rumbleMapReply = rumbleMap.clone();
        responseLength = responseLength(command);
        return CommandResult.more(configMode ? CONFIG_ID : modeId(responseLength));
    }

    @Override
    public CommandResult exchangeCommandByte(int value) {
        if (currentCommand < 0) {
            return CommandResult.last(0xFF);
        }
        if (responseIndex < 0) {
            responseIndex = 0;
            return CommandResult.more(RESPONSE_MARKER);
        }

        int index = responseIndex++;
        int response = responseByte(index);
        processCommandByte(index, value & 0xFF);
        boolean finished = responseIndex >= responseLength;
        if (finished) {
            resetTransaction();
            return CommandResult.last(response);
        }
        return CommandResult.more(response);
    }

    public int buttonsLow() {
        return buttonsLow & 0xFF;
    }

    public int buttonsHigh() {
        return buttonsHigh & 0xFF;
    }

    @Override
    public ControllerDeviceState copyState() {
        ControllerDeviceState state = new ControllerDeviceState();
        state.type = TYPE;
        state.values = new int[] {
            buttonsLow,
            buttonsHigh,
            leftX,
            leftY,
            rightX,
            rightY,
            analogMode ? 1 : 0,
            configMode ? 1 : 0,
            commandStartedInConfig ? 1 : 0,
            currentCommand,
            responseIndex,
            responseLength,
            selector,
            largeMotor,
            smallMotor ? 1 : 0
        };
        state.protocol = new int[rumbleMap.length + rumbleMapReply.length];
        System.arraycopy(rumbleMap, 0, state.protocol, 0, rumbleMap.length);
        System.arraycopy(rumbleMapReply, 0, state.protocol, rumbleMap.length,
            rumbleMapReply.length);
        return state;
    }

    @Override
    public void loadState(ControllerDeviceState state) {
        if (state == null) {
            return;
        }
        int[] values = state.values;
        buttonsLow = stateValue(values, 0, 0xFF);
        buttonsHigh = stateValue(values, 1, 0xFF);
        leftX = stateValue(values, 2, 0x80);
        leftY = stateValue(values, 3, 0x80);
        rightX = stateValue(values, 4, 0x80);
        rightY = stateValue(values, 5, 0x80);
        analogMode = stateValue(values, 6, 0) != 0;
        configMode = stateValue(values, 7, 0) != 0;
        commandStartedInConfig = stateValue(values, 8, 0) != 0;
        currentCommand = stateValue(values, 9, -1);
        responseIndex = stateValue(values, 10, 0);
        responseLength = stateValue(values, 11, 0);
        selector = stateValue(values, 12, 0);
        largeMotor = stateValue(values, 13, 0);
        smallMotor = stateValue(values, 14, 0) != 0;
        int[] protocol = state.protocol;
        rumbleMap = normalizedMap(protocol);
        if (protocol == null || protocol.length <= rumbleMap.length) {
            rumbleMapReply = EMPTY_RUMBLE_MAP.clone();
        } else {
            rumbleMapReply = normalizedMap(Arrays.copyOfRange(
                protocol,
                rumbleMap.length,
                protocol.length
            ));
        }
        dispatchRumble();
    }

    private boolean supportsCommand(int command) {
        if (configMode) {
            return command >= 0x40 && command <= 0x4F;
        }
        return command == 0x42 || command == 0x43;
    }

    private int responseLength(int command) {
        if (configMode) {
            return 6;
        }
        int normalLength = analogMode ? 6 : 2;
        if (command != 0x42 || Arrays.equals(rumbleMap, EMPTY_RUMBLE_MAP)) {
            return normalLength;
        }
        int lastMappedByte = -1;
        for (int i = 0; i < rumbleMap.length; i++) {
            if (rumbleMap[i] <= 1) {
                lastMappedByte = i;
            }
        }
        int mappedLength = lastMappedByte + 1;
        if ((mappedLength & 1) != 0) {
            mappedLength++;
        }
        return Math.max(normalLength, mappedLength);
    }

    private int modeId(int payloadLength) {
        if (analogMode) {
            return 0x70 | Math.clamp(payloadLength / 2, 1, 0xF);
        }
        return 0x40 | Math.clamp(payloadLength / 2, 1, 0xF);
    }

    private int responseByte(int index) {
        if (!commandStartedInConfig || currentCommand == 0x42) {
            return inputResponseByte(index, commandStartedInConfig || analogMode);
        }
        return switch (currentCommand) {
            case 0x43, 0x44, 0x49, 0x4A, 0x4B, 0x4E, 0x4F -> 0;
            case 0x45 -> switch (index) {
                case 0 -> 0x01;
                case 1 -> 0x02;
                case 2 -> analogMode ? 0x01 : 0x00;
                case 3 -> 0x02;
                case 4 -> 0x01;
                default -> 0;
            };
            case 0x46 -> variableResponseA(index);
            case 0x47 -> switch (index) {
                case 2 -> 0x02;
                case 4 -> 0x01;
                default -> 0;
            };
            case 0x48 -> index == 4 && selector <= 1 ? 0x01 : 0;
            case 0x4C -> index == 3 ? (selector == 0 ? 0x04 : selector == 1 ? 0x07 : 0) : 0;
            case 0x4D -> rumbleMapReply[index];
            default -> 0;
        };
    }

    private int inputResponseByte(int index, boolean includeAnalog) {
        return switch (index) {
            case 0 -> buttonsLow();
            case 1 -> buttonsHigh();
            case 2 -> includeAnalog ? rightX : 0xFF;
            case 3 -> includeAnalog ? rightY : 0xFF;
            case 4 -> includeAnalog ? leftX : 0xFF;
            case 5 -> includeAnalog ? leftY : 0xFF;
            default -> 0;
        };
    }

    private int variableResponseA(int index) {
        if (index < 2) {
            return 0;
        }
        if (selector == 0) {
            return switch (index) {
                case 2 -> 0x01;
                case 3 -> 0x02;
                case 5 -> 0x0A;
                default -> 0;
            };
        }
        if (selector == 1) {
            return switch (index) {
                case 2, 3, 4 -> 0x01;
                case 5 -> 0x14;
                default -> 0;
            };
        }
        return 0;
    }

    private void processCommandByte(int index, int value) {
        if (index == 0) {
            selector = value;
        }
        if (currentCommand == 0x42) {
            processRumbleByte(index, value);
            return;
        }
        if (currentCommand == 0x43 && index == 0) {
            configMode = value == 1;
            return;
        }
        if (configMode && currentCommand == 0x44 && index == 0 && value <= 1) {
            analogMode = value == 1;
            return;
        }
        if (configMode && currentCommand == 0x4D && index < rumbleMap.length) {
            rumbleMap[index] = value;
            if (value > 1 && value != 0xFF) {
                rumbleMap[index] = 0xFF;
            }
        }
    }

    private void processRumbleByte(int index, int value) {
        if (Arrays.equals(rumbleMap, EMPTY_RUMBLE_MAP)) {
            if (index == 0) {
                selector = value;
            } else if (index == 1) {
                setRumble(0, selector >= 0x40 && selector <= 0x7F && (value & 1) != 0);
            }
            return;
        }
        if (index >= rumbleMap.length) {
            return;
        }
        if (rumbleMap[index] == 0) {
            setRumble(largeMotor, (value & 1) != 0);
        } else if (rumbleMap[index] == 1) {
            setRumble(value, smallMotor);
        }
    }

    private void setRumble(int largeMotor, boolean smallMotor) {
        int clampedLarge = Math.clamp(largeMotor, 0, 255);
        if (this.largeMotor == clampedLarge && this.smallMotor == smallMotor) {
            return;
        }
        this.largeMotor = clampedLarge;
        this.smallMotor = smallMotor;
        dispatchRumble();
    }

    private void dispatchRumble() {
        if (rumbleHandler != null) {
            rumbleHandler.update(largeMotor, smallMotor);
        }
    }

    private void resetTransaction() {
        currentCommand = -1;
        responseIndex = 0;
        responseLength = 0;
        selector = 0;
    }

    private static int[] normalizedMap(int[] source) {
        int[] result = EMPTY_RUMBLE_MAP.clone();
        if (source != null) {
            System.arraycopy(source, 0, result, 0, Math.min(source.length, result.length));
        }
        return result;
    }

    private static int stateValue(int[] values, int index, int fallback) {
        return values != null && index >= 0 && index < values.length
            ? values[index]
            : fallback;
    }
}
