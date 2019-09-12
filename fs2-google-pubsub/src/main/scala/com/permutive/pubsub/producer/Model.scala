package com.permutive.pubsub.producer

import java.util.UUID

object Model {
  case class MessageId(value: String) extends AnyVal
  case class ProjectId(value: String) extends AnyVal
  case class Topic(value: String)     extends AnyVal

  trait Record[A] {
    def value: A
    def metadata: Map[String, String]
    def uniqueId: String
  }

  final case class SimpleRecord[A](
    value: A,
    metadata: Map[String, String] = Map.empty,
    uniqueId: String = UUID.randomUUID().toString
  ) extends Record[A]

  final case class AsyncRecord[F[_], A](
    value: A,
    callback: Either[Throwable, Unit] => F[Unit],
    metadata: Map[String, String] = Map.empty,
    uniqueId: String = UUID.randomUUID().toString
  ) extends Record[A]
}
