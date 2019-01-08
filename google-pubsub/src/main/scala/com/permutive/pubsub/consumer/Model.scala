package com.permutive.pubsub.consumer

object Model {
  case class ProjectId(value: String) extends AnyVal
  case class Subscription(value: String) extends AnyVal

  case class Record[F[_], A](value: A, ack: F[Unit], nack: F[Unit])
}
