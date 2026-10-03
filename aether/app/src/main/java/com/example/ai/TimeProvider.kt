package com.example.ai

interface TimeProvider {
    fun currentTimeMillis(): Long
    fun getZoneId(): String
}

class SystemTimeProvider : TimeProvider {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
    override fun getZoneId(): String = java.util.TimeZone.getDefault().id
}
