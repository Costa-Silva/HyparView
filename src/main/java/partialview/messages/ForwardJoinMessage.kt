package partialview.messages

import akka.actor.ActorRef
import java.io.Serializable

class ForwardJoinMessage(val newNode: ActorRef, val timeToLive: Int): Serializable