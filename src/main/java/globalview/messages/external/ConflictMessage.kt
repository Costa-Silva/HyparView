package globalview.messages.external

import akka.actor.ActorRef
import java.io.Serializable

class ConflictMessage(val myGlobalView: MutableSet<ActorRef>): Serializable