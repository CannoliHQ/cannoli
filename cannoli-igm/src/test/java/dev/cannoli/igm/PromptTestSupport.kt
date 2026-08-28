package dev.cannoli.igm

/** Answers a prompt the way a user does: by the option they read, not by where it sits. */
internal fun IgmSettingsExit.Prompt.choose(label: String) =
    options.first { it.label == label }.choose()

/** Options that record the label of whichever one was answered. */
internal fun options(vararg labels: String, record: (String) -> Unit): List<IgmPromptOption> =
    labels.map { label -> IgmPromptOption(label) { record(label) } }

internal object SaveAnswer {
    val platform: String = RaOptionStrings().savePlatform
    val game: String = RaOptionStrings().saveGame
    val discard: String = RaOptionStrings().dontSave
}
