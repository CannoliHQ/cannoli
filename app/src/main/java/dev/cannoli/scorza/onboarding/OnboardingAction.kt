package dev.cannoli.scorza.onboarding

/**
 * The one action the permissions step offers for the highlighted row. The screen draws it and the
 * handler runs it from this same value, so the legend can never promise something confirm will not
 * do. [NONE] is a granted row while a required permission is still missing: the way forward is to
 * move onto the ungranted row, so the footer stays empty rather than showing a dead label.
 */
enum class OnboardingPermissionsAction { GRANT, CONTINUE, NONE }
