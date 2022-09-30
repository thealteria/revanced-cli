package app.revanced.cli.command

import app.revanced.cli.aligning.Aligning
import app.revanced.cli.logging.impl.DefaultCliLogger
import app.revanced.cli.patcher.Patcher.start
import app.revanced.cli.patcher.logging.impl.PatcherLogger
import app.revanced.cli.signing.Signing
import app.revanced.cli.signing.SigningOptions
import app.revanced.patcher.Apk
import app.revanced.patcher.Patcher
import app.revanced.patcher.PatcherOptions
import app.revanced.patcher.extensions.PatchExtensions.compatiblePackages
import app.revanced.patcher.extensions.PatchExtensions.description
import app.revanced.patcher.extensions.PatchExtensions.patchName
import app.revanced.patcher.util.patch.impl.JarPatchBundle
import app.revanced.utils.OptionsLoader
import app.revanced.utils.adb.Adb
import app.revanced.utils.filesystem.FileSystemUtils
import picocli.CommandLine.*
import java.io.File

private class CLIVersionProvider : IVersionProvider {
    override fun getVersion() = arrayOf(
        MainCommand::class.java.`package`.implementationVersion ?: "unknown"
    )
}

@Command(
    name = "ReVanced-CLI",
    mixinStandardHelpOptions = true,
    versionProvider = CLIVersionProvider::class
)
internal object MainCommand : Runnable {
    val logger = DefaultCliLogger()

    @ArgGroup(exclusive = false, multiplicity = "1")
    lateinit var args: Args

    /**
     * Arguments for the CLI
     */
    class Args {
        @ArgGroup(exclusive = false, multiplicity = "1")
        val apkArgs: ApkArgs? = null

        @Option(names = ["--uninstall"], description = ["Uninstall the mounted apk by the package name"])
        var uninstall: String? = null

        @Option(names = ["-d", "--deploy-on"], description = ["If specified, deploy to adb device with given name"])
        var deploy: String? = null

        @Option(names = ["--mount"], description = ["If specified, instead of installing, mount"])
        var mount: Boolean = false

        @ArgGroup(exclusive = false)
        var patchArgs: PatchArgs? = null
    }

    /**
     * Arguments for apk files.
     */
    class ApkArgs {
        @Option(names = ["-a", "-apk"], description = ["The base apk file that is to be patched"], required = true)
        lateinit var baseApk: String

        @Option(names = ["--language-apk"], description = ["Additional split apk file which contains language files"])
        var languageApk: String? = null

        @Option(names = ["--library-apk"], description = ["Additional split apk file which contains libraries"])
        var libraryApk: String? = null

        @Option(names = ["--asset-apk"], description = ["Additional split apk file which contains assets"])
        var assetApk: String? = null
    }

    /**
     * Arguments for patches.
     */
    class PatchArgs {
        @Option(names = ["-b", "--bundle"], description = ["One or more bundles of patches"], required = true)
        var patchBundles = arrayOf<String>()

        @Option(names = ["--options"], description = ["Configuration file for all patch options"])
        var options: File = File("options.toml")

        @ArgGroup(exclusive = false)
        var listingArgs: ListingArgs? = null

        @ArgGroup(exclusive = false)
        var patchingArgs: PatchingArgs? = null
    }

    /**
     * Arguments for printing patches.
     */
    class ListingArgs {
        @Option(names = ["-l", "--list"], description = ["List patches only"], required = true)
        var listOnly: Boolean = false

        @Option(names = ["--with-versions"], description = ["List patches with compatible versions"])
        var withVersions: Boolean = false

        @Option(names = ["--with-packages"], description = ["List patches with compatible packages"])
        var withPackages: Boolean = false

        @Option(names = ["--with-descriptions"], description = ["List patches with their descriptions"])
        var withDescriptions: Boolean = true
    }

    /**
     * Arguments for patching.
     */
    class PatchingArgs {
        @Option(names = ["-o", "--out"], description = ["Output folder path"], required = true)
        lateinit var outputPath: File

        @Option(names = ["-e", "--exclude"], description = ["Explicitly exclude patches"])
        var excludedPatches = arrayOf<String>()

        @Option(
            names = ["--exclusive"],
            description = ["Only installs the patches you include, excluding patches by default"]
        )
        var defaultExclude = false

        @Option(names = ["-i", "--include"], description = ["Include patches"])
        var includedPatches = arrayOf<String>()

        @Option(names = ["--experimental"], description = ["Disable patch version compatibility patch"])
        var experimental: Boolean = false

        @Option(names = ["-m", "--merge"], description = ["One or more dex file containers to merge"])
        var mergeFiles = listOf<File>()

        @Option(names = ["--cn"], description = ["Overwrite the default CN for the signed file"])
        var cn = "ReVanced"

        @Option(names = ["--keystore"], description = ["File path to your keystore"])
        var keystorePath: String? = null

        @Option(names = ["-p", "--password"], description = ["Overwrite the default password for the signed file"])
        var password = "ReVanced"

        @Option(names = ["-t", "--temp-dir"], description = ["Temporary resource cache directory"])
        var cacheDirectory = File("revanced-cache")

        @Option(
            names = ["-c", "--clean"],
            description = ["Clean the temporary resource cache directory. This will always be done before running the patcher"]
        )
        var clean: Boolean = false

        @Option(names = ["--custom-aapt2-binary"], description = ["Path to custom aapt2 binary"])
        var aaptPath: String = ""

        @Option(
            names = ["--low-storage"],
            description = ["Minimizes storage usage by trying to cache as little as possible"]
        )
        var lowStorage: Boolean = false
    }

    override fun run() {
        // other types of commands
        // TODO: convert this code to picocli subcommands
        if (args.patchArgs?.listingArgs?.listOnly == true) return printListOfPatches()
        if (args.uninstall != null) return uninstall()

        // patching commands require these arguments
        val patchArgs = this.args.patchArgs ?: return
        val patchingArgs = patchArgs.patchingArgs ?: return

        // prepare the cache directory, delete it if it already exists
        val cacheDirectory = patchingArgs.cacheDirectory.also {
            if (!it.deleteRecursively())
                return logger.error("Failed to delete cache directory")
        }

        // prepare apks
        val apkArgs = args.apkArgs!!
        val baseApk = Apk.Base(apkArgs.baseApk)
        val splitApks = buildList {
            apkArgs.languageApk?.let { add(Apk.Split.Language(it)) }
            apkArgs.libraryApk?.let { add(Apk.Split.Library(it)) }
            apkArgs.assetApk?.let { add(Apk.Split.Asset(it)) }
        }

        val allApks = mutableListOf<Apk>()
            .also { it.addAll(splitApks) }
            .also { it.add(baseApk) }

        // prepare patches
        val allPatches = patchArgs.patchBundles.flatMap { bundle -> JarPatchBundle(bundle).loadPatches() }.also {
            OptionsLoader.init(patchArgs.options, it)
        }

        // prepare the patcher
        val patcher = Patcher( // constructor decodes base
            PatcherOptions(
                allApks,
                resourceCacheDirectory = patchingArgs.cacheDirectory.path,
                patchingArgs.aaptPath,
                frameworkPath = patchingArgs.cacheDirectory.path,
                PatcherLogger
            )
        )

        // prepare adb
        val adb: Adb? = args.deploy?.let { device ->
            if (args.mount) {
                Adb.RootAdb(device, logger)
            } else {
                Adb.UserAdb(device, logger)
            }
        }

        // define temporal directories
        val rawDirectory = cacheDirectory.resolve("raw")
        val alignedDirectory = cacheDirectory.resolve("aligned").also(File::mkdirs)
        val signedDirectory = cacheDirectory.resolve("signed").also(File::mkdirs)

        /**
         * Clean up a temporal directory.
         *
         * @param directory The directory to clean up.
         */
        fun delete(directory: File, force: Boolean = false) {
            if (!force && !patchingArgs.lowStorage) return
            if (!directory.deleteRecursively())
                return logger.error("Failed to delete directory $directory")
        }

        /**
         * Creates the apk file with the patches resources.
         *
         * @param apk The apk file to write.
         * @return The new patched apk file.
         */
        fun writeToNewApk(apk: Apk): File {
            val packageName = apk.packageMetadata.packageName

            /**
             * Copies the patched apk file to the file specified.
             *
             * @param file The file to copy to.
             */
            fun copyToFile(file: File) {
                FileSystemUtils(file).use { apkFileSystem ->
                    // copy resources for that apk to the cached apk
                    apk.resources?.let { apkResources ->
                        logger.info("Writing resources for $packageName")
                        FileSystemUtils(apkResources).use { resourcesFileStream ->
                            // get the resources from the resources file and write them to the cached apk
                            val resourceFiles = resourcesFileStream.getFile(File.separator)
                            apkFileSystem.writePathRecursively(resourceFiles)
                        }

                        // store resources which are doNotCompress
                        // TODO(perf): make FileSystemUtils not compress by default
                        //  by using app.revanced.utils.signing.align.zip.ZipFile
                        apk.packageMetadata.doNotCompress.forEach(apkFileSystem::uncompress)
                    }

                    // copy dex files for that apk to the cached apk, if it is a base apk
                    if (apk is Apk.Base) {
                        logger.info("Writing dex files for $packageName")
                        apk.dexFiles.forEach { dexFile ->
                            apkFileSystem.write(dexFile.name, dexFile.stream.readAllBytes())
                        }
                    }
                }
            }

            return rawDirectory.resolve(apk.file.name) // no need to mkdirs, because copyTo will create the path
                .also { apk.file.copyTo(it) } // write a copy of the original file
                .also(::copyToFile) // write patches to that file
        }

        /**
         * Alin the raw apk file.
         *
         * @param unalignedApkFile The apk file to align.
         * @return The aligned apk file.
         */
        fun alignApk(unalignedApkFile: File): File {
            logger.info("Aligning ${unalignedApkFile.name}")
            return alignedDirectory.resolve(unalignedApkFile.name)
                .also { alignedApk -> Aligning.align(unalignedApkFile, alignedApk) }
        }

        /**
         * Sign a list of apk files.
         *
         * @param unsignedApks The list of apk files to sign.
         */
        fun signApks(unsignedApks: List<File>) = if (!args.mount) {
            unsignedApks.map { unsignedApk -> // sign the unsigned apk
                logger.info("Signing ${unsignedApk.name}")
                signedDirectory.resolve(unsignedApk.name)
                    .also { signedApk ->
                        Signing.sign(
                            unsignedApk,
                            signedApk,
                            SigningOptions(
                                patchingArgs.cn,
                                patchingArgs.password,
                                patchingArgs.keystorePath ?: patchingArgs
                                    .outputPath
                                    .absoluteFile
                                    .parentFile
                                    .resolve("${baseApk.file.nameWithoutExtension}.keystore")
                                    .canonicalPath
                            )
                        )
                    }
            }
        } else {
            unsignedApks
        }

        /**
         * Copy an apk file to the output directory.
         *
         * @param apk The apk file to copy.
         */
        fun copyToOutput(apk: File): File {
            logger.info("Copying ${apk.name} to output directory")
            return patchingArgs.outputPath.resolve(apk.name).also {
                apk.copyTo(it, overwrite = true)
            }
        }

        /**
         * Install an apk file to the device.
         *
         * @param apkFile The apk file to install.
         * @return The input apk file.
         */
        fun install(apkFile: Pair<File, Apk>): File {
            val (outputFile, apk) = apkFile
            adb?.install(Adb.Apk(apk).also { it.file = outputFile })
            return outputFile
        }

        /**
         * Clean up the cache directory and output files.
         *
         * @param outputApks The list of output apk files.
         */
        fun cleanUp(outputApks: List<File>) {
            // clean up the cache directory if needed
            if (patchingArgs.clean) {
                delete(patchingArgs.cacheDirectory, true)
                if (args.deploy?.let { outputApks.any { !it.delete() } } == true)
                    logger.error("Failed to delete some output files")
            }
            logger.info("Patching complete!")

        }

        /**
         * Run the patcher and save the patched resources
         */
        fun Patcher.run() = this.also {
            // start patching
            it.start(allPatches, baseApk)
        }.save() // save the patched resources

        val patchedApks = patcher.run().files
        patchedApks
            .map(::writeToNewApk)
            .map(::alignApk).also { delete(rawDirectory) }
            .let(::signApks).also { delete(alignedDirectory) }
            .map(::copyToOutput).also { delete(signedDirectory) }.zip(patchedApks)
            .map(::install)
            .let(::cleanUp)
    }

    private fun uninstall() {
        args.uninstall?.let { packageName ->
            args.deploy?.let { device ->
                Adb.RootAdb(device, logger).uninstall(packageName)
            } ?: return logger.error("You must specify a device to uninstall from")
        }
    }

    private fun printListOfPatches() {
        val logged = mutableListOf<String>()
        for (patchBundlePath in args.patchArgs?.patchBundles!!) for (patch in JarPatchBundle(patchBundlePath).loadPatches()) {
            if (patch.patchName in logged) continue
            for (compatiblePackage in patch.compatiblePackages!!) {
                val packageEntryStr = buildString {
                    // Add package if flag is set
                    if (args.patchArgs?.listingArgs?.withPackages == true) {
                        val packageName = compatiblePackage.name.substringAfterLast(".").padStart(10)
                        append(packageName)
                        append("\t")
                    }
                    // Add patch name
                    val patchName = patch.patchName.padStart(25)
                    append(patchName)
                    // Add description if flag is set.
                    if (args.patchArgs?.listingArgs?.withDescriptions == true) {
                        append("\t")
                        append(patch.description)
                    }
                    // Add compatible versions, if flag is set
                    if (args.patchArgs?.listingArgs?.withVersions == true) {
                        val compatibleVersions = compatiblePackage.versions.joinToString(separator = ", ")
                        append("\t")
                        append(compatibleVersions)
                    }
                }

                logged.add(patch.patchName)
                logger.info(packageEntryStr)
            }
        }
    }
}
