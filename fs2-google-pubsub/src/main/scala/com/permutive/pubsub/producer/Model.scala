package com.permutive.pubsub.producer

import java.util.UUID

object Model {
  case class MessageId(value: String) extends AnyVal
  case class ProjectId(value: String) extends AnyVal
  case class Topic(value: String) extends AnyVal

  case class Record[A](
    value: A,
    metadata: Map[String, String] = Map.empty,
    uniqueId: String = UUID.randomUUID().toString
  )
}
