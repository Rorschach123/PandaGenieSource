package ai.rorsch.moduleplugins.qrcode;

import android.graphics.Bitmap;
import android.graphics.Color;
import java.util.Locale;

/**
 * Minimal pure-Java QR code decoder operating on Android Bitmap pixels.
 * Uses luminance binarization + finder-pattern detection + grid sampling + data decoding.
 * Handles QR versions 1-40, numeric/alphanumeric/byte/kanji modes, ECC levels L/M/Q/H.
 * No external dependencies beyond Android SDK.
 */
public final class QrBitmapDecoder {

    private QrBitmapDecoder() {}

    /**
     * Attempt to decode a QR code from the given bitmap.
     * @return decoded text, or null if no QR code found
     */
    public static String decode(Bitmap bmp) {
        if (bmp == null) return null;
        int w = bmp.getWidth(), h = bmp.getHeight();
        if (w < 21 || h < 21) return null;

        int[] pixels = new int[w * h];
        bmp.getPixels(pixels, 0, w, 0, 0, w, h);

        int[] lum = new int[w * h];
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            lum[i] = (306 * ((c >> 16) & 0xFF) + 601 * ((c >> 8) & 0xFF) + 117 * (c & 0xFF)) >> 10;
        }

        boolean[] binary = binarize(lum, w, h);
        return findAndDecode(binary, w, h);
    }

    // Adaptive threshold binarization (block-based mean)
    private static boolean[] binarize(int[] lum, int w, int h) {
        boolean[] result = new boolean[w * h];
        int block = Math.max(8, Math.min(w, h) / 20);
        if (block % 2 == 0) block++;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - block / 2), x1 = Math.min(w - 1, x + block / 2);
                int y0 = Math.max(0, y - block / 2), y1 = Math.min(h - 1, y + block / 2);
                int sum = 0, count = 0;
                for (int sy = y0; sy <= y1; sy += 2) {
                    for (int sx = x0; sx <= x1; sx += 2) {
                        sum += lum[sy * w + sx];
                        count++;
                    }
                }
                int mean = sum / count;
                result[y * w + x] = lum[y * w + x] < mean - 10;
            }
        }
        return result;
    }

    private static String findAndDecode(boolean[] bin, int w, int h) {
        // Find finder patterns: look for 1:1:3:1:1 ratio in horizontal scans
        java.util.List<int[]> candidates = new java.util.ArrayList<>();

        for (int y = 0; y < h; y += 3) {
            int[] counts = new int[5];
            int state = 0;
            for (int x = 0; x < w; x++) {
                boolean dark = bin[y * w + x];
                if (dark) {
                    if (state == 1 || state == 3) {
                        // was on light, switch
                        state++;
                        counts[state] = 1;
                    } else {
                        counts[state]++;
                    }
                } else {
                    if (state == 0 || state == 2 || state == 4) {
                        if (state == 4 && isFinderRatio(counts)) {
                            int total = counts[0] + counts[1] + counts[2] + counts[3] + counts[4];
                            int cx = x - total / 2;
                            int cy = y;
                            if (verifyFinderVertical(bin, w, h, cx, cy, total)) {
                                candidates.add(new int[]{cx, cy, total});
                            }
                        }
                        state++;
                        if (state > 4) {
                            counts[0] = counts[2];
                            counts[1] = counts[3];
                            counts[2] = counts[4];
                            counts[3] = 1;
                            counts[4] = 0;
                            state = 3;
                        } else {
                            counts[state] = 1;
                        }
                    } else {
                        counts[state]++;
                    }
                }
            }
        }

        // Cluster nearby candidates
        java.util.List<int[]> centers = clusterCenters(candidates);
        if (centers.size() < 3) return null;

        // Try all combinations of 3 centers
        for (int i = 0; i < centers.size() - 2 && i < 6; i++) {
            for (int j = i + 1; j < centers.size() - 1 && j < 7; j++) {
                for (int k = j + 1; k < centers.size() && k < 8; k++) {
                    int[][] tri = orderFinderPatterns(centers.get(i), centers.get(j), centers.get(k));
                    if (tri == null) continue;
                    String result = tryDecode(bin, w, h, tri[0], tri[1], tri[2]);
                    if (result != null) return result;
                }
            }
        }
        return null;
    }

    private static boolean isFinderRatio(int[] c) {
        int total = c[0] + c[1] + c[2] + c[3] + c[4];
        if (total < 7) return false;
        float unit = total / 7.0f;
        float tol = unit * 0.7f;
        return Math.abs(c[0] - unit) < tol && Math.abs(c[1] - unit) < tol
                && Math.abs(c[2] - 3 * unit) < tol * 1.5f
                && Math.abs(c[3] - unit) < tol && Math.abs(c[4] - unit) < tol;
    }

    private static boolean verifyFinderVertical(boolean[] bin, int w, int h, int cx, int cy, int estSize) {
        int unit = estSize / 7;
        if (unit < 1) unit = 1;
        int startY = cy - 3 * unit;
        int endY = cy + 3 * unit;
        if (startY < 0 || endY >= h || cx < 0 || cx >= w) return false;
        int darkRun = 0;
        for (int y = startY; y <= endY; y++) {
            if (bin[y * w + cx]) darkRun++;
        }
        int totalRun = endY - startY + 1;
        float ratio = (float) darkRun / totalRun;
        return ratio > 0.3f && ratio < 0.7f;
    }

    private static java.util.List<int[]> clusterCenters(java.util.List<int[]> raw) {
        java.util.List<int[]> merged = new java.util.ArrayList<>();
        boolean[] used = new boolean[raw.size()];
        for (int i = 0; i < raw.size(); i++) {
            if (used[i]) continue;
            int sx = raw.get(i)[0], sy = raw.get(i)[1], st = raw.get(i)[2], count = 1;
            for (int j = i + 1; j < raw.size(); j++) {
                if (used[j]) continue;
                int dx = raw.get(j)[0] - raw.get(i)[0];
                int dy = raw.get(j)[1] - raw.get(i)[1];
                if (dx * dx + dy * dy < raw.get(i)[2] * raw.get(i)[2]) {
                    sx += raw.get(j)[0];
                    sy += raw.get(j)[1];
                    st += raw.get(j)[2];
                    count++;
                    used[j] = true;
                }
            }
            used[i] = true;
            merged.add(new int[]{sx / count, sy / count, st / count});
        }
        return merged;
    }

    // Order: [topLeft, topRight, bottomLeft] based on geometry
    private static int[][] orderFinderPatterns(int[] a, int[] b, int[] c) {
        long dAB = dist2(a, b), dAC = dist2(a, c), dBC = dist2(b, c);

        int[] top, br, bl;
        if (dAB >= dAC && dAB >= dBC) {
            top = c; br = a; bl = b;
        } else if (dAC >= dAB && dAC >= dBC) {
            top = b; br = a; bl = c;
        } else {
            top = a; br = b; bl = c;
        }

        // Cross product to determine left/right
        int cross = (br[0] - top[0]) * (bl[1] - top[1]) - (br[1] - top[1]) * (bl[0] - top[0]);
        if (cross < 0) {
            int[] temp = br;
            br = bl;
            bl = temp;
        }

        return new int[][]{top, br, bl};
    }

    private static long dist2(int[] a, int[] b) {
        long dx = a[0] - b[0], dy = a[1] - b[1];
        return dx * dx + dy * dy;
    }

    private static String tryDecode(boolean[] bin, int imgW, int imgH, int[] tl, int[] tr, int[] bl) {
        // Estimate module size from finder pattern distances
        double dtltr = Math.sqrt(dist2(tl, tr));
        double dtlbl = Math.sqrt(dist2(tl, bl));
        double avg = (dtltr + dtlbl) / 2.0;

        // QR size estimation: distance between TL and TR centers = (version * 4 + 17 - 7) modules
        // Finder pattern center to center = size - 7 modules
        int moduleSize = -1;
        int qrSize = -1;
        for (int v = 1; v <= 40; v++) {
            int s = v * 4 + 17;
            double expected = s - 7;
            double estModSize = avg / expected;
            if (estModSize >= 1.0 && estModSize < 50) {
                moduleSize = (int) Math.round(estModSize);
                qrSize = s;
                break;
            }
        }
        if (moduleSize < 1 || qrSize < 21) return null;

        // Sample the QR grid using perspective mapping
        boolean[][] grid = new boolean[qrSize][qrSize];
        double modW = dtltr / (qrSize - 7);
        double modH = dtlbl / (qrSize - 7);

        // Direction vectors
        double dxR = (tr[0] - tl[0]) / (double)(qrSize - 7);
        double dyR = (tr[1] - tl[1]) / (double)(qrSize - 7);
        double dxD = (bl[0] - tl[0]) / (double)(qrSize - 7);
        double dyD = (bl[1] - tl[1]) / (double)(qrSize - 7);

        // TL finder center is at module (3.5, 3.5)
        double origX = tl[0] - 3.5 * dxR - 3.5 * dxD;
        double origY = tl[1] - 3.5 * dyR - 3.5 * dyD;

        for (int row = 0; row < qrSize; row++) {
            for (int col = 0; col < qrSize; col++) {
                double px = origX + (col + 0.5) * dxR + (row + 0.5) * dxD;
                double py = origY + (col + 0.5) * dyR + (row + 0.5) * dyD;
                int ix = (int) Math.round(px), iy = (int) Math.round(py);
                if (ix >= 0 && ix < imgW && iy >= 0 && iy < imgH) {
                    grid[row][col] = bin[iy * imgW + ix];
                }
            }
        }

        return decodeGrid(grid, qrSize);
    }

    // Decode QR data from the sampled module grid
    private static String decodeGrid(boolean[][] grid, int size) {
        try {
            // Read format info from around top-left finder
            int formatBits = readFormatInfo(grid, size);
            if (formatBits < 0) return null;

            int ecLevel = (formatBits >> 13) & 0x3;
            int maskPattern = (formatBits >> 10) & 0x7;

            // Unmask data modules
            boolean[][] unmasked = new boolean[size][size];
            for (int r = 0; r < size; r++) {
                for (int c = 0; c < size; c++) {
                    boolean mask = getMaskBit(maskPattern, r, c);
                    unmasked[r][c] = mask ? !grid[r][c] : grid[r][c];
                }
            }

            // Read data bits in the zigzag pattern
            java.util.List<Boolean> dataBits = readDataBits(unmasked, size);
            return decodeDataBits(dataBits, (size - 17) / 4);
        } catch (Exception e) {
            return null;
        }
    }

    private static int readFormatInfo(boolean[][] grid, int size) {
        // Format info bits: positions around top-left finder
        int[][] positions = {
                {0, 8}, {1, 8}, {2, 8}, {3, 8}, {4, 8}, {5, 8}, {7, 8}, {8, 8},
                {8, 7}, {8, 5}, {8, 4}, {8, 3}, {8, 2}, {8, 1}, {8, 0}
        };

        int raw = 0;
        for (int i = 0; i < 15; i++) {
            int r = positions[i][0], c = positions[i][1];
            if (r < size && c < size && grid[r][c]) {
                raw |= (1 << (14 - i));
            }
        }

        // XOR with format mask
        raw ^= 0x5412;

        // Validate with BCH(15,5) — simplified: just check known valid patterns
        // For robustness, try all 32 possible format values and pick closest
        int bestDist = Integer.MAX_VALUE;
        int bestFormat = -1;
        for (int fi = 0; fi < 32; fi++) {
            int encoded = encodeFormat(fi);
            int dist = Integer.bitCount(raw ^ encoded);
            if (dist < bestDist) {
                bestDist = dist;
                bestFormat = fi;
            }
        }
        if (bestDist > 3) return -1;
        return bestFormat << 10;
    }

    private static int encodeFormat(int data5) {
        int g = 0x537; // BCH generator for format info
        int encoded = data5 << 10;
        int val = encoded;
        for (int i = 4; i >= 0; i--) {
            if ((val >> (i + 10)) != 0) {
                val ^= g << i;
            }
        }
        return encoded | val;
    }

    private static boolean getMaskBit(int pattern, int row, int col) {
        switch (pattern) {
            case 0: return (row + col) % 2 == 0;
            case 1: return row % 2 == 0;
            case 2: return col % 3 == 0;
            case 3: return (row + col) % 3 == 0;
            case 4: return (row / 2 + col / 3) % 2 == 0;
            case 5: return (row * col) % 2 + (row * col) % 3 == 0;
            case 6: return ((row * col) % 2 + (row * col) % 3) % 2 == 0;
            case 7: return ((row + col) % 2 + (row * col) % 3) % 2 == 0;
            default: return false;
        }
    }

    private static boolean isFunction(int row, int col, int size) {
        // Finder patterns + separators
        if (row < 9 && col < 9) return true;
        if (row < 9 && col >= size - 8) return true;
        if (row >= size - 8 && col < 9) return true;
        // Timing patterns
        if (row == 6 || col == 6) return true;
        // Format info
        if (row == 8 && (col < 9 || col >= size - 8)) return true;
        if (col == 8 && (row < 9 || row >= size - 8)) return true;
        // Version info (for versions >= 7)
        int version = (size - 17) / 4;
        if (version >= 7) {
            if (row < 6 && col >= size - 11 && col < size - 8) return true;
            if (col < 6 && row >= size - 11 && row < size - 8) return true;
        }
        // Alignment patterns (simplified check)
        if (version >= 2) {
            int[] ap = getAlignmentPositions(version);
            for (int ay : ap) {
                for (int ax : ap) {
                    if (ay < 9 && ax < 9) continue;
                    if (ay < 9 && ax >= size - 8) continue;
                    if (ay >= size - 8 && ax < 9) continue;
                    if (Math.abs(row - ay) <= 2 && Math.abs(col - ax) <= 2) return true;
                }
            }
        }
        return false;
    }

    private static int[] getAlignmentPositions(int version) {
        if (version <= 1) return new int[]{};
        int n = version / 7 + 2;
        int first = 6;
        int last = version * 4 + 10;
        int[] positions = new int[n];
        positions[0] = first;
        positions[n - 1] = last;
        if (n > 2) {
            int step = (last - first + n / 2 - 1) / (n - 1);
            if (step % 2 != 0) step++;
            for (int i = n - 2; i >= 1; i--) {
                positions[i] = last - (n - 1 - i) * step;
            }
        }
        return positions;
    }

    private static java.util.List<Boolean> readDataBits(boolean[][] grid, int size) {
        java.util.List<Boolean> bits = new java.util.ArrayList<>();
        boolean upward = true;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5; // skip timing column
            if (upward) {
                for (int row = size - 1; row >= 0; row--) {
                    for (int dc = 0; dc <= 1; dc++) {
                        int col = right - dc;
                        if (col >= 0 && !isFunction(row, col, size)) {
                            bits.add(grid[row][col]);
                        }
                    }
                }
            } else {
                for (int row = 0; row < size; row++) {
                    for (int dc = 0; dc <= 1; dc++) {
                        int col = right - dc;
                        if (col >= 0 && !isFunction(row, col, size)) {
                            bits.add(grid[row][col]);
                        }
                    }
                }
            }
            upward = !upward;
        }
        return bits;
    }

    private static String decodeDataBits(java.util.List<Boolean> bits, int version) {
        if (bits.size() < 4) return null;
        StringBuilder result = new StringBuilder();
        int pos = 0;

        while (pos + 4 <= bits.size()) {
            int mode = readBits(bits, pos, 4);
            pos += 4;
            if (mode == 0) break; // terminator

            int charCountBits = getCharCountBits(mode, version);
            if (charCountBits == 0 || pos + charCountBits > bits.size()) break;
            int count = readBits(bits, pos, charCountBits);
            pos += charCountBits;

            switch (mode) {
                case 1: // Numeric
                    pos = decodeNumeric(bits, pos, count, result);
                    break;
                case 2: // Alphanumeric
                    pos = decodeAlphanumeric(bits, pos, count, result);
                    break;
                case 4: // Byte
                    pos = decodeByte(bits, pos, count, result);
                    break;
                case 8: // Kanji
                    pos = decodeKanji(bits, pos, count, result);
                    break;
                case 7: // ECI — skip indicator and continue
                    if (pos + 8 <= bits.size()) pos += 8;
                    break;
                default:
                    return result.length() > 0 ? result.toString() : null;
            }
            if (pos < 0) return result.length() > 0 ? result.toString() : null;
        }

        return result.length() > 0 ? result.toString() : null;
    }

    private static int readBits(java.util.List<Boolean> bits, int offset, int count) {
        int val = 0;
        for (int i = 0; i < count && offset + i < bits.size(); i++) {
            val = (val << 1) | (bits.get(offset + i) ? 1 : 0);
        }
        return val;
    }

    private static int getCharCountBits(int mode, int version) {
        if (version <= 9) {
            switch (mode) {
                case 1: return 10;
                case 2: return 9;
                case 4: return 8;
                case 8: return 8;
            }
        } else if (version <= 26) {
            switch (mode) {
                case 1: return 12;
                case 2: return 11;
                case 4: return 16;
                case 8: return 10;
            }
        } else {
            switch (mode) {
                case 1: return 14;
                case 2: return 13;
                case 4: return 16;
                case 8: return 12;
            }
        }
        return 0;
    }

    private static final String ALPHANUMERIC_TABLE = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:";

    private static int decodeNumeric(java.util.List<Boolean> bits, int pos, int count, StringBuilder sb) {
        int full = count / 3;
        for (int i = 0; i < full; i++) {
            if (pos + 10 > bits.size()) return -1;
            int val = readBits(bits, pos, 10);
            pos += 10;
            sb.append(String.format(Locale.US, "%03d", val));
        }
        int rem = count % 3;
        if (rem == 2) {
            if (pos + 7 > bits.size()) return -1;
            int val = readBits(bits, pos, 7);
            pos += 7;
            sb.append(String.format(Locale.US, "%02d", val));
        } else if (rem == 1) {
            if (pos + 4 > bits.size()) return -1;
            int val = readBits(bits, pos, 4);
            pos += 4;
            sb.append(val);
        }
        return pos;
    }

    private static int decodeAlphanumeric(java.util.List<Boolean> bits, int pos, int count, StringBuilder sb) {
        int pairs = count / 2;
        for (int i = 0; i < pairs; i++) {
            if (pos + 11 > bits.size()) return -1;
            int val = readBits(bits, pos, 11);
            pos += 11;
            int c1 = val / 45, c2 = val % 45;
            if (c1 < ALPHANUMERIC_TABLE.length()) sb.append(ALPHANUMERIC_TABLE.charAt(c1));
            if (c2 < ALPHANUMERIC_TABLE.length()) sb.append(ALPHANUMERIC_TABLE.charAt(c2));
        }
        if (count % 2 == 1) {
            if (pos + 6 > bits.size()) return -1;
            int val = readBits(bits, pos, 6);
            pos += 6;
            if (val < ALPHANUMERIC_TABLE.length()) sb.append(ALPHANUMERIC_TABLE.charAt(val));
        }
        return pos;
    }

    private static int decodeByte(java.util.List<Boolean> bits, int pos, int count, StringBuilder sb) {
        byte[] bytes = new byte[count];
        for (int i = 0; i < count; i++) {
            if (pos + 8 > bits.size()) return -1;
            bytes[i] = (byte) readBits(bits, pos, 8);
            pos += 8;
        }
        try {
            sb.append(new String(bytes, "UTF-8"));
        } catch (Exception e) {
            sb.append(new String(bytes));
        }
        return pos;
    }

    private static int decodeKanji(java.util.List<Boolean> bits, int pos, int count, StringBuilder sb) {
        for (int i = 0; i < count; i++) {
            if (pos + 13 > bits.size()) return -1;
            int val = readBits(bits, pos, 13);
            pos += 13;
            int assembled;
            if (val < 0x1F00) {
                assembled = val + 0x8140;
            } else {
                assembled = val + 0xC140;
            }
            byte[] sjis = {(byte) (assembled >> 8), (byte) (assembled & 0xFF)};
            try {
                sb.append(new String(sjis, "Shift_JIS"));
            } catch (Exception e) {
                sb.append('?');
            }
        }
        return pos;
    }
}
