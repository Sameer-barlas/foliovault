package com.example.services

import kotlin.math.abs

/**
 * Fractional orderIndex math for O(1) page insertion at start, end, or between any two pages.
 * Starts at 1000.0, 2000.0, 3000.0... and triggers full book rebalance if any adjacent gap
 * drops below [REBALANCE_THRESHOLD] (0.001).
 */
object OrderIndexMath {
    const val STEP: Double = 1000.0
    const val REBALANCE_THRESHOLD: Double = 0.001

    sealed interface InsertPosition {
        data object Start : InsertPosition
        data object End : InsertPosition
        data class Before(val targetIndex: Int) : InsertPosition
        data class After(val targetIndex: Int) : InsertPosition
    }

    data class ComputedInsertion(
        val orderIndex: Double,
        val requiresRebalance: Boolean,
    )

    fun computeInsertion(
        existingSortedIndices: List<Double>,
        position: InsertPosition,
    ): ComputedInsertion {
        if (existingSortedIndices.isEmpty()) {
            return ComputedInsertion(orderIndex = STEP, requiresRebalance = false)
        }

        return when (position) {
            is InsertPosition.Start -> {
                val first = existingSortedIndices.first()
                val candidate = first / 2.0
                val gap = abs(first - candidate)
                ComputedInsertion(
                    orderIndex = candidate,
                    requiresRebalance = candidate <= 0.0 || gap < REBALANCE_THRESHOLD,
                )
            }
            is InsertPosition.End -> {
                val last = existingSortedIndices.last()
                ComputedInsertion(
                    orderIndex = last + STEP,
                    requiresRebalance = false,
                )
            }
            is InsertPosition.Before -> {
                val idx = position.targetIndex.coerceIn(0, existingSortedIndices.lastIndex)
                if (idx == 0) {
                    computeInsertion(existingSortedIndices, InsertPosition.Start)
                } else {
                    val prev = existingSortedIndices[idx - 1]
                    val next = existingSortedIndices[idx]
                    val candidate = (prev + next) / 2.0
                    val gap = minOf(abs(candidate - prev), abs(next - candidate))
                    ComputedInsertion(
                        orderIndex = candidate,
                        requiresRebalance = gap < REBALANCE_THRESHOLD,
                    )
                }
            }
            is InsertPosition.After -> {
                val idx = position.targetIndex.coerceIn(0, existingSortedIndices.lastIndex)
                if (idx == existingSortedIndices.lastIndex) {
                    computeInsertion(existingSortedIndices, InsertPosition.End)
                } else {
                    val prev = existingSortedIndices[idx]
                    val next = existingSortedIndices[idx + 1]
                    val candidate = (prev + next) / 2.0
                    val gap = minOf(abs(candidate - prev), abs(next - candidate))
                    ComputedInsertion(
                        orderIndex = candidate,
                        requiresRebalance = gap < REBALANCE_THRESHOLD,
                    )
                }
            }
        }
    }

    fun rebalancedIndices(count: Int): List<Double> =
        List(count) { i -> (i + 1) * STEP }
}
