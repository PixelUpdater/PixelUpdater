/*
 * SPDX-FileCopyrightText: 2023 Pixel Updater contributors
 * SPDX-FileCopyrightText: 2023 Andrew Gunnerson
 * SPDX-FileContributor: Modified by Pixel Updater contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.github.pixelupdater.pixelupdater.updater

import android.annotation.SuppressLint
import android.content.Context
import android.net.Network
import android.os.Build
import android.os.IUpdateEngine
import android.os.IUpdateEngineCallback
import android.os.Parcelable
import android.os.PowerManager
import android.ota.OtaPackageMetadata.OtaMetadata
import android.util.Log
import com.github.pixelupdater.pixelupdater.BuildConfig
import com.github.pixelupdater.pixelupdater.Preferences
import com.github.pixelupdater.pixelupdater.extension.toSingleLineString
import com.github.pixelupdater.pixelupdater.wrapper.ServiceManagerProxy
import com.topjohnwu.superuser.Shell
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.locks.ReentrantLock
import java.util.regex.Pattern
import kotlin.concurrent.withLock
import kotlin.math.roundToInt

class UpdaterThread(
    private val context: Context,
    private val network: Network?,
    private val action: Action,
    private val listener: UpdaterThreadListener,
) : Thread() {
    private val updateEngine = IUpdateEngine.Stub.asInterface(
        ServiceManagerProxy.getServiceOrThrow("android.os.UpdateEngineService"))

    private val prefs = Preferences(context)
    // NOTE: This is not implemented.
    private val authorization: String? = null

    private lateinit var logcatProcess: Process

    // If we crash and restart while paused, the user will need to pause and unpause to resume
    // because update_engine does not report the pause state.
    var isPaused: Boolean = false
        get() = synchronized(this) { field }
        set(value) {
            synchronized(this) {
                Log.d(TAG, "Updating pause state: $value")
                if (value) {
                    updateEngine.suspend()
                } else {
                    updateEngine.resume()
                }
                field = value
            }
        }

    private var engineIsBound = false
    private val engineStatusLock = ReentrantLock()
    private val engineStatusCondition = engineStatusLock.newCondition()
    private var engineStatus = -1
    private val engineErrorLock = ReentrantLock()
    private val engineErrorCondition = engineErrorLock.newCondition()
    private var engineError = -1

    private val engineCallback = object : IUpdateEngineCallback.Stub() {
        override fun onStatusUpdate(status: Int, percentage: Float) {
            val statusMsg = UpdateEngineStatus.toString(status)
            Log.d(TAG, "onStatusUpdate($statusMsg, ${percentage * 100}%)")

            engineStatusLock.withLock {
                engineStatus = status
                engineStatusCondition.signalAll()
            }

            val max = 100
            val current = (percentage * 100).roundToInt()

            when (status) {
                UpdateEngineStatus.DOWNLOADING -> ProgressType.UPDATE
                UpdateEngineStatus.VERIFYING -> ProgressType.VERIFY
                UpdateEngineStatus.FINALIZING -> ProgressType.FINALIZE
                else -> null
            }?.let {
                listener.onUpdateProgress(this@UpdaterThread, it, current, max)
            }
        }

        override fun onPayloadApplicationComplete(errorCode: Int) {
            val errorMsg = UpdateEngineError.toString(errorCode)
            Log.d(TAG, "onPayloadApplicationComplete($errorMsg)")

            engineErrorLock.withLock {
                engineError = errorCode
                engineErrorCondition.signalAll()
            }
        }
    }

    init {
        if (action != Action.REVERT && network == null) {
            throw IllegalStateException("Network is required for non-revert actions")
        }

        updateEngine.bind(engineCallback)
        engineIsBound = true
    }

    protected fun finalize() {
        // In case the thread is somehow not started
        unbind()
    }

    private fun unbind() {
        synchronized(this) {
            if (engineIsBound) {
                updateEngine.unbind(engineCallback)
                engineIsBound = false
            }
        }
    }

    private fun waitForStatus(block: (Int) -> Boolean): Int {
        engineStatusLock.withLock {
            while (!block(engineStatus)) {
                engineStatusCondition.await()
            }
            return engineStatus
        }
    }

    private fun waitForError(block: (Int) -> Boolean): Int {
        engineErrorLock.withLock {
            while (!block(engineError)) {
                engineErrorCondition.await()
            }
            return engineError
        }
    }

    fun cancel() {
        updateEngine.cancel()
    }

    private fun openUrl(url: URL): HttpURLConnection {
        val c = network!!.openConnection(url) as HttpURLConnection
        c.connectTimeout = TIMEOUT_MS
        c.readTimeout = TIMEOUT_MS
        c.setRequestProperty("User-Agent", USER_AGENT)
        if (authorization != null) {
            c.setRequestProperty("Authorization", authorization)
        }
        return c
    }

    private fun downloadOtaPage(): List<DownloadInfo> {
        val connection = openUrl(URL(OTA_SERVER_URL))
        connection.setRequestProperty("Cookie", OTA_SERVER_COOKIE)
        connection.connect()

        if (connection.responseCode / 100 != 2) {
            throw IOException("Got ${connection.responseCode} (${connection.responseMessage}) for $OTA_SERVER_URL")
        }

        return scrapeOtaPage(connection.inputStream.bufferedReader().readText())
    }

    private fun scrapeOtaPage(otaHtml: String): List<DownloadInfo> {
        val result = mutableListOf<DownloadInfo>()

        val doc: Document = Jsoup.parse(otaHtml)
        val deviceElements: Elements = doc.select("h2")

        val buildDateMatch = Pattern.compile("\\b(\\d{6})\\b").matcher(Build.ID)
        buildDateMatch.find()
        val buildDate: String = buildDateMatch.group(1)!!

        for (deviceElement: Element in deviceElements) {
            val deviceText = deviceElement.text().trim()
            if (deviceText in listOf("Terms and conditions", "Updating instructions")) {
                continue
            }

            val deviceId = deviceElement.attr("id")
            if (deviceId != Build.DEVICE) {
                continue
            }

            // val deviceLabel = deviceElement.attr("data-text").trim('"').split(" for ")[1]
            val table = deviceElement.nextElementSibling()

            for (row: Element in table!!.select("tr").drop(1)) {
                val columns = row.select("td")
                val version = columns[0].text().trim()
                val downloadUrl = columns[1].select("a").attr("href")
                // val sha256Checksum = columns[2].text().trim()
                val dateMatch = Pattern.compile("\\b(\\d{6})\\b").matcher(version)
                dateMatch.find()
                val date: String = dateMatch.group(1)!!

                if (!prefs.allowReinstall && date.toInt() <= buildDate.toInt()) {
                    continue
                } else if (date.toInt() < buildDate.toInt()) {
                    continue
                }

                result.add(DownloadInfo(version, URL(downloadUrl), date))
            }
        }

        return result
    }

    private fun downloadOtaContentLength(downloadInfo: DownloadInfo): Long {
        val connection = openUrl(downloadInfo.url)
        @Suppress("UsePropertyAccessSyntax")
        connection.setRequestMethod("HEAD")
        connection.connect()

        if (connection.responseCode / 100 != 2) {
            throw IOException("Got ${connection.responseCode} (${connection.responseMessage}) for ${downloadInfo.url}")
        }

        return connection.contentLengthLong
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun downloadEocd(downloadInfo: DownloadInfo): Eocd {
        val contentLength = downloadOtaContentLength(downloadInfo)
        println("contentLength: $contentLength")
        val connection = openUrl(downloadInfo.url)
        connection.setRequestProperty("Range", "bytes=${contentLength - EOCD_OFFSET}-${contentLength - 1}")
        connection.connect()

        if (connection.responseCode / 100 != 2) {
            throw IOException("Got ${connection.responseCode} (${connection.responseMessage}) for ${downloadInfo.url}")
        }

        if (connection.getHeaderField("Accept-Ranges") != "bytes") {
            throw IOException("Server does not support byte ranges")
        }

        if (connection.contentLengthLong != EOCD_OFFSET) {
            throw IOException("Expected $EOCD_OFFSET bytes, but Content-Length is ${connection.contentLengthLong}")
        }

        val responseBody = connection.inputStream.readBytes()
        val eocdHeader = byteArrayOf(0x50, 0x4b, 0x05, 0x06)
        var eocdIndex = -1
        for (i in responseBody.size - EOCD_MIN_SIZE downTo 0) {
            var found = true
            for (j in eocdHeader.indices) {
                if (responseBody[i + j] != eocdHeader[j]) {
                    found = false
                    break
                }
            }
            if (found) {
                eocdIndex = i
                break
            }
        }

        if (eocdIndex == -1) {
            throw IOException("Failed to find end of central directory")
        }

        // println(responseBody.toHexString(HexFormat.Default))
        val size = ByteBuffer.wrap(responseBody.copyOfRange(eocdIndex + 12, eocdIndex + 12 + 4)).order(ByteOrder.LITTLE_ENDIAN).int
        if (size < 46 || size > 1024) {
            throw IOException("Unexpected size of central directory ($size)")
        }

        val offset = ByteBuffer.wrap(responseBody.copyOfRange(eocdIndex + 16, eocdIndex + 16 + 4)).order(ByteOrder.LITTLE_ENDIAN).int

        return Eocd(size, offset)
    }

    private fun downloadCd(downloadInfo: DownloadInfo): Map<String, PropertyFile> {
        val eocd = downloadEocd(downloadInfo)
        println("eocd: $eocd")
        val connection = openUrl(downloadInfo.url)
        connection.setRequestProperty("Range", "bytes=${eocd.offset}-${eocd.offset + eocd.size - 1}")
        connection.connect()

        if (connection.responseCode / 100 != 2) {
            throw IOException("Got ${connection.responseCode} (${connection.responseMessage}) for ${downloadInfo.url}")
        }

        if (connection.getHeaderField("Accept-Ranges") != "bytes") {
            throw IOException("Server does not support byte ranges")
        }

        if (connection.contentLengthLong != eocd.size.toLong()) {
            throw IOException("Expected ${eocd.size} bytes, but Content-Length is ${connection.contentLengthLong}")
        }

        val responseBody = connection.inputStream.readBytes()
        val propertyFiles = mutableMapOf<String, PropertyFile>()
        var offset = 0

        while (offset < responseBody.size) {
            val magic = responseBody.copyOfRange(offset, offset + 4)
            if (!magic.contentEquals(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 1, 2))) {
                throw IOException("Unexpected file header magic ($magic)")
            }

            val compressionMethod = ByteBuffer.wrap(responseBody.copyOfRange(offset + 10, offset + 10 + 2)).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
            val compressedSize = ByteBuffer.wrap(responseBody.copyOfRange(offset + 20, offset + 20 + 4)).order(ByteOrder.LITTLE_ENDIAN).int
            val n = ByteBuffer.wrap(responseBody.copyOfRange(offset + 28, offset + 28 + 2)).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
            val m = ByteBuffer.wrap(responseBody.copyOfRange(offset + 30, offset + 30 + 2)).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
            val k = ByteBuffer.wrap(responseBody.copyOfRange(offset + 32, offset + 32 + 2)).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
            val lfh = ByteBuffer.wrap(responseBody.copyOfRange(offset + 42, offset + 42 + 4)).order(ByteOrder.LITTLE_ENDIAN).int
            val filenameBytes = responseBody.copyOfRange(offset + 46, offset + 46 + n)
            val filename = String(filenameBytes, StandardCharsets.UTF_8)

            if (compressionMethod == 0) {
                propertyFiles[filename] = PropertyFile(filename, (lfh + 30 + n + m).toLong(), compressedSize.toLong())
            }

            // println("{\n" +
            //         "  \"offset\": ${offset},\n" +
            //         "  \"n\": ${n},\n" +
            //         "  \"m\": ${m},\n" +
            //         "  \"k\": ${k},\n" +
            //         "  \"lfh\": ${lfh}\n" +
            //         "}")
            offset += 46 + n + m + k
        }

        return propertyFiles
    }

    /**
     * Download a property file entry from the OTA zip. The server must support byte ranges. If the
     * server returns too few or too many bytes, then the download will fail.
     *
     * @param output Not closed by this function
     */
    private fun downloadPropertyFile(url: URL, pf: PropertyFile, output: OutputStream) {
        val connection = openUrl(url)
        connection.setRequestProperty("Range", "bytes=${pf.offset}-${pf.offset + pf.size - 1}")
        connection.connect()

        if (connection.responseCode / 100 != 2) {
            throw IOException("Got ${connection.responseCode} (${connection.responseMessage}) for $url")
        }

        if (connection.getHeaderField("Accept-Ranges") != "bytes") {
            throw IOException("Server does not support byte ranges")
        }

        if (connection.contentLengthLong != pf.size) {
            throw IOException("Expected ${pf.size} bytes, but Content-Length is ${connection.contentLengthLong}")
        }

        connection.inputStream.use { input ->
            val buf = ByteArray(16384)
            var downloaded = 0L

            while (downloaded < pf.size) {
                val toRead = java.lang.Long.min(buf.size.toLong(), pf.size - downloaded).toInt()
                val n = input.read(buf, 0, toRead)
                if (n <= 0) {
                    break
                }

                output.write(buf, 0, n)
                downloaded += n.toLong()
            }

            if (downloaded != pf.size) {
                throw IOException("Unexpected EOF after downloading $downloaded bytes (expected ${pf.size} bytes)")
            } else if (input.read() != -1) {
                throw IOException("Server returned more data than expected (expected ${pf.size} bytes)")
            }
        }
    }

    /**
     * Parse key/value pairs from properties-style files.
     *
     * The OTA property files format has equals-delimited key/value pairs, one on each line.
     * Extraneous newlines, comments, and duplicate keys are not allowed.
     */
    private fun parseKeyValuePairs(data: String): Map<String, String> {
        val result = hashMapOf<String, String>()

        for (line in data.lineSequence()) {
            if (line.isEmpty()) {
                continue
            }

            val pieces = line.split("=", limit = 2)
            if (pieces.size != 2) {
                throw BadFormatException("Invalid property file line: $line")
            } else if (pieces[0] in result) {
                throw BadFormatException("Duplicate property file key: ${pieces[0]}")
            }

            result[pieces[0]] = pieces[1]
        }

        return result
    }

    /** Download and parse key/value pairs file. */
    private fun downloadKeyValueFile(url: URL, pf: PropertyFile): Map<String, String> {
        val outputStream = ByteArrayOutputStream()
        downloadPropertyFile(url, pf, outputStream)

        return parseKeyValuePairs(outputStream.toString(Charsets.UTF_8))
    }

    /** Download the OTA metadata and validate that the update is valid for the current system. */
    private fun downloadAndCheckMetadata(url: URL, pf: PropertyFile): OtaMetadata {
        val outputStream = ByteArrayOutputStream()
        downloadPropertyFile(url, pf, outputStream)

        val metadata = OtaMetadata.newBuilder().mergeFrom(outputStream.toByteArray()).build()
        Log.d(TAG, "OTA metadata: $metadata")

        // Required
        val preDevices = metadata.precondition.deviceList
        val postSecurityPatchLevel = metadata.postcondition.securityPatchLevel
        val postTimestamp = metadata.postcondition.timestamp * 1000

        if (metadata.type != OtaMetadata.OtaType.AB) {
            throw ValidationException("Not an A/B OTA package")
        } else if (!preDevices.contains(Build.DEVICE)) {
            throw ValidationException("Mismatched device ID: " +
                    "current=${Build.DEVICE}, ota=$preDevices")
        } else if (postSecurityPatchLevel < Build.VERSION.SECURITY_PATCH) {
            // throw ValidationException("Downgrading to older security patch is not allowed: " +
            //         "current=${Build.VERSION.SECURITY_PATCH}, ota=$postSecurityPatchLevel")
        } else if (postTimestamp < Build.TIME) {
            // throw ValidationException("Downgrading to older timestamp is not allowed: " +
            //         "current=${Build.TIME}, ota=$postTimestamp")
        }

        // Optional
        val preBuildIncremental = metadata.precondition.buildIncremental
        val preBuilds = metadata.precondition.buildList

        if (preBuildIncremental.isNotEmpty() && preBuildIncremental != Build.VERSION.INCREMENTAL) {
            throw ValidationException("Mismatched incremental version: " +
                    "current=${Build.VERSION.INCREMENTAL}, ota=$preBuildIncremental")
        } else if (preBuilds.isNotEmpty() && !preBuilds.contains(Build.FINGERPRINT)) {
            throw ValidationException("Mismatched fingerprint: " +
                    "current=${Build.FINGERPRINT}, ota=$preBuilds")
        }

        return metadata
    }

    /**
     * Download the dm-verity care map to [OtaPaths.OTA_PACKAGE_DIR].
     *
     * Returns the path to the written file.
     */
    @SuppressLint("SetWorldReadable")
    private fun downloadCareMap(url: URL, pf: PropertyFile): File {
        val file = File(OtaPaths.OTA_PACKAGE_DIR, OtaPaths.CARE_MAP_NAME)

        try {
            file.outputStream().use { out ->
                downloadPropertyFile(url, pf, out)
            }
            file.setReadable(true, false)
        } catch (e: Exception) {
            file.delete()
            throw e
        }

        return file
    }

    /** Synchronously check for updates. */
    private fun checkForUpdates(): List<CheckUpdateResult> {
        println("from cache: ${prefs.otaCache}")
        if (prefs.otaCache.isNotEmpty()) {
            return Json.decodeFromString(prefs.otaCache)
        }

        val downloads = try {
            downloadOtaPage()
        } catch (e: Exception) {
            throw IOException("Failed to download update info", e)
        }



        val updates = mutableListOf<CheckUpdateResult>()
        for (ota in downloads) {
            // val ota = pickOta(downloads)!!

            // TODO: Add textbox for custom OTA url
            // val url = "https://dl.google.com/dl/android/aosp/bluejay-ota-tq3a.230805.001-fb877f31.zip"
            // val url = "https://dl.google.com/dl/android/aosp/bluejay-ota-tq3a.230901.001-1f1f0abe.zip"
            // val uri = Uri.parse(url)
            // val ota = DownloadInfo(uri.lastPathSegment!!, URL(url), "")
            Log.d(TAG, "OTA URL: ${ota.url}")
            val cd = downloadCd(ota)
            val pfMetadata = cd[OtaPaths.METADATA_NAME]!!
            val metadata = downloadAndCheckMetadata(ota.url, pfMetadata)

            if (metadata.postcondition.buildCount != 1) {
                throw ValidationException("Metadata postcondition lists multiple fingerprints")
            }
            // TODO: Use date rather than fingerprint?
            val fingerprint = metadata.postcondition.getBuild(0)

            updates.add(CheckUpdateResult(
                ota.version,
                fingerprint,
                ota.url.toString(),
                cd,
            ))
        }
        val cache = Json.encodeToString(updates)
        prefs.otaCache = cache
        println("to cache: $cache")
        return updates
    }

    private fun pickOta(downloads: List<DownloadInfo>): DownloadInfo? {
        val filtered = downloads.filter { it.version.contains("""20\d{2}\)""".toRegex()) }
        return if (filtered.isNotEmpty()) {
            filtered.minBy { it.date }
        } else {
            null
        }
    }

    /** Asynchronously trigger the update_engine payload application. */
    private fun startInstallation(otaUrl: URL, cd: Map<String, PropertyFile>) {
        val pfPayload = cd[OtaPaths.PAYLOAD_NAME]!!
        val pfPayloadProperties = cd[OtaPaths.PAYLOAD_PROPERTIES_NAME]!!
        val pfCareMap = cd[OtaPaths.CARE_MAP_NAME]!!

        Log.i(TAG, "Downloading dm-verity care map file")

        downloadCareMap(otaUrl, pfCareMap)

        Log.i(TAG, "Downloading payload properties file")

        val payloadProperties = downloadKeyValueFile(otaUrl, pfPayloadProperties)
        prefs.payloadPropertiesCache = Json.encodeToString(payloadProperties)

        Log.i(TAG, "Passing payload information to update_engine")

        val engineProperties = HashMap(payloadProperties).apply {
            put("NETWORK_ID", network!!.networkHandle.toString())
            put("USER_AGENT", USER_AGENT_UPDATE_ENGINE)

            if (authorization != null) {
                Log.i(TAG, "Passing authorization header to update_engine")
                put("AUTHORIZATION", authorization)
            }

            if (prefs.skipPostInstall) {
                put("RUN_POST_INSTALL", "0")
            }

            // TODO: Make this configurable
            put("SWITCH_SLOT_ON_REBOOT", "0")
        }


        println("url:      $otaUrl")
        println("offset:   ${pfPayload.offset}")
        println("size:     ${pfPayload.size}")
        for (property in engineProperties) {
            println("property: $property")
        }
        updateEngine.applyPayload(
            otaUrl.toString(),
            pfPayload.offset,
            pfPayload.size,
            engineProperties.map { "${it.key}=${it.value}" }.toTypedArray(),
        )
    }

    private fun switchSlot(otaUrl: URL, cd: Map<String, PropertyFile>) {
        // https://android.googlesource.com/platform/bootable/recovery/+/refs/tags/android-13.0.0_r82/updater_sample/src/com/example/android/systemupdatersample/UpdateManager.java#406
        val pfPayload = cd[OtaPaths.PAYLOAD_NAME]!!
        val payloadProperties = Json.decodeFromString<Map<String, String>>(prefs.payloadPropertiesCache)

        Log.i(TAG, "Passing payload information to update_engine")

        val engineProperties = HashMap(payloadProperties).apply {
            // https://android.googlesource.com/platform/bootable/recovery/+/refs/tags/android-13.0.0_r82/updater_sample/src/com/example/android/systemupdatersample/UpdateManager.java#408
            put("RUN_POST_INSTALL", "0")
            put("SWITCH_SLOT_ON_REBOOT", "1")
        }

        println("url:      $otaUrl")
        println("offset:   ${pfPayload.offset}")
        println("size:     ${pfPayload.size}")
        for (property in engineProperties) {
            println("property: $property")
        }
        updateEngine.applyPayload(
            otaUrl.toString(),
            pfPayload.offset,
            pfPayload.size,
            engineProperties.map { "${it.key}=${it.value}" }.toTypedArray(),
        )

    }

    private fun startLogcat() {
        assert(!this::logcatProcess.isInitialized) { "logcat already started" }

        Log.d(TAG, "Starting log file (${BuildConfig.VERSION_NAME})")

        val logcatFile = File(context.getExternalFilesDir(null),
            "${action.name.lowercase()}.log")
        logcatProcess = ProcessBuilder("logcat", "*:V")
            // This is better than -f because the logcat implementation calls fflush() when the
            // output stream is stdout.
            .redirectOutput(logcatFile)
            .redirectErrorStream(true)
            .start()
    }

    private fun stopLogcat() {
        assert(this::logcatProcess.isInitialized) { "logcat not started" }

        try {
            Log.d(TAG, "Stopping log file")

            // Give logcat a bit of time to flush the output. It does not have any special
            // handling to flush buffers when interrupted.
            sleep(1000)

            logcatProcess.destroy()
        } finally {
            logcatProcess.waitFor()
        }
    }

    @SuppressLint("WakelockTimeout")
    override fun run() {
        startLogcat()

        val pm = context.getSystemService(PowerManager::class.java)
        val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)

        try {
            wakeLock.acquire()

            listener.onUpdateProgress(this, ProgressType.INIT, 0, 0)

            Log.d(TAG, "Waiting for initial engine status")
            val status = waitForStatus { it != -1 }
            val statusStr = UpdateEngineStatus.toString(status)
            Log.d(TAG, "Initial status: $statusStr")

            if (action == Action.REVERT) {
                // if (status == UpdateEngineStatus.UPDATED_NEED_REBOOT) {
                //     Log.d(TAG, "Reverting new update because engine is pending reboot")
                    updateEngine.resetStatus()
                // } else {
                //     throw IllegalStateException("Cannot revert while in state: $statusStr")
                // }

                val newStatus = waitForStatus { it != UpdateEngineStatus.UPDATED_NEED_REBOOT }
                val newStatusStr = UpdateEngineStatus.toString(newStatus)
                Log.d(TAG, "New status after revert: $newStatusStr")

                if (newStatus == UpdateEngineStatus.IDLE) {
                    listener.onUpdateResult(this, UpdateReverted)
                } else {
                    listener.onUpdateResult(this, UpdateFailed(newStatusStr))
                }
            } else if (status == UpdateEngineStatus.UPDATED_NEED_REBOOT) {
                // Resend success notification to remind the user to reboot. We can't perform any
                // further operations besides reverting.
                listener.onUpdateResult(this, UpdateNeedReboot)
            } else {
                if (status == UpdateEngineStatus.IDLE) {
                    Log.d(TAG, "Starting new update because engine is idle")

                    listener.onUpdateProgress(this, ProgressType.CHECK, 0, 0)

                    if (action == Action.CHECK) {
                        println("clearing cache")
                        prefs.otaCache = ""
                    }

                    val checkUpdateResult = checkForUpdates()

                    if (checkUpdateResult.isEmpty()) {
                        // Update not needed
                        listener.onUpdateResult(this, UpdateUnnecessary)
                        return
                    } else if (action == Action.CHECK) {
                        if (checkUpdateResult.available().isEmpty()) {
                            listener.onUpdateResult(this, UpdateUnnecessary)
                            return
                        } else {
                            val versions = mutableListOf<Result>()
                            for ((index, update) in checkUpdateResult.available().withIndex()) {
                                println("")
                                versions.add(UpdateAvailable(update.version, index))
                            }
                            listener.onUpdateResults(this, versions)
                            return
                        }
                    }

                    val targetUpdate = checkUpdateResult.get(prefs.targetOta)
                    if (targetUpdate == null) {
                        listener.onUpdateResult(this, UpdateFailed("OTA is missing"))
                        return
                    } else {
                        if (action == Action.INSTALL) {
                            startInstallation(
                                URL(targetUpdate.otaUrl),
                                targetUpdate.cd,
                            )
                        } else if (action == Action.SWITCH_SLOT) {
                            switchSlot(
                                URL(targetUpdate.otaUrl),
                                targetUpdate.cd,
                            )

                            // TODO: Make this configurable (stock, root-only, root and disable verity/verification, etc)
                            val dest = File(context.filesDir, "flash_inactive.sh")
                            context.assets.open(dest.name).use { inputStream ->
                                dest.outputStream().use { outputStream ->
                                    inputStream.copyTo(outputStream)
                                }
                            }
                            Shell.cmd(
                                "chmod +x $dest",
                                "$dest"
                            ).exec()
                        }
                    }
                } else {
                    Log.w(TAG, "Monitoring existing update because engine is not idle")
                }

                val error = waitForError { it != -1 }
                val errorStr = UpdateEngineError.toString(error)
                Log.d(TAG, "Update engine result: $errorStr")

                if (error == UpdateEngineError.SUCCESS) {
                    Log.d(TAG, "Successfully completed upgrade")
                    listener.onUpdateResult(this, UpdateSucceeded)
                } else if (error == UpdateEngineError.UPDATED_BUT_NOT_ACTIVE) {
                    Log.d(TAG, "Successfully completed upgrade, but not active")
                    listener.onUpdateResult(this, UpdateNeedSwitchSlots)
                } else if (error == UpdateEngineError.USER_CANCELED) {
                    Log.w(TAG, "User cancelled upgrade")
                    listener.onUpdateResult(this, UpdateCancelled)
                } else {
                    throw Exception(errorStr)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install update", e)
            listener.onUpdateResult(this, UpdateFailed(e.toSingleLineString()))
        } finally {
            wakeLock.release()
            unbind()

            try {
                stopLogcat()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to dump logcat", e)
            }
        }
    }

    class BadFormatException(msg: String, cause: Throwable? = null)
        : Exception(msg, cause)

    class ValidationException(msg: String, cause: Throwable? = null)
        : Exception(msg, cause)

    @Serializable
    private data class CheckUpdateResult(
        val version: String,
        val fingerprint: String,
        val otaUrl: String,
        val cd: Map<String, PropertyFile>,
    )

    @Serializable
    private data class PropertyFile(
        val name: String,
        val offset: Long,
        val size: Long,
    )

    @Parcelize
    enum class Action : Parcelable {
        CHECK,
        INSTALL,
        REVERT,
        SWITCH_SLOT,
    }

    private fun List<CheckUpdateResult>.available() = filter { it.fingerprint != Build.FINGERPRINT || prefs.allowReinstall }
    private fun List<CheckUpdateResult>.get(version: String) = firstOrNull { it.version == version }

    private data class DownloadInfo(
        val version: String,
        val url: URL,
        // val sha256: String,
        // TODO: Use kotlinx.datetime?
        val date: String,
    )

    private data class Eocd(
        val size: Int,
        val offset: Int,
    )

    sealed interface Result {
        val isError : Boolean
    }

    data class UpdateAvailable(val version: String, val index: Int) : Result {
        override val isError = false
    }

    data object UpdateUnnecessary : Result {
        override val isError = false
    }

    data object UpdateSucceeded : Result {
        override val isError = false
    }

    data object UpdateNeedSwitchSlots : Result {
        override val isError = false
    }

    /** Update succeeded in a previous updater run. */
    data object UpdateNeedReboot : Result {
        override val isError = false
    }

    data object UpdateReverted : Result {
        override val isError = false
    }

    data object UpdateCancelled : Result {
        override val isError = true
    }

    data class UpdateFailed(val errorMsg: String) : Result {
        override val isError = true
    }

    enum class ProgressType {
        INIT,
        CHECK,
        UPDATE,
        VERIFY,
        FINALIZE,
    }

    interface UpdaterThreadListener {
        fun onUpdateResult(thread: UpdaterThread, result: Result)

        fun onUpdateResults(thread: UpdaterThread, results: List<Result>)

        fun onUpdateProgress(thread: UpdaterThread, type: ProgressType, current: Int, max: Int)
    }

    companion object {
        private val TAG = UpdaterThread::class.java.simpleName

        private const val OTA_SERVER_URL = "https://developers.google.com/android/ota"
        private const val OTA_SERVER_COOKIE = "devsite_wall_acks=nexus-image-tos,nexus-ota-tos"
        private const val USER_AGENT = "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}"
        private val USER_AGENT_UPDATE_ENGINE = "$USER_AGENT update_engine/${Build.VERSION.SDK_INT}"

        private const val EOCD_MIN_SIZE = 22
        private const val EOCD_OFFSET = 3072L
        private const val TIMEOUT_MS = 30_000
    }
}