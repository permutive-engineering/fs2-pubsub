package com.permutive.pubsub.consumer

import cats.Show

object Model {
  case class ProjectId(value: String) extends AnyVal
  object ProjectId {
    implicit val show: Show[ProjectId] = Show.fromToString
  }

  case class Subscription(value: String) extends AnyVal
  object Subscription {
    implicit val show: Show[Subscription] = Show.fromToString
  }
}
