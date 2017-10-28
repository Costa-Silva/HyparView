package systemsupervisor.statuswriter

import akka.actor.ActorRef
import akka.pattern.Patterns
import akkanetwork.AkkaConstants
import akkanetwork.AkkaUtils
import akkanetwork.NodeID
import com.google.gson.*
import partialview.wrappers.PVSharedData
import scala.concurrent.Await
import scala.concurrent.duration.FiniteDuration
import systemsupervisor.graph.NodeStateMessage
import systemsupervisor.statuswriter.messages.RequestFromAppMessage
import java.io.FileWriter
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

class WriteStatus {

    companion object {
        fun writeToFile(pvData: PVSharedData, globalView: MutableMap<ActorRef, ActorRef>, statusActor: ActorRef) {
            val root = JsonObject()
            root.addProperty("system", AkkaConstants.SYSTEM_NAME)
            val nodesInfoArray = JsonArray()

            globalView.keys.forEach {
                    val identifier = it.path().name().split("global")[0]
                    val nodeID = AkkaUtils.createNodeID(identifier)
                    if(nodeID != null) {
                        if (pvData.identifier != identifier) {
                            val newEntry = nodeInfoFor(nodeID, statusActor)
                            if (newEntry != null) {
                                nodesInfoArray.add(newEntry)
                            }
                        } else {
                            nodesInfoArray.add(createNodeInfo(pvData))
                        }
                    }
            }

            try {
                root.add("data", nodesInfoArray)
                val jsonParser = JsonParser().parse(root.toString())
                val prettyJsonString = GsonBuilder().setPrettyPrinting().create().toJson(jsonParser)

                val filepath = Paths.get(System.getProperty("user.dir"), "data.json").toString()
                val fileToDeletePath = Paths.get(filepath)
                Files.deleteIfExists(fileToDeletePath)

                FileWriter(filepath).use({ file ->
                    file.write(prettyJsonString)
                    System.err.println("Saved state!")
                })
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }

        private fun nodeInfoFor(nodeID: NodeID, statusActor: ActorRef): JsonObject? {
            val timeoutTime: Long = 500
            try {
                val future = Patterns.ask(statusActor, RequestFromAppMessage(nodeID.identifier),timeoutTime)
                val result = Await.result(future, FiniteDuration(timeoutTime, TimeUnit.MILLISECONDS))
                if (result != null) {
                    val message = result as NodeStateMessage
                    return createNodeInfo(message.partialViewData)
                }
            } catch (e: Exception) { }
            return null
        }

        private fun createNodeInfo(pvData: PVSharedData): JsonObject {
            val nodeInfo = JsonObject()
            nodeInfo.addProperty("id", pvData.identifier)

            val av = JsonArray()
            pvData.activeView.map { it.path().name()}.forEach {
                av.add(JsonPrimitive(it))
            }

            val pv = JsonArray()
            pvData.passiveView.map { it.path().name()}.forEach {
                pv.add(JsonPrimitive(it))
            }
            nodeInfo.add("av", av)
            nodeInfo.add("pv", pv)

            val mCounter = pvData.mCounter
            nodeInfo.addProperty("JoinsReceived", mCounter.joinsReceived)

            nodeInfo.addProperty("ForwardJoinsReceived", mCounter.forwardJoinsReceived)
            nodeInfo.addProperty("ForwardJoinsSent", mCounter.forwardJoinsSent)

            nodeInfo.addProperty("NeighborRequestsReceived", mCounter.neighborRequestsReceived)
            nodeInfo.addProperty("NeighborRequestsSent", mCounter.neighborRequestsSent)

            nodeInfo.addProperty("DisconnectsReceived", mCounter.disconnectsReceived)
            nodeInfo.addProperty("DisconnectsSent", mCounter.disconnectsSent)

            nodeInfo.addProperty("ShufflesReceived", mCounter.shufflesReceived)
            nodeInfo.addProperty("ShufflesSent", mCounter.shufflesSent)
            return nodeInfo
        }
    }
}