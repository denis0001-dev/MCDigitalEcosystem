package com.mcdigital.ecosystem.utils

fun Process.readErrorOutput(): String {
    val error = try {
        errorStream.bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        ""
    }

    val output = try {
        inputStream.bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        ""
    }

    return error + output
}