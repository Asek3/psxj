package nanolive.psxj.platform.input;

import nanolive.psxj.emu.api.GamepadBackend;
import nanolive.psxj.emu.devices.SioController;
import nanolive.psxj.util.Log;

import java.nio.IntBuffer;
import java.util.function.BiConsumer;

import static org.lwjgl.sdl.SDLError.SDL_GetError;
import static org.lwjgl.sdl.SDLGamepad.*;
import static org.lwjgl.sdl.SDLHints.SDL_HINT_JOYSTICK_ALLOW_BACKGROUND_EVENTS;
import static org.lwjgl.sdl.SDLHints.SDL_SetHint;
import static org.lwjgl.sdl.SDLInit.*;
import static org.lwjgl.sdl.SDLJoystick.*;
import static org.lwjgl.sdl.SDLStdinc.SDL_free;

/**
 * SDL's normalized gamepad layer covers XInput, DualShock/DualSense, Switch
 * controllers and SDL's community mapping database. Unknown HID devices fall
 * back to the low-level joystick API with a conventional positional mapping.
 */
public final class SdlGamepadBackend implements GamepadBackend {

    private static final long RECONNECT_INTERVAL_NANOS = 250_000_000L;
    private static final int RUMBLE_DURATION_MS = 120;

    private final int deadZone;
    private final boolean rumbleEnabled;
    private final BiConsumer<Boolean, String> connectionListener;
    private boolean initialized;
    private long handle;
    private boolean standardizedGamepad;
    private int instanceId = -1;
    private long nextReconnectNanos;

    public SdlGamepadBackend(int deadZonePercent) {
        this(deadZonePercent, true, null);
    }

    public SdlGamepadBackend(int deadZonePercent, boolean rumbleEnabled) {
        this(deadZonePercent, rumbleEnabled, null);
    }

    public SdlGamepadBackend(int deadZonePercent, boolean rumbleEnabled,
                             BiConsumer<Boolean, String> connectionListener) {
        deadZone = Math.clamp(deadZonePercent, 0, 50) * 32767 / 100;
        this.rumbleEnabled = rumbleEnabled;
        this.connectionListener = connectionListener != null ? connectionListener : (connected, name) -> { };
    }

    @Override
    public void open() {
        if (initialized) {
            return;
        }
        SDL_SetHint(SDL_HINT_JOYSTICK_ALLOW_BACKGROUND_EVENTS, "1");
        if (!SDL_InitSubSystem(SDL_INIT_GAMEPAD)) {
            throw new IllegalStateException("SDL gamepad init failed: " + SDL_GetError());
        }
        initialized = true;
        nextReconnectNanos = 0L;
        openFirstAvailable();
    }

    @Override
    public void poll(StateSink sink) {
        if (!initialized || sink == null) {
            return;
        }
        SDL_UpdateGamepads();
        SDL_UpdateJoysticks();
        if (handle != 0L && !connected()) {
            closeDevice(true);
        }
        if (handle == 0L) {
            long now = System.nanoTime();
            if (now >= nextReconnectNanos) {
                nextReconnectNanos = now + RECONNECT_INTERVAL_NANOS;
                openFirstAvailable();
            }
        }
        if (handle == 0L) {
            sink.update(0, 0x80, 0x80, 0x80, 0x80);
            return;
        }

        if (standardizedGamepad) {
            pollStandardGamepad(sink);
        } else {
            pollGenericJoystick(sink);
        }
    }

    @Override
    public void rumble(int largeMotor, boolean smallMotor) {
        if (!rumbleEnabled || handle == 0L) {
            return;
        }
        short lowFrequency = (short) (Math.clamp(largeMotor, 0, 255) * 0xFFFF / 255);
        short highFrequency = (short) (smallMotor ? 0xFFFF : 0);
        if (standardizedGamepad) {
            SDL_RumbleGamepad(handle, lowFrequency, highFrequency, RUMBLE_DURATION_MS);
        } else {
            SDL_RumbleJoystick(handle, lowFrequency, highFrequency, RUMBLE_DURATION_MS);
        }
    }

    @Override
    public void close() {
        closeDevice(false);
        if (initialized) {
            SDL_QuitSubSystem(SDL_INIT_GAMEPAD);
            initialized = false;
        }
    }

    private void pollStandardGamepad(StateSink sink) {
        int mask = 0;
        mask = button(mask, SDL_GAMEPAD_BUTTON_DPAD_UP, SioController.PAD_UP);
        mask = button(mask, SDL_GAMEPAD_BUTTON_DPAD_RIGHT, SioController.PAD_RIGHT);
        mask = button(mask, SDL_GAMEPAD_BUTTON_DPAD_DOWN, SioController.PAD_DOWN);
        mask = button(mask, SDL_GAMEPAD_BUTTON_DPAD_LEFT, SioController.PAD_LEFT);
        mask = button(mask, SDL_GAMEPAD_BUTTON_START, SioController.PAD_START);
        mask = button(mask, SDL_GAMEPAD_BUTTON_BACK, SioController.PAD_SELECT);
        mask = button(mask, SDL_GAMEPAD_BUTTON_LEFT_STICK, SioController.PAD_L3);
        mask = button(mask, SDL_GAMEPAD_BUTTON_RIGHT_STICK, SioController.PAD_R3);
        mask = button(mask, SDL_GAMEPAD_BUTTON_LEFT_SHOULDER, SioController.PAD_L1);
        mask = button(mask, SDL_GAMEPAD_BUTTON_RIGHT_SHOULDER, SioController.PAD_R1);
        mask = button(mask, SDL_GAMEPAD_BUTTON_SOUTH, SioController.PAD_CROSS);
        mask = button(mask, SDL_GAMEPAD_BUTTON_EAST, SioController.PAD_CIRCLE);
        mask = button(mask, SDL_GAMEPAD_BUTTON_WEST, SioController.PAD_SQUARE);
        mask = button(mask, SDL_GAMEPAD_BUTTON_NORTH, SioController.PAD_TRIANGLE);

        int leftX = SDL_GetGamepadAxis(handle, SDL_GAMEPAD_AXIS_LEFTX);
        int leftY = SDL_GetGamepadAxis(handle, SDL_GAMEPAD_AXIS_LEFTY);
        int rightX = SDL_GetGamepadAxis(handle, SDL_GAMEPAD_AXIS_RIGHTX);
        int rightY = SDL_GetGamepadAxis(handle, SDL_GAMEPAD_AXIS_RIGHTY);
        if (SDL_GetGamepadAxis(handle, SDL_GAMEPAD_AXIS_LEFT_TRIGGER) > deadZone) {
            mask |= SioController.PAD_L2;
        }
        if (SDL_GetGamepadAxis(handle, SDL_GAMEPAD_AXIS_RIGHT_TRIGGER) > deadZone) {
            mask |= SioController.PAD_R2;
        }
        sink.update(
            mask,
            axisByte(applyDeadZone(leftX)),
            axisByte(applyDeadZone(leftY)),
            axisByte(applyDeadZone(rightX)),
            axisByte(applyDeadZone(rightY))
        );
    }

    private void pollGenericJoystick(StateSink sink) {
        int buttons = SDL_GetNumJoystickButtons(handle);
        int mask = 0;
        mask = joystickButton(mask, buttons, 0, SioController.PAD_CROSS);
        mask = joystickButton(mask, buttons, 1, SioController.PAD_CIRCLE);
        mask = joystickButton(mask, buttons, 2, SioController.PAD_SQUARE);
        mask = joystickButton(mask, buttons, 3, SioController.PAD_TRIANGLE);
        mask = joystickButton(mask, buttons, 4, SioController.PAD_L1);
        mask = joystickButton(mask, buttons, 5, SioController.PAD_R1);
        mask = joystickButton(mask, buttons, 6, SioController.PAD_L2);
        mask = joystickButton(mask, buttons, 7, SioController.PAD_R2);
        mask = joystickButton(mask, buttons, 8, SioController.PAD_SELECT);
        mask = joystickButton(mask, buttons, 9, SioController.PAD_START);
        mask = joystickButton(mask, buttons, 10, SioController.PAD_L3);
        mask = joystickButton(mask, buttons, 11, SioController.PAD_R3);

        int axes = SDL_GetNumJoystickAxes(handle);
        int leftX = axis(handle, axes, 0);
        int leftY = axis(handle, axes, 1);
        int rightX = axis(handle, axes, 2);
        int rightY = axis(handle, axes, 3);
        if (SDL_GetNumJoystickHats(handle) > 0) {
            int hat = Byte.toUnsignedInt(SDL_GetJoystickHat(handle, 0));
            if ((hat & SDL_HAT_UP) != 0) mask |= SioController.PAD_UP;
            if ((hat & SDL_HAT_RIGHT) != 0) mask |= SioController.PAD_RIGHT;
            if ((hat & SDL_HAT_DOWN) != 0) mask |= SioController.PAD_DOWN;
            if ((hat & SDL_HAT_LEFT) != 0) mask |= SioController.PAD_LEFT;
        }
        sink.update(
            mask,
            axisByte(applyDeadZone(leftX)),
            axisByte(applyDeadZone(leftY)),
            axisByte(applyDeadZone(rightX)),
            axisByte(applyDeadZone(rightY))
        );
    }

    private int button(int mask, int button, int padMask) {
        return SDL_GetGamepadButton(handle, button) ? mask | padMask : mask;
    }

    private int joystickButton(int mask, int buttonCount, int button, int padMask) {
        return button < buttonCount && SDL_GetJoystickButton(handle, button) ? mask | padMask : mask;
    }

    private int applyDeadZone(int value) {
        int magnitude = Math.abs(value);
        if (magnitude <= deadZone) {
            return 0;
        }
        int scaled = (magnitude - deadZone) * 32767 / Math.max(1, 32767 - deadZone);
        return value < 0 ? -Math.min(scaled, 32768) : Math.min(scaled, 32767);
    }

    private static int axis(long joystick, int axisCount, int index) {
        return index < axisCount ? SDL_GetJoystickAxis(joystick, index) : 0;
    }

    private static int axisByte(int value) {
        return Math.clamp((value + 32768) >>> 8, 0, 255);
    }

    private boolean connected() {
        return standardizedGamepad
            ? SDL_GamepadConnected(handle)
            : SDL_JoystickConnected(handle);
    }

    private void openFirstAvailable() {
        SDL_UpdateGamepads();
        SDL_UpdateJoysticks();
        IntBuffer gamepads = SDL_GetGamepads();
        try {
            if (gamepads != null) {
                for (int i = 0; i < gamepads.remaining(); i++) {
                    int id = gamepads.get(gamepads.position() + i);
                    long opened = SDL_OpenGamepad(id);
                    if (opened != 0L) {
                        handle = opened;
                        instanceId = id;
                        standardizedGamepad = true;
                        Log.info("Gamepad connected: " + safeName(SDL_GetGamepadName(opened))
                            + " (SDL mapping, id=" + id + ")");
                        connectionListener.accept(true, safeName(SDL_GetGamepadName(opened)));
                        return;
                    }
                }
            }
        } finally {
            if (gamepads != null) {
                SDL_free(gamepads);
            }
        }

        IntBuffer joysticks = SDL_GetJoysticks();
        try {
            if (joysticks == null) {
                return;
            }
            for (int i = 0; i < joysticks.remaining(); i++) {
                int id = joysticks.get(joysticks.position() + i);
                if (SDL_IsGamepad(id)) {
                    continue;
                }
                long opened = SDL_OpenJoystick(id);
                if (opened != 0L) {
                    handle = opened;
                    instanceId = id;
                    standardizedGamepad = false;
                    Log.info("Joystick connected: " + safeName(SDL_GetJoystickName(opened))
                        + " (generic fallback, id=" + id + ")");
                    connectionListener.accept(true, safeName(SDL_GetJoystickName(opened)));
                    return;
                }
            }
        } finally {
            if (joysticks != null) {
                SDL_free(joysticks);
            }
        }
    }

    private void closeDevice(boolean notify) {
        if (handle == 0L) {
            return;
        }
        String name = standardizedGamepad
            ? safeName(SDL_GetGamepadName(handle)) : safeName(SDL_GetJoystickName(handle));
        rumble(0, false);
        if (standardizedGamepad) {
            SDL_CloseGamepad(handle);
        } else {
            SDL_CloseJoystick(handle);
        }
        Log.info("Gamepad disconnected: id=" + instanceId);
        if (notify) connectionListener.accept(false, name);
        handle = 0L;
        instanceId = -1;
        standardizedGamepad = false;
    }

    private static String safeName(String name) {
        return name == null || name.isBlank() ? "Unknown controller" : name;
    }
}
