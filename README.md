# CS240 Assignment 5: Assembler and Disassembler Project

## Description

This assignment contains Java programs that convert MIPS assembly instructions into machine code and reconstruct MIPS assembly from machine code.

The programs included are:
- MIPS Assembler
- MIPS Disassembler
- Machine-code file format reader

## Files

- `fizzbuzz.java` - Reads MIPS assembly and generates machine-code files.
- `Disassembler.java` - Converts machine code back into MIPS assembly.
- `MhcFormat.java` - Reads and handles both machine-code formats.
- `fizzbuzz.asm` - Original MIPS FizzBuzz program.
- `fizzbuzz.mhc-ascii` - Machine code stored as readable binary digits.
- `fizzbuzz.mhc-bin` - Machine code stored as actual binary bytes.
- `reconstructed_bin/` - Reconstructed assembly and machine-code files generated using the binary format.
- `reconstructed_ascii/` - Reconstructed assembly and machine-code files generated using the ASCII format.

## Requirements

- Java Development Kit (JDK 17)
- MARS MIPS Simulator for testing FizzBuzz execution

## Running the Programs

**1. Compile the Java programs:**

```powershell
javac fizzbuzz.java Disassembler.java MhcFormat.java
```

**2. Run the assembler:**

```powershell
java fizzbuzz fizzbuzz.asm
```

This creates `fizzbuzz.mhc-ascii` and `fizzbuzz.mhc-bin`.

**3. Run the disassembler using the binary format:**

```powershell
java Disassembler fizzbuzz.mhc-bin reconstructed_bin/fizzbuzz_reconstructed.asm
```

**4. Run the disassembler using the ASCII format:**

```powershell
java Disassembler fizzbuzz.mhc-ascii reconstructed_ascii/fizzbuzz_reconstructed.asm
```

The `reconstructed_bin` and `reconstructed_ascii` folders must exist before running these commands.

**5. Reassemble the reconstructed programs:**

```powershell
java fizzbuzz reconstructed_bin/fizzbuzz_reconstructed.asm
java fizzbuzz reconstructed_ascii/fizzbuzz_reconstructed.asm
```

## Testing

Screenshots of my test cases are included in the assignment PDF.

**Assembler Testing:**
- Successfully assembled FizzBuzz into 45 instructions and 10 data bytes.
- Generated both ASCII and binary machine-code files.
- Tested an unsupported instruction (`fly`) to verify error handling.

**Disassembler Testing:**
- Successfully reconstructed MIPS assembly using both machine-code formats.
- Reassembled both reconstructed programs.
- Compared the original and reconstructed binary files using SHA-256 hashes. Both comparisons returned `True`.
- Tested an invalid binary file to verify error handling.
- Verified that the original and reconstructed programs produce the same FizzBuzz output in MARS.

## Acknowledgments

The original MIPS FizzBuzz source was provided by my professor. I used ChatGPT to assist with developing the Java assembler, disassembler, and machine-code format reader, including code implementation, debugging, testing, explanations, and README documentation. I compiled, tested, and verified the programs myself.
