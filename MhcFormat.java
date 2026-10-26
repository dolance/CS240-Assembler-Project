import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal, documented MHC container for this particular FizzBuzz assembler.
 * MHC-ASCII is readable text; MHC-BIN is raw bytes with a 12-byte header.
 * Both retain separate text/data sizes so a disassembler can distinguish them.
 */
public final class MhcFormat {
    private MhcFormat() {}

    public static final class Image {
        public final byte[] text, data;
        public Image(byte[] text, byte[] data) {
            if (text.length == 0 || text.length % 4 != 0)
                throw new IllegalArgumentException("Text must contain whole 32-bit instructions.");
            this.text = text;
            this.data = data;
        }
    }

    public static void writeAscii(Path path, Image image) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("MHC-ASCII 1\n");
        sb.append("TEXT_BYTES ").append(image.text.length).append('\n');
        sb.append("DATA_BYTES ").append(image.data.length).append('\n');
        sb.append(".text\n");
        for (int i = 0; i < image.text.length; i += 4) {
            for (int j = 0; j < 4; j++) appendBits(sb, image.text[i + j] & 255);
            sb.append('\n');
        }
        sb.append(".data\n");
        for (byte value : image.data) {
            appendBits(sb, value & 255);
            sb.append('\n');
        }
        Files.write(path, sb.toString().getBytes(StandardCharsets.US_ASCII));
    }

    private static void appendBits(StringBuilder out, int value) {
        for (int i = 7; i >= 0; i--) out.append(((value >>> i) & 1) == 1 ? '1' : '0');
    }

    public static void writeBinary(Path path, Image image) throws IOException {
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(path))) {
            out.write(new byte[] {'M','H','C','1'});
            out.writeInt(image.text.length); // java DataOutputStream writes big-endian
            out.writeInt(image.data.length);
            out.write(image.text);
            out.write(image.data);
        }
    }

    public static Image readBinary(Path path) throws IOException {
        long size = Files.size(path);
        if (size < 12 || size > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Invalid MHC-BIN size.");
        try (DataInputStream in = new DataInputStream(Files.newInputStream(path))) {
            byte[] magic = new byte[4];
            in.readFully(magic);
            if (!"MHC1".equals(new String(magic, StandardCharsets.US_ASCII)))
                throw new IllegalArgumentException("Invalid MHC-BIN magic; expected MHC1.");
            int textLength = in.readInt();
            int dataLength = in.readInt();
            if (textLength <= 0 || textLength % 4 != 0 || dataLength < 0
                    || (long) textLength + dataLength != size - 12)
                throw new IllegalArgumentException("MHC-BIN section lengths do not match the file.");
            byte[] text = new byte[textLength];
            byte[] data = new byte[dataLength];
            in.readFully(text);
            in.readFully(data);
            return new Image(text, data);
        }
    }

    public static Image readAscii(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.US_ASCII);
        if (lines.size() < 5 || !lines.get(0).equals("MHC-ASCII 1"))
            throw new IllegalArgumentException("Invalid MHC-ASCII header.");
        int textLength = parseLength(lines.get(1), "TEXT_BYTES ");
        int dataLength = parseLength(lines.get(2), "DATA_BYTES ");
        if (textLength <= 0 || textLength % 4 != 0 || dataLength < 0 || !lines.get(3).equals(".text"))
            throw new IllegalArgumentException("Invalid MHC-ASCII text section.");
        int wordCount = textLength / 4;
        int dataMarkerIndex = 4 + wordCount;
        if (dataMarkerIndex >= lines.size() || !lines.get(dataMarkerIndex).equals(".data")
                || lines.size() != dataMarkerIndex + 1 + dataLength)
            throw new IllegalArgumentException("MHC-ASCII section lengths do not match the file.");
        byte[] text = new byte[textLength];
        byte[] data = new byte[dataLength];
        for (int i = 0; i < wordCount; i++) {
            String bits = lines.get(4 + i);
            if (!bits.matches("[01]{32}"))
                throw new IllegalArgumentException("Invalid 32-bit text word at ASCII line " + (5 + i));
            for (int j = 0; j < 4; j++) text[i * 4 + j] = (byte) Integer.parseInt(bits.substring(j * 8, (j + 1) * 8), 2);
        }
        for (int i = 0; i < dataLength; i++) {
            String bits = lines.get(dataMarkerIndex + 1 + i);
            if (!bits.matches("[01]{8}"))
                throw new IllegalArgumentException("Invalid 8-bit data byte at ASCII line " + (dataMarkerIndex + 2 + i));
            data[i] = (byte) Integer.parseInt(bits, 2);
        }
        return new Image(text, data);
    }

    private static int parseLength(String line, String prefix) {
        if (!line.startsWith(prefix)) throw new IllegalArgumentException("Expected " + prefix.trim());
        try { return Integer.parseInt(line.substring(prefix.length())); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("Invalid section length: " + line); }
    }
}
