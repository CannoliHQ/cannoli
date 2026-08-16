package dev.cannoli.scorza.onboarding

enum class OnboardingStep {
    WELCOME,
    PERMISSIONS,
    STORAGE;

    val number: Int get() = ordinal + 1
    val next: OnboardingStep? get() = entries.getOrNull(ordinal + 1)
    val previous: OnboardingStep? get() = entries.getOrNull(ordinal - 1)

    companion object {
        val COUNT = entries.size
    }
}
