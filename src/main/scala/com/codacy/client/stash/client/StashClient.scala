package com.codacy.client.stash.client

import com.codacy.client.stash.client.auth.Authenticator
import com.codacy.client.stash.util.HTTPStatusCodes
import play.api.libs.json._
import scalaj.http.{Http, HttpRequest, StringBodyConnectFunc}

import scala.util.Properties
import scala.util.control.NonFatal

class StashClient(apiUrl: String, authenticator: Option[Authenticator] = None) {

  /*
   * Does an API request and parses the json output into a class
   */
  def execute[T](request: Request[T])(implicit reader: Reads[T]): RequestResponse[T] = {
    get[T](request.url) match {
      case Right(json) => RequestResponse(json.asOpt[T])
      case Left(error) => error
    }
  }

  /*
   * Does an paginated API request and parses the json output into a sequence of classes
   */
  def executePaginated[T](request: Request[Seq[T]])(implicit reader: Reads[T]): RequestResponse[Seq[T]] = {
    get[Seq[T]](request.url) match {
      case Right(json) =>
        val nextRepos = (for {
          isLastPage <- (json \ "isLastPage").asOpt[Boolean] if !isLastPage
          nextPageStart <- (json \ "nextPageStart").asOpt[Int]
        } yield {
          val cleanUrl = request.url.takeWhile(_ != '?')
          val nextUrl = s"$cleanUrl?start=$nextPageStart"
          executePaginated(Request(nextUrl, request.classType)).value.getOrElse(Seq.empty)
        }).getOrElse(Seq.empty)

        RequestResponse(Some((json \ "values").as[Seq[T]] ++ nextRepos))

      case Left(resp) => resp
    }
  }

  def executePaginated[T](request: Request[Seq[T]], pageRequest: PageRequest)(
      implicit reader: Reads[T]
  ): RequestResponse[Seq[T]] = {

    val cleanUrl = request.url.takeWhile(_ != '?')
    val nextUrl = s"$cleanUrl?start=${pageRequest.getStart}&limit=${pageRequest.getLimit}"

    println("Page Request ", pageRequest)

    get[Seq[T]](nextUrl) match {
      case Right(json) => RequestResponse(Some((json \ "values").as[Seq[T]]))
      case Left(error) => error
    }
  }

  def postJson[T](request: Request[T], values: JsValue)(implicit reader: Reads[T]): RequestResponse[T] = {
    performRequest("POST", request, values)
  }

  def putJson[T](request: Request[T], values: JsValue)(implicit reader: Reads[T]): RequestResponse[T] = {
    performRequest("PUT", request, values)
  }

  //   Wrap request with authentication options
  private def withAuthentication(request: HttpRequest): HttpRequest =
    authenticator match {
      case Some(auth) => auth.withAuthentication(request)
      case None => request
    }

  /*
   * Does an API request
   */
  private def performRequest[T](method: String, request: Request[T], values: JsValue)(
      implicit reader: Reads[T]
  ): RequestResponse[T] = {
    doRequest[T](request.url, method, Option(values)) match {
      case Right((HTTPStatusCodes.OK | HTTPStatusCodes.CREATED, body)) =>
        parseJson[T](body).fold(identity, { jsValue =>
          valueOrError[T](jsValue)
        })

      case Right((HTTPStatusCodes.NO_CONTENT, _)) =>
        RequestResponse[T](None)

      case Right((statusCode, body)) =>
        getError[T](statusCode, statusCode.toString, body)

      case Left(requestResponse) =>
        requestResponse
    }
  }

  def delete(requestUrl: String): RequestResponse[Boolean] = {
    doRequest[Boolean](requestUrl, "DELETE", None) match {
      case Right((HTTPStatusCodes.NO_CONTENT, _)) =>
        RequestResponse[Boolean](Option(true))

      case Right((HTTPStatusCodes.OK, body)) =>
        parseJson[JsObject](body).fold({ error =>
          RequestResponse[Boolean](None, error.message, hasError = true)
        }, { _ =>
          RequestResponse[Boolean](Option(true))
        })

      case Right((statusCode, body)) =>
        getError[Boolean](statusCode, statusCode.toString, body)

      case Left(requestResponse) =>
        requestResponse
    }
  }

  private def get[T](requestUrl: String): Either[RequestResponse[T], JsValue] = {
    doRequest[T](requestUrl, "GET", None) match {
      case Right((HTTPStatusCodes.OK | HTTPStatusCodes.CREATED, body)) =>
        parseJson[T](body)
      case Right((statusCode, body)) =>
        Left(getError[T](statusCode, statusCode.toString, body))
      case Left(error) =>
        Left(error)
    }
  }

  def doRequest[T](
      requestPath: String,
      method: String,
      payload: Option[JsValue] = None
  ): Either[RequestResponse[T], (Int, String)] = {
    val url = generateUrl(requestPath)
    try {
      val baseRequest = Http(url).method(method)
      val request = payload
        .fold(baseRequest)(
          p =>
            // Supports PUT and POST of JSON
            baseRequest
              .header("content-type", "application/json")
              .copy(connectFunc = StringBodyConnectFunc(Json.stringify(p)))
        )
      val authenticatedRequest = withAuthentication(request)
      val response = authenticatedRequest.asString
      Right((response.code, response.body))
    } catch {
      case NonFatal(exception) =>
        Left(RequestResponse[T](None, exception.getMessage, hasError = true))
    }
  }

  private def valueOrError[T](json: JsValue)(implicit reader: Reads[T]): RequestResponse[T] = {
    json.validate[T] match {
      case s: JsSuccess[T] =>
        RequestResponse(Some(s.value))

      case e: JsError =>
        val msg =
          s"""|Failed to validate json:
              |$json
              |JsError errors:
              |${e.errors.mkString(System.lineSeparator)}
                """.stripMargin
        RequestResponse[T](None, message = msg, hasError = true)
    }
  }

  private def getError[T](status: Int, statusText: String, body: String): RequestResponse[T] = {
    val msg =
      s"""|$status: $statusText
          |Body:
          |$body
           """.stripMargin
    RequestResponse[T](None, msg, hasError = true)
  }

  private def parseJson[T](input: String): Either[RequestResponse[T], JsValue] = {
    val json = Json.parse(input)

    val errorOpt = (json \ "errors")
      .asOpt[Seq[ResponseError]]
      .map(_.map { error =>
        s"""|Context: ${error.context.getOrElse("None")}
            |Exception: ${error.exceptionName.getOrElse("None")}
            |Message: ${error.message}
         """.stripMargin
      }.mkString(Properties.lineSeparator))

    errorOpt
      .map { error =>
        Left(RequestResponse[T](None, error, hasError = true))
      }
      .getOrElse(Right(json))
  }

  private def generateUrl(endpoint: String) = s"$apiUrl$endpoint"

}
