package com.permutive.pubsub.producer.http

import cats.effect._
import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._
import com.permutive.pubsub.producer.Model
import com.permutive.pubsub.producer.encoder.MessageEncoder
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.http4s.client.okhttp.OkHttpBuilder

import scala.concurrent.duration._
import scala.util.Try

object ExampleBatching extends IOApp {

  implicit final val Codec: JsonValueCodec[ExampleObject] =
    JsonCodecMaker.make[ExampleObject](CodecMakerConfig)

  implicit val encoder: MessageEncoder[ExampleObject] = (a: ExampleObject) => {
    Try(writeToArray(a)).toEither
  }

  case class ExampleObject(
    projectId: String,
    url: String
  )

  override def run(args: List[String]): IO[ExitCode] = {
    val client = OkHttpBuilder
      .withDefaultClient[IO]
      .flatMap(_.resource)

    implicit val unsafeLogger: Logger[IO] = Slf4jLogger.getLoggerFromName("fs2-google-pubsub")

    val mkProducer = BatchingHttpPubsubProducer.resource[IO, ExampleObject](
      projectId = Model.ProjectId("test-project"),
      topic = Model.Topic("example-topic"),
      googleServiceAccountPath = Some("/path/to/service/account"),
      config = PubsubHttpProducerConfig(
        host = "localhost",
        port = 8085,
        oauthTokenRefreshInterval = 30.minutes,
        isEmulator = true
      ),
      batchingConfig = BatchingHttpProducerConfig(
        batchSize = 10,
        maxLatency = 100.millis,
        retryTimes = 0,
        retryInitialDelay = 0.millis,
        retryNextDelay = _ + 250.millis
      ),
      _
    )

    val messageCallback: Either[Throwable, Unit] => IO[Unit] = {
      case Right(_) => Logger[IO].info("Async message was sent successfully!")
      case Left(e)  => Logger[IO].warn(e)("Async message was sent unsuccessfully!")
    }

    client
      .flatMap(mkProducer)
      .use { producer =>
        val produceOne = producer.produce(
          record = ExampleObject("1f9774be-9d7c-4dd9-8d97-855b681938a9", "example.com")
        )

        val produceOneAsync = producer.produceAsync(
          record = ExampleObject("a84a3318-adbd-4eac-af78-eacf33be91ef", "example.com"),
          callback = messageCallback
        )

        for {
          result1 <- produceOne
          result2 <- produceOne
          result3 <- produceOne
          _       <- result1
          _       <- Logger[IO].info("First message was sent!")
          _       <- result2
          _       <- Logger[IO].info("Second message was sent!")
          _       <- result3
          _       <- Logger[IO].info("Third message was sent!")
          _       <- produceOneAsync
          _       <- IO.never[Unit]
        } yield ()
      }
      .as(ExitCode.Success)
  }
}
