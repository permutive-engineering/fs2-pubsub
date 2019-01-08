package com.permutive.pubsub.consumer.http.internal

import cats.effect._
import com.permutive.pubsub.consumer.Model.{ProjectId, Subscription}
import com.permutive.pubsub.consumer.http.PubsubHttpConsumerConfig
import com.permutive.pubsub.consumer.http.internal.Model.AckId
import fs2.Stream
import fs2.concurrent.Queue
import org.http4s.client.Client

private[http] object PubsubSubscriber {

  def subscribe[F[_] : Timer](
    projectId: ProjectId,
    subscription: Subscription,
    serviceAccountPath: String,
    config: PubsubHttpConsumerConfig[F],
    httpClient: Client[F],
  )(
    implicit F: Concurrent[F],
  ): Stream[F, Model.Record[F]] = {
    for {
      ackQ   <- Stream.eval(Queue.unbounded[F, AckId])
      nackQ  <- Stream.eval(Queue.unbounded[F, AckId])
      reader <- Stream.resource(
        HttpPubsubReader.resource(
          projectId = projectId,
          subscription = subscription,
          serviceAccountPath = serviceAccountPath,
          config = config,
          httpClient = httpClient,
        )
      )
      rec <- Stream.repeatEval(reader.read)
        .concurrently(
          ackQ
            .dequeue
            .groupWithin(config.acknowledgeBatchSize, config.acknowledgeBatchLatency)
            .evalMap(ids => reader.ack(ids.toList))
        )
        .concurrently(
          nackQ
            .dequeue
            .groupWithin(config.acknowledgeBatchSize, config.acknowledgeBatchLatency)
            .evalMap(ids => reader.nack(ids.toList))
        )
      msg <- Stream.emits(
        rec.receivedMessages.map { msg =>
          Model.Record(
            value = msg.message,
            ack = ackQ.enqueue1(msg.ackId),
            nack = nackQ.enqueue1(msg.ackId),
          )
        }
      )
    } yield msg
  }
}
