/*
 * Copyright (C) 2026 The Lujarch Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.lujarch.background.settings

import android.content.Context
import android.os.RemoteException
import com.android.internal.app.ILujarchBackground
import com.android.internal.app.LujarchBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.lujarch.settings.pages.Failure
import org.lujarch.settings.pages.R as PagesR

/** Log tag of Background's pages. */
const val TAG = "lujarch_background"

/** Background's system service, ILujarchBackground, from a page. */
object BackgroundClient {
    /** The service could not be reached. */
    class Unavailable(cause: Throwable? = null) : Exception("Background is not available", cause), Failure {
        override fun describe(context: Context): String =
            context.getString(R.string.background_error_unavailable)

        override val details: String? = cause?.toString()
        override val unavailable: Boolean = true
    }

    /** The service refused or failed a request. */
    class Refused(cause: Throwable) : Exception(cause.message, cause), Failure {
        override fun describe(context: Context): String =
            context.getString(PagesR.string.error_failed)

        override val details: String = cause.toString()
    }

    /** Calls the service on a background thread. */
    suspend fun <T> call(block: (ILujarchBackground) -> T): T =
        withContext(Dispatchers.IO) { callNow(block) }

    /** Calls the service on this thread (for App info rows, which are read off the main thread). */
    fun <T> callNow(block: (ILujarchBackground) -> T): T {
        val service = LujarchBackground.get() ?: throw Unavailable()
        return try {
            block(service)
        } catch (e: RemoteException) {
            throw Unavailable(e)
        } catch (e: IllegalStateException) {
            throw Unavailable(e)
        } catch (e: RuntimeException) {
            throw Refused(e)
        }
    }
}
