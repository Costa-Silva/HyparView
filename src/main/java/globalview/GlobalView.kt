package globalview

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Cancellable
import akka.pattern.Patterns
import akkanetwork.AkkaUtils
import communicationview.messages.StatusMessageWrapper
import globalview.GVHelpers.Companion.CHECK_IF_ALIVE_TIMEOUT_MS
import globalview.GVHelpers.Companion.MAY_BE_DEAD_PERIOD_MS
import globalview.GVHelpers.Companion.SEND_EVENTS_MESSAGE
import globalview.GVHelpers.Companion.SEND_EVENTS_PERIOD_MS
import globalview.GVHelpers.Companion.SEND_HASH_MESSAGE
import globalview.GVHelpers.Companion.SEND_HASH_PERIOD_MS
import globalview.GVHelpers.Companion.eventListisFull
import globalview.GVHelpers.Companion.pendingEventsisFull
import globalview.messages.external.ConflictMessage
import globalview.messages.external.GiveGlobalMessage
import globalview.messages.external.GlobalMessage
import globalview.messages.external.PingMessage
import globalview.messages.internal.StatusMessage
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration
import testlayer.statuswriter.WriteToFile
import java.util.*
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.TimeUnit

class GlobalView(private val eventList: LinkedList<Pair<UUID, Event>>,
                 private val pendingEvents: MutableMap<UUID, Event>,
                 private val toRemove: MutableSet<ActorRef>,
                 private val globalView: ConcurrentMap<ActorRef, ActorRef>, // global-partial
                 private val self: ActorRef,
                 private val system: ActorSystem,
                 private val gVMCounter: GVMessagesCounter,
                 private val pvActor: ActorRef,
                 private val commActor: ActorRef,
                 private val myID: String,
                 private val writeToFile: Boolean,
                 imContact: Boolean) {

    private var writeStatus: WriteToFile? = null
    private val actorswithDifferentHash = mutableSetOf<ActorRef>()
    val timersMayBeDead = mutableMapOf<UUID, Timer>()
    var sendEventsTimer: Cancellable? = null

    val hashTimer = system.scheduler().schedule(Duration.Zero(),
            Duration.create(SEND_HASH_PERIOD_MS, TimeUnit.MILLISECONDS), self, SEND_HASH_MESSAGE,
            system.dispatcher(), ActorRef.noSender())

    private fun sendEventsTimerSchedule(): Cancellable? {
        return system.scheduler().scheduleOnce(Duration.create(SEND_EVENTS_PERIOD_MS, TimeUnit.MILLISECONDS),
                Runnable {
                    self.tell(SEND_EVENTS_MESSAGE, ActorRef.noSender())
                }, system.dispatcher())
    }


    fun sendHash() {
        cancelTimerSendEvent()
        globalBroadcast()
    }

    init {
        if(imContact){
            globalNewNode(self, pvActor,false)
        }
    }

    fun sendEvents() {
        globalBroadcast()
    }

    fun globalBroadcast() {
        val message = StatusMessage(globalView.hashCode(), pendingEvents.toMutableMap(), toRemove.isEmpty())
        commActor.tell(StatusMessageWrapper(message, self), ActorRef.noSender())
        pendingEvents.clear()
    }

    private fun globalAdd(globalNewNode: ActorRef, partialNewNode: ActorRef, needsGlobal: Boolean) {
        globalView.put(globalNewNode, partialNewNode)
        writeStatus?.update()
        if (needsGlobal) {
            sendGlobalMessage(globalNewNode)
        }
    }

    fun receivedGlobalMessage(newView: MutableMap<ActorRef, ActorRef>, eventIds: LinkedList<Pair<UUID, Event>>) {
        globalView.clear()
        eventList.clear()
        globalView.putAll(newView)
        eventList.addAll(eventIds)
        writeStatus?.update()
    }

    private fun sendGlobalMessage(node: ActorRef) {
        node.tell(GlobalMessage(globalView, eventList), self)
    }

    fun remove(node: ActorRef) {
        globalView.remove(node)
        toRemove.remove(node)
        writeStatus?.update(node.path().name().split("global")[0])
    }

    private fun globalMayBeDead(globalNode: ActorRef, partialNode: ActorRef) {
        val uuid = UUID.randomUUID()
        addToEventList(uuid, Event(EventEnum.MAY_BE_DEAD, globalNode, partialNode))
        toRemove.add(globalNode)

        val timer = Timer()
        val removeNode = object : TimerTask() {
            override fun run() {
                remove(globalNode)
                timersMayBeDead.remove(uuid)
            }
        }
        timer.schedule(removeNode, MAY_BE_DEAD_PERIOD_MS)
        timersMayBeDead.put(uuid, timer)
    }

    fun globalNewNode(globalNewNode: ActorRef, partialNewNode: ActorRef, needsGlobal: Boolean) {
        val uuid = UUID.randomUUID()
        addToEventList(uuid, Event(EventEnum.NEW_NODE, globalNewNode, partialNewNode))
        globalAdd(globalNewNode, partialNewNode , needsGlobal)
    }

    private fun cancelTimerSendEvent() {
        sendEventsTimer?.let{
            if (!it.isCancelled) {
                it.cancel()
            }
        }
    }

    private fun reScheduleTimerEvent() {
        cancelTimerSendEvent()
        sendEventsTimer = sendEventsTimerSchedule()
    }

    private fun addToEventList(eventId: UUID, event: Event) {
        if(pendingEvents.isEmpty()) {
            reScheduleTimerEvent()
        } else if(event.globalNode == self && event.event == EventEnum.MAY_BE_DEAD) {
            reScheduleTimerEvent()
        }

        if (eventListisFull(eventList)) {
            eventList.removeFirst()
        }
        eventList.add(Pair(eventId, event))
        pendingEvents.put(eventId, event)

        if(pendingEventsisFull(pendingEvents)) {
            cancelTimerSendEvent()
            globalBroadcast()
        }
    }

    private fun imAlive() {
        addToEventList(UUID.randomUUID(), Event(EventEnum.STILL_ALIVE, self, pvActor))
    }

    fun partialDeliver(message: StatusMessageWrapper) {
        val hash = message.statusMessage.hash
        val newEvents = message.statusMessage.pendingEvents
        val toRemoveIsEmpty = message.statusMessage.toRemoveIsEmpty
        val senderID = message.sender

        var compareHash = false

        if (pendingEvents.isEmpty() && toRemoveIsEmpty) {
            compareHash = true
        }

        val gottaGoFastSet = mutableSetOf<UUID>()
        gottaGoFastSet.addAll(eventList.map { it.first })


        newEvents.forEach {
            val removeNodeTask = object : TimerTask() {
                override fun run() {
                    remove(it.value.globalNode)
                    timersMayBeDead.remove(it.key)
                }
            }

            if(!gottaGoFastSet.contains(it.key)) {
                addToEventList(it.key, it.value)
                val type = it.value.event
                val globalNode = it.value.globalNode
                val partialNode = it.value.partialNode
                if (type == EventEnum.NEW_NODE) {
                    globalAdd(globalNode, partialNode,false)
                } else if (type == EventEnum.MAY_BE_DEAD) {
                    if(globalNode != self) {
                        toRemove.add(globalNode)
                        val timer = Timer()
                        timer.schedule(removeNodeTask, MAY_BE_DEAD_PERIOD_MS)
                        timersMayBeDead.put(it.key, timer)
                    } else {
                        imAlive()
                    }
                } else if (type == EventEnum.STILL_ALIVE) {
                    if(toRemove.contains(globalNode)) {
                        toRemove.remove(globalNode)
                        timersMayBeDead.remove(it.key)?.cancel()
                    } else {
                        globalAdd(it.value.globalNode, partialNode, false)
                    }
                }

            }
        }
        if(compareHash && toRemove.isEmpty()) {
            globalCompare(hash,senderID)
        }
    }

    private fun globalCompare(hash: Int, sender: ActorRef) {
        val myHash = globalView.hashCode()
        if (myHash != hash) {

            if (actorswithDifferentHash.contains(sender)) {
                actorswithDifferentHash.remove(sender)
                val myNumber = AkkaUtils.numberFromIdentifier(self.path().name())
                val enemyNumber = AkkaUtils.numberFromIdentifier(sender.path().name())
                if (enemyNumber>myNumber) {
                    gVMCounter.messagesToResolveConflict++
                    sender.tell(ConflictMessage(globalView), self)
                } else {
                    sender.tell(GiveGlobalMessage(), self)
                }
            } else {
                actorswithDifferentHash.add(sender)
            }
        }
    }

    fun giveGlobalReceived(sender: ActorRef) {
        gVMCounter.messagesToResolveConflict++
        sender.tell(ConflictMessage(globalView), self)
    }

    fun conflictMessageReceived(otherGlobalView: MutableMap<ActorRef, ActorRef>) {
        otherGlobalView
                .filter { !globalView.containsKey(it.key)}
                .forEach { entry ->
                    var isAlive = false
                    if (entry.key != self) {
                        try {
                            gVMCounter.messagesToCheckIfAlive++
                            val future = Patterns.ask(entry.key, PingMessage(), CHECK_IF_ALIVE_TIMEOUT_MS)
                            Await.result(future, FiniteDuration(CHECK_IF_ALIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) as Boolean
                            isAlive = true
                        } catch (e: Exception) {
                            System.err.println("Couldn't connect to ${entry.key}")
                        }
                    } else {
                        isAlive = true
                    }
                    if(isAlive) {
                        globalNewNode(entry.key, entry.value, false)
                    } else {
                        addToEventList(UUID.randomUUID(), Event(EventEnum.MAY_BE_DEAD, entry.key, entry.value))
                    }
                }
        globalView
                .filter { !otherGlobalView.containsKey(it.key)}
                .forEach { entry ->
                    var isAlive = false
                    if(entry.key != self) {
                        try {
                            gVMCounter.messagesToCheckIfAlive++
                            val future = Patterns.ask(entry.key, PingMessage(), CHECK_IF_ALIVE_TIMEOUT_MS)
                            isAlive = Await.result(future, FiniteDuration(CHECK_IF_ALIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) as Boolean
                        } catch (e: Exception) {
                            System.err.println("Couldn't connect to ${entry.key}")
                        }
                    }else {
                        isAlive = true
                    }
                    if(isAlive) {
                        addToEventList(UUID.randomUUID() ,Event(EventEnum.STILL_ALIVE, entry.key, entry.value))
                    } else {
                        globalMayBeDead(entry.key, entry.value)
                    }
                }
    }

    fun partialNodeMayBeDead(partialNode: ActorRef) {
        val globalNode = globalView.filterValues { it == partialNode }.entries.firstOrNull()?.key
        globalNode?.let { globalMayBeDead(it, partialNode)  }
    }

    fun startWritting(myStatusActor: ActorRef) {
        writeStatus = WriteToFile(writeToFile, myID, myStatusActor)
        writeStatus?.update()
    }
}