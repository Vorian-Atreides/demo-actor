package com.messagebird.demo

import akka.io.{Tcp}
import java.net.InetSocketAddress

import akka.actor.typed.scaladsl.adapter._
import akka.actor.{Actor, ActorRef, Props}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.util.ByteString

object TCPSession {
  sealed trait Command
  final case class Say(msg: String) extends Command
  final case class PrivateMessage(msg: String, toUserName: String) extends Command
  final case class Login(userName: String) extends Command
  final case class Help() extends Command
  final case class ListUsers()  extends Command

  private case class State(
                            user: Option[String],
                            channel: Option[String],
                            connection: Option[ActorRef]
                          )

  def props(remote: InetSocketAddress, replies: ActorRef): Props =
    Props(classOf[TCPSession], remote, replies)
}

class TCPSession(remote: InetSocketAddress, listener: ActorRef) extends Actor {
  import TCPSession._

  private val sharding = ClusterSharding(context.system.toTyped)
  private val mainChannel = ClusterSharding(context.system.toTyped).entityRefFor(Channel.TypeKey, "mainChannel")

  private def sendToUser(userName: String, cmd: User.Command): Unit =
    sharding.entityRefFor(User.TypeKey, userName) ! cmd

  private def sendToClient(msg: String): Unit =
    state.connection.foreach(_ ! Tcp.Write(ByteString.apply(msg)))

  private var state: State = State(None, None, None)

  private def parseCommand(data: String): Either[String, Command] = data.toList match {
    case '/' :: rest =>
      rest.mkString.split(' ').toList match {
        case cmd :: args =>
          cmd match {
            case "login" if args.size == 1 => Right(Login(args.head))
            case "users" if args.isEmpty => Right(ListUsers())
            case "pm" if args.size >= 2 => Right(PrivateMessage(args.head, args.tail.mkString(" ")))
            case "help" if args.isEmpty => Right(Help())
            case _ => Left(s"unrecognized command '$cmd' with args '$args'\n")
          }
        case _ => Left(s"/cmd arg1 arg2\n")
      }
    case msg =>
      Right(Say(msg.mkString))
  }

  private def processCommand(command: Command): Unit = command match {
    case _: Login if state.user.isDefined  =>
      sendToClient("you have already been logged in\n")
    case Login(userName) =>
      sendToUser(userName, User.Login(self))
    case _ : ListUsers =>
      mainChannel ! Channel.ListConnectedUsers(self)
    case Say(msg) if state.user.isDefined =>
      state.user.foreach(sendToUser(_, User.Say(msg)))
    case PrivateMessage(to, msg) if state.user.isDefined =>
      state.user.foreach(sendToUser(_, User.SendPrivateMessage(msg, to)))
    case _ =>
      val msg = ByteString.createBuilder
        .append(ByteString.apply("[/help] will show you this message\n"))
        .append(ByteString.apply("[/login $userName] will log you in the channel with the given user name\n"))
        .append(ByteString.apply("[/users] will list the connected users in the channel\n"))
        .append(ByteString.apply("[/pm $toUserName $msg] will send a private message to a specific user in the channel\n"))
        .append(ByteString.apply("[$msg] will send a message to everybody in the channel\n"))
        .result()
      sendToClient(msg.utf8String)
  }

  override def postStop(): Unit = {
    state.connection.foreach(_ ! Tcp.Close)
  }

  def receive: Receive = {
    case Tcp.Received(data) =>
      state = state.copy(connection = Some(sender()))
      val cleanedData = data.utf8String.trim.take(128)
      parseCommand(cleanedData) match {
        case Left(err) => sendToClient(err)
        case Right(cmd) =>
          processCommand(cmd)
      }

    case User.PrintToUser(msg) =>
      sendToClient(msg)

    case User.LoginResponse(Left(err)) =>
      sendToClient(err)
    case User.LoginResponse(Right(userName)) =>
      state = state.copy(user = Some(userName))
      sendToUser(state.user.get, User.JoinChannel("mainChannel", self))
    case User.JoinChannelResponse(Some(err)) =>
      println(err)
    case User.JoinChannelResponse(None) =>
      val msg = s"joined the channel 'mainChannel'\n"
      sendToClient(msg)

    case Channel.ConnectedUsers(userNames) if userNames.nonEmpty =>
      val msg = ByteString.createBuilder
        .append(ByteString.apply("the connected users are: "))
        .append(ByteString.apply(userNames.mkString(", ")))
        .putByte('\n')
        .result()
      sendToClient(msg.utf8String)
    case Channel.ConnectedUsers(userNames) =>
      sendToClient("there is no connected users\n")

    case Tcp.PeerClosed => context.stop(self)
  }
}
