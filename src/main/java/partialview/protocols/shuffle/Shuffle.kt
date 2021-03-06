package partialview.protocols.shuffle

import akka.actor.ActorRef
import akkanetwork.AkkaUtils
import partialview.PVHelpers
import partialview.PVHelpers.Companion.PASSIVE_VIEW_MAX_SIZE
import partialview.PVMessagesCounter
import partialview.ViewOperations
import partialview.protocols.shuffle.messages.ShuffleMessage
import partialview.protocols.shuffle.messages.ShuffleReplyMessage
import java.util.*

class Shuffle(private val activeView: MutableSet<ActorRef>,
              private val passiveView: MutableSet<ActorRef>,
              private val viewOperations: ViewOperations,
              private var self: ActorRef,
              private val mCounter: PVMessagesCounter) {

    private val samplesSent = mutableMapOf<UUID, MutableSet<ActorRef>>()

    init {
        val shuffleTask = object : TimerTask() {
            override fun run() {
                shufflePassiveView()
            }
        }
        Timer().scheduleAtFixedRate(shuffleTask ,0, PVHelpers.TTSHUFFLE_MS)
    }

    private fun shufflePassiveView() {
        val sample = mutableSetOf<ActorRef>()
        val uuid = UUID.randomUUID()
        val activeNodesToFind = Math.min(PVHelpers.N_ACTIVE_NODES_SHUFF, activeView.size)
        val passiveNodesToFind = Math.min(PVHelpers.N_PASSIVE_NODES_SHUFF, passiveView.size)
        val activeNodes = populateSample(activeNodesToFind, activeView)
        val passiveNodes = populateSample(passiveNodesToFind, passiveView)

        sample.addAll(activeNodes)
        sample.addAll(passiveNodes)
        sample.add(self)

        val actor = AkkaUtils.chooseRandom(activeView)
        actor?.let {
            mCounter.shufflesSent++
            samplesSent.put(uuid, sample)
            it.tell(ShuffleMessage(sample, PVHelpers.SHUFFLE_TTL, uuid, self), self)
        }
    }

    fun shuffle(sample: MutableSet<ActorRef>, timeToLive: Int, uuid: UUID, origin: ActorRef, sender: ActorRef) {
        mCounter.shufflesReceived++
        val newTLL = timeToLive - 1
        if (newTLL > 0 && activeView.size > 1) {
            val actor = AkkaUtils.chooseRandomWithout(mutableSetOf(origin, sender), activeView)
            actor?.let {
                mCounter.shufflesSent++
                it.tell(ShuffleMessage(sample, newTLL, uuid, origin), self)
            }
        } else {
            val passiveNodesToFind = Math.min(sample.size - 1, passiveView.size)
            val randomPassiveNodes = populateSample(passiveNodesToFind, passiveView)
            randomPassiveNodes.add(self)

            val myRandomPassiveNodes = mutableSetOf<ActorRef>()
            myRandomPassiveNodes.addAll(randomPassiveNodes)

            removeAlreadyKnownNodes(sample)
            replaceNodesInPassiveView(sample, randomPassiveNodes)
            origin.tell(ShuffleReplyMessage(myRandomPassiveNodes, uuid), self)
        }
    }

    private fun replaceNodesInPassiveView(sample: MutableSet<ActorRef>, randomPassiveNodes: MutableSet<ActorRef>) {
        val sumNodes = passiveView.size + sample.size
        val nodesToRemove = if(sumNodes > PASSIVE_VIEW_MAX_SIZE) (sumNodes-PASSIVE_VIEW_MAX_SIZE) else 0
        val removedNodes = mutableSetOf<ActorRef>()
        for ( i in 0 until nodesToRemove) {
            var actor: ActorRef? = null
            while (actor == null || removedNodes.contains(actor)) {
                actor = AkkaUtils.chooseRandomWithout(removedNodes, randomPassiveNodes)
                if(actor == null) {
                    actor = AkkaUtils.chooseRandomWithout(removedNodes, passiveView)
                } else {
                    randomPassiveNodes.remove(actor)
                }
            }
            removedNodes.add(actor)
        }
        viewOperations.replaceNodesInPassiveViewShuffle(removedNodes, sample)
    }

    fun shuffleReply(sample: MutableSet<ActorRef>, uuid: UUID) {
        samplesSent.remove(uuid)?.let { setSent ->
            removeAlreadyKnownNodes(sample)
            replaceNodesInPassiveView(sample, setSent)
        }
    }

    private fun removeAlreadyKnownNodes(sample: MutableSet<ActorRef>) {
        sample.removeAll { it == self || activeView.contains(it) || passiveView.contains(it) }
    }

    private fun populateSample(nodesToFind: Int, viewSet: Set<ActorRef>): MutableSet<ActorRef> {
        val resultSet = mutableSetOf<ActorRef>()
        if(nodesToFind > 0) {
            while (resultSet.size < nodesToFind) {
                val actor = AkkaUtils.chooseRandom(viewSet)
                if(actor != null)
                    resultSet.add(actor)
            }
        }
        return resultSet
    }
}