package com.gastonsiffert.demo

import java.net.InetSocketAddress

import akka.actor.{Actor, Props}
import akka.io.{IO, Tcp}

object TCPServer {
  def props(port: Int): Props =
    Props(classOf[TCPServer], port)
}

class TCPServer(port: Int) extends Actor {
    import Tcp._
    import context.system

    IO(Tcp) ! Bind(self, new InetSocketAddress("localhost", port))

    def receive: Receive = {
      case b @ Bound(localAddress) =>
        println(localAddress)
        context.parent ! b

      case CommandFailed(_: Bind) => context.stop(self)

      case c @ Connected(remote, local) =>
        val connection = sender()
        val handler = context.actorOf(TCPSession.props(remote, self))
        connection ! Register(handler)
    }

}
