package com.permutive.pubsub.producer.http

import java.util.concurrent.Executors

import cats.effect._
import cats.syntax.all._
import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._
import com.permutive.pubsub.producer.Model
import com.permutive.pubsub.producer.encoder.MessageEncoder
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.http4s.client.okhttp.OkHttpBuilder

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.Try

object ExampleBatching extends IOApp {

  final implicit val Codec: JsonValueCodec[ExampleObject] =
    JsonCodecMaker.make[ExampleObject](CodecMakerConfig())

  implicit val encoder: MessageEncoder[ExampleObject] = (a: ExampleObject) => {
    Try(writeToArray(a)).toEither
  }

  case class ExampleObject(
    projectId: String,
    url: String,
  )

  def blockingThreadPool[F[_]](implicit F: Sync[F]): Resource[F, ExecutionContext] = {
    Resource
      .make(F.delay(Executors.newCachedThreadPool()))(e => F.delay(e.shutdown()))
      .map(ExecutionContext.fromExecutor)
  }

  override def run(args: List[String]): IO[ExitCode] = {
    val client = blockingThreadPool[IO].flatMap(
      OkHttpBuilder
        .withDefaultClient[IO](_)
        .flatMap(_.resource)
    )

    implicit val unsafeLogger: Logger[IO] = Slf4jLogger.getLoggerFromName("fs2-google-pubsub")

    val mkProducer = BatchingHttpPubsubProducer.resource[IO, ExampleObject](
      projectId = Model.ProjectId("test-project"),
      topic = Model.Topic("example-topic"),
      googleServiceAccountPath = "/path/to/service/account",
      config = PubsubHttpProducerConfig(
        host = "localhost",
        port = 8085,
        oauthTokenRefreshInterval = 30.minutes,
        isEmulator = true,
      ),
      batchingConfig = BatchingHttpProducerConfig(
        batchSize = 10,
        maxLatency = 100.millis,

        retryTimes = 0,
        retryInitialDelay = 0.millis,
        retryNextDelay = _ + 250.millis,
      ),
      _
    )

    client
      .flatMap(mkProducer)
      .use { producer =>
        val value = producer.produceAsync(
          record = ExampleObject("1f9774be-9d7c-4dd9-8d97-855b681938a9", "example.com"),
        )

        for {
          result1 <- value
          result2 <- value
          result3 <- value
          _       <- result1
          _       <- Logger[IO].info("First message was sent!")
          _       <- result2
          _       <- Logger[IO].info("Second message was sent!")
          _       <- result3
          _       <- Logger[IO].info("Third message was sent!")
        } yield ()
      }
      .as(ExitCode.Success)
  }
}
