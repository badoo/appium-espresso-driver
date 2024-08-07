/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.appium.espressoserver.lib.http

import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.GsonBuilder
import fi.iki.elonen.NanoHTTPD
import io.appium.espressoserver.lib.drivers.DriverContext
import io.appium.espressoserver.lib.helpers.AndroidLogger
import io.appium.espressoserver.lib.helpers.CustomFailureHandler
import io.appium.espressoserver.lib.helpers.StringHelpers
import io.appium.espressoserver.lib.helpers.setAccessibilityServiceState
import io.appium.espressoserver.lib.http.response.AppiumResponse
import io.appium.espressoserver.lib.http.response.BaseResponse
import org.junit.rules.TestRule
import java.io.IOException
import java.net.SocketException
import java.util.*

const val DEFAULT_PORT = 6791

class Server : NanoHTTPD(DEFAULT_PORT), TestRule by DriverContext.composeTestRule {

    private var router: Router? = null

    @Volatile
    private var isStopRequestReceived: Boolean = false

    private val syncComposeClock = Thread {
        while (!isStopRequestReceived) {
            if (DriverContext.currentStrategyType == DriverContext.StrategyType.COMPOSE) {
                DriverContext.composeTestRule.mainClock.advanceTimeByFrame()
            }
            // Let Android run measure, draw and in general any other async operations. AndroidComposeTestRule.android.kt:325
            Thread.sleep(ANDROID_ASYNC_WAIT_TIME_MS)
        }
    }

    private fun buildFixedLengthResponse(response: BaseResponse): Response {
        val gsonBuilder = GsonBuilder()
                .serializeNulls()
        return newFixedLengthResponse(response.httpStatus,
                "application/json", gsonBuilder.create().toJson(response))
    }

    fun run() {
        try {
            start()
            syncComposeClock.start()
            while (!isStopRequestReceived) {
                Thread.sleep(1000)
            }
        } finally {
            stop()
            syncComposeClock.join()
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val files = LinkedHashMap<String, String>()
        val response = try {
            session.parseBody(files)
            router!!.route(session.uri, session.method, files)
        } catch (e: Exception) {
            when (e) {
                is RuntimeException, is IOException, is ResponseException ->
                    AppiumResponse(e)
                else -> throw e
            }
        }

        if (response is AppiumResponse) {
            if (response.httpStatus === Response.Status.OK) {
                AndroidLogger.info("Responding to server with value: " +
                        StringHelpers.abbreviate(response.value?.toString(), 300))
            } else {
                AndroidLogger.info("Responding to server with error: ${response.value}")
            }
        }

        return try {
            buildFixedLengthResponse(response)
        } catch (e: RuntimeException) {
            buildFixedLengthResponse(AppiumResponse(e))
        }

    }

    @Throws(IOException::class)
    override fun start() {
        if (super.isAlive()) {
            //kill the server if its already running
            try {
                super.stop()
            } catch (e: Exception) {
                //ignore the exception
            }
        }

        setAccessibilityServiceState()
        setCustomFailureHandler()

        try {
            super.start(SOCKET_READ_TIMEOUT, false)
        } catch (e: SocketException) {
            throw IllegalStateException("The application under test must require android.permission.INTERNET " +
                    "permission in its manifest", e)
        }

        AndroidLogger.info("\nRunning Appium Espresso Server at port $DEFAULT_PORT\n")
        router = Router(this)
    }

    private fun setCustomFailureHandler() =
        Espresso.setFailureHandler(
            CustomFailureHandler(InstrumentationRegistry.getInstrumentation().targetContext)
        )

    override fun stop() {
        super.stop()
        AndroidLogger.info("\nStopping Appium Espresso at port $DEFAULT_PORT\n")
    }

    fun makeRequestForServerToStop() {
        isStopRequestReceived = true
    }

    private companion object {
        const val ANDROID_ASYNC_WAIT_TIME_MS = 10L
    }
}
