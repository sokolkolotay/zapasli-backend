package ru.zapasli.backend.auth

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

interface PasswordHasher {
    fun hash(password: String): String

    fun verify(password: String, encodedHash: String): Boolean
}

class Argon2idPasswordHasher(
    private val secureRandom: SecureRandom = SecureRandom(),
) : PasswordHasher {
    override fun hash(password: String): String {
        val salt = ByteArray(SALT_BYTES).also(secureRandom::nextBytes)
        val hash = derive(password, salt)
        return buildString {
            append("\$argon2id\$v=19\$m=")
            append(MEMORY_KIB)
            append(",t=")
            append(ITERATIONS)
            append(",p=")
            append(PARALLELISM)
            append('$')
            append(encoder.encodeToString(salt))
            append('$')
            append(encoder.encodeToString(hash))
        }.also {
            salt.fill(0)
            hash.fill(0)
        }
    }

    override fun verify(password: String, encodedHash: String): Boolean {
        val parsed = parse(encodedHash) ?: return false
        val calculated = derive(password, parsed.salt)
        return try {
            MessageDigest.isEqual(calculated, parsed.hash)
        } finally {
            parsed.salt.fill(0)
            parsed.hash.fill(0)
            calculated.fill(0)
        }
    }

    private fun derive(password: String, salt: ByteArray): ByteArray {
        val passwordBytes = password.toByteArray(StandardCharsets.UTF_8)
        return try {
            val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(MEMORY_KIB)
                .withIterations(ITERATIONS)
                .withParallelism(PARALLELISM)
                .withSalt(salt)
                .build()
            ByteArray(HASH_BYTES).also { output ->
                Argon2BytesGenerator().apply { init(parameters) }
                    .generateBytes(passwordBytes, output)
            }
        } finally {
            passwordBytes.fill(0)
        }
    }

    private fun parse(value: String): ParsedHash? = runCatching {
        val parts = value.split('$')
        require(parts.size == 6 && parts[0].isEmpty())
        require(parts[1] == "argon2id" && parts[2] == "v=19")
        require(parts[3] == "m=$MEMORY_KIB,t=$ITERATIONS,p=$PARALLELISM")
        val salt = decoder.decode(parts[4])
        val hash = decoder.decode(parts[5])
        require(salt.size == SALT_BYTES && hash.size == HASH_BYTES)
        ParsedHash(salt, hash)
    }.getOrNull()

    private data class ParsedHash(
        val salt: ByteArray,
        val hash: ByteArray,
    )

    companion object {
        const val MEMORY_KIB = 19_456
        const val ITERATIONS = 2
        const val PARALLELISM = 1
        private const val SALT_BYTES = 16
        private const val HASH_BYTES = 32
        private val encoder = Base64.getEncoder().withoutPadding()
        private val decoder = Base64.getDecoder()
    }
}
