package com.messagebird.demo

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityTypeKey}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}

object Channel {
  val TypeKey = EntityTypeKey[Command]("Channel")

  sealed trait Command extends Serializable
  final case class Join(userName: String) extends Command
  final case class ListConnectedUsers(from: ActorRef[ConnectedUsers]) extends Command
  final case class Broadcast(msg: String, fromUserName: String) extends Command
  final case class Leave(userName: String) extends Command

  sealed trait Response extends Serializable
  final case class ConnectedUsers(userNames: List[String]) extends Response

  sealed trait Event extends Serializable
  final case class UserJoined(userName: String) extends Event
  final case class UserLeft(userName: String) extends Event

  private final case class State(
                    name: String,
                    connectedUsers: Set[String]
                  ) extends Serializable

  def apply(name: String): Behavior[Command] = Behaviors.setup { ctx =>
    val channel = new Channel(ctx)
    val behavior = EventSourcedBehavior[Command, Event, State](
      persistenceId = PersistenceId.ofUniqueId(name),
      emptyState = State(name, Set[String]()),
      commandHandler = channel.commandHandler,
      eventHandler = channel.eventHandler
    )
    Behaviors.supervise(behavior).onFailure[Exception](SupervisorStrategy.restart)
  }

}

private class Channel(ctx: ActorContext[Channel.Command]) {
  import Channel._

  private val sharding = ClusterSharding(ctx.system)

  private def sendToUser(userName: String, cmd: User.Command): Unit =
    sharding.entityRefFor(User.TypeKey, userName) ! cmd

  private def commandHandler(state: State, command: Command): Effect[Event, State] = command match {
    case Join(userName) if !state.connectedUsers.contains(userName) =>
      Effect.persist(UserJoined(userName)).thenReply(ctx.self){_ => Broadcast("joined the channel", userName)}
    case Leave(userName) if state.connectedUsers.contains(userName) =>
      Effect.persist(UserLeft(userName)).thenReply(ctx.self){_ => Broadcast("left the channel", userName)}
    case ListConnectedUsers(from) =>
      Effect.none.thenReply(from){ _ =>ConnectedUsers(state.connectedUsers.toList) }
    case Broadcast(msg, from) =>
      val cmd = User.Received(msg, from)
      Effect.none.thenRun { _ =>
        state.connectedUsers.filterNot(_ == from).foreach( sendToUser(_, cmd))
      }

    case _ =>
      Effect.unhandled
  }

  private def eventHandler(state: State, event: Event): State = event match {
    case UserJoined(userName) =>
      state.copy(
        connectedUsers = state.connectedUsers + userName
      )
    case UserLeft(userName) =>
      state.copy(
        connectedUsers = state.connectedUsers - userName
      )
  }

}
