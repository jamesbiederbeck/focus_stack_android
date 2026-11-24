package design.antics.focusstack;

import android.graphics.Bitmap;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * Minimal animated-GIF89a writer: plain Java, no dependencies (this repo's
 * Make-only build has no androidx/Gradle to pull one in). Each frame gets
 * its own 256-colour local palette (median cut on a 15-bit histogram) and
 * Floyd-Steinberg dithering, since consecutive focus-bracket frames can
 * differ a lot in colour and a single global palette would band badly.
 * Frames are written as they are added, so only one is resident at a time.
 * All frames must be the size the encoder was constructed with.
 */
final class GifEncoder {

    private final OutputStream out;
    private final int width, height;
    private boolean headerWritten;

    GifEncoder(OutputStream out, int width, int height) {
        this.out = out;
        this.width = width;
        this.height = height;
    }

    /** @param delayCs frame duration in hundredths of a second */
    void addFrame(Bitmap bitmap, int delayCs) throws IOException {
        if (bitmap.getWidth() != width || bitmap.getHeight() != height) {
            throw new IllegalArgumentException("frame size mismatch");
        }
        int[] argb = new int[width * height];
        bitmap.getPixels(argb, 0, width, 0, 0, width, height);
        addFrame(argb, delayCs);
    }

    /** @param argb width*height pixels, row-major (alpha ignored) */
    void addFrame(int[] argb, int delayCs) throws IOException {
        if (!headerWritten) {
            writeHeader();
            headerWritten = true;
        }

        int[] palette = buildPalette(argb);
        byte[] indices = quantize(argb, palette);

        // Graphic control extension: disposal = leave in place, no transparency.
        out.write(0x21);
        out.write(0xF9);
        out.write(4);
        out.write(1 << 2);
        writeShort(delayCs);
        out.write(0);
        out.write(0);

        // Image descriptor with a 256-entry local colour table.
        out.write(0x2C);
        writeShort(0);
        writeShort(0);
        writeShort(width);
        writeShort(height);
        out.write(0x80 | 7);
        for (int c : palette) {
            out.write((c >> 16) & 0xFF);
            out.write((c >> 8) & 0xFF);
            out.write(c & 0xFF);
        }

        out.write(8); // LZW minimum code size
        lzw(indices);
        out.write(0); // block terminator
    }

    void finish() throws IOException {
        out.write(0x3B);
        out.flush();
    }

    private void writeHeader() throws IOException {
        out.write(new byte[]{'G', 'I', 'F', '8', '9', 'a'});
        writeShort(width);
        writeShort(height);
        out.write(0x70); // no global colour table, 8 bits/channel
        out.write(0);
        out.write(0);
        // NETSCAPE2.0 application extension: loop forever.
        out.write(0x21);
        out.write(0xFF);
        out.write(11);
        out.write(new byte[]{'N', 'E', 'T', 'S', 'C', 'A', 'P', 'E', '2', '.', '0'});
        out.write(3);
        out.write(1);
        writeShort(0);
        out.write(0);
    }

    private void writeShort(int v) throws IOException {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
    }

    // ---- quantization -------------------------------------------------------

    private static int key15(int r, int g, int b) {
        return ((r >> 3) << 10) | ((g >> 3) << 5) | (b >> 3);
    }

    /** Median cut over the frame's 15-bit colour histogram; always returns 256 entries. */
    private static int[] buildPalette(int[] argb) {
        int[] hist = new int[32768];
        int distinct = 0;
        for (int p : argb) {
            int k = key15((p >> 16) & 0xFF, (p >> 8) & 0xFF, p & 0xFF);
            if (hist[k]++ == 0) distinct++;
        }
        // arr entry: key in bits 20..34, count in bits 0..19 (count is capped
        // so a huge flat region can't overflow into the key bits); bits 40+
        // hold the sort channel during a split.
        long[] arr = new long[distinct];
        int n = 0;
        for (int k = 0; k < hist.length; k++) {
            if (hist[k] > 0) arr[n++] = ((long) k << 20) | Math.min(hist[k], 0xFFFFF);
        }

        int[] lo = new int[256], hi = new int[256];
        int boxes = 1;
        lo[0] = 0;
        hi[0] = distinct;
        final int[] shifts = {10, 5, 0};

        while (boxes < 256) {
            int best = -1, bestRange = 0, bestCh = 0;
            for (int b = 0; b < boxes; b++) {
                if (hi[b] - lo[b] < 2) continue;
                int[] mn = {31, 31, 31}, mx = {0, 0, 0};
                for (int j = lo[b]; j < hi[b]; j++) {
                    int k = (int) ((arr[j] >> 20) & 0x7FFF);
                    for (int c = 0; c < 3; c++) {
                        int v = (k >> shifts[c]) & 31;
                        if (v < mn[c]) mn[c] = v;
                        if (v > mx[c]) mx[c] = v;
                    }
                }
                for (int c = 0; c < 3; c++) {
                    int range = mx[c] - mn[c];
                    if (range > bestRange) {
                        bestRange = range;
                        best = b;
                        bestCh = c;
                    }
                }
            }
            if (best < 0) break;

            int l = lo[best], h = hi[best];
            for (int j = l; j < h; j++) {
                int k = (int) ((arr[j] >> 20) & 0x7FFF);
                arr[j] = ((long) ((k >> shifts[bestCh]) & 31) << 40) | (arr[j] & 0xFFFFFFFFFFL);
            }
            Arrays.sort(arr, l, h);

            long total = 0;
            for (int j = l; j < h; j++) total += arr[j] & 0xFFFFF;
            long acc = 0;
            int mid = l + 1;
            for (int j = l; j < h; j++) {
                acc += arr[j] & 0xFFFFF;
                if (acc * 2 >= total) {
                    mid = j + 1;
                    break;
                }
            }
            mid = Math.max(l + 1, Math.min(h - 1, mid));
            lo[boxes] = mid;
            hi[boxes] = h;
            hi[best] = mid;
            boxes++;
        }

        int[] palette = new int[256]; // unused tail entries stay black
        for (int b = 0; b < boxes; b++) {
            long r = 0, g = 0, bl = 0, w = 0;
            for (int j = lo[b]; j < hi[b]; j++) {
                int k = (int) ((arr[j] >> 20) & 0x7FFF);
                long cnt = arr[j] & 0xFFFFF;
                r += cnt * expand5((k >> 10) & 31);
                g += cnt * expand5((k >> 5) & 31);
                bl += cnt * expand5(k & 31);
                w += cnt;
            }
            if (w == 0) continue;
            palette[b] = ((int) (r / w) << 16) | ((int) (g / w) << 8) | (int) (bl / w);
        }
        return palette;
    }

    private static int expand5(int v) {
        return (v << 3) | (v >> 2);
    }

    /** Floyd-Steinberg dither to the palette, with a 15-bit colour -> index cache. */
    private byte[] quantize(int[] argb, int[] palette) {
        int[] cache = new int[32768];
        Arrays.fill(cache, -1);
        byte[] result = new byte[width * height];

        int[][] err = new int[2][(width + 2) * 3];
        for (int y = 0; y < height; y++) {
            int[] cur = err[y & 1], nxt = err[(y + 1) & 1];
            Arrays.fill(nxt, 0);
            for (int x = 0; x < width; x++) {
                int p = argb[y * width + x];
                int r = clamp(((p >> 16) & 0xFF) + cur[(x + 1) * 3] / 16);
                int g = clamp(((p >> 8) & 0xFF) + cur[(x + 1) * 3 + 1] / 16);
                int b = clamp((p & 0xFF) + cur[(x + 1) * 3 + 2] / 16);

                int k = key15(r, g, b);
                int idx = cache[k];
                if (idx < 0) {
                    idx = nearest(palette, r, g, b);
                    cache[k] = idx;
                }
                result[y * width + x] = (byte) idx;

                int pc = palette[idx];
                int[] e = {r - ((pc >> 16) & 0xFF), g - ((pc >> 8) & 0xFF), b - (pc & 0xFF)};
                for (int c = 0; c < 3; c++) {
                    cur[(x + 2) * 3 + c] += e[c] * 7;
                    nxt[x * 3 + c] += e[c] * 3;
                    nxt[(x + 1) * 3 + c] += e[c] * 5;
                    nxt[(x + 2) * 3 + c] += e[c];
                }
            }
        }
        return result;
    }

    private static int nearest(int[] palette, int r, int g, int b) {
        int best = 0;
        long bestD = Long.MAX_VALUE;
        for (int i = 0; i < palette.length; i++) {
            int dr = r - ((palette[i] >> 16) & 0xFF);
            int dg = g - ((palette[i] >> 8) & 0xFF);
            int db = b - (palette[i] & 0xFF);
            long d = (long) dr * dr + (long) dg * dg + (long) db * db;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }

    // ---- LZW ----------------------------------------------------------------

    private static final int HSIZE = 5003;
    private static final int CLEAR = 256, EOI = 257;

    // Bit packer state for the current frame.
    private int bitBuf, bitCount;
    private final byte[] block = new byte[255];
    private int blockLen;

    private void lzw(byte[] pixels) throws IOException {
        int[] hashKey = new int[HSIZE];
        int[] hashVal = new int[HSIZE];
        Arrays.fill(hashKey, -1);
        bitBuf = 0;
        bitCount = 0;
        blockLen = 0;

        int bits = 9, maxCode = (1 << bits) - 1, next = EOI + 1;
        emit(CLEAR, bits);

        int prefix = pixels[0] & 0xFF;
        for (int i = 1; i < pixels.length; i++) {
            int c = pixels[i] & 0xFF;
            int key = (prefix << 8) | c;
            int h = (int) ((((long) key * 2654435761L) >>> 8 & 0x7FFFFFFFL) % HSIZE);
            while (hashKey[h] != -1 && hashKey[h] != key) {
                h = (h + 1) % HSIZE;
            }
            if (hashKey[h] == key) {
                prefix = hashVal[h];
                continue;
            }

            emit(prefix, bits);
            if (next > maxCode && bits < 12) {
                bits++;
                maxCode = (1 << bits) - 1;
            }
            if (next < 4096) {
                hashKey[h] = key;
                hashVal[h] = next++;
            } else {
                emit(CLEAR, bits);
                Arrays.fill(hashKey, -1);
                next = EOI + 1;
                bits = 9;
                maxCode = (1 << bits) - 1;
            }
            prefix = c;
        }
        emit(prefix, bits);
        if (next > maxCode && bits < 12) {
            bits++;
        }
        emit(EOI, bits);
        if (bitCount > 0) {
            putByte(bitBuf & 0xFF);
            bitBuf = 0;
            bitCount = 0;
        }
        flushBlock();
    }

    private void emit(int code, int bits) throws IOException {
        bitBuf |= code << bitCount;
        bitCount += bits;
        while (bitCount >= 8) {
            putByte(bitBuf & 0xFF);
            bitBuf >>>= 8;
            bitCount -= 8;
        }
    }

    private void putByte(int b) throws IOException {
        block[blockLen++] = (byte) b;
        if (blockLen == 255) flushBlock();
    }

    private void flushBlock() throws IOException {
        if (blockLen > 0) {
            out.write(blockLen);
            out.write(block, 0, blockLen);
            blockLen = 0;
        }
    }
}
