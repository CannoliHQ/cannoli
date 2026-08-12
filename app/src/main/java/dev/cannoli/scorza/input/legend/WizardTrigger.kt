package dev.cannoli.scorza.input.legend

import dev.cannoli.scorza.input.DeviceMapping
import dev.cannoli.scorza.input.MappingSource

// A pad only gets the legend wizard when Cannoli could not identify it (so its bindings are
// Android's raw defaults) and it reports a standard 4-face diamond, since anything else has no
// reliable capture shape to walk the user through.
fun shouldRunLegendWizard(mapping: DeviceMapping, hasStandardFaceCodes: Boolean): Boolean =
    mapping.source == MappingSource.ANDROID_DEFAULT && hasStandardFaceCodes
