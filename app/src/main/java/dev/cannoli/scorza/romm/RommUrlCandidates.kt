package dev.cannoli.scorza.romm

object RommUrlCandidates {
    private val IPV4 = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")

    fun build(typedHost: String): List<String> {
        val trimmed = typedHost.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return listOf(trimmed.trimEnd('/'))
        }
        val hostPart = trimmed.removePrefix("//").trimEnd('/')
        val firstHost = hostPart.substringBefore('/').substringBefore(':')
        val httpFirst = firstHost.matches(IPV4) || firstHost == "localhost"
        return if (httpFirst) {
            listOf("http://$hostPart", "https://$hostPart")
        } else {
            listOf("https://$hostPart", "http://$hostPart")
        }
    }
}
