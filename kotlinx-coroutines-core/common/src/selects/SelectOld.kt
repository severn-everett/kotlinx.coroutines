/*
 * Copyright 2016-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.selects

import kotlinx.coroutines.*
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.*

/*
 * For binary compatibility, we need to maintain the previous `select` implementations.
 * Thus, we keep [SelectBuilderImpl] and [UnbiasedSelectBuilderImpl] and implement the
 * functions marked with `@PublishedApi`.
 *
 * We keep the old `select` functions as [selectOld] and [selectUnbiasedOld] for test purpose.
 */

@PublishedApi
internal class SelectBuilderImpl<R>(
    val uCont: Continuation<R> // unintercepted delegate continuation
) : SelectImplementation<R>(uCont.context) {
    private val cont = CancellableContinuationImpl(uCont.intercepted(), MODE_CANCELLABLE)

    @PublishedApi
    internal fun getResult(): Any? {
        // In the current `select` design, the [select] and [selectUnbiased] functions
        // do not wrap the operation in `suspendCoroutineUninterceptedOrReturn` and
        // suspend explicitly via [doSelect] call, which returns the final result.
        // However, [doSelect] is a suspend function, so it cannot be invoked directly.
        //
        // As a solution, we:
        // 1) create a CancellableContinuationImpl with the provided unintercepted continuation as a delegate;
        // 2) wrap the [doSelect] call in an additional coroutine, which we launch in UNDISPATCHED mode;
        // 3) resume the created CancellableContinuationImpl after the [doSelect] invocation completes;
        // 4) use CancellableContinuationImpl.getResult() as a result of this function.
        CoroutineScope(context).launch(start = CoroutineStart.UNDISPATCHED) {
            val result = doSelect()
            cont.resumeUndispatched(result)
        }
        return cont.getResult()
    }

    @PublishedApi
    internal fun handleBuilderException(e: Throwable) {
        cont.resumeWithException(e)
    }
}

@PublishedApi
internal class UnbiasedSelectBuilderImpl<R>(
    val uCont: Continuation<R> // unintercepted delegate continuation
) : UnbiasedSelectImplementation<R>(uCont.context) {
    private val cont = CancellableContinuationImpl(uCont.intercepted(), MODE_CANCELLABLE)

    @PublishedApi
    internal fun initSelectResult(): Any? {
        // Here, we do the same trick as in [SelectBuilderImpl].
        CoroutineScope(context).launch(start = CoroutineStart.UNDISPATCHED) {
            val result = doSelect()
            cont.resumeUndispatched(result)
        }
        return cont.getResult()
    }

    @PublishedApi
    internal fun handleBuilderException(e: Throwable) {
        cont.resumeWithException(e)
    }
}

// This is the old version of `select`. It should work to guarantee binary compatibility.
internal suspend inline fun <R> selectOld(crossinline builder: SelectBuilder<R>.() -> Unit): R {
    return suspendCoroutineUninterceptedOrReturn { uCont ->
        val scope = SelectBuilderImpl(uCont)
        try {
            builder(scope)
        } catch (e: Throwable) {
            scope.handleBuilderException(e)
        }
        scope.getResult()
    }
}

// This is the old version of `selectUnbiased`. It should work to guarantee binary compatibility.
internal suspend inline fun <R> selectUnbiasedOld(crossinline builder: SelectBuilder<R>.() -> Unit): R =
    suspendCoroutineUninterceptedOrReturn { uCont ->
        val scope = UnbiasedSelectBuilderImpl(uCont)
        try {
            builder(scope)
        } catch (e: Throwable) {
            scope.handleBuilderException(e)
        }
        scope.initSelectResult()
    }

@OptIn(ExperimentalStdlibApi::class)
private fun <T> CancellableContinuation<T>.resumeUndispatched(result: T) {
    val dispatcher = context[CoroutineDispatcher]
    if (dispatcher != null) {
        dispatcher.resumeUndispatched(result)
    } else {
        resume(result)
    }
}