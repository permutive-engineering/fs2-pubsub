package com.permutive.pubsub.http.oauth

import cats.effect.{Async, Sync}
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import com.github.plokhotnyuk.jsoniter_scala.core.readFromArray
import com.permutive.pubsub.http.oauth.GoogleOAuth.FailedRequest
import org.http4s.Method.GET
import org.http4s.Uri
import org.http4s.client.Client
import org.http4s.client.dsl.Http4sClientDsl
import org.typelevel.log4cats.Logger

import java.time.Instant
import scala.concurrent.duration._
import scala.util.control.NoStackTrace

// Obtains OAuth token from instance metadata
// https://cloud.google.com/compute/docs/access/create-enable-service-accounts-for-instances#applications
class InstanceMetadataOAuth[F[_]: Async: Logger](httpClient: Client[F]) extends OAuth[F] with Http4sClientDsl[F] {

  // https://cloud.google.com/compute/docs/access/create-enable-service-accounts-for-instances#applications
  final private[this] val googleInstanceMetadataTokenUri = Uri.unsafeFromString(
    "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token"
  )

  final private[this] val request =
    GET(googleInstanceMetadataTokenUri, "Metadata-Flavor" -> "Google")

  private[this] val doAuthenticate: F[Option[AccessToken]] =
    httpClient
      .expectOr[Array[Byte]](request) { resp =>
        resp.as[String].map(FailedRequest.apply)
      }
      .flatMap(bytes => Sync[F].delay(readFromArray[AccessToken](bytes)).map(_.some))
      .handleErrorWith(Logger[F].warn(_)("Failed to retrieve JWT Access Token from Google").as(None))

  override def authenticate(iss: String, scope: String, exp: Instant, iat: Instant): F[Option[AccessToken]] =
    doAuthenticate

  final override val maxDuration: FiniteDuration = 1.hour
}

object InstanceMetadataOAuth {
  case class FailedRequest(body: String)
      extends RuntimeException(s"Failed request, got response: $body")
      with NoStackTrace
}
