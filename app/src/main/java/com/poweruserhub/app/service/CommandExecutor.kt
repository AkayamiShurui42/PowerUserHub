package com.poweruserhub.app.service

interface CommandExecutor {
    fun getName(): String
    fun isAvailable(): Boolean
    fun execute(command: String): CommandResult
}
