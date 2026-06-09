package com.roastcompanion.audio

sealed class CrackEvent {
    object FirstCrackStarted : CrackEvent()
    data class FirstCrackEnded(val durationMs: Long) : CrackEvent()
    object SecondCrackStarted : CrackEvent()
}
