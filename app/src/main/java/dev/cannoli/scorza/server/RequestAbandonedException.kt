package dev.cannoli.scorza.server

/** Thrown by a long-running handler once the client it was building a response for has gone away,
 *  so the work stops instead of running to completion for a socket nobody is reading. */
internal class RequestAbandonedException : RuntimeException("client disconnected")
