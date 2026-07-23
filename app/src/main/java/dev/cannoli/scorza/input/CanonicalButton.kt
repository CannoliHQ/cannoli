package dev.cannoli.scorza.input

// Moved to cannoli-igm so the IGM input translator and the launcher share one enum.
// Kept as a typealias here so existing `dev.cannoli.scorza.input.CanonicalButton` imports
// and `CanonicalButton.BTN_*` references continue to resolve unchanged.
typealias CanonicalButton = dev.cannoli.igm.CanonicalButton
