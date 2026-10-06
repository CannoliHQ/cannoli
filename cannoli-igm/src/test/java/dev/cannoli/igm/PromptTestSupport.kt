package dev.cannoli.igm

internal fun IgmSettingsExit.Prompt.choose(label: String) =
    options.first { it.label == label }.choose()

internal fun options(vararg labels: String, record: (String) -> Unit): List<IgmPromptOption> =
    labels.map { label -> IgmPromptOption(label) { record(label) } }

internal object SaveAnswer {
    val platform: String = RaOptionStrings().savePlatform
    val game: String = RaOptionStrings().saveGame
    val discard: String = RaOptionStrings().dontSave
}
