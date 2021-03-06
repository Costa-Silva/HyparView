package globalview.messages.external

import akka.actor.ActorRef
import globalview.Event
import java.io.Serializable
import java.util.*

class GlobalMessage(val globalView: MutableMap<ActorRef, ActorRef>, val eventList: LinkedList<Pair<UUID, Event>>): Serializable