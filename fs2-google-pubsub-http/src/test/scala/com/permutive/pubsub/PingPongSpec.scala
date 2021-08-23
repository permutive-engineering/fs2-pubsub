package com.permutive.pubsub

import cats.effect._
import com.google.cloud.pubsub.v1.{SubscriptionAdminClient, TopicAdminClient}
import com.google.pubsub.v1.{ProjectSubscriptionName, TopicName}
import com.permutive.pubsub.consumer.ConsumerRecord
import com.permutive.pubsub.consumer.http.Example.ValueHolder
import com.permutive.pubsub.producer.PubsubProducer
import fs2.Stream
import org.http4s.client.Client
import org.scalatest.BeforeAndAfterEach
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.concurrent.duration._

class PingPongSpec extends PubSubSpec with BeforeAndAfterEach {

  implicit val logger: Logger[IO] = Slf4jLogger.getLogger

  // Delete topic and subscriptions after each test to ensure state is clean
  override def afterEach(): Unit =
    clearTopicSubscription.unsafeRunSync()

  private[this] val topicAndSubscriptionClient: Resource[IO, (TopicAdminClient, SubscriptionAdminClient)] =
    for {
      transCreds         <- providers
      topicClient        <- topicAdminClient(transCreds._1, transCreds._2)
      subscriptionClient <- subscriptionAdminClient(transCreds._1, transCreds._2)
    } yield (topicClient, subscriptionClient)

  private[this] val clearTopicSubscription: IO[Unit] =
    topicAndSubscriptionClient.use { case (topicClient, subscriptionClient) =>
      for {
        _ <- deleteSubscription(subscriptionClient, ProjectSubscriptionName.of(project, subscription))
        _ <- deleteTopic(topicClient, TopicName.of(project, topic))
      } yield ()
    }

  private def setup(ackDeadlineSeconds: Int): Resource[IO, (Client[IO], PubsubProducer[IO, ValueHolder])] =
    for {
      c <- client
      _ <- Resource.eval(createTopic(project, topic))
      _ <- Resource.eval(createSubscription(project, topic, subscription, ackDeadlineSeconds))
      p <- producer(c)
    } yield (c, p)

  private def consumeExpectingSingleMessage(client: Client[IO]): Stream[IO, ConsumerRecord[IO, ValueHolder]] =
    consumer(client)
      // Check we only receive a single element
      .zipWithIndex
      .flatMap { case (record, ix) =>
        Stream.fromEither[IO](
          if (ix == 0L) Right(record)
          else Left(new RuntimeException("Received more than a single element from PubSub"))
        )
      }
      // Check body is as we expect
      .flatMap(record =>
        Stream.fromEither[IO](
          if (record.value == ValueHolder("ping")) Right(record)
          else Left(new RuntimeException(s"Consumed element did not have correct value: ${record.value}"))
        )
      )

  private def consumeAndAck(
    client: Client[IO],
    elementReceived: Ref[IO, Boolean],
  ): Stream[IO, ConsumerRecord[IO, ValueHolder]] =
    consumeExpectingSingleMessage(client)
      // Indicate we have received the element as expected
      .evalTap(_ => elementReceived.set(true))
      .evalTap(_.ack)

  it should "send and receive a message, acknowledging as expected" in {
    (for {
      // We will sleep for 10 seconds, which means if the message is not acked it will be redelivered before end of test
      (client, producer) <- Stream.resource(setup(ackDeadlineSeconds = 5))
      _                  <- Stream.eval(producer.produce(ValueHolder("ping")))
      ref                <- Stream.eval(Ref.of[IO, Boolean](false))
      // Wait 10 seconds whilst we run the consumer to check we have a single element and it has the right data
      _               <- Stream.sleep[IO](10.seconds).concurrently(consumeAndAck(client, ref))
      elementReceived <- Stream.eval(ref.get)
    } yield elementReceived should ===(true))
      .as(ExitCode.Success)
      .compile
      .drain
      .unsafeRunSync()
  }

  private def consumeExtendSleepAck(
    client: Client[IO],
    elementReceived: Ref[IO, Boolean],
    extendDuration: FiniteDuration,
    sleepDuration: FiniteDuration,
  ): Stream[IO, ConsumerRecord[IO, ValueHolder]] =
    consumeExpectingSingleMessage(client)
      .evalTap(_.extendDeadline(extendDuration))
      .evalTap(_ => IO.sleep(sleepDuration))
      // Indicate we have received the element as expected
      .evalTap(_ => elementReceived.set(true))
      .evalTap(_.ack)

  it should "extend the deadline for a message" in {
    // These setting mean that if extension does not work the message will be redelivered before the end of the test
    val ackDeadlineSeconds = 2
    val sleepDuration      = 3.seconds
    val extendDuration     = 10.seconds

    (for {
      (client, producer) <- Stream.resource(setup(ackDeadlineSeconds = ackDeadlineSeconds))
      _                  <- Stream.eval(producer.produce(ValueHolder("ping")))
      ref                <- Stream.eval(Ref.of[IO, Boolean](false))
      // Wait 10 seconds whilst we run the consumer to check we have a single element and it has the right data
      _ <- Stream
        .sleep[IO](10.seconds)
        .concurrently(
          consumeExtendSleepAck(client, ref, extendDuration = extendDuration, sleepDuration = sleepDuration)
        )
      elementReceived <- Stream.eval(ref.get)
    } yield elementReceived should ===(true))
      .as(ExitCode.Success)
      .compile
      .drain
      .unsafeRunSync()
  }

}
