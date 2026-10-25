package app.afih.desktop

import app.afih.model.AppConfiguration
import com.sun.jna.platform.win32.Crypt32Util
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Entire configuration is DPAPI protected for the current Windows user. */
class ConfigurationStore(private val file: Path) {
    private val json = Json { encodeDefaults = true }

    fun load(): AppConfiguration {
        checkWindows()
        if (!Files.exists(file)) return AppConfiguration()
        val plaintext = Crypt32Util.cryptUnprotectData(Files.readAllBytes(file))
        return try {
            json.decodeFromString<AppConfiguration>(plaintext.decodeToString()).also {
                check(it.schemaVersion == 1) { "Unsupported configuration version." }
            }
        } finally {
            plaintext.fill(0)
        }
    }

    fun save(configuration: AppConfiguration) {
        checkWindows()
        val plaintext = json.encodeToString(configuration).encodeToByteArray()
        val encrypted = try { Crypt32Util.cryptProtectData(plaintext) } finally { plaintext.fill(0) }
        Files.createDirectories(file.parent)
        val temporary = Files.createTempFile(file.parent, "config-", ".dpapi")
        try {
            Files.write(temporary, encrypted)
            try {
                Files.move(temporary, file, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file, REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun checkWindows() = check(System.getProperty("os.name").startsWith("Windows")) {
        "Secure configuration storage currently requires Windows."
    }

    companion object {
        fun defaultPath(): Path = Path.of(
            System.getenv("LOCALAPPDATA") ?: error("Windows local application data is unavailable."),
            "a-Fih", "configuration.dpapi",
        )
    }
}
