package dev.pi.intellijdiff

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil
import java.security.SecureRandom

@State(name = "PiDiffApprovalSettings", storages = [Storage("piDiffApproval.xml")])
@Service(Service.Level.APP)
class PiDiffSettings : PersistentStateComponent<PiDiffSettings.State> {
    data class State(
        var port: Int = 63345,
        var token: String = generateToken(),
        var piCommand: String = "pi",
        var approveCreates: Boolean = true,
        var approveEdits: Boolean = true,
        var approveDeletes: Boolean = true,
        var reviewMode: String = "pre-apply",
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
        if (this.state.token.isBlank()) this.state.token = generateToken()
    }

    companion object {
        fun getInstance(): PiDiffSettings = ApplicationManager.getApplication().getService(PiDiffSettings::class.java)

        fun generateToken(): String {
            val bytes = ByteArray(24)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
