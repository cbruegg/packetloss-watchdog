import com.squareup.moshi.Moshi
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.internal.closeQuietly
import okhttp3.logging.HttpLoggingInterceptor
import org.jsoup.Jsoup
import org.mozilla.javascript.Context
import org.mozilla.javascript.ScriptableObject
import ru.gildor.coroutines.okhttp.await
import java.io.IOException
import java.lang.Exception
import java.time.*
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.collections.HashMap
import kotlin.math.roundToInt
import kotlin.streams.asSequence
import kotlin.time.*
import kotlin.time.Duration
import kotlinx.coroutines.DEBUG_PROPERTY_NAME as COROUTINES_DEBUG_PROPERTY_NAME
import kotlinx.coroutines.DEBUG_PROPERTY_VALUE_ON as COROUTINES_DEBUG_PROPERTY_VALUE_ON

const val RouterIpDefault = "192.168.0.1"
const val PingTargetDefault = "1.1.1.1"

@ExperimentalTime
val DurationBetweenMeasurementsDefault = 30.toDuration(DurationUnit.MINUTES)

@ExperimentalTime
val MeasurementDurationDefault = 3.toDuration(DurationUnit.MINUTES)

const val PacketLossTooHighThresholdDefault = 0.04

val RestartTimeDefault: LocalTime = LocalTime.of(5, 0)

const val CancelPendingRestartAfterHowManyNormalMeasurementsDefault = 4

@ExperimentalTime
val MeasurementDelayAfterRestartDefault = 30.toDuration(DurationUnit.MINUTES)

@ExperimentalTime
suspend fun main(args: Array<String>) {
    if ("linux" !in System.getProperty("os.name").toLowerCase()) {
        System.err.println("Only Linux is supported!")
        return
    }

    if ("-h" in args) {
        println(
            """
            Usage:
            -h: Print this message
            -v: Verbose output
            
            Environment variables:
            PLWD_ROUTER_IP:                                  Vodafone Station address. (Defaults to $RouterIpDefault)
            PLWD_ROUTER_PASSWORD:                            Vodafone Station admin password.
            PLWD_PING_TARGET:                                The host to measure the packet loss with. (Defaults to $PingTargetDefault)
            PLWD_DURATION_BETWEEN_MEASUREMENTS_MS:           Delay between measurements. (Defaults to $DurationBetweenMeasurementsDefault)
            PLWD_TOO_HIGH_THRESHOLD:                         If this packet loss value is exceeded, a router restart is scheduled. (Defaults to $PacketLossTooHighThresholdDefault for ${(PacketLossTooHighThresholdDefault * 100).roundToInt()} % Packet Loss)
            PLWD_RESTART_TIME:                               The time of day at which the router should be restarted. (Defaults to $RestartTimeDefault)
            PLWD_CANCEL_PENDING_AFTER_NORMAL_MEASUREMENTS:   After this number of non-exceeding measurements, any pending router restart is canceled. (Defaults to $CancelPendingRestartAfterHowManyNormalMeasurementsDefault)
            PLWD_MEASUREMENT_DELAY_AFTER_RESTART_MS:         The duration to wait until measurements should be resumed after a router restart. (Defaults to $MeasurementDelayAfterRestartDefault)
        """.trimIndent()
        )
        return
    }

    System.setProperty(COROUTINES_DEBUG_PROPERTY_NAME, COROUTINES_DEBUG_PROPERTY_VALUE_ON)

    val verbose = "-v" in args
    val env = System.getenv()
    val routerIp = env["PLWD_ROUTER_IP"] ?: RouterIpDefault
    val routerPassword = env["PLWD_ROUTER_PASSWORD"] ?: error("PLWD_ROUTER_PASSWORD environment variable is not set!")
    val pingTarget = env["PLWD_PING_TARGET"] ?: PingTargetDefault
    val durationBetweenMeasurements =
        env["PLWD_DURATION_BETWEEN_MEASUREMENTS_MS"]?.toLongOrNull()?.toDuration(DurationUnit.MILLISECONDS)
            ?: DurationBetweenMeasurementsDefault
    val measurementDuration = env["PLWD_MEASUREMENT_DURATION_MS"]?.toLongOrNull()?.toDuration(DurationUnit.MILLISECONDS)
        ?: MeasurementDurationDefault
    val packetLossTooHighThreshold =
        env["PLWD_TOO_HIGH_THRESHOLD"]?.toDoubleOrNull() ?: PacketLossTooHighThresholdDefault
    val restartTime = env["PLWD_RESTART_TIME"]?.let { LocalTime.parse(it) } ?: RestartTimeDefault
    val cancelPendingRestartAfterHowManyNormalMeasurements =
        env["PLWD_CANCEL_PENDING_AFTER_NORMAL_MEASUREMENTS"]?.toIntOrNull()
            ?: CancelPendingRestartAfterHowManyNormalMeasurementsDefault
    val measurementDelayAfterRestart =
        env["PLWD_MEASUREMENT_DELAY_AFTER_RESTART_MS"]?.toLongOrNull()?.toDuration(TimeUnit.MILLISECONDS)
            ?: MeasurementDelayAfterRestartDefault

    println(
        """
        Using the following configuration to measure and eliminate packet loss:
        Router IP:                        $routerIp
        Ping Target:                      $pingTarget
        Duration between Measurements:    $durationBetweenMeasurements
        Measurement Duration:             $measurementDuration
        Packet Loss too High Threshold:   ${packetLossTooHighThreshold * 100} %
        Restart Time:                     $restartTime
        Cancel Pending Restart After:     $cancelPendingRestartAfterHowManyNormalMeasurements normal measurements
        Measurement Delay After Restart:  $measurementDelayAfterRestart
    """.trimIndent()
    )

    var nextRestart: LocalDateTime? = null
    var normalMeasurementCount = 0
    while (true) {
        if (nextRestart?.isAfter(LocalDateTime.now()) == false) {
            log("Restarting router now!")
            restartRouter(routerIp, routerPassword)
            nextRestart = null
            log("Waiting for $measurementDelayAfterRestart before resuming measurements...")
            delay(measurementDelayAfterRestart)
            log("Resuming measurements.")
        } else {
            log("Starting next measurement...")
            val packetLoss = measurePacketLoss(pingTarget, measurementDuration)
            if (packetLoss >= packetLossTooHighThreshold) {
                val todayRestartTime = restartTime.atDate(LocalDate.now())
                val tomorrowRestartTime = restartTime.atDate(LocalDate.now().plusDays(1))
                normalMeasurementCount = 0
                nextRestart =
                    if (LocalDateTime.now().isBefore(todayRestartTime)) todayRestartTime else tomorrowRestartTime
                log("Measured a packet loss of ${(packetLoss * 100).roundToInt()} %, scheduling restart at $nextRestart.")
            } else {
                if (verbose) {
                    log("Measured a normal packet loss of ${(packetLoss * 100).roundToInt()} %")
                }
                normalMeasurementCount++
                if (normalMeasurementCount >= cancelPendingRestartAfterHowManyNormalMeasurements && nextRestart != null) {
                    log("$normalMeasurementCount normal measurements in a row, canceling pending restart.")
                    nextRestart = null
                }
            }
        }

        val nextMeasurementDue = Instant.now().plus(durationBetweenMeasurements.toJavaDuration())
        val nextRestartDue = nextRestart?.atZone(ZoneId.systemDefault())?.toInstant()
        val nextWakeupDue = minOf(nextRestartDue ?: Instant.MAX, nextMeasurementDue)
        nextWakeupDue.delayUntil(verbose)
    }
}

suspend fun Instant.delayUntil(verbose: Boolean) {
    while (Instant.now().isBefore(this)) {
        val delayDuration = Instant.now().until(this, ChronoUnit.MILLIS)
        if (verbose) {
            log("Sleeping for $delayDuration ms...")
        }
        delay(delayDuration)
    }
}

val packetLossRegex = Regex.fromLiteral(".*?, ([0-9]+)% packet loss, .*?")

typealias ZeroToOne = Double

@ExperimentalTime
suspend fun measurePacketLoss(pingTarget: String, measurementDuration: Duration): ZeroToOne {
    return withContext(Dispatchers.IO) {
        val durationSeconds = measurementDuration.inSeconds.roundToInt()
        val packetLossMatch = ProcessBuilder("ping", "-i", "0.2", "-w", durationSeconds.toString(), pingTarget)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
            .inputStream
            .use { cmdResult ->
                val pingLines = cmdResult.bufferedReader().lines().asSequence()
                pingLines.mapNotNull { packetLossRegex.find(it) }.firstOrNull()
            }

        packetLossMatch?.groupValues?.get(1)?.toDoubleOrNull()?.div(100) ?: 0.0
    }
}

suspend fun restartRouter(ip: String, password: String) {
    repeat(3) {
        try {
            log("Trying to restart router...")
            restartRouterInternal(ip, password)
            log("No errors during router restart. Assuming restart was successful.")
            return
        } catch (e: Exception) {
            loge("Could not restart router! Reason:")
            e.printStackTrace()
        }
    }
    loge("Giving up on retrying!")
}

suspend fun restartRouterInternal(ip: String, password: String) {
    val cookieJar = InsecureCookieJar()
    val http = OkHttpClient.Builder().cookieJar(cookieJar)
        .addNetworkInterceptor(HttpLoggingInterceptor(object : HttpLoggingInterceptor.Logger {
            override fun log(message: String) {
                println(message)
            }
        }).apply { level = HttpLoggingInterceptor.Level.BODY }).build()
    val moshi = Moshi.Builder().build()
    val moshiMapAdapter = moshi.adapter(Map::class.java)

    val sjclReq = Request.Builder().url("http://$ip/scripts/sjcl.js").build()
    val sjclCryptoReq = Request.Builder().url("http://$ip/scripts/sjclCrypto.js").build()
    val overviewHtmlReq = Request.Builder().url("http://$ip").build()
    val base95xReq = Request.Builder().url("http://$ip/base_95x.js").build()

    val (sjcl, sjclCrypto, overviewHtml, base95x) = coroutineScope {
        val sjclAsync = async(Dispatchers.IO) {
            http.newCall(sjclReq).await().body?.string() ?: throw IOException("Could not load sjcl")
        }
        val sjclCryptoAsync = async(Dispatchers.IO) {
            http.newCall(sjclCryptoReq).await().body?.string() ?: throw IOException("Could not load sjclCrypto")
        }
        val overviewHtmlAsync = async(Dispatchers.IO) {
            http.newCall(overviewHtmlReq).await().body?.string() ?: throw IOException("Could not load overview HTML")
        }
        val base95xAsync = async(Dispatchers.IO) {
            http.newCall(base95xReq).await().body?.string() ?: throw IOException("Could not load base95x")
        }
        listOf(sjclAsync, sjclCryptoAsync, overviewHtmlAsync, base95xAsync).awaitAll()
    }

    val parsedOverview = Jsoup.parse(overviewHtml)
    val overviewScripts = parsedOverview.head().getElementsByTag("script").toList()

    val credentialRegex = Regex("""createCookie\s*?\(\s*?"credential"\s*?,\s*?"(.*?)"\s*\)""")
    val credential = credentialRegex.find(base95x)?.groupValues?.getOrNull(1)
        ?: throw IOException("Could not retrieve credential cookie!")

    cookieJar += Cookie.Builder().name("credential").value(credential).domain(ip).build()

    // This contains 3 important declarations: currentSessionId, myIv and mySalt
    val currentSessionDeclarationRegex = Regex("(var|let|const) +currentSessionId")
    val initVarsScript = overviewScripts.map { it.data() }.first { currentSessionDeclarationRegex in it }

    val jsCoContext = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    val js = withContext(jsCoContext) { Context.enter() }
    try {
        val scope = withContext(jsCoContext) { js.initSafeStandardObjects() }
        withContext(jsCoContext) {
            js.evaluateString(scope, sjcl, "sjcl", 1, null)
            js.evaluateString(scope, sjclCrypto, "sjclCrypto", 1, null)
            js.evaluateString(scope, initVarsScript, "initVarsScript", 1, null)
        }

        // Emulation of `function login(name, password)`
        val createLoginDataJs = """
            function createLoginData(name, password) {
                const jsData = '{"Password": "'+ password +'", "Nonce": "' + currentSessionId + '"}';
                const key = sjclPbkdf2(password, mySalt, DEFAULT_SJCL_ITERATIONS, DEFAULT_SJCL_KEYSIZEBITS);
                const authData = "loginPassword";
                const encryptData = sjclCCMencrypt(key, jsData, myIv, authData, DEFAULT_SJCL_TAGLENGTH);
                
                return { 
                    "EncryptData": encryptData, 
                    "Name": name, 
                    "AuthData": authData,
                    "_key": key // Not to be sent with the request
                };
            }
        """.trimIndent()

        val (loginData, key) = withContext(jsCoContext) {
            val createLoginData = js.compileFunction(scope, createLoginDataJs, "createLoginDataJs", 1, null)
            val thisObj = js.newObject(scope)
            val loginDataJs =
                createLoginData.call(js, scope, thisObj, arrayOf("admin", password)) as ScriptableObject
            val encryptData = loginDataJs["EncryptData"] as String
            val name = loginDataJs["Name"] as String
            val authData = loginDataJs["AuthData"] as String
            val key = loginDataJs["_key"] as String
            val loginData = mapOf("EncryptData" to encryptData, "Name" to name, "AuthData" to authData)
            listOf(loginData, key)
        }
        loginData as Map<*, *>

        val loginDataJson = moshiMapAdapter.toJson(loginData)
        val setPasswordReq = Request.Builder()
            .url("http://$ip/php/ajaxSet_Password.php")
            .put(loginDataJson.toRequestBody("application/json".toMediaType()))
            .build()

        val setPasswordResponseBody = withContext(Dispatchers.IO) {
            val setPasswordResponseBody = http.newCall(setPasswordReq).await().body?.string()?.trim()
            if (setPasswordResponseBody != null) {
                moshiMapAdapter.fromJson(setPasswordResponseBody) as Map<String, String>
            } else {
                throw IOException("ajaxSet_Password failed!")
            }
        }

        val decryptedNonce = run {
            val decryptNonceJs = """
                function decryptNonce(key, encryptedNonce) {
                    return sjclCCMdecrypt(key, encryptedNonce, myIv, "nonce", DEFAULT_SJCL_TAGLENGTH);
                }
            """.trimIndent()

            withContext(jsCoContext) {
                val decryptNonce = js.compileFunction(scope, decryptNonceJs, "decryptNonceJs", 1, null)
                val thisObj = js.newObject(scope)
                decryptNonce.call(js, scope, thisObj, arrayOf(key, setPasswordResponseBody["encryptData"])) as String
            }
        }

        val overviewPageReq = Request.Builder()
            .url("http://$ip/")
            .header(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9"
            )
            .header("Referer", "http://$ip/?overview")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/80.0.3987.163 Safari/537.36"
            )
            .build()
        withContext(Dispatchers.IO) { http.newCall(overviewPageReq).await().body?.string() }

        val setSessionReq = Request.Builder()
            .url("http://$ip/php/ajaxSet_Session.php")
            .post("".toRequestBody())
            .header("csrfNonce", decryptedNonce)
            .header("Accept", "*/*")
            .header("Origin", "http://$ip")
            .header("Referer", "http://$ip/")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/80.0.3987.163 Safari/537.36"
            )
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        withContext(Dispatchers.IO) { http.newCall(setSessionReq).await().body?.string() }

        val restartPageReq = Request.Builder()
            .url("http://$ip/?status_restart&mid=StatusRestart")
            .build()
        withContext(Dispatchers.IO) { http.newCall(restartPageReq).await().body?.string() }

        val restartReq = Request.Builder()
            .url("http://$ip/php/ajaxSet_status_restart.php")
            .put("""{"RestartReset":"Restart"}""".toRequestBody("application/json".toMediaType()))
            .header("csrfNonce", decryptedNonce)
            .header("Accept", "*/*")
            .header("Origin", "http://$ip")
            .header("Referer", "http://$ip/?status_restart&mid=StatusRestart")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/80.0.3987.163 Safari/537.36"
            )
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        val restartResponse = http.newCall(restartReq).await()
        println("${restartResponse.code} ${withContext(Dispatchers.IO) { restartResponse.body?.string() }}")
    } finally {
        withContext(jsCoContext) {
            Context.exit()
        }
        jsCoContext.close()
    }
}

class InsecureCookieJar : CookieJar {

    private val cookies: MutableMap<String, Cookie> = HashMap()

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        return cookies.map { it.value }
    }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        this.cookies += cookies.map { it.name to it }
    }

    @Synchronized
    operator fun plusAssign(cookie: Cookie) {
        cookies += cookie.name to cookie
    }
}

fun log(message: String) {
    println("[${LocalDateTime.now()}] $message")
}

fun loge(message: String) {
    System.err.println("[${LocalDateTime.now()}] $message")
}