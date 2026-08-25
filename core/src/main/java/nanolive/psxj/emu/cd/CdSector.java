package nanolive.psxj.emu.cd;

public record CdSector(int minute, int second, int frame, byte[] raw2352, byte[] userData) {
}
