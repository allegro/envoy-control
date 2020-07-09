package pl.allegro.tech.servicemesh.envoycontrol.config.testcontainers

import org.testcontainers.containers.output.BaseConsumer
import org.testcontainers.containers.output.OutputFrame

class LogRecorder : BaseConsumer<LogRecorder>() {

    private var recorder: (frame: OutputFrame?) -> Unit = {}
    private var recordedLogs: MutableList<String> = mutableListOf()

    override fun accept(frame: OutputFrame?) {
        recorder(frame)
    }

    fun recordLogs(logPredicate: (line: String) -> Boolean) {
        recorder = { frame ->
            val line = frame?.utf8String ?: ""
            if (logPredicate(line)) {
                recordedLogs.add(line)
            }
        }
    }

    fun stopRecording() {
        recorder = {}
        recordedLogs = mutableListOf()
    }

    fun getRecordedLogs(): List<String> = recordedLogs
}
