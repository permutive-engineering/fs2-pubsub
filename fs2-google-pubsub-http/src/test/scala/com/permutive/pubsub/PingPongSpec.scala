package com.permutive.pubsub

import cats.effect._
import cats.effect.concurrent.Ref
import cats.syntax.all._
import com.google.cloud.pubsub.v1.{SubscriptionAdminClient, TopicAdminClient}
import com.google.pubsub.v1.{ProjectSubscriptionName, TopicName}
import com.permutive.pubsub.consumer.ConsumerRecord
import com.permutive.pubsub.consumer.http.Example.ValueHolder
import com.permutive.pubsub.producer.PubsubProducer
import fs2.Stream
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.http4s.client.Client
import org.scalatest.BeforeAndAfterEach

import scala.concurrent.duration._

class PingPongSpec extends PubSubSpec with BeforeAndAfterEach {

  implicit val logger: Logger[IO] = Slf4jLogger.getLogger

  // Delete topic and subscriptions after each test to ensure state is clean
  override def afterEach(): Unit =
    clearTopicSubscription.unsafeRunSync()

  private[this] val topicAndSubscriptionClientBlocker
    : Resource[IO, (Blocker, TopicAdminClient, SubscriptionAdminClient)] =
    for {
      blockTransCreds    <- providersAndBlocker(None)
      topicClient        <- topicAdminClient(blockTransCreds._2._1, blockTransCreds._2._2)
      subscriptionClient <- subscriptionAdminClient(blockTransCreds._2._1, blockTransCreds._2._2)
    } yield (blockTransCreds._1, topicClient, subscriptionClient)

  private[this] val clearTopicSubscription: IO[Unit] =
    topicAndSubscriptionClientBlocker.use { case (blocker, topicClient, subscriptionClient) =>
      for {
        _ <- deleteSubscription(subscriptionClient, ProjectSubscriptionName.of(project, subscription), Some(blocker))
        _ <- deleteTopic(topicClient, TopicName.of(project, topic), Some(blocker))
      } yield ()
    }

  private def setup(ackDeadlineSeconds: Int): Resource[IO, (Client[IO], PubsubProducer[IO, ValueHolder])] =
    for {
      b <- Blocker[IO]
      c <- client(Some(b))
      _ <- Resource.eval(createTopic(project, topic, Some(b)))
      _ <- Resource.eval(createSubscription(project, topic, subscription, ackDeadlineSeconds, Some(b)))
      p <- producer(c)
    } yield (c, p)

  private def consumeExpectingLimitedMessages(
    client: Client[IO],
    messagesExpected: Int,
  ): Stream[IO, ConsumerRecord[IO, ValueHolder]] =
    consumer(client)
      // Check we only receive a single element
      .zipWithIndex
      .flatMap { case (record, ix) =>
        Stream.fromEither[IO](
          // Index is 0-based, so we expected index to reach 1 less than `messagesExpected`
          if (ix < messagesExpected.toLong) Right(record)
          else Left(new RuntimeException(s"Received more than $messagesExpected from PubSub"))
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
    elementsReceived: Ref[IO, Int],
  ): Stream[IO, ConsumerRecord[IO, ValueHolder]] =
    consumeExpectingLimitedMessages(client, messagesExpected = 1)
      // Indicate we have received the element as expected
      .evalTap(_ => elementsReceived.update(_ + 1))
      .evalTap(_.ack)

  it should "send and receive a message, acknowledging as expected" in {
    (for {
      // We will sleep for 10 seconds, which means if the message is not acked it will be redelivered before end of test
      (client, producer) <- Stream.resource(setup(ackDeadlineSeconds = 5))
      _                  <- Stream.eval(producer.produce(ValueHolder("ping")))
      ref                <- Stream.eval(Ref.of[IO, Int](0))
      // Wait 10 seconds whilst we run the consumer to check we have a single element and it has the right data
      _                <- Stream.sleep[IO](10.seconds).concurrently(consumeAndAck(client, ref))
      elementsReceived <- Stream.eval(ref.get)
    } yield elementsReceived should ===(1))
      .as(ExitCode.Success)
      .compile
      .drain
      .unsafeRunSync()
  }

  private def consumeExtendSleepAck(
    client: Client[IO],
    elementsReceived: Ref[IO, Int],
    extendDuration: FiniteDuration,
    sleepDuration: FiniteDuration,
  ): Stream[IO, ConsumerRecord[IO, ValueHolder]] =
    consumeExpectingLimitedMessages(client, messagesExpected = 1)
      .evalTap(_.extendDeadline(extendDuration))
      .evalTap(_ => IO.sleep(sleepDuration))
      // Indicate we have received the element as expected
      .evalTap(_ => elementsReceived.update(_ + 1))
      .evalTap(_.ack)

  it should "extend the deadline for a message" in {
    // These setting mean that if extension does not work the message will be redelivered before the end of the test
    val ackDeadlineSeconds = 2
    val sleepDuration      = 3.seconds
    val extendDuration     = 10.seconds

    (for {
      (client, producer) <- Stream.resource(setup(ackDeadlineSeconds = ackDeadlineSeconds))
      _                  <- Stream.eval(producer.produce(ValueHolder("ping")))
      ref                <- Stream.eval(Ref.of[IO, Int](0))
      // Wait 10 seconds whilst we run the consumer to check we have a single element and it has the right data
      _ <- Stream
        .sleep[IO](10.seconds)
        .concurrently(
          consumeExtendSleepAck(client, ref, extendDuration = extendDuration, sleepDuration = sleepDuration)
        )
      elementsReceived <- Stream.eval(ref.get)
    } yield elementsReceived should ===(1))
      .as(ExitCode.Success)
      .compile
      .drain
      .unsafeRunSync()
  }

  private def consumeNackThenAck(
    client: Client[IO],
    elementsReceived: Ref[IO, Int],
    messagesExpected: Int,
  ): Stream[IO, Unit] =
    // We expect the message to be redelivered once, so expect 2 messages total
    consumeExpectingLimitedMessages(client, messagesExpected = messagesExpected)
      // Indicate we have received the element as expected
      .evalTap(_ => elementsReceived.update(_ + 1))
      // Nack the first message, then ack subsequent ones
      .evalScan(false) { case (nackedAlready, record) =>
        if (nackedAlready) record.ack.as(true) else record.nack.as(true)
      }
      .void

  it should "nack a message properly" in {
    // These setting mean that a message will only be redelivered if it is nacked
    val ackDeadlineSeconds = 100
    val messagesExpected   = 2

    (for {
      (client, producer) <- Stream.resource(setup(ackDeadlineSeconds = ackDeadlineSeconds))
      _                  <- Stream.eval(producer.produce(ValueHolder("ping")))
      ref                <- Stream.eval(Ref.of[IO, Int](0))
      // Wait 10 seconds whilst we run the consumer which nacks the message, then acks
      _ <- Stream
        .sleep[IO](10.seconds)
        .concurrently(consumeNackThenAck(client, ref, messagesExpected))
      elementsReceived <- Stream.eval(ref.get)
    } yield elementsReceived should ===(messagesExpected))
      .as(ExitCode.Success)
      .compile
      .drain
      .unsafeRunSync()
  }

}
