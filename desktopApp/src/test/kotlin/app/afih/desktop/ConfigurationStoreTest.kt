package app.afih.desktop

import app.afih.model.AppConfiguration
import app.afih.model.TelegramCredentials
import app.afih.model.TelegramProvider
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse

class ConfigurationStoreTest {
    @Test fun encryptedRoundTripAndUnreadableFilesArePreserved() {
        if (!System.getProperty("os.name").startsWith("Windows")) return
        val directory = Files.createTempDirectory("afih-vault-test")
        val file = directory.resolve("configuration.dpapi")
        try {
            val store = ConfigurationStore(file)
            assertEquals(AppConfiguration(), store.load())
            val configuration = AppConfiguration(providers = listOf(TelegramProvider("test", "My storage", TelegramCredentials(123, "a".repeat(32), "123:testSecret"))))
            store.save(configuration)
            assertEquals(configuration, store.load())
            assertFalse(Files.readAllBytes(file).decodeToString().contains("testSecret"))
            val unreadable = byteArrayOf(1, 2, 3)
            Files.write(file, unreadable)
            assertFails { store.load() }
            assertContentEquals(unreadable, Files.readAllBytes(file))
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }
}
