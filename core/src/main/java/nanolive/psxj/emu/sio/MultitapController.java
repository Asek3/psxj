package nanolive.psxj.emu.sio;

import java.util.Arrays;

/** Sony SCPH-1070 multitap controller side (four controller sockets). */
public final class MultitapController implements ControllerDevice {

    public static final String TYPE = "sony-scph-1070-multitap";
    public static final int SLOT_COUNT = 4;

    private static final int SLOT_RESPONSE_BYTES = 8;
    private static final int LONG_RESPONSE_BYTES = SLOT_COUNT * SLOT_RESPONSE_BYTES;

    private final ControllerDevice[] controllers = new ControllerDevice[SLOT_COUNT];

    private int selectedSlot = -1;
    private boolean methodOneLongPending;
    private boolean longTransaction;
    private int delegateExchangeIndex;
    private int longResponseIndex;
    private int[] longResponse = new int[0];

    public void setController(int slot, ControllerDevice controller) {
        validateSlot(slot);
        if (selectedSlot == slot && controllers[slot] != null) {
            controllers[slot].cancelTransaction();
            cancelTransaction();
        }
        controllers[slot] = controller;
    }

    public ControllerDevice controller(int slot) {
        validateSlot(slot);
        return controllers[slot];
    }

    @Override
    public String typeId() {
        return TYPE;
    }

    @Override
    public boolean beginTransaction(int address) {
        cancelActiveDelegate();
        int unsignedAddress = address & 0xFF;
        if (unsignedAddress < 1 || unsignedAddress > SLOT_COUNT) {
            cancelTransaction();
            return false;
        }
        selectedSlot = unsignedAddress - 1;
        ControllerDevice selected = controllers[selectedSlot];
        if (selected == null) {
            cancelTransaction();
            return false;
        }

        longTransaction = selectedSlot == 0 && methodOneLongPending;
        if (longTransaction) {
            methodOneLongPending = false;
            delegateExchangeIndex = -1;
            longResponseIndex = -1;
            longResponse = new int[0];
            return true;
        }
        delegateExchangeIndex = -1;
        return selected.beginTransaction(0x01);
    }

    @Override
    public CommandResult beginCommand(int command) {
        if (selectedSlot < 0) {
            return CommandResult.last(0xFF);
        }
        if (longTransaction) {
            if ((command & 0xFF) != 0x42) {
                cancelTransaction();
                return CommandResult.last(0xFF);
            }
            longResponse = captureAllSlots();
            longResponseIndex = -1;
            return CommandResult.more(0x80);
        }

        ControllerDevice selected = controllers[selectedSlot];
        if (selected == null) {
            cancelTransaction();
            return CommandResult.last(0xFF);
        }
        delegateExchangeIndex = -1;
        return selected.beginCommand(command);
    }

    @Override
    public CommandResult exchangeCommandByte(int value) {
        if (selectedSlot < 0) {
            return CommandResult.last(0xFF);
        }
        if (longTransaction) {
            if (longResponseIndex < 0) {
                longResponseIndex = 0;
                return CommandResult.more(0x5A);
            }
            if (longResponseIndex >= longResponse.length) {
                cancelTransaction();
                return CommandResult.last(0xFF);
            }
            int response = longResponse[longResponseIndex++];
            if (longResponseIndex >= longResponse.length) {
                cancelTransaction();
                return CommandResult.last(response);
            }
            return CommandResult.more(response);
        }

        ControllerDevice selected = controllers[selectedSlot];
        if (selected == null) {
            cancelTransaction();
            return CommandResult.last(0xFF);
        }
        if (selectedSlot == 0 && delegateExchangeIndex == -1 && (value & 0xFF) == 0x01) {
            methodOneLongPending = true;
        }
        CommandResult result = selected.exchangeCommandByte(value);
        delegateExchangeIndex++;
        if (result.finished()) {
            selectedSlot = -1;
        }
        return result;
    }

    @Override
    public void cancelTransaction() {
        cancelActiveDelegate();
        selectedSlot = -1;
        longTransaction = false;
        delegateExchangeIndex = -1;
        longResponseIndex = 0;
        longResponse = new int[0];
    }

    @Override
    public boolean lightgunIrqLine(BeamPosition position) {
        boolean asserted = false;
        for (ControllerDevice controller : controllers) {
            if (controller != null) {
                asserted |= controller.lightgunIrqLine(position);
            }
        }
        return asserted;
    }

    @Override
    public boolean samplesLightgunIrqLine() {
        for (ControllerDevice controller : controllers) {
            if (controller != null && controller.samplesLightgunIrqLine()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public ControllerDeviceState copyState() {
        ControllerDeviceState state = new ControllerDeviceState();
        state.type = TYPE;
        state.values = new int[] {
            selectedSlot,
            methodOneLongPending ? 1 : 0,
            longTransaction ? 1 : 0,
            delegateExchangeIndex,
            longResponseIndex
        };
        state.protocol = longResponse.clone();
        state.children = new ControllerDeviceState[SLOT_COUNT];
        for (int i = 0; i < SLOT_COUNT; i++) {
            state.children[i] = controllers[i] == null ? null : controllers[i].copyState();
        }
        return state;
    }

    @Override
    public void loadState(ControllerDeviceState state) {
        int[] values = state == null ? null : state.values;
        selectedSlot = value(values, 0, -1);
        methodOneLongPending = value(values, 1, 0) != 0;
        longTransaction = value(values, 2, 0) != 0;
        delegateExchangeIndex = value(values, 3, -1);
        longResponseIndex = value(values, 4, 0);
        longResponse = state == null || state.protocol == null
            ? new int[0]
            : state.protocol.clone();
        Arrays.fill(controllers, null);
        if (state != null && state.children != null) {
            for (int i = 0; i < Math.min(SLOT_COUNT, state.children.length); i++) {
                controllers[i] = ControllerDeviceFactory.restore(state.children[i]);
            }
        }
        if (selectedSlot < 0 || selectedSlot >= SLOT_COUNT
            || (!longTransaction && controllers[selectedSlot] == null)) {
            selectedSlot = -1;
            longTransaction = false;
        }
    }

    private int[] captureAllSlots() {
        int[] result = new int[LONG_RESPONSE_BYTES];
        Arrays.fill(result, 0xFF);
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            ControllerDevice controller = controllers[slot];
            if (controller == null || !controller.beginTransaction(0x01)) {
                continue;
            }
            CommandResult command = controller.beginCommand(0x42);
            if (command.finished()) {
                controller.cancelTransaction();
                continue;
            }
            int base = slot * SLOT_RESPONSE_BYTES;
            result[base] = command.response();
            for (int index = 1; index < SLOT_RESPONSE_BYTES; index++) {
                CommandResult next = controller.exchangeCommandByte(0x00);
                result[base + index] = next.response();
                if (next.finished()) {
                    break;
                }
            }
            controller.cancelTransaction();
        }
        return result;
    }

    private void cancelActiveDelegate() {
        if (!longTransaction && selectedSlot >= 0 && selectedSlot < SLOT_COUNT) {
            ControllerDevice selected = controllers[selectedSlot];
            if (selected != null) {
                selected.cancelTransaction();
            }
        }
    }

    private static int value(int[] values, int index, int fallback) {
        return values != null && index >= 0 && index < values.length
            ? values[index]
            : fallback;
    }

    private static void validateSlot(int slot) {
        if (slot < 0 || slot >= SLOT_COUNT) {
            throw new IllegalArgumentException("Multitap slot must be 0..3: " + slot);
        }
    }
}
