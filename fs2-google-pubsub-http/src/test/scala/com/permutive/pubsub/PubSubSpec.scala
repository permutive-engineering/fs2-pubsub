package com.permutive.pubsub

import cats.effect.{Blocker, ContextShift, IO, Resource, Timer}
import cats.syntax.all._
import com.dimafeng.testcontainers.{ForAllTestContainer, GenericContainer}
import com.google.api.gax.core.{CredentialsProvider, NoCredentialsProvider}
import com.google.api.gax.grpc.GrpcTransportChannel
import com.google.api.gax.rpc.{FixedTransportChannelProvider, TransportChannelProvider}
import com.google.cloud.pubsub.v1.{
  SubscriptionAdminClient,
  SubscriptionAdminSettings,
  TopicAdminClient,
  TopicAdminSettings
}
import com.google.pubsub.v1._
import com.permutive.pubsub.consumer.http.Example.ValueHolder
import com.permutive.pubsub.consumer.http.{PubsubHttpConsumer, PubsubHttpConsumerConfig}
import com.permutive.pubsub.consumer.{ConsumerRecord, Model}
import com.permutive.pubsub.producer.PubsubProducer
import com.permutive.pubsub.producer.http.{HttpPubsubProducer, PubsubHttpProducerConfig}
import fs2.Stream
import io.chrisdavenport.log4cats.Logger
import io.grpc.{ManagedChannel, ManagedChannelBuilder}
import org.http4s.client.Client
import org.http4s.client.okhttp.OkHttpBuilder
import org.scalactic.TripleEquals
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.testcontainers.containers.wait.strategy.Wait

trait PubSubSpec extends AnyFlatSpec with ForAllTestContainer with Matchers with TripleEquals {

  implicit val logger: Logger[IO]
  implicit val ctx: ContextShift[IO] = IO.contextShift(scala.concurrent.ExecutionContext.global)
  implicit val T: Timer[IO]          = IO.timer(scala.concurrent.ExecutionContext.global)

  val project      = "test-project"
  val topic        = "example-topic"
  val subscription = "example-subcription"

  override val container: GenericContainer =
    GenericContainer(
      "google/cloud-sdk:311.0.0", // newer version don't work for some reason
      exposedPorts = Seq(8085),
      waitStrategy = Wait.forLogMessage("(?s).*started.*$", 1),
      command = s"gcloud beta emulators pubsub start --project=$project --host-port 0.0.0.0:8085"
        .split(" ")
        .toSeq
    )

  override def afterStart(): Unit =
    updateEnv("PUBSUB_EMULATOR_HOST", s"localhost:${container.mappedPort(8085)}")

  def providers: Resource[IO, (TransportChannelProvider, CredentialsProvider)] =
    Resource
      .make(
        IO {
          ManagedChannelBuilder
            .forAddress("localhost", container.mappedPort(8085))
            .usePlaintext()
            .build(): ManagedChannel
        }
      )(ch => IO(ch.shutdown()).void)
      .map { channel =>
        val channelProvider: FixedTransportChannelProvider =
          FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel))
        val credentialsProvider: NoCredentialsProvider = NoCredentialsProvider.create

        (channelProvider: TransportChannelProvider, credentialsProvider: CredentialsProvider)
      }

  def createTopic(projectId: String, topicId: String): Resource[IO, Topic] =
    providers.evalMap { case (channel, cred) =>
      IO(
        TopicAdminClient.create(
          TopicAdminSettings
            .newBuilder()
            .setTransportChannelProvider(channel)
            .setCredentialsProvider(cred)
            .build()
        )
      )
        .flatMap { client =>
          IO(client.createTopic(TopicName.of(projectId, topicId)))
            .guarantee(IO(client.close()))
        }
    }

  def createSubscription(projectId: String, topicId: String, subscription: String): Resource[IO, Subscription] =
    providers.evalMap { case (channel, cred) =>
      IO(
        SubscriptionAdminClient.create(
          SubscriptionAdminSettings
            .newBuilder()
            .setTransportChannelProvider(channel)
            .setCredentialsProvider(cred)
            .build()
        )
      )
        .flatMap { client =>
          IO(
            client.createSubscription(
              ProjectSubscriptionName.format(projectId, subscription),
              TopicName.format(projectId, topicId),
              PushConfig.getDefaultInstance,
              60
            )
          )
            .guarantee(IO(client.close()))
        }
    }

  def client: Resource[IO, Client[IO]] = Blocker[IO].flatMap(blocker =>
    OkHttpBuilder
      .withDefaultClient[IO](blocker)
      .flatMap(_.resource)
  )

  def producer(
    client: Client[IO],
    project: String = project,
    topic: String = topic
  ): Resource[IO, PubsubProducer[IO, ValueHolder]] =
    HttpPubsubProducer.resource[IO, ValueHolder](
      com.permutive.pubsub.producer.Model.ProjectId(project),
      com.permutive.pubsub.producer.Model.Topic(topic),
      Some("/path/to/service/account"),
      config = PubsubHttpProducerConfig(
        host = container.host,
        port = container.mappedPort(8085),
        isEmulator = true,
      ),
      client
    )

  def consumer(
    client: Client[IO],
    project: String = project,
    subscription: String = subscription,
  ): Stream[IO, ConsumerRecord[IO, ValueHolder]] =
    for {
      out <- PubsubHttpConsumer.subscribe[IO, ValueHolder](
        Model.ProjectId(project),
        Model.Subscription(subscription),
        Some("/path/to/service/account"),
        PubsubHttpConsumerConfig(
          host = container.host,
          port = container.mappedPort(8085),
          isEmulator = true
        ),
        client,
        (msg, err, ack, _) => IO(println(s"Msg $msg got error $err")) >> ack
      )
    } yield out

  def updateEnv(name: String, value: String): Unit = {
    val env   = System.getenv
    val field = env.getClass.getDeclaredField("m")
    field.setAccessible(true)
    field.get(env).asInstanceOf[java.util.Map[String, String]].put(name, value)
    ()
  }
}
