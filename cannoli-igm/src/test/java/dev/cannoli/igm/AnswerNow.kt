package dev.cannoli.igm

internal inline fun answerNow(onDone: (RaApplyResult?) -> Unit, apply: () -> RaApplyResult?): Boolean {
    val result = apply() ?: return false
    onDone(result)
    return true
}
