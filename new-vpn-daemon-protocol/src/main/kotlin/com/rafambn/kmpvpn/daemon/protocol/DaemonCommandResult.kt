package com.rafambn.kmpvpn.daemon.protocol

import kotlinx.serialization.Serializable

@Serializable
sealed class DaemonCommandResult<out S> {
    @Serializable
    data class Success<out S>(
        val data: S,
    ) : DaemonCommandResult<S>() {
        override fun toString(): String {
            return "Success(data=$data)"
        }
    }

    @Serializable
    data class Failure(
        val kind: DaemonErrorKind,
        val message: String,
    ) : DaemonCommandResult<Nothing>() {
        override fun toString(): String {
            return "Failure(kind=$kind, message=$message)"
        }
    }

    val isSuccess: Boolean
        get() = this is Success<*>

    val isFailure: Boolean
        get() = this is Failure

    inline fun onSuccess(action: (Success<S>) -> Unit): DaemonCommandResult<S> {
        if (this is Success<S>) {
            action(this)
        }
        return this
    }

    inline fun onFailure(action: (Failure) -> Unit): DaemonCommandResult<S> {
        if (this is Failure) {
            action(this)
        }
        return this
    }

    companion object {
        fun <S> success(data: S): DaemonCommandResult<S> {
            return Success(data = data)
        }

        fun <S> failure(
            kind: DaemonErrorKind,
            message: String,
        ): DaemonCommandResult<S> {
            return Failure(
                kind = kind,
                message = message,
            )
        }
    }
}
