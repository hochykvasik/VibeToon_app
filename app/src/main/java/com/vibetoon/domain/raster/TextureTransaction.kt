package com.vibetoon.domain.raster

/** Вызывать внутри барьера и не отменять coroutine после начала GL-операции. */
object TextureTransaction {
    enum class Outcome { COMMITTED, REJECTED, ROLLED_BACK, RECOVERY_REQUIRED }

    /** false от replace означает гарантированно неизменённую текстуру; исключение означает неизвестное состояние. */
    suspend fun execute(replace: suspend () -> Boolean, publish: suspend () -> Unit, rollback: suspend () -> Boolean): Outcome {
        val replaced = try { replace() } catch (_: Throwable) { return Outcome.RECOVERY_REQUIRED }
        if (!replaced) return Outcome.REJECTED
        return try {
            publish()
            Outcome.COMMITTED
        } catch (_: Throwable) {
            val restored = try { rollback() } catch (_: Throwable) { false }
            if (restored) Outcome.ROLLED_BACK else Outcome.RECOVERY_REQUIRED
        }
    }
}
