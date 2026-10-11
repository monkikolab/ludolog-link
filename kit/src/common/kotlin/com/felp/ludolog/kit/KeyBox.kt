package com.felp.ludolog.kit

import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Las claves de las fuentes de arte de Ludolog (las de IGDB) de un aparato a otro (10-10-2026): se
 * escriben en una consola o en el PC y Link las pasa a todos los emparejados. Gana la puesta mas
 * tarde, tambien cuando se quita: una clave vacia con su fecha viaja igual, y apaga la fuente en todos.
 *
 * Link habla en HTTP sin cifrar (TLS sigue pendiente), y en una red compartida cualquiera puede leer
 * lo que pasa. Por eso estas van en una caja: cada lado hace un par de claves de un solo uso (ECDH
 * sobre P-256), de las dos sale un secreto que quien solo escucha no puede sacar, y con el se cifra
 * (AES-GCM). La clave del emparejamiento entra en la cuenta como sal: sin ella no sale la misma caja,
 * asi que ademas de ponerse en medio habria que conocerla.
 *
 * Lo compilan la app de la consola y la del PC: si cambia algo, cambia en las dos.
 */
object KeyBox {
    /** Una clave y cuando se puso. Vacia con fecha: se quito, y eso tambien viaja. */
    data class Entry(val value: String, val at: Long)

    private const val INFO = "ludolog-link art keys v1"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private val rnd = SecureRandom()

    /** Un par de un solo uso. */
    fun pair(): KeyPair = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1"), rnd) }.generateKeyPair()

    /** La parte publica, para mandarla. */
    fun pub(k: KeyPair): String = b64(k.public)

    /** La publica del otro lado, o nulo si no es una. */
    fun parse(pub: String?): PublicKey? = runCatching {
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(pub!!)))
    }.getOrNull()

    /** Lo de [entries], cerrado para [theirs] con [mine]. */
    fun seal(mine: KeyPair, theirs: PublicKey, token: String, entries: Map<String, Entry>): String {
        val iv = ByteArray(IV_BYTES).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(mine, theirs, token), GCMParameterSpec(TAG_BITS, iv))
        return Base64.getEncoder().encodeToString(iv + c.doFinal(json(entries).toString().toByteArray(Charsets.UTF_8)))
    }

    /** Lo que cerro el otro lado con [seal]; nulo si no se abre (otra clave, otro par, o tocado por el camino). */
    fun open(mine: KeyPair, theirs: PublicKey, token: String, box: String): Map<String, Entry>? = runCatching {
        val raw = Base64.getDecoder().decode(box)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(mine, theirs, token), GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES))
        entries(JSONObject(String(c.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)))
    }.getOrNull()

    /** Lo de [theirs] mas nuevo que lo de [mine]: lo que hay que tomar. */
    fun newer(mine: Map<String, Entry>, theirs: Map<String, Entry>): Map<String, Entry> =
        theirs.filter { (k, e) -> e.at > (mine[k]?.at ?: 0L) }

    /** Lo de [mine] mas nuevo que lo que el otro dice tener ([stamps]): lo que hay que darle. */
    fun ahead(mine: Map<String, Entry>, stamps: Map<String, Long>): Map<String, Entry> =
        mine.filter { (k, e) -> e.at > (stamps[k] ?: 0L) }

    /** Solo las fechas, que viajan en claro: con ellas el otro sabe si le hace falta algo, sin ver nada. */
    fun stamps(m: Map<String, Entry>): JSONObject = JSONObject().also { j -> m.forEach { (k, e) -> j.put(k, e.at) } }

    fun stampsOf(j: JSONObject?): Map<String, Long> =
        j?.keys()?.asSequence()?.associateWith { j.optLong(it) }?.filterValues { it > 0 }.orEmpty()

    private fun json(m: Map<String, Entry>) = JSONObject().also { j ->
        m.forEach { (k, e) -> j.put(k, JSONObject().put("v", e.value).put("t", e.at)) }
    }

    private fun entries(j: JSONObject): Map<String, Entry> = j.keys().asSequence().mapNotNull { k ->
        val o = j.optJSONObject(k) ?: return@mapNotNull null
        val at = o.optLong("t").takeIf { it > 0 } ?: return@mapNotNull null
        k to Entry(o.optString("v"), at)
    }.toMap()

    /**
     * HKDF-SHA256 (RFC 5869) en una vuelta, que da los 32 bytes de AES-256: del secreto de las dos
     * mitades, con la clave del emparejamiento de sal y las dos publicas, en orden, de contexto.
     */
    private fun key(mine: KeyPair, theirs: PublicKey, token: String): SecretKeySpec {
        val shared = KeyAgreement.getInstance("ECDH").run { init(mine.private); doPhase(theirs, true); generateSecret() }
        val salt = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        val prk = hmac(salt, shared)
        val info = listOf(b64(mine.public), b64(theirs)).sorted().joinToString("|", prefix = "$INFO|").toByteArray(Charsets.UTF_8)
        return SecretKeySpec(hmac(prk, info + byteArrayOf(1)), "AES")
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    private fun b64(k: PublicKey): String = Base64.getEncoder().encodeToString(k.encoded)
}
