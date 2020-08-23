package com.gastonsiffert.demo

import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityTypeKey}
import akka.persistence.typed.{PersistenceId, RecoveryCompleted}
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}

object User {
  val TypeKey = EntityTypeKey[Command]("User")

  sealed trait Command extends Serializable
  sealed trait InitializingCommand extends Command
  final case class Login(from: ActorRef[Response]) extends InitializingCommand
  final case class JoinChannel(channelName: String, from: ActorRef[JoinChannelResponse]) extends InitializingCommand

  sealed trait ConnectedCommand extends Command
  final case class Say(msg: String) extends ConnectedCommand
  final case class SendPrivateMessage(msg: String, toUserName: String) extends ConnectedCommand
  final case class Received(msg: String, from: String) extends ConnectedCommand

  private final case class SessionClosed() extends Command

  sealed trait Response extends Serializable
  final case class LoginResponse(result: Either[String, String]) extends Response
  final case class JoinChannelResponse(err: Option[String]) extends Response
  final case class PrintToUser(msg: String) extends Response

  sealed trait Event extends Serializable
  final case class LoggedIn(session: ActorRef[Response]) extends Event
  final case class JoinedChannel(channelName: String) extends Event
  final case class Disconnected() extends Event

  private sealed trait State extends Serializable
  private case class InitializingState(
                                      userName: String,
                                      channelName: Option[String],
                                      session: Option[ActorRef[Response]]
                                      ) extends  State
  private case class ConnectedState(
                          userName: String,
                          channelName: String,
                          session: ActorRef[Response]
                          ) extends State

  def apply(userName: String): Behavior[Command] =
    Behaviors.setup { ctx =>
      val user = new User(ctx)
      val behavior = EventSourcedBehavior[Command, Event, State](
        persistenceId = PersistenceId.ofUniqueId(userName),
        emptyState = InitializingState(userName, None, None),
        commandHandler = user.commandHandler,
        eventHandler = user.eventHandler
      ).receiveSignal {
        case (state: ConnectedState, RecoveryCompleted) =>
          ctx.watchWith(state.session, SessionClosed())
      }
      Behaviors.supervise(behavior).onFailure[Exception](SupervisorStrategy.restart)
    }
}

private class User(ctx: ActorContext[User.Command]) {
  import User._

  private val sharding = ClusterSharding(ctx.system)

  private def sendToChannel(channelName: String, cmd: Channel.Command): Unit =
    sharding.entityRefFor(Channel.TypeKey, channelName) ! cmd

  private def sendToUser(userName: String, cmd: Command): Unit =
    sharding.entityRefFor(TypeKey, userName) ! cmd

  private def initializingHandler(state: InitializingState, command: Command): Effect[Event, State] = command match {
    case Login(from) if state.session.isDefined =>
      val response = LoginResponse(Left(s"the username '${state.userName} is already being used\n"))
      Effect.none.thenReply(from){ _ => response }
    case Login(from) =>
      Effect.persist(LoggedIn(from)).thenRun { _ =>
        ctx.watchWith(from, SessionClosed())
        from ! LoginResponse(Right(state.userName))
      }
    case JoinChannel(_, from) if state.session.isEmpty =>
      val response = JoinChannelResponse(Some(s"you must be logged in before joining a channel\n"))
      Effect.none.thenReply(from){ _ => response}
    case JoinChannel(channelName, from) =>
      Effect.persist(JoinedChannel(channelName)).thenRun { _ =>
        sendToChannel(channelName, Channel.Join(state.userName))
        from ! JoinChannelResponse(None)
      }
    case _: SessionClosed =>
      Effect.persist(Disconnected()).thenStop()
    case _ => Effect.none
  }

  private def connectedHandler(state: ConnectedState, command: Command): Effect[Event, State] = command match {
        case Say(msg) =>
          Effect.none.thenRun{ _ =>
            sendToChannel(state.channelName, Channel.Broadcast(msg, state.userName))
          }
        case SendPrivateMessage(msg, to) if to != state.userName =>
          Effect.none.thenRun{ _ =>
            sendToUser(to, Received(msg, state.userName))
          }
        case Received(msg, from) =>
          val data = s"$from: $msg\n"
          Effect.reply(state.session)(PrintToUser(data))

        case Login(from) =>
          val response = LoginResponse(Left(s"the username '${state.userName}' is already being used\n"))
          Effect.none.thenReply(from){ _ => response }
        case _: SessionClosed =>
          Effect.persist(Disconnected()).thenRun { (_: State) =>
            sendToChannel(state.channelName, Channel.Leave(state.userName))
          }.thenStop()
        case _ => Effect.none
  }

  private def commandHandler(state: State, command: Command): Effect[Event, State] = state match {
    case initializing: InitializingState  => initializingHandler(initializing, command)
    case connected: ConnectedState =>
      connectedHandler(connected, command)
  }

  private def eventHandler(state: State, event: Event): State = state match {
    case initializing: InitializingState =>
      event match {
        case LoggedIn(session) => initializing.copy(session = Some(session))
        case JoinedChannel(channelName) => ConnectedState(
          userName = initializing.userName,
          session = initializing.session.get,
          channelName = channelName
        )
        case _: Disconnected =>
          InitializingState(userName = initializing.userName, None, None)
      }
    case connected: ConnectedState =>
      event match {
        case _: Disconnected =>
          InitializingState(userName = connected.userName, None, None)
        case _ => connected
      }
  }
}
