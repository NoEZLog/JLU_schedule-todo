package cn.jlu.schedule.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.GeneralSecurityException

/** 校园账号凭据（学号 + 密码），仅存在于加密存储中 */
data class JluCredentials(
    val studentId: String,
    val password: String
)

/**
 * 校园账号加密凭据存储。
 *
 * 使用 EncryptedSharedPreferences（Keystore 主密钥）。Keystore 密钥在系统迁移/恢复出厂后
 * 可能丢失导致密文不可解密（allowBackup 已排除该文件），此时自愈为清空重建、返回空凭据。
 */
object JluCredentialStore {
    private const val TAG = "JluCredentialStore"
    private const val PREFS_NAME = "jlu_account_secure"
    private const val KEY_STUDENT_ID = "student_id"
    private const val KEY_PASSWORD = "password"

    /** 只有加密存储确实写入成功，才向界面报告已保存。 */
    fun save(context: Context, credentials: JluCredentials): Boolean {
        if (credentials.studentId.isBlank() || credentials.password.isEmpty()) return false
        return runCatching {
            prefs(context).edit()
                .putString(KEY_STUDENT_ID, credentials.studentId.trim())
                .putString(KEY_PASSWORD, credentials.password)
                .commit()
        }.onFailure { Log.w(TAG, "保存加密凭据失败", it) }.getOrDefault(false)
    }

    fun load(context: Context): JluCredentials? {
        return runCatching {
            val prefs = prefs(context)
            val id = prefs.getString(KEY_STUDENT_ID, null)?.trim().orEmpty()
            val password = prefs.getString(KEY_PASSWORD, null).orEmpty()
            if (id.isEmpty() || password.isEmpty()) null else JluCredentials(id, password)
        }.onFailure { Log.w(TAG, "读取加密凭据失败", it) }.getOrNull()
    }

    fun studentId(context: Context): String? {
        return load(context)?.studentId
    }

    fun hasSaved(context: Context): Boolean = load(context) != null

    fun clear(context: Context) {
        runCatching { prefs(context) }
            .onFailure { return }
            .getOrNull()
            ?.edit()
            ?.clear()
            ?.apply()
    }

    private fun prefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (error: GeneralSecurityException) {
            Log.w(TAG, "加密凭据存储不可用，重置后重建", error)
            File(context.applicationInfo.dataDir, "shared_prefs/$PREFS_NAME.xml").delete()
            throw error
        }
    }
}
