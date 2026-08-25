package nanolive.psxj.emu;

import nanolive.psxj.emu.core.Bus;
import nanolive.psxj.emu.cpu.R3000Cpu;
import nanolive.psxj.emu.devices.CdRomController;
import nanolive.psxj.emu.devices.DmaController;
import nanolive.psxj.emu.devices.Gpu;
import nanolive.psxj.emu.devices.InterruptController;
import nanolive.psxj.emu.devices.Mdec;
import nanolive.psxj.emu.devices.Sio1Controller;
import nanolive.psxj.emu.devices.SioController;
import nanolive.psxj.emu.devices.Spu;
import nanolive.psxj.emu.devices.TimerController;

final class SaveStateDto {

    int version;
    R3000Cpu.State cpu;
    int[] cop0;
    int[] gteData;
    int[] gteControl;
    String ram;
    String scratchpad;
    Bus.State bus;
    String vram;
    Gpu.State gpu;
    String spuRam;
    Spu.State spu;
    InterruptController.State interrupts;
    DmaController.State dma;
    TimerController.State timers;
    SioController.State sio;
    Sio1Controller.State sio1;
    Mdec.State mdec;
    CdRomController.State cdrom;
    CycleScheduler.State scheduler;
}
