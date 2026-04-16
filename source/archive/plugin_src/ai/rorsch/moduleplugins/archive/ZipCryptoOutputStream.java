package ai.rorsch.moduleplugins.archive;

import java.io.*;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Creates standard password-protected ZIP files using ZipCrypto (Traditional PKZIP encryption).
 * Compatible with WinZip, 7-Zip, macOS Archive Utility, etc.
 */
public class ZipCryptoOutputStream implements Closeable {

    private final OutputStream out;
    private final String password;
    private final java.util.List<EntryRecord> entries = new java.util.ArrayList<>();
    private long bytesWritten = 0;

    public ZipCryptoOutputStream(OutputStream out, String password) {
        this.out = out;
        this.password = password;
    }

    public void addFile(File file, String entryName) throws IOException {
        byte[] uncompressed = readFile(file);

        CRC32 crc32 = new CRC32();
        crc32.update(uncompressed);
        long crc = crc32.getValue();

        byte[] compressed = deflate(uncompressed);
        boolean stored = compressed.length >= uncompressed.length;
        byte[] data = stored ? uncompressed : compressed;
        int method = stored ? 0 : 8; // STORED or DEFLATED

        boolean encrypt = password != null && !password.isEmpty();
        int flags = (method == 8 ? 0x0002 : 0) | (encrypt ? 0x0001 : 0);

        byte[] encryptionHeader = null;
        byte[] encryptedData = null;

        if (encrypt) {
            int[] keys = initKeys(password);
            encryptionHeader = new byte[12];
            Random rng = new Random();
            for (int i = 0; i < 11; i++) {
                encryptionHeader[i] = encryptByte(keys, (byte) rng.nextInt(256));
            }
            encryptionHeader[11] = encryptByte(keys, (byte) ((crc >> 24) & 0xFF));

            encryptedData = new byte[data.length];
            for (int i = 0; i < data.length; i++) {
                encryptedData[i] = encryptByte(keys, data[i]);
            }
        }

        long localHeaderOffset = bytesWritten;

        int dosTime = javaToDosTime(file.lastModified());
        int dosDate = javaToDosDate(file.lastModified());

        byte[] nameBytes = entryName.getBytes("UTF-8");
        int compressedSize = (encrypt ? 12 : 0) + data.length;

        // Local file header
        writeInt(0x04034b50);       // signature
        writeShort(20);             // version needed (2.0)
        writeShort(flags | 0x0800); // flags (bit 11 = UTF-8)
        writeShort(method);         // compression method
        writeShort(dosTime);
        writeShort(dosDate);
        writeInt((int) crc);
        writeInt(compressedSize);
        writeInt(uncompressed.length);
        writeShort(nameBytes.length);
        writeShort(0);              // extra length
        writeRaw(nameBytes);

        if (encrypt) {
            writeRaw(encryptionHeader);
            writeRaw(encryptedData);
        } else {
            writeRaw(data);
        }

        entries.add(new EntryRecord(entryName, nameBytes, method, flags | 0x0800,
                dosTime, dosDate, crc, compressedSize, uncompressed.length, localHeaderOffset));
    }

    public void finish() throws IOException {
        long centralStart = bytesWritten;

        for (EntryRecord e : entries) {
            writeInt(0x02014b50);       // central directory signature
            writeShort(20);             // version made by
            writeShort(20);             // version needed
            writeShort(e.flags);
            writeShort(e.method);
            writeShort(e.dosTime);
            writeShort(e.dosDate);
            writeInt((int) e.crc);
            writeInt(e.compressedSize);
            writeInt(e.uncompressedSize);
            writeShort(e.nameBytes.length);
            writeShort(0);              // extra length
            writeShort(0);              // comment length
            writeShort(0);              // disk number
            writeShort(0);              // internal attrs
            writeInt(0);                // external attrs
            writeInt((int) e.localOffset);
            writeRaw(e.nameBytes);
        }

        long centralEnd = bytesWritten;
        int centralSize = (int) (centralEnd - centralStart);

        // End of central directory
        writeInt(0x06054b50);
        writeShort(0);                  // disk number
        writeShort(0);                  // central dir disk
        writeShort(entries.size());
        writeShort(entries.size());
        writeInt(centralSize);
        writeInt((int) centralStart);
        writeShort(0);                  // comment length
    }

    @Override
    public void close() throws IOException {
        out.close();
    }

    // --- ZipCrypto implementation ---

    private static int[] initKeys(String password) {
        int[] keys = {305419896, 591751049, 878082192}; // 0x12345678, 0x23456789, 0x34567890
        for (int i = 0; i < password.length(); i++) {
            updateKeys(keys, (byte) password.charAt(i));
        }
        return keys;
    }

    private static void updateKeys(int[] keys, byte b) {
        keys[0] = crc32Update(keys[0], b);
        keys[1] = (keys[1] + (keys[0] & 0xFF)) & 0xFFFFFFFF;
        keys[1] = (keys[1] * 134775813 + 1) & 0xFFFFFFFF;
        keys[2] = crc32Update(keys[2], (byte) ((keys[1] >> 24) & 0xFF));
    }

    private static byte encryptByte(int[] keys, byte b) {
        int temp = keys[2] | 2;
        byte c = (byte) (((temp * (temp ^ 1)) >>> 8) & 0xFF);
        byte encrypted = (byte) (b ^ c);
        updateKeys(keys, b);
        return encrypted;
    }

    private static final int[] CRC_TABLE = new int[256];
    static {
        for (int n = 0; n < 256; n++) {
            int c = n;
            for (int k = 0; k < 8; k++) {
                if ((c & 1) != 0) c = 0xEDB88320 ^ (c >>> 1);
                else c = c >>> 1;
            }
            CRC_TABLE[n] = c;
        }
    }

    private static int crc32Update(int crc, byte b) {
        return CRC_TABLE[(crc ^ b) & 0xFF] ^ (crc >>> 8);
    }

    // --- Helpers ---

    private static byte[] readFile(File f) throws IOException {
        byte[] buf = new byte[(int) f.length()];
        try (FileInputStream fis = new FileInputStream(f)) {
            int off = 0;
            while (off < buf.length) {
                int n = fis.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
        }
        return buf;
    }

    private static byte[] deflate(byte[] input) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        deflater.setInput(input);
        deflater.finish();
        ByteArrayOutputStream baos = new ByteArrayOutputStream(input.length);
        byte[] buf = new byte[8192];
        while (!deflater.finished()) {
            int n = deflater.deflate(buf);
            baos.write(buf, 0, n);
        }
        deflater.end();
        return baos.toByteArray();
    }

    @SuppressWarnings("deprecation")
    private static int javaToDosTime(long millis) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(millis);
        int h = cal.get(java.util.Calendar.HOUR_OF_DAY);
        int m = cal.get(java.util.Calendar.MINUTE);
        int s = cal.get(java.util.Calendar.SECOND);
        return (h << 11) | (m << 5) | (s / 2);
    }

    @SuppressWarnings("deprecation")
    private static int javaToDosDate(long millis) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(millis);
        int y = cal.get(java.util.Calendar.YEAR) - 1980;
        int mon = cal.get(java.util.Calendar.MONTH) + 1;
        int d = cal.get(java.util.Calendar.DAY_OF_MONTH);
        return (y << 9) | (mon << 5) | d;
    }

    private void writeRaw(byte[] data) throws IOException {
        out.write(data);
        bytesWritten += data.length;
    }

    private void writeShort(int v) throws IOException {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        bytesWritten += 2;
    }

    private void writeInt(int v) throws IOException {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
        bytesWritten += 4;
    }

    private static class EntryRecord {
        final String name;
        final byte[] nameBytes;
        final int method, flags, dosTime, dosDate;
        final long crc;
        final int compressedSize, uncompressedSize;
        final long localOffset;

        EntryRecord(String name, byte[] nameBytes, int method, int flags,
                    int dosTime, int dosDate, long crc, int compressedSize,
                    int uncompressedSize, long localOffset) {
            this.name = name;
            this.nameBytes = nameBytes;
            this.method = method;
            this.flags = flags;
            this.dosTime = dosTime;
            this.dosDate = dosDate;
            this.crc = crc;
            this.compressedSize = compressedSize;
            this.uncompressedSize = uncompressedSize;
            this.localOffset = localOffset;
        }
    }
}
