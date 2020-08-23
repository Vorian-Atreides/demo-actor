package com.gastonsiffert.demo

import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}
import akka.persistence.journal.{PersistencePluginProxy}
import com.typesafe.config.{ConfigFactory}

object Gateway extends App {
  import akka.actor.typed.scaladsl.adapter._

  val config = ConfigFactory.load().getConfig("tcpServer").withFallback(ConfigFactory.load())
  println(config.toString)
  val system = akka.actor.ActorSystem("DemoChat", config)

  PersistencePluginProxy.start(system)
  val sharding = ClusterSharding(system.toTyped)
  val shardUser = sharding.init(
    Entity(User.TypeKey)(createBehavior = entityContext => User(entityContext.entityId)).withRole("chatServer")
  )
  val shardChannel = sharding.init(
    Entity(Channel.TypeKey)(createBehavior = entityContext => Channel(entityContext.entityId)).withRole("chatServer")
  )

  val port = args(0).toInt
  val server = system.actorOf(TCPServer.props(port))
}

object ChatServer extends App {
  import akka.actor.typed.scaladsl.adapter._

  val baseConfig = ConfigFactory.load().getConfig("chatServer").withFallback(ConfigFactory.load())
  val config = ConfigFactory.parseString(s"akka.remote.artery.canonical.port=${args(0)}")
    .withFallback(baseConfig)
  println(config.toString)

  val system = akka.actor.ActorSystem("DemoChat", config)
  val typedSystem = system.toTyped

  val sharding = ClusterSharding(typedSystem)
  val shardUser = sharding.init(
    Entity(User.TypeKey)(createBehavior = entityContext => User(entityContext.entityId)).withRole("chatServer")
  )
  val shardChannel = sharding.init(
    Entity(Channel.TypeKey)(createBehavior = entityContext => Channel(entityContext.entityId)).withRole("chatServer")
  )
  shardChannel ! ShardingEnvelope("mainChannel", Channel.ListConnectedUsers(system.deadLetters))
}
