package com.permutive.pubsub.producer

import java.util.UUID

object Model {
  case class MessageId(value: String) extends AnyVal
  case class ProjectId(value: String) extends AnyVal
  case class Topic(value: String)     extends AnyVal

  trait Record[A] {
    def data: A
    def attributes: Map[String, String]
    def uniqueId: String
  }

  final case class SimpleRecord[A](
    data: A,
    attributes: Map[String, String] = Map.empty,
    uniqueId: String = UUID.randomUUID().toString
  ) extends Record[A]

  final case class AsyncRecord[F[_], A](
    data: A,
    callback: Either[Throwable, Unit] => F[Unit],
    attributes: Map[String, String] = Map.empty,
    uniqueId: String = UUID.randomUUID().toString
  ) extends Record[A]
}
