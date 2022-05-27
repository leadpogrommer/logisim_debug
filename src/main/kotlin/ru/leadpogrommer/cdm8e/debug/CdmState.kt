package ru.leadpogrommer.cdm8e.debug

@kotlinx.serialization.Serializable
data class CdmRegisters(
    val r0: Int,
    val r1: Int,
    val r2: Int,
    val r3: Int,
    val ps: Int,
    val pc: Int,
    val sp: Int,
){}

@kotlinx.serialization.Serializable
data class CdmState(val memory: List<Int>, val registers: CdmRegisters) {}