package com.sailens.shell.app

/**
 * Which build this is, for the provenance of recorded data (field captures). Bound by the host
 * app, which knows its own commit; a distribution that pins this repository records its own SHA,
 * which identifies both.
 *
 * @property gitSha the full commit SHA, suffixed "-dirty" when tracked files were modified; null
 *   when the build could not tell.
 */
data class BuildIdentity(val gitSha: String?)
