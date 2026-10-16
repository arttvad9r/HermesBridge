package io.github.arttvad9r.typeddroid

import java.io.File
import java.util.Base64
import kotlin.system.exitProcess

/** Debug-only command-line probe for emulator/physical embedded-helper E2E tests. */
object EmbeddedPrivilegedProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            run(args)
        } catch (error: Throwable) {
            System.err.println("errorClass=${error::class.java.simpleName}")
            System.err.println("errorMessage=${(error.message ?: "unknown").take(300)}")
            if (error is EmbeddedPrivilegedRemoteException) {
                System.err.println("remoteTimedOut=${error.timedOut}")
            }
            exitProcess(2)
        }
    }

    private fun run(args: Array<String>) {
        require(args.size >= 3) { "Usage: <port> <token> <operation> [args...]" }
        val client = EmbeddedPrivilegedClient(
            EmbeddedPrivilegedEndpoint(args[0].toInt(), args[1])
        )
        when (args[2]) {
            "ping" -> println("ping=${client.ping()}")
            "exec" -> {
                require(args.size >= 5) { "exec requires timeoutMillis and command" }
                printResult(client.exec(args.drop(4).joinToString(" "), args[3].toLong()))
            }
            "battery" -> printResult(client.batteryStats())
            "force-stop" -> {
                require(args.size == 4)
                printResult(client.forceStop(args[3]))
            }
            "uninstall" -> {
                require(args.size == 5)
                printResult(client.uninstall(args[3], args[4].toBooleanStrict()))
            }
            "revoke" -> {
                require(args.size == 6)
                printResult(client.revokePermission(args[3], args[4], args[5].toInt()))
            }
            "install" -> {
                require(args.size == 8)
                val file = File(args[3])
                printResult(
                    client.installApk(
                        VerifiedApkArtifact(
                            file = file,
                            fileName = args[4],
                            sizeBytes = args[5].toLong(),
                            sha256 = args[6],
                        ),
                        replace = args[7].toBooleanStrict(),
                    )
                )
            }
            "shutdown" -> println("shutdown=${client.shutdown()}")
            else -> error("Unknown operation: ${args[2]}")
        }
    }

    private fun printResult(result: EmbeddedPrivilegedExecResult) {
        println("exitCode=${result.exitCode}")
        println("stdoutTruncated=${result.stdoutTruncated}")
        println("stderrTruncated=${result.stderrTruncated}")
        println("stdoutBase64=${Base64.getEncoder().encodeToString(result.stdout)}")
        println("stderrBase64=${Base64.getEncoder().encodeToString(result.stderr)}")
    }
}
