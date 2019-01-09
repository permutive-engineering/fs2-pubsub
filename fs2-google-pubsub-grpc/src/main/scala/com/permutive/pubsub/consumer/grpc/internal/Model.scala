package com.permutive.pubsub.consumer.grpc.internal

import com.google.pubsub.v1.PubsubMessage

private[consumer] object Model {
  case class Record[F[_]](value: PubsubMessage, ack: F[Unit], nack: F[Unit])
}
