package dev.cannoli.scorza.input

interface ActivityActions {
    fun finishAffinity()
    fun restartApp()
    fun startRaLogin(username: String, password: String)
}
