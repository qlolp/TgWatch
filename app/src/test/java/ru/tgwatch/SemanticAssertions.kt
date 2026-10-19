package ru.tgwatch

internal fun TextValue.allKeys(): List<TextKey> = listOf(key) +
    args.filterIsInstance<TextArgument.Value>().flatMap { it.value.allKeys() }
internal fun TextValue.allArguments(): List<TextArgument> = args +
    args.filterIsInstance<TextArgument.Value>().flatMap { it.value.allArguments() }
