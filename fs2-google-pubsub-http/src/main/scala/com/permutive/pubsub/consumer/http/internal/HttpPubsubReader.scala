package com.permutive.pubsub.consumer.http.internal

import cats.Applicative
import cats.effect._
import cats.syntax.all._
import com.github.plokhotnyuk.jsoniter_scala.core.{readFromArray, writeToArray, JsonValueCodec}
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import com.permutive.pubsub.consumer.Model.{ProjectId, Subscription}
import com.permutive.pubsub.consumer.http.PubsubHttpConsumerConfig
import com.permutive.pubsub.consumer.http.internal.Model.{
  AckId,
  AckRequest,
  ModifyAckDeadlineRequest,
  ProjectNameSubscription,
  PullRequest,
  PullResponse
}
import com.permutive.pubsub.http.oauth.{AccessToken, DefaultTokenProvider}
import com.permutive.pubsub.http.util.RefreshableEffect
import io.chrisdavenport.log4cats.Logger
import org.http4s.Method._
import org.http4s.Uri._
import org.http4s._
import org.http4s.client._
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.headers._

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

private[internal] class HttpPubsubReader[F[_]] private (
  baseApiUrl: Uri,
  client: Client[F],
  tokenF: F[AccessToken],
  returnImmediately: Boolean,
  maxMessages: Int
)(
  implicit F: Sync[F]
) extends PubsubReader[F] {
  object dsl extends Http4sClientDsl[F]

  import HttpPubsubReader._
  import dsl._

  implicit final val ErrorEntityDecoder: EntityDecoder[F, PubSubErrorResponse] =
    EntityDecoder.byteArrayDecoder.map(readFromArray[PubSubErrorResponse](_))

  final private[this] val pullEndpoint           = baseApiUrl.copy(path = baseApiUrl.path.concat(":pull"))
  final private[this] val acknowledgeEndpoint    = baseApiUrl.copy(path = baseApiUrl.path.concat(":acknowledge"))
  final private[this] val modifyDeadlineEndpoint = baseApiUrl.copy(path = baseApiUrl.path.concat(":modifyAckDeadline"))

  final override val read: F[PullResponse] = {
    for {
      json <- F.delay(
        writeToArray(
          PullRequest(
            returnImmediately = returnImmediately,
            maxMessages = maxMessages
          )
        )
      )
      token <- tokenF
      req <- POST(
        json,
        pullEndpoint.withQueryParam("access_token", token.accessToken),
        `Content-Type`(MediaType.application.json)
      )
      resp <- client.expectOr[Array[Byte]](req)(onError)
      resp <- F.delay(readFromArray[PullResponse](resp))
    } yield resp
  }

  final override def ack(ackIds: List[AckId]): F[Unit] =
    for {
      json <- F.delay(
        writeToArray(
          AckRequest(
            ackIds = ackIds
          )
        )
      )
      token <- tokenF
      req <- POST(
        json,
        acknowledgeEndpoint.withQueryParam("access_token", token.accessToken),
        `Content-Type`(MediaType.application.json)
      )
      _ <- client.expectOr[Array[Byte]](req)(onError)
    } yield ()

  final override def nack(ackIds: List[AckId]): F[Unit] =
    modifyDeadline(ackIds, 0.seconds)

  final override def modifyDeadline(ackId: List[AckId], by: FiniteDuration): F[Unit] =
    for {
      json <- F.delay(
        writeToArray(
          ModifyAckDeadlineRequest(
            ackIds = ackId,
            ackDeadlineSeconds = by.toSeconds,
          )
        )
      )
      token <- tokenF
      req <- POST(
        json,
        modifyDeadlineEndpoint.withQueryParam("access_token", token.accessToken),
        `Content-Type`(MediaType.application.json)
      )
      _ <- client.expectOr[Array[Byte]](req)(onError)
    } yield ()

  @inline
  final private def onError(resp: Response[F]): F[Throwable] =
    resp.as[PubSubErrorResponse].map(PubSubError.fromResponse)
}

private[internal] object HttpPubsubReader {
  def resource[F[_]: Concurrent: Timer: Logger](
    projectId: ProjectId,
    subscription: Subscription,
    serviceAccountPath: String,
    config: PubsubHttpConsumerConfig[F],
    httpClient: Client[F]
  ): Resource[F, PubsubReader[F]] =
    for {
      tokenProvider <- Resource.liftF(
        if (config.isEmulator) DefaultTokenProvider.noAuth.pure
        else DefaultTokenProvider.google(serviceAccountPath, httpClient)
      )
      accessTokenRefEffect <- RefreshableEffect.createRetryResource(
        refresh = tokenProvider.accessToken,
        refreshInterval = config.oauthTokenRefreshInterval,
        onRefreshSuccess = config.onTokenRefreshSuccess.getOrElse(Applicative[F].unit),
        onRefreshError = config.onTokenRefreshError,
        retryDelay = config.oauthTokenFailureRetryDelay,
        retryNextDelay = config.oauthTokenFailureRetryNextDelay,
        retryMaxAttempts = config.oauthTokenFailureRetryMaxAttempts,
        onRetriesExhausted = config.onTokenRetriesExhausted,
      )
    } yield new HttpPubsubReader(
      baseApiUrl = createBaseApi(config, ProjectNameSubscription.of(projectId, subscription)),
      client = httpClient,
      tokenF = accessTokenRefEffect.value,
      returnImmediately = config.readReturnImmediately,
      maxMessages = config.readMaxMessages
    )

  def createBaseApi[F[_]](config: PubsubHttpConsumerConfig[F], projectNameSubscription: ProjectNameSubscription): Uri =
    Uri(
      scheme = Option(if (config.port == 443) Uri.Scheme.https else Uri.Scheme.http),
      authority = Option(Uri.Authority(host = RegName(config.host), port = Option(config.port))),
      path = s"/v1/${projectNameSubscription.value}"
    )

  sealed abstract class PubSubError(msg: String)
      extends Throwable(s"Failed request to PubSub. Underlying message: ${msg}")
      with NoStackTrace

  object PubSubError {
    case object NoAckIds                          extends PubSubError("No ack ids specified")
    case class Unknown(body: PubSubErrorResponse) extends PubSubError(body.toString)

    def fromResponse(response: PubSubErrorResponse): PubSubError =
      response.error.message match {
        case "No ack ids specified." => NoAckIds
        case _                       => Unknown(response)
      }
  }

  case class PubSubErrorMessage(message: String, status: String, code: Int)
  case class PubSubErrorResponse(error: PubSubErrorMessage)

  object PubSubErrorResponse {
    implicit final val Codec: JsonValueCodec[PubSubErrorResponse] =
      JsonCodecMaker.make[PubSubErrorResponse](CodecMakerConfig)
  }
}
