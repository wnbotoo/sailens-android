package com.friady.sailens.domain.util

private const val COORD_MASK = 0xFFFF

fun packCoordinate(x: Int, y: Int): Int = (y shl 16) or (x and COORD_MASK)

fun unpackCoordinateX(packed: Int): Int = packed and COORD_MASK

fun unpackCoordinateY(packed: Int): Int = packed ushr 16

class IntArrayList(initialCapacity: Int = 16) {
    private var values = IntArray(initialCapacity.coerceAtLeast(1))
    var size: Int = 0
        private set

    fun add(value: Int) {
        ensureCapacity(size + 1)
        values[size++] = value
    }

    operator fun get(index: Int): Int {
        require(index in 0 until size) { "Index $index out of bounds for size $size" }
        return values[index]
    }

    fun toIntArray(): IntArray = values.copyOf(size)

    private fun ensureCapacity(requiredCapacity: Int) {
        if (requiredCapacity <= values.size) return
        values = values.copyOf(maxOf(requiredCapacity, values.size * 2))
    }
}

class IntArrayQueue(initialCapacity: Int = 16) {
    private var values = IntArray(initialCapacity.coerceAtLeast(1))
    private var head = 0
    private var tail = 0
    var size: Int = 0
        private set

    fun addLast(value: Int) {
        ensureCapacity(size + 1)
        values[tail] = value
        tail = (tail + 1) % values.size
        size++
    }

    fun removeFirst(): Int {
        require(size > 0) { "Queue is empty" }
        val value = values[head]
        head = (head + 1) % values.size
        size--
        return value
    }

    fun isNotEmpty(): Boolean = size > 0

    private fun ensureCapacity(requiredCapacity: Int) {
        if (requiredCapacity <= values.size) return

        val newValues = IntArray(maxOf(requiredCapacity, values.size * 2))
        var index = 0
        while (index < size) {
            newValues[index] = values[(head + index) % values.size]
            index++
        }
        values = newValues
        head = 0
        tail = size
    }
}

