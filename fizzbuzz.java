import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class fizzbuzz {
    static final int TEXT_BASE = 0x00400000;
    static final int DATA_BASE = 0x10010000;

    static final String[] REGISTER_NAMES = {
        "zero", "at", "v0", "v1", "a0", "a1", "a2", "a3",
        "t0", "t1", "t2", "t3", "t4", "t5", "t6", "t7",
        "s0", "s1", "s2", "s3", "s4", "s5", "s6", "s7",
        "t8", "t9", "k0", "k1", "gp", "sp", "fp", "ra"
    };

    static class Instruction {
        String code;
        int address;
        int lineNumber;

        Instruction(String code, int address, int lineNumber) {
            this.code = code;
            this.address = address;
            this.lineNumber = lineNumber;
        }
    }

    private final Map<String, Integer> labels = new HashMap<>();
    private final Set<String> codeLabels = new HashSet<>();
    private final List<String> globalLabels = new ArrayList<>();
    private final List<Instruction> instructions = new ArrayList<>();
    private final List<Byte> data = new ArrayList<>();
    private String section = "";
    private int address = TEXT_BASE;

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: java fizzbuzz <path-to-fizzbuzz.asm>");
            System.exit(1);
        }

        try {
            String path = args[0];
            File file = new File(path);
            if (!file.isFile()) throw new IllegalArgumentException("File not found: " + path);

            List<String> lines = new ArrayList<>();
            try (Scanner scanner = new Scanner(file, "UTF-8")) {
                while (scanner.hasNextLine()) {
                    lines.add(scanner.nextLine());
                }
            }

            fizzbuzz assembler = new fizzbuzz();
            assembler.firstPass(lines);
            byte[] machineCode = assembler.secondPass();
            byte[] dataBytes = assembler.getDataBytes();

            Path folder = file.toPath().toAbsolutePath().getParent();
            Path asciiPath = folder.resolve("fizzbuzz.mhc-ascii");
            Path binaryPath = folder.resolve("fizzbuzz.mhc-bin");
            writeAscii(asciiPath, machineCode, dataBytes);
            writeBinary(binaryPath, machineCode, dataBytes);

            System.out.println("Success: " + assembler.instructions.size() + " instructions, "
                    + dataBytes.length + " data bytes.");
            System.out.println("Created " + asciiPath);
            System.out.println("Created " + binaryPath);
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("Assembler error: " + e.getMessage());
            System.exit(1);
        }
    }

    // First pass: record label addresses and expand pseudoinstructions.
    private void firstPass(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            try {
                String line = removeComment(lines.get(i)).trim();
                if (i == 0 && line.startsWith("\uFEFF")) line = line.substring(1).trim();
                if (line.isEmpty()) continue;

                Pattern labelPattern = Pattern.compile("^([A-Za-z_][A-Za-z_0-9]*):");
                Matcher matcher = labelPattern.matcher(line);
                while (matcher.find()) {
                    String label = matcher.group(1);
                    if (labels.containsKey(label)) error("Duplicate label: " + label);
                    if (section.isEmpty()) error("Label appears before .text or .data: " + label);
                    labels.put(label, section.equals("data") ? DATA_BASE + data.size() : address);
                    if (section.equals("text")) codeLabels.add(label);
                    line = line.substring(matcher.end()).trim();
                    matcher = labelPattern.matcher(line);
                }
                if (line.isEmpty()) continue;

                if (line.startsWith(".")) {
                    processDirective(line);
                } else {
                    if (!section.equals("text")) error("Instruction outside .text: " + line);
                    expandInstruction(line, i + 1);
                }
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Line " + (i + 1) + ": " + e.getMessage());
            }
        }

        for (String label : globalLabels) {
            if (!labels.containsKey(label)) error("Undefined global label: " + label);
        }
        if (instructions.isEmpty()) error("No instructions found in .text section.");
    }

    private void processDirective(String line) {
        String[] parts = line.split("\\s+", 2);
        String directive = parts[0];
        String value = parts.length > 1 ? parts[1].trim() : "";

        switch (directive) {
            case ".text":
            case ".data":
                if (!value.isEmpty()) error("Unexpected argument after " + directive);
                section = directive.equals(".text") ? "text" : "data";
                break;
            case ".globl":
            case ".global":
                if (!value.matches("[A-Za-z_][A-Za-z_0-9]*")) error("Expected global label name.");
                globalLabels.add(value);
                break;
            case ".asciiz":
                if (!section.equals("data")) error(".asciiz must be in .data section.");
                String text = decodeQuoted(value, '"');
                for (char character : text.toCharArray()) data.add((byte) character);
                data.add((byte) 0);
                break;
            default:
                error("Unsupported directive: " + directive);
        }
    }

    private void addInstruction(String code, int lineNumber) {
        instructions.add(new Instruction(code, address, lineNumber));
        address += 4;
    }

    private void loadImmediate(String destination, int value, int lineNumber) {
        register(destination);
        if (value >= -32768 && value <= 32767) {
            addInstruction("addiu " + destination + ", $zero, " + value, lineNumber);
        } else if (value >= 0 && value <= 65535) {
            addInstruction("ori " + destination + ", $zero, " + value, lineNumber);
        } else {
            addInstruction("lui " + destination + ", " + (value >>> 16), lineNumber);
            addInstruction("ori " + destination + ", " + destination + ", " + (value & 65535), lineNumber);
        }
    }

    private void expandInstruction(String line, int lineNumber) {
        String[] parts = line.split("\\s+", 2);
        String op = parts[0].toLowerCase();
        String[] a = operands(parts);

        switch (op) {
            case "li":
                expect(a, 2);
                loadImmediate(a[0], number(a[1]), lineNumber);
                break;
            case "la":
                expect(a, 2);
                register(a[0]);
                if (!a[1].matches("[A-Za-z_][A-Za-z_0-9]*")) error("la needs a label.");
                addInstruction("lui " + a[0] + ", hi(" + a[1] + ")", lineNumber);
                addInstruction("ori " + a[0] + ", " + a[0] + ", lo(" + a[1] + ")", lineNumber);
                break;
            case "move":
                expect(a, 2);
                addInstruction("addu " + a[0] + ", " + a[1] + ", $zero", lineNumber);
                break;
            case "rem":
                expect(a, 3);
                if (register(a[0]) == 1 || register(a[1]) == 1) error("rem reserves $at register.");
                int divisor = number(a[2]);
                if (divisor == 0) error("rem divisor cannot be zero.");
                loadImmediate("$at", divisor, lineNumber);
                addInstruction("div " + a[1] + ", $at", lineNumber);
                addInstruction("mfhi " + a[0], lineNumber);
                break;
            case "nop":
                expect(a, 0);
                addInstruction("sll $zero, $zero, 0", lineNumber);
                break;
            default:
                addInstruction(line, lineNumber);
        }
    }

    // Second pass: all labels are known, so branches and jumps can be encoded.
    private byte[] secondPass() {
        byte[] bytes = new byte[instructions.size() * 4];
        int index = 0;
        for (Instruction instruction : instructions) {
            try {
                int word = encode(instruction);
                for (int shift = 24; shift >= 0; shift -= 8) {
                    bytes[index++] = (byte) (word >>> shift);
                }
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Line " + instruction.lineNumber + ": " + e.getMessage());
            }
        }
        return bytes;
    }

    private int encode(Instruction instruction) {
        String[] parts = instruction.code.split("\\s+", 2);
        String op = parts[0].toLowerCase();
        String[] a = operands(parts);

        switch (op) {
            case "addi":
            case "addiu":
            case "ori":
                expect(a, 3);
                int imm = value(a[2]);
                bounds(imm, op.equals("ori") ? 0 : -32768, op.equals("ori") ? 65535 : 32767);
                return iType(op.equals("addi") ? 8 : op.equals("addiu") ? 9 : 13,
                        register(a[1]), register(a[0]), imm);
            case "lui":
                expect(a, 2);
                return iType(15, 0, register(a[0]), bounds(value(a[1]), 0, 65535));
            case "add":
            case "addu":
            case "sub":
                expect(a, 3);
                return rType(register(a[1]), register(a[2]), register(a[0]), 0,
                        op.equals("add") ? 32 : op.equals("sub") ? 34 : 33);
            case "div":
                expect(a, 2);
                return rType(register(a[0]), register(a[1]), 0, 0, 26);
            case "mfhi":
                expect(a, 1);
                return rType(0, 0, register(a[0]), 0, 16);
            case "sll":
                expect(a, 3);
                return rType(0, register(a[1]), register(a[0]), bounds(number(a[2]), 0, 31), 0);
            case "beq":
            case "bne":
                expect(a, 3);
                int target = codeLabel(a[2]);
                int difference = target - (instruction.address + 4);
                if (difference % 4 != 0) error("Branch target is not word-aligned.");
                return iType(op.equals("beq") ? 4 : 5, register(a[0]), register(a[1]),
                        bounds(difference / 4, -32768, 32767));
            case "j":
            case "jal":
                expect(a, 1);
                int jumpTarget = codeLabel(a[0]);
                if ((jumpTarget & 0xF0000000) != ((instruction.address + 4) & 0xF0000000))
                    error("Jump target out of range: " + a[0]);
                return ((op.equals("j") ? 2 : 3) << 26) | ((jumpTarget >>> 2) & 0x03FFFFFF);
            case "jr":
                expect(a, 1);
                return rType(register(a[0]), 0, 0, 0, 8);
            case "lw":
            case "sw":
                expect(a, 2);
                String memory = a[1].replaceAll("\\s+", "");
                Matcher m = Pattern.compile("^([^()]+)\\((\\$[A-Za-z0-9]+)\\)$").matcher(memory);
                if (!m.matches()) error("Expected offset($register): " + a[1]);
                return iType(op.equals("lw") ? 35 : 43, register(m.group(2)), register(a[0]),
                        bounds(number(m.group(1)), -32768, 32767));
            case "syscall":
                expect(a, 0);
                return 0x0000000C;
            default:
                error("Unsupported instruction: " + op);
                return 0;
        }
    }

    private static int rType(int rs, int rt, int rd, int shamt, int funct) {
        return (rs << 21) | (rt << 16) | (rd << 11) | (shamt << 6) | funct;
    }

    private static int iType(int opcode, int rs, int rt, int imm) {
        return (opcode << 26) | (rs << 21) | (rt << 16) | (imm & 65535);
    }

    private int codeLabel(String name) {
        int result = labelAddress(name);
        if (!codeLabels.contains(name)) error("Jump or branch target is not in .text: " + name);
        return result;
    }

    private int labelAddress(String name) {
        Integer result = labels.get(name);
        if (result == null) error("Undefined label: " + name);
        return result;
    }

    private int value(String token) {
        if (token.startsWith("hi(") && token.endsWith(")"))
            return labelAddress(token.substring(3, token.length() - 1)) >>> 16;
        if (token.startsWith("lo(") && token.endsWith(")"))
            return labelAddress(token.substring(3, token.length() - 1)) & 65535;
        return number(token);
    }

    private static int register(String token) {
        String name = token.trim().toLowerCase();
        if (name.equals("$s8")) return 30;
        for (int i = 0; i < REGISTER_NAMES.length; i++) {
            if (name.equals("$" + REGISTER_NAMES[i]) || name.equals("$" + i)) return i;
        }
        error("Unknown register: " + token);
        return -1;
    }

    private static int number(String token) {
        String s = token.trim();
        if (s.startsWith("'")) {
            String character = decodeQuoted(s, '\'');
            if (character.length() != 1) error("Expected one character: " + s);
            return character.charAt(0);
        }
        try {
            boolean negative = s.startsWith("-");
            String digits = (s.startsWith("+") || s.startsWith("-")) ? s.substring(1) : s;
            int radix = 10;
            if (digits.startsWith("0x") || digits.startsWith("0X")) {
                radix = 16;
                digits = digits.substring(2);
            } else if (digits.startsWith("0b") || digits.startsWith("0B")) {
                radix = 2;
                digits = digits.substring(2);
            }
            long value = Long.parseLong(digits, radix);
            if (negative) value = -value;
            if (value < Integer.MIN_VALUE || value > 0xFFFFFFFFL) error("Number out of range: " + token);
            return (int) value;
        } catch (NumberFormatException e) {
            error("Invalid number: " + token);
            return 0;
        }
    }

    private static String removeComment(String line) {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '#') {
                return line.substring(0, i);
            }
        }
        if (quote != 0) error("Unclosed quoted string.");
        return line;
    }

    private static String decodeQuoted(String token, char quotation) {
        if (token.length() < 2 || token.charAt(0) != quotation
                || token.charAt(token.length() - 1) != quotation)
            error("Expected quoted text: " + token);
        StringBuilder out = new StringBuilder();
        for (int i = 1; i < token.length() - 1; i++) {
            char c = token.charAt(i);
            if (c == '\\') {
                if (++i >= token.length() - 1) error("Incomplete escape sequence.");
                char escape = token.charAt(i);
                switch (escape) {
                    case 'n': c = '\n'; break;
                    case 'r': c = '\r'; break;
                    case 't': c = '\t'; break;
                    case '0': c = 0; break;
                    case '\\': c = '\\'; break;
                    case '\'': c = '\''; break;
                    case '"': c = '"'; break;
                    default: error("Unsupported escape sequence: \\" + escape);
                }
            } else if (c == quotation) {
                error("Unexpected quote in literal.");
            }
            if (c > 127) error("Only ASCII characters are supported in strings.");
            out.append(c);
        }
        return out.toString();
    }

    private static String[] operands(String[] instruction) {
        if (instruction.length == 1 || instruction[1].trim().isEmpty()) return new String[0];
        List<String> list = new ArrayList<>();
        String line = instruction[1];
        char quote = 0;
        int start = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == ',') {
                list.add(line.substring(start, i).trim());
                start = i + 1;
            }
        }
        list.add(line.substring(start).trim());
        return list.toArray(new String[0]);
    }

    private static void expect(String[] operands, int count) {
        if (operands.length != count) error("Expected " + count + " operand(s), got " + operands.length);
        for (String operand : operands) if (operand.isEmpty()) error("Empty operand.");
    }

    private static int bounds(int value, int low, int high) {
        if (value < low || value > high) error("Value " + value + " is outside " + low + " to " + high);
        return value;
    }

    private static void error(String message) {
        throw new IllegalArgumentException(message);
    }

    private byte[] getDataBytes() {
        byte[] result = new byte[data.size()];
        for (int i = 0; i < data.size(); i++) result[i] = data.get(i);
        return result;
    }

    // The ASCII file stores readable bits; .text and .data stay separate.
    private static void writeAscii(Path path, byte[] text, byte[] data) throws IOException {
        StringBuilder output = new StringBuilder();
        output.append("MHC-ASCII 1\n");
        output.append("TEXT_BYTES ").append(text.length).append('\n');
        output.append("DATA_BYTES ").append(data.length).append('\n');
        output.append(".text\n");
        for (int i = 0; i < text.length; i += 4) {
            for (int j = 0; j < 4; j++) output.append(bits(text[i + j]));
            output.append('\n');
        }
        output.append(".data\n");
        for (byte b : data) output.append(bits(b)).append('\n');
        Files.write(path, output.toString().getBytes(StandardCharsets.US_ASCII));
    }

    private static String bits(byte b) {
        String value = Integer.toBinaryString(b & 255);
        return "00000000".substring(value.length()) + value;
    }

    // The binary header gives the sizes needed to tell code from data.
    private static void writeBinary(Path path, byte[] text, byte[] data) throws IOException {
        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(path))) {
            output.write(new byte[] {'M', 'H', 'C', '1'});
            output.writeInt(text.length);
            output.writeInt(data.length);
            output.write(text);
            output.write(data);
        }
    }
}
