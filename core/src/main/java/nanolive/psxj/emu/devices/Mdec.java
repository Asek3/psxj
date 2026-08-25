package nanolive.psxj.emu.devices;

import nanolive.psxj.emu.dma.DmaPort;

import java.util.Arrays;

/**
 * PlayStation macroblock decoder.
 *
 * <p>The input side is deliberately modelled as a stream of halfwords. The real
 * decoder consumes RLE data while a command is still being supplied, and can
 * therefore back-pressure DMA0 while an already decoded macroblock is waiting
 * in the output FIFO.</p>
 */
public final class Mdec implements DmaPort {

    private static final int DATA_IN_ENABLE = 1 << 30;
    private static final int DATA_OUT_ENABLE = 1 << 29;

    // Physical FIFO capacities are 400h input bytes and 300h output bytes.
    private static final int INPUT_FIFO_HALFWORDS = 0x400 / 2;
    private static final int OUTPUT_FIFO_WORDS = 0x300 / 4;
    private static final int DMA_INPUT_BLOCK_HALFWORDS = 0x20 * 2;
    private static final int MACROBLOCK_LATENCY = 448 * 6;

    private static final int[] ZAGZIG_TRANSPOSED = {
        0, 8, 1, 2, 9, 16, 24, 17,
        10, 3, 4, 11, 18, 25, 32, 40,
        33, 26, 19, 12, 5, 6, 13, 20,
        27, 34, 41, 48, 56, 49, 42, 35,
        28, 21, 14, 7, 15, 22, 29, 36,
        43, 50, 57, 58, 51, 44, 37, 30,
        23, 31, 38, 45, 52, 59, 60, 53,
        46, 39, 47, 54, 61, 62, 55, 63
    };

    private final IntFifo inputHalfwords = new IntFifo(INPUT_FIFO_HALFWORDS);
    private final IntFifo outputFifo = new IntFifo(OUTPUT_FIFO_WORDS);
    private final int[] luminanceQuantTable = new int[64];
    private final int[] colorQuantTable = new int[64];
    private final short[] scaleTable = new short[64];
    private final short[] idctIntermediate = new short[64];
    private final int[] monochrome4Output = new int[8];
    private final int[] monochrome8Output = new int[16];
    private final int[] color15Output = new int[128];
    private final int[] color24Output = new int[192];
    private final int[][] blocks = {
        new int[64], new int[64], new int[64],
        new int[64], new int[64], new int[64]
    };

    private final DmaPort inputDmaPort = new DmaPort() {
        @Override
        public int read() {
            return 0;
        }

        @Override
        public void write(int value) {
            writeParameter(value);
        }

        @Override
        public boolean dmaRequest() {
            return dataInRequestActive();
        }

        @Override
        public boolean dmaRequest(boolean fromRam) {
            return fromRam && dataInRequestActive();
        }

        @Override
        public boolean dmaRequestStableFor(boolean fromRam, int cycles) {
            return fromRam && inputDmaRequestStableFor(cycles);
        }
    };

    private int command;
    private int control;
    private int wordsRemaining;
    private int decodeCyclesRemaining;
    private int currentBlock;
    private int currentCoefficient = 64;
    private int currentQScale;
    private int idleWordCountField;
    private int lastDataWord;
    private int pendingOutputIndex;
    private int[] pendingOutput;
    private boolean awaitingCommand = true;

    public DmaPort inputDmaPort() {
        return inputDmaPort;
    }

    public void writeParameter(int value) {
        if (awaitingCommand) {
            latchCommand(value);
            return;
        }
        if (wordsRemaining <= 0 || inputHalfwords.size() > INPUT_FIFO_HALFWORDS - 2) {
            return;
        }

        inputHalfwords.addLast(value & 0xFFFF);
        inputHalfwords.addLast((value >>> 16) & 0xFFFF);
        wordsRemaining--;
        processInput();
    }

    public int readData() {
        if (outputFifo.isEmpty()) {
            return lastDataWord;
        }

        int value = outputFifo.removeFirst();
        lastDataWord = value;
        if (outputFifo.isEmpty()) {
            processInput();
        }
        return value;
    }

    public int status() {
        int result = 0;
        if (outputFifo.isEmpty()) {
            result |= 1 << 31;
        }
        if (dataInFifoFull()) {
            result |= 1 << 30;
        }
        if (!awaitingCommand) {
            result |= 1 << 29;
        }
        if (dataInRequestActive()) {
            result |= 1 << 28;
        }
        if (dataOutRequestActive()) {
            result |= 1 << 27;
        }
        result |= (command & 0x1E00_0000) >>> 2;
        result |= (statusCurrentBlock() & 0x7) << 16;
        result |= statusWordCountField();
        return result;
    }

    public void writeControl(int value) {
        if ((value & 0x8000_0000) != 0) {
            softReset();
        }
        control = value & (DATA_IN_ENABLE | DATA_OUT_ENABLE);
    }

    public void tick(int cycles) {
        if (cycles == 1 && decodeCyclesRemaining > 1) {
            decodeCyclesRemaining--;
            return;
        }
        int remaining = Math.max(0, cycles);
        while (true) {
            if (decodeCyclesRemaining == 0) {
                publishPendingOutput();
                if (pendingOutput != null) {
                    return;
                }
                boolean wasAwaiting = awaitingCommand;
                int oldInputSize = inputHalfwords.size();
                int oldBlock = currentBlock;
                processInput();
                if (decodeCyclesRemaining == 0
                    && wasAwaiting == awaitingCommand
                    && oldInputSize == inputHalfwords.size()
                    && oldBlock == currentBlock) {
                    return;
                }
            }

            if (remaining <= 0) {
                return;
            }
            int elapsed = Math.min(remaining, decodeCyclesRemaining);
            decodeCyclesRemaining -= elapsed;
            remaining -= elapsed;
        }
    }

    public boolean clockActive() {
        return decodeCyclesRemaining > 0
            || (pendingOutput != null && outputFifo.size() < OUTPUT_FIFO_WORDS);
    }

    @Override
    public int read() {
        return readData();
    }

    @Override
    public void write(int value) {
        writeParameter(value);
    }

    @Override
    public boolean dmaRequest() {
        return dataOutRequestActive();
    }

    @Override
    public boolean dmaRequest(boolean fromRam) {
        return !fromRam && dataOutRequestActive();
    }

    @Override
    public boolean dmaRequestStableFor(boolean fromRam, int cycles) {
        if (fromRam) {
            return false;
        }
        if (dataOutRequestActive()) {
            return true;
        }
        return decodeCyclesRemaining == 0 || decodeCyclesRemaining > cycles;
    }

    private void latchCommand(int value) {
        command = value;
        if ((command >>> 29) == 1) {
            outputFifo.clear();
            pendingOutput = null;
            pendingOutputIndex = 0;
        }
        decodeCyclesRemaining = 0;
        inputHalfwords.clear();
        resetDecoder();

        int opcode = command >>> 29;
        if (opcode == 0 || opcode > 3) {
            wordsRemaining = 0;
            idleWordCountField = command & 0xFFFF;
            awaitingCommand = true;
            return;
        }

        wordsRemaining = commandWordCount(command);
        awaitingCommand = false;
        if (wordsRemaining == 0) {
            processInput();
        }
    }

    private int commandWordCount(int value) {
        return switch (value >>> 29) {
            case 1 -> value & 0xFFFF;
            case 2 -> (value & 0x1) != 0 ? 32 : 16;
            case 3 -> 32;
            default -> 0;
        };
    }

    private void processInput() {
        if (awaitingCommand || pendingOutput != null || decodeCyclesRemaining > 0) {
            return;
        }

        switch (command >>> 29) {
            case 1 -> processDecodeCommand();
            case 2 -> {
                if (wordsRemaining == 0 && inputHalfwords.size() >= commandWordCount(command) * 2) {
                    loadQuantTables();
                    finishCommand();
                }
            }
            case 3 -> {
                if (wordsRemaining == 0 && inputHalfwords.size() >= 64) {
                    loadScaleTable();
                    finishCommand();
                }
            }
            default -> finishCommand();
        }
    }

    private void processDecodeCommand() {
        int blocksPerMacroblock = outputDepth() < 2 ? 1 : 6;
        while (currentBlock < blocksPerMacroblock) {
            if (!decodeBlock(blocks[currentBlock], quantTableForCurrentBlock())) {
                if (wordsRemaining == 0 && inputHalfwords.isEmpty()) {
                    finishCommand();
                }
                return;
            }

            applyIdct(blocks[currentBlock]);
            currentBlock++;
        }
        if (!outputFifo.isEmpty()) {
            return;
        }
        pendingOutput = outputDepth() < 2
            ? buildMonochromeOutput()
            : buildColorMacroblockOutput();
        pendingOutputIndex = 0;
        decodeCyclesRemaining = MACROBLOCK_LATENCY;
        resetDecoder();
    }

    private int[] quantTableForCurrentBlock() {
        return outputDepth() >= 2 && currentBlock < 2 ? colorQuantTable : luminanceQuantTable;
    }

    private boolean decodeBlock(int[] block, int[] quantTable) {
        if (currentCoefficient == 64) {
            int first = nextNonPaddingHalfword();
            if (first < 0) {
                return false;
            }

            Arrays.fill(block, 0);
            currentCoefficient = 0;
            currentQScale = (first >>> 10) & 0x3F;
            int value = signed10(first);
            int coefficient;
            if (currentQScale == 0) {
                coefficient = value << 5;
            } else {
                coefficient = (value * quantTable[0] << 4) + coefficientBias(value);
            }
            int destination = currentQScale == 0
                ? directCoefficientIndex(0)
                : ZAGZIG_TRANSPOSED[0];
            block[destination] = Math.clamp(coefficient, -0x4000, 0x3FFF);
        }

        while (!inputHalfwords.isEmpty()) {
            int code = inputHalfwords.removeFirst();
            currentCoefficient += ((code >>> 10) & 0x3F) + 1;
            if (currentCoefficient < 64) {
                int value = signed10(code);
                int coefficient = currentQScale == 0
                    ? value << 5
                    : (((value * currentQScale * quantTable[currentCoefficient]) >> 3) << 4)
                        + coefficientBias(value);
                int destination = currentQScale == 0
                    ? directCoefficientIndex(currentCoefficient)
                    : ZAGZIG_TRANSPOSED[currentCoefficient];
                block[destination] =
                    Math.clamp(coefficient, -0x4000, 0x3FFF);
            }
            if (currentCoefficient >= 63) {
                currentCoefficient = 64;
                return true;
            }
        }
        return false;
    }

    private static int directCoefficientIndex(int coefficient) {
        return ((coefficient & 7) << 3) | (coefficient >>> 3);
    }

    private int nextNonPaddingHalfword() {
        while (!inputHalfwords.isEmpty()) {
            int value = inputHalfwords.removeFirst();
            if (value != 0xFE00) {
                return value;
            }
        }
        return -1;
    }

    private int coefficientBias(int value) {
        if (value == 0) {
            return 0;
        }
        return value < 0 ? 8 : -8;
    }

    private void applyIdct(int[] block) {
        for (int x = 0; x < 8; x++) {
            int sourceOffset = x * 8;
            int nonZero = block[sourceOffset]
                | block[sourceOffset + 1]
                | block[sourceOffset + 2]
                | block[sourceOffset + 3]
                | block[sourceOffset + 4]
                | block[sourceOffset + 5]
                | block[sourceOffset + 6]
                | block[sourceOffset + 7];
            if (nonZero == 0) {
                for (int y = 0; y < 8; y++) {
                    idctIntermediate[y * 8 + x] = 0;
                }
                continue;
            }
            for (int y = 0; y < 8; y++) {
                idctIntermediate[y * 8 + x] = idctRow(
                    block, sourceOffset, scaleTable, y * 8);
            }
        }

        for (int x = 0; x < 8; x++) {
            int sourceOffset = x * 8;
            for (int y = 0; y < 8; y++) {
                int value = idctRow(
                    idctIntermediate, sourceOffset, scaleTable, y * 8);
                block[x * 8 + y] = Math.clamp(signExtend9(value), -128, 127);
            }
        }
    }

    private short idctRow(int[] source, int sourceOffset, short[] matrix, int matrixOffset) {
        long sum = 0;
        for (int i = 0; i < 8; i++) {
            sum += (long) source[sourceOffset + i] * matrix[matrixOffset + i];
        }
        return (short) ((sum + 0x2_0000L) >> 18);
    }

    private short idctRow(short[] source, int sourceOffset, short[] matrix, int matrixOffset) {
        long sum = 0;
        for (int i = 0; i < 8; i++) {
            sum += (long) source[sourceOffset + i] * matrix[matrixOffset + i];
        }
        return (short) ((sum + 0x2_0000L) >> 18);
    }

    private int[] buildMonochromeOutput() {
        int depth = outputDepth();
        if (depth == 0) {
            for (int word = 0; word < monochrome4Output.length; word++) {
                int packed = 0;
                for (int byteIndex = 0; byteIndex < 4; byteIndex++) {
                    int sample = (word * 4 + byteIndex) * 2;
                    int low = outputByte(blocks[0][sample]) >>> 4;
                    int high = outputByte(blocks[0][sample + 1]) >>> 4;
                    packed |= (low | (high << 4)) << (byteIndex * 8);
                }
                monochrome4Output[word] = packed;
            }
            return monochrome4Output;
        }
        for (int word = 0; word < monochrome8Output.length; word++) {
            int packed = 0;
            for (int byteIndex = 0; byteIndex < 4; byteIndex++) {
                packed |= outputByte(blocks[0][word * 4 + byteIndex]) << (byteIndex * 8);
            }
            monochrome8Output[word] = packed;
        }
        return monochrome8Output;
    }

    private int[] buildColorMacroblockOutput() {
        int depth = outputDepth();
        boolean signed = outputSigned();
        int bit15 = outputBit15() ? 0x8000 : 0;
        boolean output24Bit = depth == 2;
        int outputWord = 0;
        int packed = 0;
        int shift = 0;
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int pixel = colorPixel(y, x, signed, bit15, output24Bit);
                if (output24Bit) {
                    packed |= (pixel & 0xFF) << shift;
                    shift += 8;
                    if (shift == 32) {
                        color24Output[outputWord++] = packed;
                        packed = 0;
                        shift = 0;
                    }
                    packed |= ((pixel >>> 8) & 0xFF) << shift;
                    shift += 8;
                    if (shift == 32) {
                        color24Output[outputWord++] = packed;
                        packed = 0;
                        shift = 0;
                    }
                    packed |= ((pixel >>> 16) & 0xFF) << shift;
                    shift += 8;
                    if (shift == 32) {
                        color24Output[outputWord++] = packed;
                        packed = 0;
                        shift = 0;
                    }
                } else {
                    if ((x & 1) == 0) {
                        packed = pixel;
                    } else {
                        color15Output[outputWord++] = packed | (pixel << 16);
                    }
                }
            }
        }
        return output24Bit ? color24Output : color15Output;
    }

    private int colorPixel(int y, int x, boolean signed, int bit15, boolean output24Bit) {
        int luminanceRow = (y & 7) * 8;
        int chromaIndex = (y >>> 1) * 8 + (x >>> 1);
        int luminanceBlock = ((y >>> 3) << 1) + 2 + (x >>> 3);
        int luminance = blocks[luminanceBlock][luminanceRow + (x & 7)];
        int cr = blocks[0][chromaIndex];
        int cb = blocks[1][chromaIndex];
        int r = colorComponent(luminance + ((359 * cr + 0x80) >> 8));
        int gDelta = (((-88 * cb) & ~0x1F) + ((-183 * cr) & ~0x07) + 0x80) >> 8;
        int g = colorComponent(luminance + gDelta);
        int b = colorComponent(luminance + ((454 * cb + 0x80) >> 8));
        if (output24Bit) {
            return outputByteClamped(r, signed)
                | (outputByteClamped(g, signed) << 8)
                | (outputByteClamped(b, signed) << 16);
        }
        return packed5Clamped(r, signed)
            | (packed5Clamped(g, signed) << 5)
            | (packed5Clamped(b, signed) << 10)
            | bit15;
    }

    private int colorComponent(int value) {
        return Math.clamp(signExtend9(value), -128, 127);
    }

    private int outputByte(int signedSample) {
        int value = Math.clamp(signedSample, -128, 127);
        return outputByteClamped(value, outputSigned());
    }

    private static int outputByteClamped(int value, boolean signed) {
        return signed ? value & 0xFF : (value + 0x80) & 0xFF;
    }

    private static int packed5Clamped(int signedSample, boolean signed) {
        int value = outputByteClamped(signedSample, signed);
        return Math.min((value + 4) >>> 3, 0x1F);
    }

    private void publishPendingOutput() {
        if (pendingOutput == null) {
            return;
        }
        while (pendingOutputIndex < pendingOutput.length && outputFifo.size() < OUTPUT_FIFO_WORDS) {
            outputFifo.addLast(pendingOutput[pendingOutputIndex++]);
        }
        if (pendingOutputIndex == pendingOutput.length) {
            pendingOutput = null;
            pendingOutputIndex = 0;
            processInput();
        }
    }

    private void loadQuantTables() {
        for (int i = 0; i < 64; i += 2) {
            int packed = inputHalfwords.removeFirst();
            luminanceQuantTable[i] = packed & 0xFF;
            luminanceQuantTable[i + 1] = (packed >>> 8) & 0xFF;
        }
        if ((command & 1) != 0) {
            for (int i = 0; i < 64; i += 2) {
                int packed = inputHalfwords.removeFirst();
                colorQuantTable[i] = packed & 0xFF;
                colorQuantTable[i + 1] = (packed >>> 8) & 0xFF;
            }
        }
    }

    private void loadScaleTable() {
        short[] supplied = new short[64];
        for (int i = 0; i < supplied.length; i++) {
            supplied[i] = (short) (inputHalfwords.removeFirst() & 0xFFF8);
        }
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                scaleTable[y * 8 + x] = supplied[x * 8 + y];
            }
        }
    }

    private void finishCommand() {
        inputHalfwords.clear();
        wordsRemaining = 0;
        decodeCyclesRemaining = 0;
        idleWordCountField = 0xFFFF;
        awaitingCommand = true;
        resetDecoder();
    }

    private void softReset() {
        inputHalfwords.clear();
        outputFifo.clear();
        pendingOutput = null;
        command = 0;
        wordsRemaining = 0;
        decodeCyclesRemaining = 0;
        idleWordCountField = 0;
        lastDataWord = 0;
        pendingOutputIndex = 0;
        awaitingCommand = true;
        resetDecoder();
    }

    private void resetDecoder() {
        currentBlock = 0;
        currentCoefficient = 64;
        currentQScale = 0;
    }

    private int outputDepth() {
        return (command >>> 27) & 0x3;
    }

    private boolean outputSigned() {
        return ((command >>> 26) & 0x1) != 0;
    }

    private boolean outputBit15() {
        return ((command >>> 25) & 0x1) != 0;
    }

    private int statusCurrentBlock() {
        return (currentBlock + 4) % 6;
    }

    private int statusWordCountField() {
        if (awaitingCommand) {
            return idleWordCountField;
        }
        return wordsRemaining > 0 ? (wordsRemaining - 1) & 0xFFFF : 0xFFFF;
    }

    private boolean dataInFifoFull() {
        return inputHalfwords.size() >= INPUT_FIFO_HALFWORDS;
    }

    private boolean dataInRequestActive() {
        return (control & DATA_IN_ENABLE) != 0
            && !awaitingCommand
            && wordsRemaining > 0
            && INPUT_FIFO_HALFWORDS - inputHalfwords.size() >= DMA_INPUT_BLOCK_HALFWORDS;
    }

    private boolean inputDmaRequestStableFor(int cycles) {
        if (dataInRequestActive()) {
            return true;
        }
        if (decodeCyclesRemaining > 0) {
            return decodeCyclesRemaining > cycles;
        }
        return pendingOutput == null || outputFifo.size() >= OUTPUT_FIFO_WORDS;
    }

    private boolean dataOutRequestActive() {
        return (control & DATA_OUT_ENABLE) != 0
            && !outputFifo.isEmpty();
    }

    private int signed10(int value) {
        return (value << 22) >> 22;
    }

    private int signExtend9(int value) {
        return (value << 23) >> 23;
    }

    public State copyState() {
        State state = new State();
        state.inputHalfwords = inputHalfwords.toArray();
        state.outputFifo = outputFifo.toArray();
        state.luminanceQuantTable = luminanceQuantTable.clone();
        state.colorQuantTable = colorQuantTable.clone();
        state.scaleTable = scaleTable.clone();
        state.blocks = cloneBlocks(blocks);
        state.pendingOutput = pendingOutput == null ? null : pendingOutput.clone();
        state.command = command;
        state.control = control;
        state.wordsRemaining = wordsRemaining;
        state.decodeCyclesRemaining = decodeCyclesRemaining;
        state.currentBlock = currentBlock;
        state.currentCoefficient = currentCoefficient;
        state.currentQScale = currentQScale;
        state.idleWordCountField = idleWordCountField;
        state.lastDataWord = lastDataWord;
        state.pendingOutputIndex = pendingOutputIndex;
        state.awaitingCommand = awaitingCommand;
        return state;
    }

    public void loadState(State state) {
        if (state == null) {
            return;
        }

        inputHalfwords.clear();
        inputHalfwords.load(state.inputHalfwords);
        outputFifo.load(state.outputFifo);
        copyInto(state.luminanceQuantTable, luminanceQuantTable);
        copyInto(state.colorQuantTable, colorQuantTable);
        if (state.scaleTable != null) {
            System.arraycopy(state.scaleTable, 0, scaleTable, 0,
                Math.min(state.scaleTable.length, scaleTable.length));
        }
        if (state.blocks != null) {
            for (int i = 0; i < Math.min(blocks.length, state.blocks.length); i++) {
                copyInto(state.blocks[i], blocks[i]);
            }
        }
        pendingOutput = state.pendingOutput == null ? null : state.pendingOutput.clone();
        command = state.command;
        control = state.control;
        wordsRemaining = state.wordsRemaining;
        decodeCyclesRemaining = state.decodeCyclesRemaining;
        currentBlock = state.currentBlock;
        currentCoefficient = state.currentCoefficient;
        currentQScale = state.currentQScale;
        idleWordCountField = state.idleWordCountField;
        lastDataWord = state.lastDataWord;
        pendingOutputIndex = Math.max(0, state.pendingOutputIndex);
        awaitingCommand = state.awaitingCommand;
    }

    private static void copyInto(int[] source, int[] target) {
        if (source != null) {
            System.arraycopy(source, 0, target, 0, Math.min(source.length, target.length));
        }
    }

    private static final class IntFifo {
        private int[] values;
        private int head;
        private int size;

        private IntFifo(int capacity) {
            values = new int[Math.max(1, capacity)];
        }

        private int size() {
            return size;
        }

        private boolean isEmpty() {
            return size == 0;
        }

        private void clear() {
            head = 0;
            size = 0;
        }

        private void addLast(int value) {
            if (size == values.length) {
                throw new IllegalStateException("MDEC FIFO overflow");
            }
            int tail = head + size;
            if (tail >= values.length) {
                tail -= values.length;
            }
            values[tail] = value;
            size++;
        }

        private int removeFirst() {
            if (size == 0) {
                throw new IllegalStateException("MDEC FIFO underflow");
            }
            int value = values[head];
            head++;
            if (head == values.length) {
                head = 0;
            }
            size--;
            return value;
        }

        private int[] toArray() {
            int[] result = new int[size];
            int index = head;
            for (int i = 0; i < size; i++) {
                result[i] = values[index++];
                if (index == values.length) {
                    index = 0;
                }
            }
            return result;
        }

        private void load(int[] source) {
            clear();
            if (source == null || source.length == 0) {
                return;
            }
            if (source.length > values.length) {
                values = new int[source.length];
            }
            System.arraycopy(source, 0, values, 0, source.length);
            size = source.length;
        }
    }

    private static int[][] cloneBlocks(int[][] source) {
        int[][] result = new int[source.length][];
        for (int i = 0; i < source.length; i++) {
            result[i] = source[i].clone();
        }
        return result;
    }

    public static final class State {
        int[] inputHalfwords;
        int[] outputFifo;
        int[] outputBlockFifo;
        int[] luminanceQuantTable;
        int[] colorQuantTable;
        short[] scaleTable;
        int[][] blocks;
        int[] pendingOutput;
        int command;
        int control;
        int wordsRemaining;
        int decodeCyclesRemaining;
        int currentBlock;
        int currentCoefficient;
        int currentQScale;
        int idleWordCountField;
        int lastDataWord;
        int outputWordsRead;
        int outputWordsPerBlock;
        int pendingOutputIndex;
        int pendingOutputBlock;
        int processingStatusBlock;
        boolean awaitingCommand;
    }
}
