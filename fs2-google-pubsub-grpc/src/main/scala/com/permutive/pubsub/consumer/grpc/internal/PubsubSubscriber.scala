package com.permutive.pubsub.consumer.grpc.internal

import cats.Applicative
import cats.effect.kernel.{Resource, Sync}
import cats.syntax.all._
import com.google.api.core.ApiService
import com.google.api.gax.batching.FlowControlSettings
import com.google.cloud.pubsub.v1.{AckReplyConsumer, MessageReceiver, Subscriber}
import com.google.common.util.concurrent.MoreExecutors
import com.google.pubsub.v1.{ProjectSubscriptionName, PubsubMessage}
import com.permutive.pubsub.consumer.grpc.PubsubGoogleConsumer.InternalPubSubError
import com.permutive.pubsub.consumer.grpc.PubsubGoogleConsumerConfig
import com.permutive.pubsub.consumer.{Model => PublicModel}
import fs2.{Chunk, Stream}
import org.threeten.bp.Duration

import java.util
import collection.JavaConverters._
import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue, TimeUnit}

private[consumer] object PubsubSubscriber {

  def createSubscriber[F[_]: Sync](
    projectId: PublicModel.ProjectId,
    subscription: PublicModel.Subscription,
    config: PubsubGoogleConsumerConfig[F],
    queue: BlockingQueue[Either[InternalPubSubError, Model.Record[F]]],
  ): Resource[F, ApiService] =
    Resource.make(
      Sync[F].delay {
        val receiver         = new PubsubMessageReceiver(queue)
        val subscriptionName = ProjectSubscriptionName.of(projectId.value, subscription.value)

        // build subscriber with "normal" settings
        val builder =
          Subscriber
            .newBuilder(subscriptionName, receiver)
            .setFlowControlSettings(
              FlowControlSettings
                .newBuilder()
                .setMaxOutstandingElementCount(config.maxQueueSize.toLong)
                .build()
            )
            .setParallelPullCount(config.parallelPullCount)
            .setMaxAckExtensionPeriod(Duration.ofMillis(config.maxAckExtensionPeriod.toMillis))

        // if provided, use subscriber transformer to modify subscriber
        val sub =
          config.customizeSubscriber
            .map(f => f(builder))
            .getOrElse(builder)
            .build()

        sub.addListener(new PubsubErrorListener(queue), MoreExecutors.directExecutor)

        sub.startAsync()
      }
    )(service =>
      Sync[F]
        .blocking(service.stopAsync().awaitTerminated(config.awaitTerminatePeriod.toSeconds, TimeUnit.SECONDS))
        .handleErrorWith(config.onFailedTerminate)
    )

  class PubsubMessageReceiver[F[_]: Sync, L](queue: BlockingQueue[Either[L, Model.Record[F]]]) extends MessageReceiver {
    override def receiveMessage(message: PubsubMessage, consumer: AckReplyConsumer): Unit =
      queue.put(Right(Model.Record(message, Sync[F].delay(consumer.ack()), Sync[F].delay(consumer.nack()))))
  }

  class PubsubErrorListener[R](queue: BlockingQueue[Either[InternalPubSubError, R]]) extends ApiService.Listener {
    override def failed(from: ApiService.State, failure: Throwable): Unit =
      queue.put(Left(InternalPubSubError(failure)))
  }

  def takeNextElements[F[_]: Sync, A](messages: BlockingQueue[A]): F[Chunk[A]] =
    for {
      nextOpt  <- Sync[F].delay(Option(messages.poll())) // `poll` is non-blocking, returning `null` if queue is empty
      next     <- nextOpt.fold(Sync[F].blocking(messages.take()))(Applicative[F].pure) // `take` can wait for an element
      elements <- Sync[F].delay(new util.ArrayList[A])
      _        <- Sync[F].delay(elements.add(next))
      _        <- Sync[F].delay(messages.drainTo(elements))
    } yield Chunk.buffer(elements.asScala)

  def subscribe[F[_]: Sync](
    projectId: PublicModel.ProjectId,
    subscription: PublicModel.Subscription,
    config: PubsubGoogleConsumerConfig[F],
  ): Stream[F, Model.Record[F]] =
    for {
      queue <- Stream.eval(
        Sync[F].delay(new LinkedBlockingQueue[Either[InternalPubSubError, Model.Record[F]]](config.maxQueueSize))
      )
      _     <- Stream.resource(PubsubSubscriber.createSubscriber(projectId, subscription, config, queue))
      taken <- Stream.repeatEval(takeNextElements(queue))
      // Only retains the first error (if there are multiple), but that is OK, the stream is failing anyway...
      msg <- Stream.fromEither[F](taken.sequence).unchunks
    } yield msg
}
