import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.TreeMap;

/** Rebuild supported MIPS source from either MHC encoding, preserving bytes. */
public class Disassembler {
    private static final int TEXT_BASE = 0x00400000;
    private static final String[] REG = {
        "$zero", "$at", "$v0", "$v1", "$a0", "$a1", "$a2", "$a3",
        "$t0", "$t1", "$t2", "$t3", "$t4", "$t5", "$t6", "$t7",
        "$s0", "$s1", "$s2", "$s3", "$s4", "$s5", "$s6", "$s7",
        "$t8", "$t9", "$k0", "$k1", "$gp", "$sp", "$fp", "$ra"
    };

    public static void main(String[] args) {
        if (args.length < 1 || args.length > 2) {
            System.err.println("Usage: java Disassembler <file.mhc-ascii|file.mhc-bin> [output.asm]");
            System.exit(1);
        }
        try {
            File input = new File(args[0]);
            Path inputPath = input.toPath();
            if (!input.isFile()) throw new IllegalArgumentException("Input file not found: " + input);
            MhcFormat.Image image;
            if (input.getName().endsWith(".mhc-ascii")) image = MhcFormat.readAscii(inputPath);
            else if (input.getName().endsWith(".mhc-bin")) image = MhcFormat.readBinary(inputPath);
            else throw new IllegalArgumentException("Expected .mhc-ascii or .mhc-bin extension.");
            String source = disassemble(image);
            Path output = args.length == 2 ? Paths.get(args[1]) : Paths.get("fizzbuzz_reconstructed.asm");
            Files.write(output, source.getBytes(StandardCharsets.UTF_8));
            System.out.println("Reconstructed " + image.text.length / 4 + " instructions and "
                    + image.data.length + " data bytes in " + output.toAbsolutePath());
        } catch (IOException | IllegalArgumentException ex) {
            System.err.println("Disassembler error: " + ex.getMessage());
            System.exit(1);
        }
    }

    public static String disassemble(MhcFormat.Image image) {
        int n = image.text.length / 4;
        int[] words = new int[n];
        for (int i = 0; i < n; i++) {
            int k = i * 4;
            words[i] = ((image.text[k] & 255) << 24) | ((image.text[k + 1] & 255) << 16)
                    | ((image.text[k + 2] & 255) << 8) | (image.text[k + 3] & 255);
        }
        Map<Integer, String> labels = new TreeMap<>();
        labels.put(TEXT_BASE, "main");
        for (int i = 0; i < n; i++) {
            int word = words[i];
            int opcode = word >>> 26;
            if (opcode == 4 || opcode == 5 || opcode == 2 || opcode == 3) {
                int target = branchOrJumpTarget(word, TEXT_BASE + i * 4);
                if (target < TEXT_BASE || target >= TEXT_BASE + image.text.length
                        || (target - TEXT_BASE) % 4 != 0)
                    throw new IllegalArgumentException("Branch/jump targets outside reconstructed .text: 0x"
                            + Integer.toHexString(target));
                if (!labels.containsKey(target)) labels.put(target, "L" + Integer.toHexString(target));
            }
        }
        StringBuilder out = new StringBuilder();
        if (image.data.length > 0) {
            out.append(".data\n");
            int start = 0;
            int labelIndex = 0;
            for (int i = 0; i < image.data.length; i++) {
                if (image.data[i] == 0) {
                    out.append("data").append(labelIndex++).append(": .asciiz \"");
                    for (int j = start; j < i; j++) out.append(escape(image.data[j] & 255));
                    out.append("\"\n");
                    start = i + 1;
                }
            }
            if (start != image.data.length)
                throw new IllegalArgumentException("Unsupported data layout: every ASCII string must end in a zero byte.");
            out.append('\n');
        }
        out.append(".text\n.globl main\n");
        for (int i = 0; i < n; i++) {
            int pc = TEXT_BASE + i * 4;
            String label = labels.get(pc);
            if (label != null) out.append(label).append(":\n");
            out.append("    ").append(decode(words[i], pc, labels)).append('\n');
        }
        return out.toString();
    }

    private static String escape(int byteValue) {
        if (byteValue > 127 || (byteValue < 32 && byteValue != 10 && byteValue != 13 && byteValue != 9))
            throw new IllegalArgumentException("Unsupported non-printable data byte: " + byteValue);
        switch (byteValue) {
            case 10: return "\\n";
            case 13: return "\\r";
            case 9: return "\\t";
            case 34: return "\\\"";
            case 92: return "\\\\";
            default: return Character.toString((char) byteValue);
        }
    }

    private static int branchOrJumpTarget(int word, int pc) {
        int op = word >>> 26;
        if (op == 4 || op == 5) return pc + 4 + (((short) word) << 2);
        if (op == 2 || op == 3) return ((pc + 4) & 0xF0000000) | ((word & 0x03FFFFFF) << 2);
        throw new IllegalArgumentException("Not a branch/jump instruction.");
    }

    private static String decode(int w, int pc, Map<Integer, String> labels) {
        int op = w >>> 26;
        int rs = (w >>> 21) & 31, rt = (w >>> 16) & 31, rd = (w >>> 11) & 31;
        int shamt = (w >>> 6) & 31, fn = w & 63;
        int uimm = w & 0xffff, simm = (short) uimm;
        switch (op) {
            case 0:
                switch (fn) {
                    case 0:
                        if (rs == 0) return "sll " + REG[rd] + ", " + REG[rt] + ", " + shamt;
                        break;
                    case 8:
                        if (rt == 0 && rd == 0 && shamt == 0) return "jr " + REG[rs];
                        break;
                    case 12:
                        if (w == 12) return "syscall";
                        break;
                    case 16:
                        if (rs == 0 && rt == 0 && shamt == 0) return "mfhi " + REG[rd];
                        break;
                    case 26:
                        if (rd == 0 && shamt == 0) return "div " + REG[rs] + ", " + REG[rt];
                        break;
                    case 32:
                        if (shamt == 0) return "add " + REG[rd] + ", " + REG[rs] + ", " + REG[rt];
                        break;
                    case 33:
                        if (shamt == 0) return "addu " + REG[rd] + ", " + REG[rs] + ", " + REG[rt];
                        break;
                    case 34:
                        if (shamt == 0) return "sub " + REG[rd] + ", " + REG[rs] + ", " + REG[rt];
                        break;
                }
                break;
            case 8: return "addi " + REG[rt] + ", " + REG[rs] + ", " + simm;
            case 9: return "addiu " + REG[rt] + ", " + REG[rs] + ", " + simm;
            case 13: return "ori " + REG[rt] + ", " + REG[rs] + ", " + uimm;
            case 15:
                if (rs == 0) return "lui " + REG[rt] + ", " + uimm;
                break;
            case 4: return "beq " + REG[rs] + ", " + REG[rt] + ", " + labels.get(branchOrJumpTarget(w, pc));
            case 5: return "bne " + REG[rs] + ", " + REG[rt] + ", " + labels.get(branchOrJumpTarget(w, pc));
            case 2: return "j " + labels.get(branchOrJumpTarget(w, pc));
            case 3: return "jal " + labels.get(branchOrJumpTarget(w, pc));
            case 35: return "lw " + REG[rt] + ", " + simm + "(" + REG[rs] + ")";
            case 43: return "sw " + REG[rt] + ", " + simm + "(" + REG[rs] + ")";
        }
        throw new IllegalArgumentException("Unsupported machine instruction at PC 0x"
                + Integer.toHexString(pc) + ": 0x" + String.format("%08X", w));
    }
}
