package com.rafambn.wgkotlin.network.io

class ManualPeriodicTicker(
    private val values: ArrayDeque<Boolean> = ArrayDeque(),
) : () -> Boolean {
    override fun invoke(): Boolean {
        return values.removeFirstOrNull() ?: false
    }

    fun enqueue(value: Boolean) {
        values.addLast(value)
    }
}
