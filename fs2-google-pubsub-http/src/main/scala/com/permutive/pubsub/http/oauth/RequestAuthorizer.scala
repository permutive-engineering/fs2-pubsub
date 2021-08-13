package com.permutive.pubsub.http.oauth

import cats.syntax.all._
import cats.{Applicative, Functor}
import org.http4s.{AuthScheme, Credentials, Request}
import org.http4s.headers.Authorization

// This could be implemented as client middleware but we think that could be a footgun; a client using auth middleware
// could easily be used in the wrong place by accident.
trait RequestAuthorizer[F[_]] {
  def authorize(request: Request[F]): F[Request[F]]
}

object RequestAuthorizer {

  def tokenProvider[F[_]: Functor](tokenProvider: TokenProvider[F]): RequestAuthorizer[F] =
    new RequestAuthorizer[F] {
      override def authorize(request: Request[F]): F[Request[F]] =
        tokenProvider.accessToken.map(token =>
          request.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token.accessToken)))
        )
    }

  def noop[F[_]: Applicative]: RequestAuthorizer[F] = new RequestAuthorizer[F] {
    override def authorize(request: Request[F]): F[Request[F]] = Applicative[F].pure(request)
  }

}
