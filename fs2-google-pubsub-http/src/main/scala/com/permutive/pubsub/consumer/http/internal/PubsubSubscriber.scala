package com.permutive.pubsub.consumer.http.internal

import cats.effect._
import cats.syntax.all._
import com.permutive.pubsub.consumer.Model.{ProjectId, Subscription}
import com.permutive.pubsub.consumer.http.PubsubHttpConsumerConfig
import com.permutive.pubsub.consumer.http.internal.HttpPubsubReader.PubSubError
import com.permutive.pubsub.consumer.http.internal.Model.AckId
import fs2.Stream
import fs2.concurrent.Queue
import io.chrisdavenport.log4cats.Logger
import org.http4s.client.Client

private[http] object PubsubSubscriber {

  def subscribe[F[_] : Timer : Logger](
    projectId: ProjectId,
    subscription: Subscription,
    serviceAccountPath: String,
    config: PubsubHttpConsumerConfig[F],
    httpClient: Client[F],
  )(
    implicit F: Concurrent[F],
  ): Stream[F, Model.Record[F]] = {
    val errorHandler: Throwable => F[Unit] = {
      case PubSubError.NoAckIds =>
        Logger[F].warn(s"[PubSub/Ack] a message was sent with no ids in it. This is likely a bug.")
      case PubSubError.Unknown(e) =>
        Logger[F].error(s"[PubSub] Unknown PubSub error occurred. Body is: ${e}")
      case e =>
        Logger[F].error(e)(s"[PubSub] An unknown error occurred")
    }

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
      source =
        if (config.readConcurrency == 1) Stream.repeatEval(reader.read)
        else Stream.emit(reader.read).repeat.covary[F].mapAsyncUnordered(config.readConcurrency)(identity)
      rec <- source
        .concurrently(
          ackQ
            .dequeue
            .groupWithin(config.acknowledgeBatchSize, config.acknowledgeBatchLatency)
            .evalMap(ids => reader.ack(ids.toList).handleErrorWith(errorHandler))
            .onFinalize(Logger[F].debug("[PubSub] Ack queue has exited."))
        )
        .concurrently(
          nackQ
            .dequeue
            .groupWithin(config.acknowledgeBatchSize, config.acknowledgeBatchLatency)
            .evalMap(ids => reader.nack(ids.toList).handleErrorWith(errorHandler))
            .onFinalize(Logger[F].debug("[PubSub] Nack queue has exited."))
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
