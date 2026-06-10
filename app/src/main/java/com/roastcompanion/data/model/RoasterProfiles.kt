package com.roastcompanion.data.model

data class RoasterProfile(
    val name: String,
    val type: String,
    val carryoverSecs: Int
)

object RoasterProfiles {
    val all = listOf(
        RoasterProfile("Gene Cafe CBR-101", "Drum roaster",  45),
        RoasterProfile("Bullet R1 V2",      "Drum roaster",  30),
        RoasterProfile("Hottop KN-8828B",   "Drum roaster",  40),
        RoasterProfile("Behmor 1600+",      "Drum roaster",  35),
        RoasterProfile("Kaldi Wide",        "Drum roaster",  20),
        RoasterProfile("Fresh Roast SR800", "Fluid bed",     10),
        RoasterProfile("Nesco Roaster",     "Fluid bed",      8),
        RoasterProfile("IKAWA Pro",         "Air roaster",    5),
        RoasterProfile("Other",             "Custom",        30)
    )

    fun byName(name: String): RoasterProfile? = all.find { it.name == name }
}
