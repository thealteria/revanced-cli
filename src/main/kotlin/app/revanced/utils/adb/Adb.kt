package app.revanced.utils.adb

import app.revanced.cli.command.MainCommand
import app.revanced.cli.logging.CliLogger
import app.revanced.utils.adb.Constants.replacePlaceholder
import se.vidstige.jadb.JadbConnection
import se.vidstige.jadb.JadbDevice
import java.io.Closeable
import java.util.concurrent.Executors

internal sealed class Adb(deviceSerial: String) : Closeable {
    val device: JadbDevice = JadbConnection().devices.find { it.serial == deviceSerial }
        ?: throw IllegalArgumentException("The device with the serial $deviceSerial can not be found.")

    open val logger: CliLogger? = null
    abstract fun install(apk: Apk)
    abstract fun uninstall(packageName: String)


    protected fun log(packageName: String) {
        val executor = Executors.newSingleThreadExecutor()
        val pipe = if (logger != null) {
            ProcessBuilder.Redirect.INHERIT
        } else {
            ProcessBuilder.Redirect.PIPE
        }

        val process = device.buildCommand(Constants.COMMAND_LOGCAT.replacePlaceholder(packageName))
            .redirectOutput(pipe)
            .redirectError(pipe)
            .useExecutor(executor)
            .start()

        Thread.sleep(500) // give the app some time to start up.
        while (true) {
            try {
                while (device.run("${Constants.COMMAND_PID_OF} $packageName") == 0) {
                    Thread.sleep(1000)
                }
                break
            } catch (e: Exception) {
                throw RuntimeException("An error occurred while monitoring the state of app", e)
            }
        }
        MainCommand.logger.info("Stopped logging because the app was closed")
        process.destroy()
        executor.shutdown()
    }

    override fun close() {
        logger?.trace("Closed")
    }

    class RootAdb(deviceSerial: String, override val logger: CliLogger? = null) : Adb(deviceSerial) {
        init {
            if (device.run("su -h", false) != 0)
                throw IllegalArgumentException("Root required on $deviceSerial. Task failed")
        }

        override fun install(apk: Apk) {
            TODO("Install with root")
        }

        override fun uninstall(packageName: String) {
            TODO("Uninstall with root")
        }
    }

    class UserAdb(deviceSerial: String, override val logger: CliLogger? = null) : Adb(deviceSerial) {
        override fun install(apk: Apk) {
            TODO("Install without root")
        }

        override fun uninstall(packageName: String) {
            TODO("Uninstall without root")
        }

    }

    class Apk(patcherApk: app.revanced.patcher.Apk) {
        public var file = patcherApk.file
        val packageName = patcherApk.packageMetadata.packageName
        val type = when (patcherApk) {
            is app.revanced.patcher.Apk.Base -> Type.BASE
            is app.revanced.patcher.Apk.Split.Asset -> Type.ASSET
            is app.revanced.patcher.Apk.Split.Language -> Type.LANGUAGE
            is app.revanced.patcher.Apk.Split.Library -> Type.LIBRARY
        }

        enum class Type {
            BASE,
            ASSET,
            LANGUAGE,
            LIBRARY
        }
    }

    enum class InstallMode {
        SPLIT,
        FULL
    }
}