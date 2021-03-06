package akkanetwork

import java.io.Serializable

data class NodeID(val ip: String, val port: String, val identifier: String): Serializable {
    override fun toString(): String {
        return "$ip:$port"
    }
}