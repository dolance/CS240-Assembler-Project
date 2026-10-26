.data
data0: .asciiz "Fizz"
data1: .asciiz "Buzz"

.text
.globl main
main:
    addiu $t1, $zero, 1
    addiu $t2, $zero, 101
L400008:
    jal L40001c
    jal L40009c
    addi $t1, $t1, 1
    bne $t1, $t2, L400008
    jal L4000ac
L40001c:
    addi $sp, $sp, -8
    sw $ra, 0($sp)
    addiu $at, $zero, 3
    div $t1, $at
    mfhi $t3
    addiu $at, $zero, 5
    div $t1, $at
    mfhi $t4
    bne $t3, $zero, L400044
    jal L400064
L400044:
    bne $t4, $zero, L400050
    jal L400078
    j L400058
L400050:
    beq $t3, $zero, L400058
    jal L40008c
L400058:
    lw $ra, 0($sp)
    addi $sp, $sp, 8
    jr $ra
L400064:
    addiu $v0, $zero, 4
    lui $a0, 4097
    ori $a0, $a0, 0
    syscall
    jr $ra
L400078:
    addiu $v0, $zero, 4
    lui $a0, 4097
    ori $a0, $a0, 5
    syscall
    jr $ra
L40008c:
    addiu $v0, $zero, 1
    addu $a0, $t1, $zero
    syscall
    jr $ra
L40009c:
    addiu $v0, $zero, 11
    addiu $a0, $zero, 10
    syscall
    jr $ra
L4000ac:
    addiu $v0, $zero, 10
    syscall
