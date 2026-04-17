package com.linetrace.app.feature.economy

import kotlin.math.sqrt

data class Cargo(
    val id: String,
    val name: String,
    val baseValue: Int,
    var quantity: Int = 0
)

object PlayerState {
    var credits: Int = 1000
    val inventory = mutableListOf<Cargo>(
        Cargo("nebula_dust", "Nebula Dust", 50, 5),
        Cargo("void_crystals", "Void Crystals", 500, 0),
        Cargo("chronos_spice", "Chronos Spice", 1200, 0),
        Cargo("ionized_salts", "Ionized Salts", 150, 0),
        Cargo("stardust_fuel", "Stardust Fuel", 25, 10)
    )

    fun addCredits(amount: Int) {
        credits += amount
    }

    fun removeCredits(amount: Int): Boolean {
        if (credits >= amount) {
            credits -= amount
            return true
        }
        return false
    }

    /**
     * Converts raw AR path data into Syndicate Credits.
     * This simulates the "Data Monetization" of the underlying VIO technology.
     */
    fun processTechBounty(points: Int, stability: Float): Int {
        val baseBounty = points * 5
        val stabilityBonus = if (stability > 0.8f) 2.0f else 1.0f
        val finalBounty = (baseBounty * stabilityBonus).toInt()
        credits += finalBounty
        return finalBounty
    }
}

class MarketGenerator {
    fun getPreferredSubstance(seed: Int): Cargo {
        val index = Math.abs(seed) % PlayerState.inventory.size
        return PlayerState.inventory[index]
    }

    fun getPrice(cargo: Cargo, x: Float, y: Float, z: Float): Int {
        // Distance-based pricing: Prices increase as you move further from the "Core" (Origin)
        val distance = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
        val distanceMultiplier = 1.0f + (distance * 0.1f) // 10% increase per meter
        
        // Add some random "Market Volatility"
        val volatility = (Math.random().toFloat() - 0.5f) * 0.2f
        
        return (cargo.baseValue * distanceMultiplier * (1.0f + volatility)).toInt()
    }
}
