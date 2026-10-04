package com.exteragram.messenger.utils

fun <T> MutableList<T>.addIf(condition: Boolean, element: T) {
    if (condition) {
        add(element)
    }
}
