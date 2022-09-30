package app.revanced.cli.aligning

import app.revanced.cli.command.MainCommand.logger
import app.revanced.utils.signing.align.ZipAligner
import java.io.File

object Aligning {
    fun align(inputFile: File, outputFile: File) {
        ZipAligner.align(inputFile, outputFile)
    }
}
