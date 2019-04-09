package com.permutive.pubsub.producer.http.internal

import cats.Traverse
import cats.effect.syntax.all._
import cats.effect.{Concurrent, Resource, Timer}
import cats.syntax.all._
import com.permutive.pubsub.producer.encoder.MessageEncoder
import com.permutive.pubsub.producer.http.BatchingHttpProducerConfig
import com.permutive.pubsub.producer.http.BatchingHttpPubsubProducer.Batch
import com.permutive.pubsub.producer.{AsyncPubsubProducer, Model, PubsubProducer}
import fs2.Chunk._
import fs2.concurrent.{Enqueue, Queue}
import fs2.{Chunk, Stream}

private[http] class BatchingHttpPublisher[F[_] : Concurrent : Timer, A: MessageEncoder] private(
  queue: Enqueue[F, Model.AsyncRecord[F, A]],
) extends AsyncPubsubProducer[F, A] {

  override def produceAsync(
    record: A,
    callback: F[Unit],
    metadata: Map[String, String],
    uniqueId: String
  ): F[Unit] = {
    queue.enqueue1(Model.AsyncRecord(record, callback, metadata, uniqueId))
  }

  override def produceManyAsync[G[_] : Traverse](records: G[Model.AsyncRecord[F, A]]): F[Unit] =
    records.traverse(queue.enqueue1).void
}

private[http] object BatchingHttpPublisher {
  def resource[F[_] : Concurrent : Timer, A: MessageEncoder](
    publisher: PubsubProducer[F, A],
    config: BatchingHttpProducerConfig,
    onPublishFailure: (Batch[F, A], Throwable) => F[Unit],
  ): Resource[F, AsyncPubsubProducer[F, A]] = {
    for {
      queue <- Resource.liftF(Queue.unbounded[F, Model.AsyncRecord[F, A]])
      _ <- Resource.make(consume(publisher, config, queue, onPublishFailure).start)(_.cancel)
    } yield new BatchingHttpPublisher(queue)
  }

  private def consume[F[_] : Concurrent : Timer, A: MessageEncoder](
    underlying: PubsubProducer[F, A],
    config: BatchingHttpProducerConfig,
    queue: Queue[F, Model.AsyncRecord[F, A]],
    onPublishFailure: (Batch[F, A], Throwable) => F[Unit],
  ): F[Unit] = {
    val handler: Chunk[Model.AsyncRecord[F, A]] => F[Unit] =
      if (config.retryTimes == 0) {
        records => underlying.produceMany[Chunk](records) >> records.traverse_(_.callback)
      } else {
        records =>
          Stream.retry(
            underlying.produceMany[Chunk](records),
            delay = config.retryInitialDelay,
            nextDelay = config.retryNextDelay,
            maxAttempts = config.retryTimes,
          ).compile.lastOrError >> records.traverse_(_.callback)
      }

    queue
      .dequeue
      .groupWithin(config.batchSize, config.maxLatency)
      .evalMap { records =>
        handler(records).handleErrorWith(onPublishFailure(records, _))
      }
      .compile
      .drain
  }
}