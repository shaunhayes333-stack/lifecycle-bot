package android.content

/** Test-only Android preference API adapter. The production source cohort, selection and persistence code runs unchanged. */
open class Context {
    companion object { const val MODE_PRIVATE = 0 }
    private val preferences = java.util.concurrent.ConcurrentHashMap<String, SharedPreferences>()
    open val applicationContext: Context get() = this
    open fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        preferences.computeIfAbsent(name) { SharedPreferences() }
}

class SharedPreferences {
    private val values = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile var commitSucceeds: Boolean = true
    val commitAttempts = java.util.concurrent.atomic.AtomicInteger()
    fun getString(key: String, fallback: String?): String? = values[key] ?: fallback
    fun edit(): Editor = Editor(this, values)
    class Editor(private val owner: SharedPreferences, private val target: java.util.concurrent.ConcurrentHashMap<String, String>) {
        private val pending = LinkedHashMap<String, String>()
        fun putString(key: String, value: String): Editor = apply { pending[key] = value }
        fun apply() { target.putAll(pending) }
        fun commit(): Boolean {
            owner.commitAttempts.incrementAndGet()
            if (!owner.commitSucceeds) return false
            target.putAll(pending)
            return true
        }
    }
}
